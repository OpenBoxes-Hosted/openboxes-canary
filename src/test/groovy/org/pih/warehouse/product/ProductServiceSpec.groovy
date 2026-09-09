package org.pih.warehouse.product

import grails.testing.gorm.DataTest
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import org.pih.warehouse.LocalizationUtil
import org.pih.warehouse.core.LocalizationService
import org.pih.warehouse.data.DataService
import org.pih.warehouse.product.Product
import org.pih.warehouse.product.ProductIdentifierService
import org.pih.warehouse.product.ProductService
import org.pih.warehouse.product.ProductType

@Unroll
class ProductServiceSpec extends Specification implements DataTest {

    @Shared
    ProductService service

    void setupSpec() {
        mockDomain Product
    }

    void setup() {
        service = new ProductService()
    }

    void 'getProducts returns the requested products'() {
        given:
        new Product(id: 1).save(validate: false)
        new Product(id: 2).save(validate: false)
        new Product(id: 3, active: false).save(validate: false)

        when:
        List<Product> products = service.getProducts(productIds as String[])

        then:
        products.size() == expectedNumProducts

        where:
        productIds      || expectedNumProducts
        null            || 0
        []              || 0
        ['2']           || 1
        ['3']           || 0
        ['1', '2', '3'] || 2
    }

    @Ignore('The executeQuery in ProductService.validateProductIdentifier cannot be stubbed easily. It should be moved to a static method in the Domain class.')
    void 'saveProduct can create a product'() {
        given:
        ProductType productType = new ProductType()
        String productCode = 'testcode'

        and: 'the following mocks'
        // Product.metaClass.static.executeQuery = {String query, List params -> return [0]}
        service.productIdentifierService = Stub(ProductIdentifierService) {
            generate(_ as Product) >> productCode
        }

        when:
        def returnedProduct = service.saveProduct(new Product(id: 1, productType: productType))

        then:
        returnedProduct != null
        // Verify other fields such as productCode
    }

    void 'searchProductDtos should bind search terms instead of concatenating them'() {
        given: 'a search term that is an injection attempt'
        String payload = "a%'\nunion\nselect\n1,2,3\nfrom\nuser\n--\n"
        String capturedSql = null
        Map capturedParams = null
        GroovyMock(LocalizationUtil, global: true)
        LocalizationUtil.localizationService >> Stub(LocalizationService) {
            getCurrentLocale() >> Locale.ENGLISH
        }
        service.dataService = Stub(DataService) {
            executeQuery(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql = sql
                capturedParams = params
                return []
            }
        }

        when:
        service.searchProductDtos(['aspirin', payload] as String[])

        then: 'no part of either term reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(payload)
        !capturedSql.contains('aspirin')
        !capturedSql.contains('union')

        and: 'each term is bound twice, as a prefix pattern and as a contains pattern'
        capturedParams['termPrefix0'] == 'aspirin%'
        capturedParams['termContains0'] == '%aspirin%'
        capturedParams['termPrefix1'] == "${payload}%".toString()
        capturedParams['termContains1'] == "%${payload}%".toString()

        and: 'the exact-match comparison and the display-name subquery are bound too'
        capturedParams['exactTerm'] == "aspirin ${payload}".toString()
        capturedParams['synonymTypeCode'] == 'DISPLAY_NAME'
        capturedParams.containsKey('locale')

        and: 'the query refers to those parameters'
        capturedSql.contains('lower(product.name) like :termContains0')
        capturedSql.contains('lower(product.product_code) like :termPrefix0')
        capturedSql.contains('product.product_code = :exactTerm')

        and: 'the manufacturer-name search stays a prefix match, not a contains match, matching pre-change behaviour'
        capturedSql.contains('lower(product_supplier.manufacturer_name) like :termPrefix0')
        !capturedSql.contains('lower(product_supplier.manufacturer_name) like :termContains0')
    }
}
