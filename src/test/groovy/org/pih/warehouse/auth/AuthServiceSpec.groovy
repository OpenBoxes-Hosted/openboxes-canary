package org.pih.warehouse.auth

import java.lang.reflect.Field
import java.lang.reflect.Modifier

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification
import spock.lang.Unroll

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

@Unroll
class AuthServiceSpec extends Specification implements ServiceUnitTest<AuthService>, DataTest {

    void setupSpec() {
        mockDomain(Location)
        mockDomain(User)
    }

    void cleanup() {
        AuthService.clear()
    }

    /**
     * The ThreadLocals used to be created lazily inside the setters, with a plain
     * "if (!field) { field = new ThreadLocal() }" on a non-volatile static. Two threads racing the
     * first request after a restart could each construct one; the loser's set() then landed on an
     * object the static getter no longer read, and a signed-in user looked signed out.
     */
    void "#fieldName is created once, up front, and cannot be replaced"() {
        given:
        Field field = AuthService.getDeclaredField(fieldName)
        field.accessible = true

        expect:
        Modifier.isStatic(field.modifiers)
        Modifier.isFinal(field.modifiers)
        field.get(null) != null

        where:
        fieldName << ['threadLocalUser', 'threadLocalLocation']
    }

    void "clear() detaches the thread from the user and location it was serving"() {
        given:
        User user = new User(username: 'tenant-a', password: 'pass', passwordConfirm: 'pass',
                firstName: 'Tenant', lastName: 'A', email: 'a@example.org').save(validate: false)
        Location location = new Location(name: 'Depot A').save(validate: false)

        and: 'written through the injected service and read through the class, spelled out: p4'
        and: 'declares the setters as instance methods and the getters as static ones, and the'
        and: 'property syntax hides that asymmetry rather than resolving it'
        service.setCurrentUser(user)
        service.setCurrentLocation(location)

        and:
        assert AuthService.getCurrentUser()?.id == user.id
        assert AuthService.getCurrentLocation()?.id == location.id

        when:
        AuthService.clear()

        then:
        AuthService.getCurrentUser() == null
        AuthService.getCurrentLocation() == null
    }

    void "clear() is safe to call on a thread that never served a request"() {
        when:
        AuthService.clear()
        AuthService.clear()

        then:
        noExceptionThrown()
        AuthService.getCurrentUser() == null
    }
}
