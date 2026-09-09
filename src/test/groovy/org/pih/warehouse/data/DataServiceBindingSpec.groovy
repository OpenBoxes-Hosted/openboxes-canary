package org.pih.warehouse.data

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import spock.lang.Specification

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import javax.sql.DataSource

import org.pih.warehouse.data.DataService

class DataServiceBindingSpec extends Specification {

    private List<String> preparedSql
    private List boundValues
    private int connectionsBorrowed
    private DataService service

    private ResultSet foreignKeyChecksResultSet() {
        boolean consumed = false
        ResultSetMetaData metaData = Stub(ResultSetMetaData) {
            getColumnCount() >> 1
            getColumnLabel(1) >> 'foreignKeyChecks'
            getColumnName(1) >> 'foreignKeyChecks'
        }
        return Stub(ResultSet) {
            getMetaData() >> metaData
            next() >> { boolean more = !consumed; consumed = true; return more }
            getObject(1) >> 1
            getObject('foreignKeyChecks') >> 1
        }
    }

    void setup() {
        preparedSql = []
        boundValues = []
        connectionsBorrowed = 0

        PreparedStatement preparedStatement = Stub(PreparedStatement) {
            execute() >> false
            executeQuery() >> Stub(ResultSet)
            getUpdateCount() >> 1
            setObject(_, _) >> { int index, Object value -> boundValues << value }
        }

        // Task P2.7-1's core reads @@SESSION.foreign_key_checks through a plain Statement before
        // and after the batch; only the batch itself is prepared.
        Statement statement = Stub(Statement) {
            executeQuery(_ as String) >> foreignKeyChecksResultSet()
            execute(_ as String) >> false
            getUpdateCount() >> 0
        }

        Connection connection = Stub(Connection) {
            createStatement() >> statement
            createStatement(_, _) >> statement
            // groovy.sql.Sql's default resultSetType (1003) / resultSetConcurrency (1007) with no
            // holdability set (-1 sentinel) routes named-parameter execute() through
            // Connection.prepareStatement(String, int, int), not the single-arg overload -
            // confirmed by javap -c on groovy-sql-2.4.21.jar (Sql$CreatePreparedStatementCommand).
            prepareStatement(_ as String) >> { String sql ->
                preparedSql << sql
                return preparedStatement
            }
            // groovy.sql.Sql always calls this 3-arg overload for a named-parameter execute (it
            // never calls the 1-arg overload above in practice) - matched by arg count only,
            // since "_ as int" fails to match a primitive int parameter here.
            prepareStatement(_, _, _) >> { String sql, int resultSetType, int resultSetConcurrency ->
                preparedSql << sql
                return preparedStatement
            }
        }

        DataSource dataSource = Stub(DataSource) {
            getConnection() >> { connectionsBorrowed++; return connection }
        }

        service = new DataService()
        service.dataSource = dataSource

        // DataService is @Transactional, and the AST transform routes every public method through
        // a GrailsTransactionTemplate, so the service needs a transaction manager to be callable at
        // all outside a real GORM/Spring context. Same fix as DataServiceRestoreFailureSpec, for
        // the same reason.
        service.transactionManager = Stub(PlatformTransactionManager) {
            getTransaction(_) >> Stub(TransactionStatus)
        }
    }

    void 'executeStatement with params should send the value as a bind, not as SQL text'() {
        given: 'a statement whose value is attacker-controlled'
        String payload = "X'); DROP TABLE product_availability; -- "
        String statement = 'UPDATE product_availability SET product_code = :productCode WHERE id = :id'

        when:
        service.executeStatement(statement, [productCode: payload, id: 'ABC'])

        then: 'the driver was handed placeholders, and the payload is nowhere in the SQL text'
        preparedSql == ['UPDATE product_availability SET product_code = ? WHERE id = ?']
        !preparedSql[0].contains('DROP TABLE')
        !preparedSql[0].contains(payload)

        and: 'the payload arrived as a bound parameter'
        boundValues == [payload, 'ABC']
    }

    void 'executeStatement with params must go through the one batch core, not its own connection'() {
        when: 'three parameterised statements are submitted as one batch'
        service.executeStatements([
                [sql: 'UPDATE product_availability SET product_code = :code WHERE id = :id', params: [code: 'A', id: '1']],
                [sql: 'UPDATE product_availability SET product_code = :code WHERE id = :id', params: [code: 'B', id: '2']],
                [sql: 'UPDATE product_availability SET product_code = :code WHERE id = :id', params: [code: 'C', id: '3']],
        ])

        then: 'exactly one connection was borrowed for the whole batch'
        connectionsBorrowed == 1

        and: 'and every statement really ran'
        preparedSql.size() == 3
        boundValues == ['A', '1', 'B', '2', 'C', '3']
    }

    void 'a single parameterised statement borrows exactly one connection'() {
        when:
        service.executeStatement('DELETE FROM order_summary_mv WHERE id = :orderId', [orderId: 'ORDER-1'])

        then:
        connectionsBorrowed == 1
        preparedSql == ['DELETE FROM order_summary_mv WHERE id = ?']
        boundValues == ['ORDER-1']
    }
}
