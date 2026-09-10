package org.pih.warehouse.api

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONObject
import org.hibernate.ObjectNotFoundException
import spock.lang.Specification
import spock.lang.Unroll

import org.pih.warehouse.core.Person
import org.pih.warehouse.product.Product
import org.pih.warehouse.shipping.ShipmentType

/**
 * Tests that the generic API resolves only the resources it is documented to expose,
 * and that every entry point into the service is covered by that check.
 */
@Unroll
class GenericApiServiceSpec extends Specification implements ServiceUnitTest<GenericApiService>, DataTest {

    // The resources the generic API is documented to expose, plus shipmentType, which the
    // user interface reads. Spelled out here so that the list cannot drift silently.
    static final List<String> DOCUMENTED_RESOURCES = [
        "product",
        "inventoryItem",
        "shipment",
        "shipmentItem",
        "requisition",
        "requisitionItem",
        "transaction",
        "transactionEntry",
        "category",
        "locationType",
        "locationGroup",
        "person",
        "organization",
        "shipmentType",
    ]

    void setupSpec() {
        mockDomains(Product, Person, ShipmentType)
    }

    void "getDomainClass resolves the allowed resource #resourceName"() {
        expect:
        service.getDomainClass(resourceName) == expectedClass

        where:
        resourceName   || expectedClass
        "product"      || Product
        "shipmentType" || ShipmentType
        "person"       || Person
        "SHIPMENTTYPE" || ShipmentType
        "Product"      || Product
    }

    void "getDomainClass refuses the resource #resourceName"() {
        when:
        service.getDomainClass(resourceName)

        then:
        thrown(ObjectNotFoundException)

        where:
        resourceName << ["user", "role", "locationRole", "document", "documentType", "nonsense", "", null]
    }

    void "#entryPoint refuses a resource that is not on the allowlist"() {
        when:
        invocation.call(service)

        then:
        thrown(ObjectNotFoundException)

        where:
        entryPoint      | invocation
        "getList"       | { GenericApiService it -> it.getList("user", [:]) }
        "getObject"     | { GenericApiService it -> it.getObject("user", "1") }
        "createObject"  | { GenericApiService it -> it.createObject("user", new JSONObject()) }
        "createObjects" | { GenericApiService it -> it.createObjects("user", new JSONArray([new JSONObject()])) }
        "updateObject"  | { GenericApiService it -> it.updateObject("user", "1", new JSONObject()) }
        "deleteObject"  | { GenericApiService it -> it.deleteObject("user", "1") }
        "searchObjects" | { GenericApiService it -> it.searchObjects("user", new JSONObject(), [:]) }
    }

    void "the allowlist covers every documented generic API resource"() {
        expect:
        DOCUMENTED_RESOURCES.every { String resource ->
            GenericApiService.ALLOWED_RESOURCES.any { String allowed -> allowed.equalsIgnoreCase(resource) }
        }
    }

    void "the allowlist does not expose security sensitive domains"() {
        expect:
        ["user", "role", "locationRole", "document", "documentType"].every { String resource ->
            !GenericApiService.ALLOWED_RESOURCES.any { String allowed -> allowed.equalsIgnoreCase(resource) }
        }
    }
}
