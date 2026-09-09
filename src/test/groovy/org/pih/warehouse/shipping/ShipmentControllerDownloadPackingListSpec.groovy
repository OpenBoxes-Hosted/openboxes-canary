package org.pih.warehouse.shipping

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import groovy.sql.Sql
import spock.lang.Specification

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentController

/**
 * The action's fixture is a REAL shipment id, not an injection payload: line 672's
 * Shipment.get(params.id) is a Hibernate primary-key lookup with a bound parameter, so a payload
 * that is not an existing id returns null and the action redirects at 675 without ever building a
 * query. A spec that persisted the payload as the id would be asserting on a path the action
 * cannot reach, and could fail in the fixture rather than in the assertion (C11).
 *
 * The injection payload therefore lives in the lower-level binding feature at the bottom, which
 * exercises the mechanism the fix relies on directly.
 */
class ShipmentControllerDownloadPackingListSpec extends Specification
        implements ControllerUnitTest<ShipmentController>, DataTest {

    // A 32-character hex string: the shape id generator: 'uuid' produces (application.groovy:7).
    private static final String SHIPMENT_ID = 'ff8081818f0a4c1e818f0a4c1e000001'
    private static final String PAYLOAD = "1 UNION SELECT 1,2,3,4,5,6,7,8,9,10 FROM shipment_item -- "

    private List<String> preparedSql
    private List boundValues
    private Connection connection

    void setupSpec() {
        mockDomains(Shipment)
    }

    void setup() {
        preparedSql = []
        boundValues = []

        // groovy.sql.Sql's own seam: eachRow(String, List, Closure) turns the '?' query into a
        // PreparedStatement and sets each value through setObject. Recording the driver's side of
        // that call is what proves the id left as a bind and not as SQL text.
        // "_ as int" does not match a primitive int parameter, so setObject's index arg is matched
        // with a plain "_" (see DataServiceBindingSpec for the same fix).
        PreparedStatement preparedStatement = Stub(PreparedStatement) {
            executeQuery() >> Stub(ResultSet)
            setObject(_, _) >> { int index, Object value -> boundValues << value }
        }

        connection = Stub(Connection) {
            prepareStatement(_ as String) >> { String sql ->
                preparedSql << sql
                return preparedStatement
            }
            // groovy.sql.Sql(Connection)'s default resultSetType (1003) / resultSetConcurrency
            // (1007) with no holdability set (-1 sentinel) routes eachRow(String, List, Closure)
            // through Connection.prepareStatement(String, int, int), not the single-arg overload
            // above - confirmed by javap -c on groovy-sql-2.4.21.jar
            // (Sql$CreatePreparedStatementCommand) and already the documented behaviour in
            // DataServiceBindingSpec. Matched by arg count only, since "_ as int" fails to match a
            // primitive int parameter here.
            prepareStatement(_, _, _) >> { String sql, int resultSetType, int resultSetConcurrency ->
                preparedSql << sql
                return preparedStatement
            }
        }

        // sessionFactory is an untyped Grails injection point, so a map-of-closures stands in for it
        // (the same idiom ReportControllerDownloadShippingReportSpec uses for reportService).
        controller.sessionFactory = [currentSession: [connection: { -> connection }]]
    }

    void 'downloadPackingList should bind the shipment id, not concatenate it'() {
        given: 'an ordinary shipment, because the action will not run for anything else'
        Shipment shipment = new Shipment(id: SHIPMENT_ID).save(validate: false, failOnError: false)
        params.id = shipment.id

        when:
        controller.downloadPackingList()

        then: 'the id is a placeholder in the SQL, not a value in it'
        preparedSql.size() == 1
        preparedSql[0].contains('shipment.id = ?')
        !preparedSql[0].contains(shipment.id)

        and: 'and it is bound at execution'
        boundValues == [shipment.id]

        and: 'the download still renders'
        response.contentType.startsWith('text/csv')
    }

    void 'binding a parameter keeps an injection payload out of the SQL text'() {
        given: 'the mechanism the fix relies on, exercised directly with an attacker-shaped value'
        String query = 'select shipment_item.quantity from shipment where shipment.id = ?'

        when:
        new Sql(connection).eachRow(query, [PAYLOAD]) { row -> }

        then: 'the payload is never part of the statement the driver was asked to prepare'
        preparedSql == [query]
        !preparedSql[0].contains(PAYLOAD)
        !preparedSql[0].contains('UNION SELECT')

        and: 'it arrived as a bound value instead'
        boundValues == [PAYLOAD]
    }
}
