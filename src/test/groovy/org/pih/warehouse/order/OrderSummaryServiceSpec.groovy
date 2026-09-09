package org.pih.warehouse.order

import spock.lang.Specification

import org.pih.warehouse.data.DataService
import org.pih.warehouse.order.OrderSummaryService

class OrderSummaryServiceSpec extends Specification {

    private static final String PAYLOAD = "1' UNION SELECT id, order_number FROM `order` -- "

    private OrderSummaryService service
    private String capturedSql
    private Map capturedParams
    private List<List<Map>> capturedBatches

    void setup() {
        capturedBatches = []
        service = new OrderSummaryService()
        service.dataService = Stub(DataService) {
            executeQuery(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql = sql
                capturedParams = params
                return []
            }
            executeQuery(_ as String) >> { String sql -> return [[:]] }
            // The batch core, recorded whole: the spec asserts the shape of the CALL as well as the
            // shape of the SQL, because turning one batch into N calls is the regression that
            // silently discards Task P2.7-1's single-connection guarantee.
            executeStatements(_ as List, _ as Boolean) >> { List statements, Boolean log ->
                capturedBatches << (statements as List<Map>)
            }
        }
    }

    void 'getOrderItemsDerivedStatus should bind the order id instead of concatenating it'() {
        when:
        service.getOrderItemsDerivedStatus(PAYLOAD)

        then: 'the payload never reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(PAYLOAD)
        !capturedSql.contains('UNION SELECT')

        and: 'every order-id predicate is a named parameter'
        capturedSql.contains('`order`.id = :orderId')
        !capturedSql.contains("`order`.id = '")

        and: 'the id is bound'
        capturedParams == ['orderId': PAYLOAD]
    }

    void 'refreshOrderSummary should bind the order id in the materialized-view statements'() {
        when:
        service.refreshOrderSummary([PAYLOAD], isDelete)

        then: 'exactly one statement was issued, and it does not contain the payload'
        capturedBatches.size() == 1
        capturedBatches[0].size() == 1
        !(capturedBatches[0][0].sql as String).contains(PAYLOAD)
        (capturedBatches[0][0].sql as String).contains(':orderId')

        and: 'the id is bound'
        capturedBatches[0][0].params == ['orderId': PAYLOAD]

        where:
        isDelete << [true, false]
    }

    void 'refreshOrderSummary should submit every order id as ONE batch, not one call per id'() {
        given: 'three orders queued for a materialized-view refresh'
        List<String> orderIds = ['ORDER-1', 'ORDER-2', PAYLOAD]

        when:
        service.refreshOrderSummary(orderIds, false)

        then: 'the service made exactly one call into the batch core'
        capturedBatches.size() == 1

        and: 'and handed it all three statements, each with its own bound id'
        capturedBatches[0].size() == 3
        capturedBatches[0]*.params*.orderId == orderIds

        and: 'no id is anywhere in the SQL text'
        capturedBatches[0].every { Map statement -> !(statement.sql as String).contains(PAYLOAD) }
    }
}
