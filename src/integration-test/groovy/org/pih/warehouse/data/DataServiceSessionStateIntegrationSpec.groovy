package org.pih.warehouse.data

import groovy.sql.Sql
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.Shared
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import org.pih.warehouse.common.base.IntegrationSpec
import org.pih.warehouse.core.Tag

import javax.sql.DataSource
import java.sql.Connection

/**
 * executeStatements is how the reporting and materialized-view rebuilds run raw SQL, and the
 * batches bracket their deletes with "SET FOREIGN_KEY_CHECKS = 0" ... "= 1". Each statement
 * swallows its own exception, so the trailing reset normally still runs - but the session
 * variable is not transactional and does not follow a rollback, so a batch that ends early hands
 * the connection back to the pool with referential integrity checks switched off for whoever
 * borrows it next.
 *
 * These tests pin the connection deliberately (SingleConnectionDataSource). Against the real
 * pool the assertion would be a coin toss on which connection came back, which is exactly the
 * property that makes the defect hard to see in production.
 */
@Unroll
class DataServiceSessionStateIntegrationSpec extends IntegrationSpec {

    DataService dataService

    DataSource dataSource

    @Shared
    static final String FK_CHECKS = "SELECT @@SESSION.foreign_key_checks AS fkChecks"

    /**
     * The injected "dataSource" bean is Spring's TransactionAwareDataSourceProxy wrapping the
     * pool, so the pool has to be unwrapped before its size can be inspected.
     */
    private static org.apache.tomcat.jdbc.pool.DataSource poolOf(DataSource candidate) {
        DataSource current = candidate
        while (current instanceof DelegatingDataSource) {
            current = ((DelegatingDataSource) current).targetDataSource
        }
        if (current instanceof org.apache.tomcat.jdbc.pool.DataSource) {
            return (org.apache.tomcat.jdbc.pool.DataSource) current
        }
        return current.unwrap(org.apache.tomcat.jdbc.pool.DataSource)
    }

    void 'executeStatements restores foreign_key_checks to #entryValue when a statement in the batch fails'() {
        given: 'one real connection, so the session variable we assert on is the one the batch ran on'
        Connection connection = dataSource.connection
        SingleConnectionDataSource pinned = new SingleConnectionDataSource(connection, true)
        Object original = dataService.dataSource

        and: 'the session starts at the value under test - not every caller starts from 1'
        new Sql(pinned).execute("SET SESSION foreign_key_checks = ${entryValue}".toString())

        and: 'a batch that disables the checks and then dies before it can re-enable them'
        List statements = [
                "SET FOREIGN_KEY_CHECKS = 0",
                "DELETE FROM a_table_that_does_not_exist",
        ]

        when:
        dataService.dataSource = pinned
        dataService.executeStatements(statements)

        then: 'the swallowed failure did not leave the connection with the checks the batch set'
        new Sql(pinned).firstRow(FK_CHECKS).fkChecks == entryValue

        cleanup:
        new Sql(pinned).execute("SET SESSION foreign_key_checks = 1")
        dataService.dataSource = original
        pinned.destroy()

        where:
        entryValue << [1, 0]
    }

