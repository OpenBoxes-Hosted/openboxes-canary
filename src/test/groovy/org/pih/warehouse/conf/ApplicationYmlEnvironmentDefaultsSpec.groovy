package org.pih.warehouse.conf

import org.yaml.snakeyaml.Yaml
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The Grails console plugin executes arbitrary Groovy in the running JVM, and the dbconsole
 * exposes a SQL client over HTTP. Both are development conveniences. Their defaults are
 * asserted here rather than left to a reviewer's eye because the failure is silent: a
 * production deployment with either enabled looks entirely healthy.
 */
@Unroll
class ApplicationYmlEnvironmentDefaultsSpec extends Specification {

    @Shared
    Map productionConfig

    void setupSpec() {
        // application.yml is a multi-document YAML file (four documents, separated by `---`).
        // The `environments` block does not live in the first document, so find whichever
        // document declares it instead of assuming a fixed position.
        Map environments = null
        for (Object document : new Yaml().loadAll(new File('grails-app/conf/application.yml').newInputStream())) {
            if (document instanceof Map && ((Map) document).containsKey('environments')) {
                environments = (Map) ((Map) document).environments
                break
            }
        }
        productionConfig = (Map) environments.production
    }

    void 'the #console console is disabled in the production environment'() {
        expect:
        value(productionConfig, path) == false

        where:
        console    | path
        'Groovy'   | ['grails', 'plugin', 'console', 'enabled']
        'database' | ['grails', 'dbconsole', 'enabled']
    }

    private static Object value(Map root, List<String> path) {
        return path.inject((Object) root) { Object node, String segment ->
            node instanceof Map ? node[segment] : null
        }
    }
}
