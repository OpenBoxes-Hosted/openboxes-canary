package org.pih.warehouse.core.db

/**
 * Helpers for building native SQL that binds its values instead of concatenating them.
 *
 * Each method adds its value(s) to the given parameter map and returns only the SQL fragment that
 * refers to them, so the caller passes the same map to DataService.executeQuery(String, Map) or
 * DataService.executeStatement(String, Map), which hand it to groovy.sql.Sql as named parameters.
 *
 * Named parameters may be repeated in one statement; groovy.sql.Sql resolves each occurrence
 * against the same map entry.
 */
class SqlBindUtil {

    /**
     * Binds a single value.
     *
     * @return ":<name>", the named-parameter reference to embed in the SQL
     */
    static String bindValue(String name, Object value, Map params) {
        params.put(name, value)
        return ":${name}"
    }

    /**
     * Binds each item of a collection under its own name ("<prefix>0", "<prefix>1", ...) so that an
     * id list can be used in an IN clause without concatenating any item into the SQL.
     *
     * Returns "null" for a null or empty collection, which keeps "IN (...)" valid SQL matching
     * nothing, rather than "IN ()", which is a syntax error.
     *
     * @return the comma-separated named-parameter references, e.g. ":categoryId0, :categoryId1"
     */
    static String bindList(String prefix, Collection<?> values, Map params) {
        if (!values) {
            return "null"
        }
        List<String> references = []
        values.eachWithIndex { Object value, int index ->
            references << bindValue("${prefix}${index}", value, params)
        }
        return references.join(", ")
    }
}
