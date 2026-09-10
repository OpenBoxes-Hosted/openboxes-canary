/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.auth

import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Detaches the request thread from the request before the container reuses it.
 *
 * SecurityInterceptor.afterView() and LoggingInterceptor.afterView() already do this, and on an
 * ordinary request they are enough. They are not enough on a request that an interceptor refuses:
 * Grails runs every interceptor through one Spring HandlerInterceptor, and when that one's
 * preHandle returns false Spring's HandlerExecutionChain does not call its afterCompletion, so no
 * interceptor's afterView() runs. A denied request would then hand the next request on that pooled
 * thread a user identity and a logging context that are not its own.
 *
 * Registered in grails-app/conf/spring/resources.groovy.
 */
class RequestThreadStateFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            try {
                AuthService.clear()
            } catch (Exception e) {
                logger.error("Unable to clear the current user and location from the request thread", e)
            }
            // The whole map, not a list of keys: on a refused request none of the nine keys
            // LoggingInterceptor.before() sets is ever removed, and request-scoped keys put there
            // by anything else leak the same way. Nothing outlives a request in the MDC.
            MDC.clear()
        }
    }
}
