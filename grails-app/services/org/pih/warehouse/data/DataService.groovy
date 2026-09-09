/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.data

import grails.gorm.transactions.Transactional
import groovy.sql.Sql
import org.apache.commons.lang.StringEscapeUtils
import org.apache.tomcat.jdbc.pool.PooledConnection
import org.apache.poi.hssf.usermodel.*
import org.apache.poi.ss.usermodel.*
import grails.plugins.csv.CSVWriter
import org.grails.plugins.excelimport.ExpectedPropertyType
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.ProductPrice
import org.pih.warehouse.core.UnitOfMeasure
import org.pih.warehouse.core.UnitOfMeasureClass
import org.pih.warehouse.core.UnitOfMeasureType
import org.pih.warehouse.importer.CSVUtils
import org.pih.warehouse.importer.ImportDataCommand
import org.pih.warehouse.importer.InventoryLevelExcelImporter
import org.pih.warehouse.core.UnitOfMeasure
import org.pih.warehouse.core.UnitOfMeasureClass
import org.pih.warehouse.core.Tag
import org.pih.warehouse.inventory.Inventory
import org.pih.warehouse.inventory.InventoryLevel
import org.pih.warehouse.inventory.InventoryStatus
import org.pih.warehouse.product.Category
import org.pih.warehouse.product.Product
import org.pih.warehouse.product.ProductPackage
import org.pih.warehouse.product.ProductType
import org.pih.warehouse.product.ProductTypeCode
import org.springframework.transaction.support.TransactionSynchronizationManager

import java.sql.Connection
import java.text.SimpleDateFormat

@Transactional
class DataService {

    def dataSource

    List executeQuery(String query) {
        return new Sql(dataSource).rows(query)
    }

    List executeQuery(String query, Map params) {
        return new Sql(dataSource).rows(query, params)
    }

    private static final String FOREIGN_KEY_CHECKS_QUERY =
            "SELECT @@SESSION.foreign_key_checks AS foreignKeyChecks"

    void executeStatement(String statement, Boolean logStatement = true) {
        executeStatements([[sql: statement, params: null]], logStatement)
    }

