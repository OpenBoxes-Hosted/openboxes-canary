package org.pih.warehouse.dashboard

import grails.testing.gorm.DataTest
import grails.web.servlet.mvc.GrailsParameterMap
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import org.pih.warehouse.core.Location
import org.pih.warehouse.data.DataService
import org.pih.warehouse.dashboard.IndicatorDataService

class IndicatorDataServiceSpec extends Specification implements DataTest {

    private static final String PAYLOAD = "x' OR 1=1 -- "

    private IndicatorDataService service
    private List<String> capturedSql
    private List<Map> capturedParams

    void setupSpec() {
        mockDomains(Location)
    }

    void setup() {
        capturedSql = []
        capturedParams = []
        service = new IndicatorDataService()
        service.dataService = Stub(DataService) {
            executeQuery(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql << sql
                capturedParams << params
                return []
            }
        }
    }

    private GrailsParameterMap categoryFilter(Map extra = [:]) {
        Map values = ['listFiltersSelected': 'category', 'value': ['CAT-1', PAYLOAD] as String[]]
        values.putAll(extra)
        return new GrailsParameterMap(values, new MockHttpServletRequest())
    }

    void 'getFillRate should bind category ids instead of concatenating them'() {
        given:
        Location origin = new Location().save(validate: false)

        when:
        service.getFillRate(origin, null, categoryFilter(['querySize': '1']))

        then: 'every query it issued is free of the payload'
        capturedSql.size() == 3
        capturedSql.every { !it.contains(PAYLOAD) && !it.contains('OR 1=1') }

        and: 'the category filter is a list of named parameters'
        capturedSql.every { it.contains('c.id in (:categoryId0, :categoryId1)') }

        and: 'both category ids are bound on every query, one binding per item'
        capturedParams.every { it['categoryId0'] == 'CAT-1' && it['categoryId1'] == PAYLOAD }
    }

    void 'getFillRateSnapshot should bind category ids instead of concatenating them'() {
        given:
        Location origin = new Location().save(validate: false)

        when:
        service.getFillRateSnapshot(origin, categoryFilter())

        then: 'every query it issued is free of the payload'
        capturedSql.size() == 12
        capturedSql.every { !it.contains(PAYLOAD) && !it.contains('OR 1=1') }

        and: 'the category filter is a list of named parameters'
        capturedSql.every { it.contains('c.id in (:categoryId0, :categoryId1)') }

        and: 'both category ids are bound on every query, one binding per item'
        capturedParams.every { it['categoryId0'] == 'CAT-1' && it['categoryId1'] == PAYLOAD }
    }
}
