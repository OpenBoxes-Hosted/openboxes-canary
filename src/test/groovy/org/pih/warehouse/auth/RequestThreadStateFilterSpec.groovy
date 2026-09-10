package org.pih.warehouse.auth

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

/**
 * Grails 3 runs every interceptor through one Spring HandlerInterceptor,
 * GrailsInterceptorHandlerInterceptorAdapter. When an interceptor's before() returns false that
 * adapter's preHandle returns false, and HandlerExecutionChain.applyPreHandle then triggers
 * afterCompletion only for the interceptors it had already run - which does not include the
 * adapter. So on a halted request NO interceptor's afterView() runs, and anything an earlier
 * interceptor put on the thread stays on it for the next request the pool hands that thread.
 *
 * A filter's finally block is the only cleanup Spring cannot skip.
 */
class RequestThreadStateFilterSpec extends Specification
        implements ServiceUnitTest<AuthService>, DataTest {

    void setupSpec() {
        mockDomain(Location)
        mockDomain(User)
    }

    void cleanup() {
        AuthService.clear()
        MDC.clear()
    }

    void "the filter clears the thread when the interceptor chain halts and afterView never runs"() {
        given: 'a request whose interceptors set the thread state'
        boolean chainRan = false
        User user = new User(username: 'tenant-a', password: 'pass', passwordConfirm: 'pass',
                firstName: 'Tenant', lastName: 'A', email: 'a@example.org').save(validate: false)
        Location location = new Location(name: 'Depot A').save(validate: false)
        AuthService authService = service

        and: 'a chain that sets the state and is then never unwound - SecurityInterceptor.afterView()'
        and: 'deliberately does not run, which is what a halted interceptor chain looks like'
        FilterChain haltedChain = new FilterChain() {
            @Override
            void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                chainRan = true            // proves the filter reached the chain before the finally ran
                authService.setCurrentUser(user)
                authService.setCurrentLocation(location)
                MDC.put('requestUrl', 'https://tenant-a.example.org/openboxes/dashboard/index')
                MDC.put('requestUri', '/openboxes/dashboard/index')
            }
        }

        when:
        new RequestThreadStateFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), haltedChain)

        then: 'nothing is left for the next request on this thread to pick up'
        chainRan                                   // the chain executed, so the finally that follows it is what cleared the state
        AuthService.getCurrentUser() == null
        AuthService.getCurrentLocation() == null
        MDC.get('requestUrl') == null
        MDC.get('requestUri') == null
    }

    void "the filter still clears the thread when the request itself blows up"() {
        given:
        User user = new User(username: 'tenant-a', password: 'pass', passwordConfirm: 'pass',
                firstName: 'Tenant', lastName: 'A', email: 'a@example.org').save(validate: false)
        AuthService authService = service

        FilterChain failingChain = new FilterChain() {
            @Override
            void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                authService.setCurrentUser(user)
                throw new IllegalStateException('the request died here')
            }
        }

        when:
        new RequestThreadStateFilter().doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain)

        then: 'the failure still reaches the container'
        thrown(IllegalStateException)

        and: 'and the thread was cleaned up on the way out'
        AuthService.getCurrentUser() == null
    }
}
