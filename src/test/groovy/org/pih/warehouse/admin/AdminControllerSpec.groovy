package org.pih.warehouse.admin

import grails.testing.web.controllers.ControllerUnitTest
import org.pih.warehouse.data.DataService
import org.quartz.Scheduler
import org.springframework.boot.info.GitProperties
import spock.lang.Specification

import util.ConfigMasker

import java.sql.SQLException

/**
 * Administration > Settings renders the merged Grails config. Grails 3.3.16 merges every
 * EnumerablePropertySource into that config, systemEnvironment included, so the page is the
 * one place a container's environment variables become visible over HTTP. These tests pin
 * that the controller - not the view - decides what leaves the JVM.
 */
class AdminControllerSpec extends Specification implements ControllerUnitTest<AdminController> {

    void setup() {
        controller.quartzScheduler = Stub(Scheduler)
        controller.gitProperties = Stub(GitProperties)
    }

    void 'showSettings never puts an environment variable name in the model'() {
        given: 'a config key that shadows a real environment variable, as the merged config does'
        assert System.getenv('PATH') != null
        config.put('PATH', '/should/not/be/rendered')
        config.put('server.contextPath', '/openboxes')

        when:
        Map model = controller.showSettings()

        then:
        !model.externalConfigProperties.containsKey('PATH')
        model.externalConfigProperties['server.contextPath'] == '/openboxes'
    }

    void 'showSettings masks credential-shaped config keys regardless of case'() {
        given:
        config.put('dataSource.PassWord', 'hunter2')
        config.put('openboxes.oauth.clientSecret', 'shhh')
        config.put('dataSource.username', 'openboxes')

        when:
        Map model = controller.showSettings()

        then:
        model.externalConfigProperties['dataSource.PassWord'] == ConfigMasker.MASK
        model.externalConfigProperties['openboxes.oauth.clientSecret'] == ConfigMasker.MASK
        model.externalConfigProperties['dataSource.username'] == 'openboxes'
    }

    void 'showSettings masks system properties as well as config'() {
        given:
        System.setProperty('spec.fake.password', 'hunter2')

        when:
        Map model = controller.showSettings()

        then:
        model.systemProperties['spec.fake.password'] == ConfigMasker.MASK
        model.systemProperties['java.version'] == System.getProperty('java.version')

        cleanup:
        System.clearProperty('spec.fake.password')
    }

    void 'showDatabaseStatus renders an explanation instead of failing when the query is refused'() {
        given: 'a database account without PROCESS - SHOW ENGINE INNODB STATUS is refused'
        controller.dataService = Stub(DataService) {
            executeQuery('show engine innodb status') >> {
                throw new SQLException("Access denied; you need (at least one of) the PROCESS privilege(s)")
            }
        }

        when:
        controller.showDatabaseStatus()

        then:
        noExceptionThrown()
        response.status == 200
        response.text.contains('could not be read')
    }

    void 'showDatabaseStatus renders an explanation instead of failing when the query returns nothing'() {
        given: 'some servers answer the query with no rows rather than refusing it'
        controller.dataService = Stub(DataService) {
            executeQuery('show engine innodb status') >> []
        }

        when:
        controller.showDatabaseStatus()

        then:
        noExceptionThrown()
        response.text.contains('could not be read')
    }

    void 'showDatabaseProcessList renders an explanation instead of failing when the query is refused'() {
        given:
        controller.dataService = Stub(DataService) {
            executeQuery('show processlist') >> { throw new SQLException("Access denied") }
        }

        when:
        controller.showDatabaseProcessList()

        then:
        noExceptionThrown()
        response.text.contains('could not be read')
    }
}