    /**
     * Runs every statement in the batch on one connection, and restores the session state the
     * batch changed even when a statement fails.
     *
     * Each element is either a Map [sql: String, params: Map] or a bare String. The List<Map>
     * generic documents the intended contract; a String element is normalised, so the callers
     * that still pass a list of strings are unaffected.
     *
     * Callers bracket destructive batches with "SET FOREIGN_KEY_CHECKS = 0" ... "= 1". That
     * variable is per-session and not transactional: it does not follow a rollback, and it
     * travels with the connection back into the pool. Each statement swallows its own exception,
     * so the trailing reset normally still runs - but a batch that ends early, or one written
     * without the reset, used to hand referential integrity checks off to whoever borrowed the
     * connection next.
     *
     * Commit and rollback belong to the enclosing transaction. This service is @Transactional, so
     * one is always active in the application; the per-statement commit this method used to issue
     * did not commit "one statement", it committed the caller's transaction.
     */
    void executeStatements(List<Map> statements, Boolean logStatement = true) {
        Connection connection = dataSource.connection
        boolean discarded = false
        try {
            Sql sql = new Sql(connection)
            Integer foreignKeyChecksOnEntry = readForeignKeyChecks(sql)
            try {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    runStatements(sql, statements, logStatement)
                } else {
                    sql.withTransaction {
                        runStatements(sql, statements, logStatement)
                    }
                }
            } finally {
                try {
                    restoreForeignKeyChecks(sql, foreignKeyChecksOnEntry)
                } catch (Exception e) {
                    // The session is dirty and we could not clean it, so we no longer know what
                    // state the next borrower would inherit. Take the connection out of service.
                    discarded = true
                    discardConnection(connection)
                    throw e
                }
            }
        } finally {
            if (!discarded) {
                connection.close()
            }
        }
    }

    private void runStatements(Sql sql, List<Map> statements, Boolean logStatement) {
        statements.each { Object element ->
            Map statement = (element instanceof Map)
                    ? (Map) element
                    : [sql: element?.toString(), params: null]
            String statementSql = statement.sql as String
            Map params = statement.params as Map
            try {
                long startTime = System.currentTimeMillis()
                log.info "Executing statement ${logStatement ? statementSql : ''}"
                if (params) {
                    sql.execute(params, statementSql)
                } else {
                    sql.execute(statementSql)
                }
                log.info "Updated ${sql.updateCount} rows in " + (System.currentTimeMillis() - startTime) + " ms"
            } catch (Exception e) {
                // Upstream behaviour: one bad statement does not abandon the rest of the batch.
                log.error("Error while executing statement: " + e.message, e)
            }
        }
    }

    private static Integer readForeignKeyChecks(Sql sql) {
        Object value = sql.firstRow(FOREIGN_KEY_CHECKS_QUERY)?.foreignKeyChecks
        if (value == null) {
            return null
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0
        }
        return ((Number) value).intValue()
    }

    /**
     * Restores foreign_key_checks to the value the batch inherited. Throws if it cannot: the
     * caller discards the connection rather than returning a session in an unknown state.
     */
    protected void restoreForeignKeyChecks(Sql sql, Integer valueOnEntry) {
        if (valueOnEntry == null) {
            return
        }
        Integer valueOnExit = readForeignKeyChecks(sql)
        if (valueOnExit == valueOnEntry) {
            return
        }
        sql.execute(valueOnEntry == 0
                ? "SET SESSION foreign_key_checks = 0"
                : "SET SESSION foreign_key_checks = 1")
        log.warn "Restored session foreign_key_checks to ${valueOnEntry}; a statement batch left it at ${valueOnExit}"
    }

    /**
     * Takes a connection out of service. Connection.abort(Executor) would be the JDBC way, but it
     * is optional and several drivers throw SQLFeatureNotSupportedException, so we mark the pool's
     * own wrapper discarded - tomcat-jdbc then destroys the physical connection on close() instead
     * of returning it to the idle set - and close it.
     */
    private void discardConnection(Connection connection) {
        try {
            // ProxyConnection.unwrap() special-cases the pool's own PooledConnection and returns
            // it, and that is the object holding the discard flag. Its isWrapperFor() cannot be
            // used as a guard: that one reports whether the *driver's* connection is an instance
            // of the requested type, so it answers false for PooledConnection. Attempt the unwrap
            // and let it tell us whether this is a pooled connection.
            PooledConnection pooled = (PooledConnection) connection.unwrap(PooledConnection)
            pooled.setDiscarded(true)
        } catch (Exception e) {
            log.error("Cannot discard ${connection.getClass().name} after a failed " +
                    "foreign_key_checks restore, so close() may return a session with unknown " +
                    "foreign_key_checks: " + e.message, e)
        }
        try {
            connection.close()
        } catch (Exception e) {
            log.error("Unable to close the discarded connection: " + e.message, e)
        }
    }


    /**
     * Should use the apache library to handle this.
     * @param str
     * @return
     */
    def getFloat(str) {
        try {
            return str.toFloat()
        } catch (NumberFormatException e) {
            log.error("Error converting string ${str} to float.")

            throw e
        }
        return 0.0
    }

    def transformObjects(List objects, List includeFields) {
        Map includeFieldsMap = includeFields.inject([:]) { result, includeField ->
            result[includeField] = includeField
            return result
        }

        transformObjects(objects, includeFieldsMap)
    }

    def transformObjects(List objects, Map includeFields) {
        objects.collect { object ->
            return transformObject(object, includeFields)
        }
    }

    Map transformObject(Object object, Map includeFields) {
        Map properties = [:]
        includeFields.each { fieldName, element ->
            def value = null
            if (element instanceof LinkedHashMap) {
                value = object.get(element.property) ?: element.property.tokenize('.').inject(object) { v, k -> v?."$k" }
                if (element.defaultValue && element.dateFormat && !value) {
                    value = element.defaultValue.format(element.dateFormat)
                } else if (element.dateFormat && value) {
                    value = value.format(element.dateFormat)
                } else if (element.defaultValue && !value) {
                    value = element.defaultValue
                }
                // We can't just check the truthiness of the value, because the false boolean would be evaluated to an empty string
                properties[fieldName] = value == null ? "" : value
            } else {
                // to access object value by key we must use the object.get(key) instead of object[key]
                // because using the object[key] will throw an error when trying to export data using the batch controller
                value = object.get(element) ?: element.tokenize('.').inject(object) { v, k -> v?."$k" }
                // We can't just check the truthiness of the value, because the false boolean would be evaluated to an empty string
                properties[fieldName] = value == null ? "" : value
            }
        }
        return properties
    }

    /**
     * Generic method to generate CSV string based on given csvrows map.
     * @param csvrows
     * @return
     */
    String generateCsv(csvrows) {
        def sw = new StringWriter()
        if (csvrows) {
            def columnHeaders = csvrows[0].keySet().collect { value -> StringEscapeUtils.escapeCsv(value) }
            sw.append(columnHeaders.join(",")).append("\n")
            csvrows.each { row ->
                def values = row.values().collect { value ->
                    if (value?.toString()?.isNumber()) {
                        value
                    } else {
                        StringEscapeUtils.escapeCsv(value.toString())
                    }
                }
                sw.append(values.join(","))
                sw.append("\n")
            }
        }
        return CSVUtils.prependBomToCsvString(sw.toString())
    }

}
