package org.pih.warehouse

import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.Unroll

/**
 * before() puts nine keys into the MDC and afterView() is meant to take all nine out again. It
 * removed 'requestUri' twice and 'requestUrl' never, so a request's URL stayed on the pooled thread
 * and was attached - by the log pattern and by Sentry, which picks up every MDC tag - to whatever
 * that thread served next, including requests this interceptor does not match.
 */
@Unroll
class LoggingInterceptorSpec extends Specification {

    void cleanup() {
        MDC.clear()
    }

    void "afterView() removes '#key', so it cannot follow the thread to the next request"() {
        given:
        MDC.put(key, 'value from the previous request')

        when:
        new LoggingInterceptor().afterView()

        then:
        MDC.get(key) == null

        where:
        key << ['sessionId', 'username', 'location', 'locale', 'ipAddress',
                'requestUri', 'requestUrl', 'queryString', 'serverUrl']
    }

    void "afterView() leaves nothing behind"() {
        given:
        ['sessionId', 'username', 'location', 'locale', 'ipAddress',
         'requestUri', 'requestUrl', 'queryString', 'serverUrl'].each { MDC.put(it, 'x') }

        when:
        new LoggingInterceptor().afterView()

        then:
        !MDC.copyOfContextMap
    }
}
