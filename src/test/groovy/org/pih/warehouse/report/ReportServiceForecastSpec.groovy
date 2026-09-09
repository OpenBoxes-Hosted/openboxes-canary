package org.pih.warehouse.report

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

import org.pih.warehouse.data.DataService
import org.pih.warehouse.product.Category
import org.pih.warehouse.product.Product
import org.pih.warehouse.report.ReportService

class ReportServiceForecastSpec extends Specification implements DataTest, ServiceUnitTest<ReportService> {

    private static final String PAYLOAD = "x') UNION SELECT id, 1 FROM product -- "

    private String capturedSql
    private Map capturedParams

    void setupSpec() {
        mockDomains(Product, Category)
    }

    void setup() {
        config.openboxes.forecasting.enabled = true
        service.dataService = Stub(DataService) {
            executeQuery(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql = sql
                capturedParams = params
                return []
            }
        }
    }

    void 'getForecastReport should bind #filter values instead of concatenating them'() {
        given: 'a filter whose second value is an injection attempt'
        Map params = [
                originId : 'LOC-1',
                startDate: new Date() - 30,
                endDate  : new Date(),
        ]
        params.put(filter, ['GOOD-1', PAYLOAD] as String[])

        when:
        service.getForecastReport(params)

        then: 'the payload never reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(PAYLOAD)
        !capturedSql.contains('UNION SELECT')

        and: 'the filter is a list of named parameters'
        capturedSql.contains("${column} in (:${prefix}0, :${prefix}1)")

        and: 'both values are bound, one binding per item'
        capturedParams["${prefix}0"] == 'GOOD-1'
        capturedParams["${prefix}1"] == PAYLOAD

        where:
        filter      | column                                    | prefix
        'locations' | 'pdd.destination_id'                      | 'destinationId'
        'tags'      | 'product_tag.tag_id'                      | 'tagId'
        'catalogs'  | 'product_catalog_item.product_catalog_id' | 'catalogId'
    }

    // The category filter is the one whose values are resolved through GORM (Category.get()) before
    // they reach the query, so it can't reuse the fixture above: GroovySpy(Category, global: true)
    // fights DataTest's in-memory GORM (the mocked Category constructor is routed through Spock's
    // mock validation binding and blows up with "object is not an instance of declaring class").
    // Fallback per the task brief: save two real Category rows so Category.get() resolves them for
    // real, and assert on their generated ids rather than an injection payload — this still proves
    // the category branch binds through SqlBindUtil.bindList instead of concatenating.
    void 'getForecastReport should bind category values instead of concatenating them'() {
        given: 'two real persisted categories, so Category.get() resolves them through real GORM'
        Category first = new Category(name: 'First').save(validate: false, flush: true)
        Category second = new Category(name: 'Second').save(validate: false, flush: true)

        Map params = [
                originId : 'LOC-1',
                startDate: new Date() - 30,
                endDate  : new Date(),
                category : [first.id, second.id] as String[],
        ]

        when:
        service.getForecastReport(params)

        then: 'the category filter is a list of named parameters'
        capturedSql != null
        capturedSql.contains('product.category_id in (:categoryId0, :categoryId1)')

        and: 'both resolved category ids are bound, not concatenated into the SQL text'
        capturedParams.values().containsAll([first.id, second.id])
        !capturedSql.contains("'${first.id}'")
        !capturedSql.contains("'${second.id}'")
    }
}
