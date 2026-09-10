/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package util

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Redacts credential-shaped values out of a configuration map before it is rendered.
 *
 * Administration > Settings renders the merged Grails config. In Grails 3.3 that merge pulls in
 * every EnumerablePropertySource, systemEnvironment included, so a container's environment
 * variables - database passwords, OAuth client secrets - are part of the map handed to the view.
 * The mask that used to live in the GSP tested "key.contains('password')", which is
 * case-sensitive: OPENBOXES_DB_PASSWORD and CLIENT_SECRET both rendered in the clear.
 */
class ConfigMasker {

    static final String MASK = "********"

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            ".*(password|secret|key|token).*", Pattern.CASE_INSENSITIVE)

    // Matches the "scheme://user:pass@" prefix of a URL so the credentials can be dropped
    // without disturbing the host/path that follows. Plain file paths (`file:/opt/...`) have
    // no "//" after the scheme and never match.
    private static final Pattern URL_USER_INFO = Pattern.compile("(://)([^/@]+)@")

    /**
     * Returns a copy of the given map with the value of every credential-shaped key replaced by
     * {@link #MASK}. Nested maps are masked too; a null value stays null, so the page keeps
     * distinguishing "unset" from "set but hidden".
     */
    static Map mask(Map source) {
        if (source == null) {
            return null
        }
        Map masked = [:]
        source.each { key, value ->
            if (value instanceof Map) {
                masked[key] = mask((Map) value)
            } else if (value != null && isSensitive(key?.toString())) {
                masked[key] = MASK
            } else {
                masked[key] = value
            }
        }
        return masked
    }

    /** Returns a copy of the given map without any entry whose key appears in excludedKeys. */
    static Map withoutKeys(Map source, Collection<String> excludedKeys) {
        if (source == null) {
            return null
        }
        Collection<String> excluded = excludedKeys ?: []
        return source.findAll { key, value -> !excluded.contains(key?.toString()) }
    }

    /**
     * Returns a copy of {@code grails.config.locations} with any URL user-info masked out.
     * Grails resolves that setting to a String, a List of Strings, or null depending on how it
     * was configured; a location given as a URL - {@code https://user:token@host/config.groovy} -
     * would otherwise disclose those credentials verbatim on the Settings page. Plain file paths
     * and URLs with no user-info are returned unchanged; null becomes an empty String.
     */
    static Object maskUrlUserInfo(Object locations) {
        if (locations == null) {
            return ""
        }
        if (locations instanceof Collection) {
            return locations.collect { maskUrlUserInfoInString(it?.toString()) }
        }
        return maskUrlUserInfoInString(locations.toString())
    }

    private static String maskUrlUserInfoInString(String location) {
        if (location == null) {
            return ""
        }
        Matcher matcher = URL_USER_INFO.matcher(location)
        return matcher.find() ? matcher.replaceFirst('$1***@') : location
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).matches()
    }
}
