/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.api

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONObject
import org.hibernate.ObjectNotFoundException
import spock.lang.Specification
import spock.lang.Unroll

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.PaymentTerm
import org.pih.warehouse.core.Person
import org.pih.warehouse.core.PreferenceType
import org.pih.warehouse.core.Synonym
import org.pih.warehouse.core.Tag
import org.pih.warehouse.core.User
import org.pih.warehouse.inventory.InventoryLevel
import org.pih.warehouse.product.Product
import org.pih.warehouse.product.ProductAssociation
import org.pih.warehouse.product.ProductCatalog
import org.pih.warehouse.product.ProductCatalogItem
import org.pih.warehouse.product.ProductGroup
import org.pih.warehouse.product.ProductPackage
import org.pih.warehouse.product.ProductSupplierPreference
import org.pih.warehouse.shipping.ShipmentType

/**
 * Tests that the generic API resolves only the resources it is documented to expose, that
 * every entry point into the service is covered by that check, and that a write acts only
 * on an object of exactly the requested class.
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
        mockDomains(Product, Person, ShipmentType, User,
                InventoryLevel, Location, PaymentTerm, PreferenceType, ProductAssociation,
                ProductCatalog, ProductCatalogItem, ProductGroup, ProductPackage,
                ProductSupplierPreference, Synonym, Tag)
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

    /**
     * The names in INTERNAL_RESOURCES exist only because the application's own controllers
     * resolve these classes by name. The class is the source of truth here, so renaming one
     * or dropping its entry from the allowlist fails the build rather than silently emptying
     * a dropdown or a spreadsheet download in production.
     */
    void "getDomainClass resolves the internal resource #expectedClass.simpleName"() {
        expect:
        service.getDomainClass(expectedClass.simpleName) == expectedClass

        where:
        expectedClass << [
            // SelectOptionsApiController passes Class.simpleName for these.
            ProductGroup,
            ProductCatalog,
            Tag,
            PaymentTerm,
            PreferenceType,
            // BatchController.downloadExcel receives these as its `type` request parameter.
            InventoryLevel,
            Location,
            ProductAssociation,
            ProductCatalogItem,
            ProductPackage,
            ProductSupplierPreference,
            Synonym,
        ]
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
        entryPoint              | invocation
        "getList"               | { GenericApiService it -> it.getList("user", [:]) }
        "getObject"             | { GenericApiService it -> it.getObject("user", "1") }
        "createObject"          | { GenericApiService it -> it.createObject("user", new JSONObject()) }
        "createObjects"         | { GenericApiService it -> it.createObjects("user", oneElementArray()) }
        "createObjects (empty)" | { GenericApiService it -> it.createObjects("user", new JSONArray()) }
        "updateObject"          | { GenericApiService it -> it.updateObject("user", "1", new JSONObject()) }
        "deleteObject"          | { GenericApiService it -> it.deleteObject("user", "1") }
        "searchObjects"         | { GenericApiService it -> it.searchObjects("user", new JSONObject(), [:]) }
    }

    /**
     * User extends Person, so Person.get(id) returns the user. A write addressed to person
     * must not act on it: the allowlist alone closes `user`, not this route to the same row.
     */
    void "#operation addressed to #resourceName cannot reach a user account"() {
        given:
        User user = givenUser()

        when:
        invocation.call(service, user.id)

        then:
        thrown(ObjectNotFoundException)
        User.get(user.id).username == "ada"
        User.get(user.id).firstName == "Ada"

        where:
        operation      | resourceName | invocation
        "updateObject" | "user"       | { GenericApiService s, String id -> s.updateObject("user", id, hijackPayload()) }
        "updateObject" | "person"     | { GenericApiService s, String id -> s.updateObject("person", id, hijackPayload()) }
        "deleteObject" | "user"       | { GenericApiService s, String id -> s.deleteObject("user", id) }
        "deleteObject" | "person"     | { GenericApiService s, String id -> s.deleteObject("person", id) }
        "createObject" | "user"       | { GenericApiService s, String id -> s.createObject("user", hijackPayloadWithId(id)) }
        "createObject" | "person"     | { GenericApiService s, String id -> s.createObject("person", hijackPayloadWithId(id)) }
    }

    void "a read addressed to person still returns a user account"() {
        given:
        User user = givenUser()

        when:
        Object found = service.getObject("person", user.id)

        then:
        found instanceof User
        found.username == "ada"
    }

    void "updateObject still updates an object of exactly the requested class"() {
        given:
        Person person = new Person(firstName: "Grace", lastName: "Hopper").save(validate: false, flush: true)
        assert person?.id
        JSONObject json = new JSONObject()
        json.put("firstName", "Updated")

        when:
        service.updateObject("person", person.id, json)

        then:
        Person.get(person.id).firstName == "Updated"
    }

    void "the documented half of the allowlist is exactly the fourteen resources"() {
        expect:
        GenericApiService.DOCUMENTED_RESOURCES == DOCUMENTED_RESOURCES
    }

    void "the allowlist covers every documented generic API resource"() {
        expect:
        DOCUMENTED_RESOURCES.every { String resource ->
            GenericApiService.ALLOWED_RESOURCES.any { String allowed -> allowed.equalsIgnoreCase(resource) }
        }
    }

    void "the allowlist does not name a security sensitive domain"() {
        expect:
        ["user", "role", "locationRole", "document", "documentType"].every { String resource ->
            !GenericApiService.ALLOWED_RESOURCES.any { String allowed -> allowed.equalsIgnoreCase(resource) }
        }
    }

    private static User givenUser() {
        User user = new User(firstName: "Ada", lastName: "Lovelace",
                username: "ada", password: "s3cret").save(validate: false, flush: true)
        assert user?.id
        return user
    }

    private static JSONObject hijackPayload() {
        JSONObject json = new JSONObject()
        json.put("firstName", "Hijacked")
        json.put("username", "hijacked")
        return json
    }

    private static JSONObject hijackPayloadWithId(String id) {
        JSONObject json = hijackPayload()
        json.put("id", id)
        return json
    }

    private static JSONArray oneElementArray() {
        JSONArray array = new JSONArray()
        array.add(new JSONObject())
        return array
    }
}
