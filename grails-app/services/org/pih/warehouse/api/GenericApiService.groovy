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

import grails.core.GrailsApplication
import grails.core.GrailsDomainClass
import grails.gorm.transactions.Transactional
import grails.validation.ValidationException
import org.grails.web.json.JSONArray
import org.grails.web.json.JSONObject
import org.hibernate.Hibernate
import org.hibernate.ObjectNotFoundException
import org.hibernate.SessionFactory
import org.hibernate.criterion.Criterion
import org.hibernate.criterion.Restrictions

@Transactional
class GenericApiService {

    /**
     * The resources the generic API is documented to expose, plus shipmentType, which the
     * user interface reads. Names are simple domain class names and are matched
     * case-insensitively.
     */
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
    ].asImmutable()

    /**
     * Resources that no API client asks for, but that the application itself resolves by
     * name through this service: the select option lists (SelectOptionsApiController) and
     * the spreadsheet templates offered by the import screens (BatchController).
     */
    static final List<String> INTERNAL_RESOURCES = [
        "inventoryLevel",
        "location",
        "paymentTerm",
        "preferenceType",
        "productAssociation",
        "productCatalog",
        "productCatalogItem",
        "productGroup",
        "productPackage",
        "productSupplierPreference",
        "synonym",
        "tag",
    ].asImmutable()

    /** Every resource name this service is willing to resolve to a domain class. */
    static final List<String> ALLOWED_RESOURCES = (DOCUMENTED_RESOURCES + INTERNAL_RESOURCES).asImmutable()

    SessionFactory sessionFactory
    GrailsApplication grailsApplication

    GrailsDomainClass getDomainClassByName(String className) {
        GrailsDomainClass grailsDomainClass = grailsApplication.domainClasses.find {
            it.clazz.simpleName == className
        }
        if (!grailsDomainClass) {
            throw new IllegalAccessException("Unable to locate domain ${className}")
        }
        return grailsDomainClass
    }

    Class getDomainClass(String resourceName) {
        String allowedResource = ALLOWED_RESOURCES.find { String allowed -> allowed.equalsIgnoreCase(resourceName) }
        if (!allowedResource) {
            // Answer exactly as we would for a resource that does not exist, so that the
            // response cannot be used to discover which domain classes the application has.
            throw new ObjectNotFoundException(resourceName, resourceName)
        }
        return getDomainClassByName(allowedResource.capitalize()).clazz
    }

    List getList(String resourceName, Map params) {
        Class domainClass = getDomainClass(resourceName)
        List list = domainClass.list(params)
        return list
    }

    Object getObject(String resourceName, String id) {
        def domainClass = getDomainClass(resourceName)
        Object domainObject = domainClass.get(id)
        if (!domainObject) {
            throw new ObjectNotFoundException(id, domainClass.simpleName)
        }
        return domainObject
    }

    /**
     * Loads an object a write is about to act on, and refuses it unless it is of exactly the
     * requested class. Domain classes are polymorphic — User extends Person, so
     * Person.get(id) returns the user — and the API binds whatever the request body
     * contains, so without this a write addressed to one resource could reach an object of
     * another. Reads stay polymorphic; only the write paths use this.
     */
    private Object getObjectForWrite(String resourceName, String id) {
        Class domainClass = getDomainClass(resourceName)
        Object domainObject = getObject(resourceName, id)
        if (Hibernate.getClass(domainObject) != domainClass) {
            // Answer exactly as the unknown-identifier path does: the response must not
            // disclose that the object exists as some other kind of resource.
            throw new ObjectNotFoundException(id, domainClass.simpleName)
        }
        return domainObject
    }

    Object createObject(String resourceName, JSONObject jsonObject) {
        log.debug "Create object " + jsonObject.class + ": " + jsonObject
        def domainClass = getDomainClass(resourceName)

        def domainObject
        if (jsonObject.id) {
            domainObject = getObjectForWrite(resourceName, jsonObject.id)
        } else {
            domainObject = domainClass.newInstance()
        }
        domainObject.properties = jsonObject
        if (domainObject.hasErrors() || !domainObject.save()) {
            throw new ValidationException("Cannot create product due to validation errors", domainObject.errors)
        }
        return domainObject
    }

    Object createObjects(String resourceName, JSONArray jsonArray) {
        log.debug "Create objects " + jsonArray.class + ": " + jsonArray
        def domainObjects = []
        jsonArray.each { JSONObject jsonObject ->
            domainObjects << createObject(resourceName, jsonObject)
        }
        return domainObjects
    }

    Object updateObject(String resourceName, String id, JSONObject jsonObject) {
        log.debug "Update " + jsonObject
        def domainObject = getObjectForWrite(resourceName, id)
        domainObject.properties = jsonObject
        if (domainObject.hasErrors() || !domainObject.save()) {
            throw new ValidationException("Cannot create product due to validation errors", domainObject.errors)
        }
        return domainObject
    }

    boolean deleteObject(String resourceName, String id) {
        log.debug "Delete " + id
        def domainObject = getObjectForWrite(resourceName, id)
        return domainObject.delete()
    }

    def searchObjects(String resourceName, JSONObject jsonObject, Map params) {
        Class domainClass = getDomainClass(resourceName)
        def session = sessionFactory.currentSession
        def criteria = session.createCriteria(domainClass)
        jsonObject.searchAttributes.each { attr ->
            Criterion criterion = buildCriterion(attr.property, attr.operator, attr.value)
            criteria.add(criterion)
        }
        return criteria.list()
    }

    Criterion buildCriterion(String propertyName, String operator, String value) {
        if (!propertyName || !value) {
            throw new IllegalArgumentException("Property and value are required in order to perform searches")
        }

        // Default operator should be eq
        operator = operator ?: "eq"

        switch (operator) {

            case "eq":
                Restrictions.eq(propertyName, value)
                break
            case "like":
                Restrictions.like(propertyName, value)
                break
            case "ilike":
                Restrictions.ilike(propertyName, value)
                break
            default:
                throw new UnsupportedOperationException("Operator ${operator} is not supported at this time")
        }
    }
}
