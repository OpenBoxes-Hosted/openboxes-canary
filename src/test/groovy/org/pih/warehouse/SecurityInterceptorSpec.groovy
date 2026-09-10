package org.pih.warehouse

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

/**
 * afterView() is Spring's afterCompletion. GrailsInterceptorHandlerInterceptorAdapter runs every
 * matched interceptor's afterView() in one loop with no exception handling around it, so anything
 * thrown here skips the cleanup of every other interceptor in the request. Detaching the thread
 * from its user therefore has to work without a collaborator and without a transaction.
 */
class SecurityInterceptorSpec extends Specification implements ServiceUnitTest<AuthService>, DataTest {

    void setupSpec() {
        mockDomain(Location)
        mockDomain(User)
    }

    void cleanup() {
        AuthService.clear()
    }

    void "afterView() detaches the thread from the user and location, with nothing wired in"() {
        given: 'a request thread carrying a user and a location'
        User user = new User(username: 'tenant-a', password: 'pass', passwordConfirm: 'pass',
                firstName: 'Tenant', lastName: 'A', email: 'a@example.org').save(validate: false)
        Location location = new Location(name: 'Depot A').save(validate: false)
        service.setCurrentUser(user)
        service.setCurrentLocation(location)

        and:
        assert AuthService.getCurrentUser()?.id == user.id
        assert AuthService.getCurrentLocation()?.id == location.id

        and: 'an interceptor with no collaborators injected'
        SecurityInterceptor interceptor = new SecurityInterceptor()

        when: 'the request unwinds'
        interceptor.afterView()

        then: 'nothing is left for the next request on this thread to pick up'
        AuthService.getCurrentUser() == null
        AuthService.getCurrentLocation() == null

        and: 'and clearing needed nothing else, so it cannot abort the interceptor unwind'
        noExceptionThrown()
    }
}
