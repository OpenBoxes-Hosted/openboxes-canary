package org.pih.warehouse.dashboard

import grails.testing.gorm.DataTest
import grails.web.servlet.mvc.GrailsParameterMap
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import org.pih.warehouse.data.DataService
import org.pih.warehouse.dashboard.NumberDataService

class NumberDataServiceSpec extends Specification implements DataTest {

    private static final String PAYLOAD = "x' UNION SELECT id, 1 FROM `order` -- "

    private NumberDataService service
    private String capturedSql
    private Map capturedParams

    void setup() {
        service = new NumberDataService()
        service.dataService = Stub(DataService) {
            executeQuery(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql = sql
                capturedParams = params
                return []
            }
        }
    }

    void 'getOpenPurchaseOrdersCount should bind supplier ids instead of concatenating them'() {
        given: 'a supplier filter whose second value is an injection attempt'
        GrailsParameterMap params = new GrailsParameterMap(
                ['value': ['SUP-1', PAYLOAD] as String[]],
                new MockHttpServletRequest())

        when:
        service.getOpenPurchaseOrdersCount(params)

        then: 'the payload never reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(PAYLOAD)
        !capturedSql.contains('UNION SELECT')

        and: 'the origin filter is a list of named parameters'
        capturedSql.contains('o.origin_id in (:supplierId0, :supplierId1)')

        and: 'both supplier ids are bound, one binding per item'
        capturedParams['supplierId0'] == 'SUP-1'
        capturedParams['supplierId1'] == PAYLOAD
    }
}
