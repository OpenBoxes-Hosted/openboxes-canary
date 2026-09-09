package org.pih.warehouse.inventory

import grails.testing.gorm.DataTest
import spock.lang.Specification

import org.pih.warehouse.data.DataService
import org.pih.warehouse.inventory.InventoryItem
import org.pih.warehouse.inventory.ProductAvailabilityService
import org.pih.warehouse.product.Product
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus

class ProductAvailabilityServiceMergeSpec extends Specification implements DataTest {

    // product_code and lot_number are free-text columns, so their content is stored user input
    private static final String CODE_PAYLOAD = "AB', product_id = (SELECT id FROM product LIMIT 1) -- "
    private static final String LOT_PAYLOAD = "LOT-1', quantity_on_hand = 999999 -- "

    private ProductAvailabilityService service
    private String capturedSql
    private Map capturedParams

    void setupSpec() {
        mockDomains(Product, InventoryItem)
    }

    void setup() {
        service = new ProductAvailabilityService()
        service.dataService = Stub(DataService) {
            executeStatement(_ as String, _ as Map) >> { String sql, Map params ->
                capturedSql = sql
                capturedParams = params
            }
        }

        // ProductAvailabilityService is class-level @Transactional, so the AST transform routes
        // every public method through a GrailsTransactionTemplate that needs a transaction
        // manager to be callable at all when the service is constructed bare (no Spring
        // container). Same scaffolding as DataServiceRestoreFailureSpec.
        service.transactionManager = Stub(PlatformTransactionManager) {
            getTransaction(_) >> Stub(TransactionStatus)
        }
    }

    // Every domain field below is set via direct property assignment after a no-arg
    // constructor, never via the map constructor. Confirmed against this fork: the map
    // constructor goes through Grails' data binder, which (a) silently drops the "id" key
    // for a uuid-generated id (new Product(id: 'x').id == null) and (b) trims trailing
    // whitespace off String values (grails.databinding.trimStrings), which would silently
    // corrupt the trailing-space injection payloads asserted below.
    void 'updateProductAvailabilityOnMergeProduct should bind the product code, not concatenate it'() {
        given:
        Product primaryProduct = new Product()
        primaryProduct.id = 'PROD-1'
        primaryProduct.productCode = CODE_PAYLOAD
        InventoryItem obsoleteInventoryItem = new InventoryItem()
        obsoleteInventoryItem.id = 'II-OBSOLETE'
        Product obsoleteProduct = new Product()
        obsoleteProduct.id = 'PROD-2'

        when:
        service.updateProductAvailabilityOnMergeProduct(obsoleteInventoryItem, primaryProduct, obsoleteProduct)

        then: 'the stored payload never reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(CODE_PAYLOAD)
        !capturedSql.contains('SELECT id FROM product')

        and: 'every value is a named parameter'
        capturedSql.contains('product_code = :productCode')
        capturedSql.contains('product_id = :productId')
        capturedSql.contains('inventory_item_id = :inventoryItemId')

        and: 'the values are bound'
        capturedParams == [
                'productCode'    : CODE_PAYLOAD,
                'productId'      : 'PROD-1',
                'inventoryItemId': 'II-OBSOLETE',
        ]
    }

    void 'updateProductAvailabilityOnMergeProduct should bind the lot number, not concatenate it'() {
        given:
        Product primaryProduct = new Product()
        primaryProduct.id = 'PROD-1'
        primaryProduct.productCode = 'AB-1'
        InventoryItem primaryInventoryItem = new InventoryItem()
        primaryInventoryItem.id = 'II-PRIMARY'
        primaryInventoryItem.lotNumber = LOT_PAYLOAD
        InventoryItem obsoleteInventoryItem = new InventoryItem()
        obsoleteInventoryItem.id = 'II-OBSOLETE'
        Product obsoleteProduct = new Product()
        obsoleteProduct.id = 'PROD-2'

        when:
        service.updateProductAvailabilityOnMergeProduct(
                primaryInventoryItem, obsoleteInventoryItem, primaryProduct, obsoleteProduct)

        then: 'the stored payload never reaches the SQL text'
        capturedSql != null
        !capturedSql.contains(LOT_PAYLOAD)
        !capturedSql.contains('quantity_on_hand = 999999')

        and: 'every value is a named parameter'
        capturedSql.contains('lot_number = :lotNumber')

        and: 'the values are bound'
        capturedParams == [
                'productCode'            : 'AB-1',
                'productId'              : 'PROD-1',
                'primaryInventoryItemId' : 'II-PRIMARY',
                'lotNumber'              : LOT_PAYLOAD,
                'inventoryItemId'        : 'II-OBSOLETE',
        ]
    }
}
