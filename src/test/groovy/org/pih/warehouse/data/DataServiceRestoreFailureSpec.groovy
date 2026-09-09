package org.pih.warehouse.data

import org.apache.tomcat.jdbc.pool.PooledConnection
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement

/**
 * executeStatements restores the session's foreign_key_checks in a finally. If that restore
 * itself fails - the server went away, the account lost the privilege, the connection was
 * killed mid-batch - the session is dirty and we do not know its state. Returning it to the
 * pool hands referential-integrity checks off to whoever borrows it next, silently, on a
 * different request and possibly a different tenant. So the connection is marked discarded
 * and the failure is rethrown.
 */
class DataServiceRestoreFailureSpec extends Specification {

    private List<String> executed
    private int foreignKeyReads
    private PooledConnection pooledConnection
    private DataService service

    private ResultSet foreignKeyChecksResultSet(int value) {
        boolean consumed = false
        ResultSetMetaData metaData = Stub(ResultSetMetaData) {
            getColumnCount() >> 1
            getColumnLabel(1) >> 'foreignKeyChecks'
            getColumnName(1) >> 'foreignKeyChecks'
        }
        return Stub(ResultSet) {
            getMetaData() >> metaData
            next() >> { boolean more = !consumed; consumed = true; return more }
            getObject(1) >> value
            getObject('foreignKeyChecks') >> value
        }
    }

    void setup() {
        executed = []
        foreignKeyReads = 0
        pooledConnection = Mock(PooledConnection)

        Statement statement = Stub(Statement) {
            executeQuery(_ as String) >> {
                foreignKeyReads++
                if (foreignKeyReads == 1) {
                    return foreignKeyChecksResultSet(1)
                }
                throw new SQLException('injected: the session is gone')
            }
            execute(_ as String) >> { String sql -> executed << sql; return false }
            getUpdateCount() >> 0
        }

        Connection connection = Mock(Connection) {
            createStatement() >> statement
            createStatement(_, _) >> statement
            prepareStatement(_ as String) >> { throw new SQLException('not expected in this spec') }
            // Matches tomcat-jdbc: ProxyConnection.unwrap() hands back the pool's own
            // PooledConnection, while its isWrapperFor() answers false for that type.
            isWrapperFor(PooledConnection) >> false
            unwrap(PooledConnection) >> pooledConnection
        }

        DataSource dataSource = Stub(DataSource) {
            getConnection() >> connection
        }

        service = new DataService()
        service.dataSource = dataSource

        // DataService is @Transactional, and the AST transform routes every public method through
        // a GrailsTransactionTemplate, so the service needs a transaction manager to be callable at
        // all. A stub never binds a connection to the thread, so this spec exercises the branch
        // that runs when no transaction is active - the one that wraps the batch in
        // Sql.withTransaction. The branch taken inside a real transaction is covered by
        // DataServiceSessionStateIntegrationSpec.
        service.transactionManager = Stub(PlatformTransactionManager) {
            getTransaction(_) >> Stub(TransactionStatus)
        }
    }

    void 'a failed foreign_key_checks restore discards the connection and propagates'() {
        when:
        service.executeStatements(['SET FOREIGN_KEY_CHECKS = 0', 'DELETE FROM does_not_matter'])

        then: 'the batch itself ran to the end - a statement failure never aborts the batch'
        executed.contains('SET FOREIGN_KEY_CHECKS = 0')
        executed.contains('DELETE FROM does_not_matter')

        and: 'the connection was marked unusable before it could go back to the pool'
        1 * pooledConnection.setDiscarded(true)

        and: 'and the failure was not swallowed'
        SQLException e = thrown()
        e.message.contains('injected')
    }
}