    void 'a connection whose foreign_key_checks restore fails is discarded, not handed back to the pool'() {
        given: 'a connection borrowed straight from the real pool - not pinned, because pool.size' +
                ' needs to reflect what happens to THIS physical connection - and suppressClose=false,' +
                ' so the eventual discard-close is not swallowed the way the other cases in this spec' +
                ' deliberately swallow it'
        org.apache.tomcat.jdbc.pool.DataSource pooledDataSource = poolOf(dataSource)
        Connection victim = pooledDataSource.connection
        long victimConnectionId = new Sql(victim).firstRow("SELECT CONNECTION_ID() AS id").id as long
        SingleConnectionDataSource pinned = new SingleConnectionDataSource(victim, false)
        Object original = dataService.dataSource
        int sizeBeforeBatch = pooledDataSource.pool.size

        when: 'the batch kills its own session - deterministic, no thread/timing needed. The' +
                ' entry read at the top of executeStatements already succeeded (the connection was' +
                ' alive when the method started), so it is the restore\'s exit read, moments later,' +
                ' that discovers the session is gone'
        dataService.dataSource = pinned
        dataService.executeStatements([
                "SET FOREIGN_KEY_CHECKS = 0",
                "KILL CONNECTION_ID()",
        ])

        then: 'the restore could not confirm the session state it was meant to restore, so the' +
                ' failure propagated instead of a connection of unknown state going back to the pool'
        thrown(Exception)

        and: 'the discarded connection actually left the pool once closed, rather than returning' +
                ' to the idle set (tomcat-jdbc reclaims it, so poll rather than assert immediately)'
        new PollingConditions(timeout: 5, initialDelay: 0.1).eventually {
            assert pooledDataSource.pool.size == sizeBeforeBatch - 1
        }

        and: 'a fresh borrow is a different physical connection than the one that was discarded -' +
                ' the discarded connection is not handed out again'
        Connection next = pooledDataSource.connection
        new Sql(next).firstRow("SELECT CONNECTION_ID() AS id").id != victimConnectionId

        cleanup:
        dataService.dataSource = original
        next?.close()
    }

    void 'executeStatements leaves foreign_key_checks alone when the batch never touched it'() {
        given:
        Connection connection = dataSource.connection
        SingleConnectionDataSource pinned = new SingleConnectionDataSource(connection, true)
        Object original = dataService.dataSource

        and: 'the session starts with the checks on. Using the connection here also matters: the' +
                ' injected dataSource hands out a transaction-aware proxy that binds to a real' +
                ' connection on first use, and if that first use happened inside the batch it' +
                ' would bind to - and release with - the transaction the batch runs in'
        new Sql(pinned).execute("SET SESSION foreign_key_checks = 1")

        when: 'a batch that does not mention foreign keys at all'
        dataService.dataSource = pinned
        dataService.executeStatements(["SELECT 1"])

        then:
        new Sql(pinned).firstRow(FK_CHECKS).fkChecks == 1

        cleanup:
        dataService.dataSource = original
        pinned.destroy()
    }

    void 'a statement that fails does not discard the statements before it'() {
        given: 'a tag name no other row uses'
        String tagName = "p2-7-partial-failure-${UUID.randomUUID()}"
        String tagId = UUID.randomUUID().toString().replaceAll('-', '')

        when: 'the batch inserts, then dies, then inserts again'
        dataService.executeStatements([
                "INSERT INTO tag (id, version, tag, is_active, date_created, last_updated) " +
                        "VALUES ('${tagId}', 0, '${tagName}', 1, NOW(), NOW())".toString(),
                "DELETE FROM a_table_that_does_not_exist",
                "UPDATE tag SET is_active = 0 WHERE id = '${tagId}'".toString(),
        ])

        then: 'both surviving statements committed, and GORM can see them'
        Tag.withNewSession {
            Tag persisted = Tag.findByTag(tagName)
            assert persisted != null
            assert persisted.isActive == Boolean.FALSE
            return true
        }

        cleanup:
        dataService.executeStatements(["DELETE FROM tag WHERE id = '${tagId}'".toString()])
    }

    void 'executeStatements runs the whole batch on one connection'() {
        given: 'a temporary table, which lives and dies with a single connection'
        Sql sql = new Sql(dataSource)
        sql.execute("DROP TABLE IF EXISTS ob_batch_connection_probe")

        when: 'statement 2 can only see what statement 1 created if both ran on one connection'
        dataService.executeStatements([
                "CREATE TEMPORARY TABLE ob_batch_probe_tmp (connection_id BIGINT)",
                "INSERT INTO ob_batch_probe_tmp VALUES (CONNECTION_ID())",
                "CREATE TABLE ob_batch_connection_probe (connection_id BIGINT)",
                "INSERT INTO ob_batch_connection_probe SELECT connection_id FROM ob_batch_probe_tmp",
        ])

        then:
        sql.firstRow("SELECT COUNT(*) AS rowCount FROM ob_batch_connection_probe").rowCount == 1

        cleanup:
        sql.execute("DROP TABLE IF EXISTS ob_batch_connection_probe")
        sql.close()
    }
}
