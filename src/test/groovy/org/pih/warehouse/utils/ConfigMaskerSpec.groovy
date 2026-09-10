package org.pih.warehouse.utils

import spock.lang.Specification
import spock.lang.Unroll

import util.ConfigMasker

@Unroll
class ConfigMaskerSpec extends Specification {

    void 'mask redacts credential-shaped keys at any depth and leaves everything else alone'() {
        when:
        Map masked = ConfigMasker.mask([dataSource: [PassWord: 'x', apiSecretKey: 'y', token: 'z', name: 'n']])

        then:
        masked.dataSource.PassWord == ConfigMasker.MASK
        masked.dataSource.apiSecretKey == ConfigMasker.MASK
        masked.dataSource.token == ConfigMasker.MASK
        masked.dataSource.name == 'n'
    }

    void 'mask redacts #key because it is credential-shaped'() {
        expect:
        ConfigMasker.mask([(key): 'secret-value'])[key] == ConfigMasker.MASK

        where:
        key << ['password', 'PASSWORD', 'PassWord', 'dataSource.password', 'grails.mail.password',
                'OPENBOXES_DB_PASSWORD', 'client_secret', 'OAUTH_CLIENT_SECRET', 'apiKey',
                'openboxes.recaptcha.secretKey', 'accessToken', 'CSRF_TOKEN']
    }

    void 'mask leaves #key alone because it names nothing sensitive'() {
        expect:
        ConfigMasker.mask([(key): 'plain-value'])[key] == 'plain-value'

        where:
        key << ['name', 'dataSource.username', 'dataSource.url', 'grails.mail.host',
                'openboxes.locale.defaultLocale', 'server.contextPath']
    }

    void 'mask does not mutate the map it was given'() {
        given:
        Map source = [password: 'original']

        when:
        ConfigMasker.mask(source)

        then:
        source.password == 'original'
    }

    void 'mask leaves a null value null rather than masking absence'() {
        expect:
        ConfigMasker.mask([password: null]).password == null
    }

    void 'withoutKeys drops exactly the excluded keys'() {
        expect:
        ConfigMasker.withoutKeys([PATH: '/usr/bin', HOME: '/root', 'server.port': '8080'],
                                 ['PATH', 'HOME'] as Set) == ['server.port': '8080']
    }

    void 'maskUrlUserInfo redacts the credentials out of a URL location'() {
        expect:
        ConfigMasker.maskUrlUserInfo('https://user:token@example.com/config.groovy') ==
                'https://***@example.com/config.groovy'
    }

    void 'maskUrlUserInfo leaves a plain file path unchanged'() {
        expect:
        ConfigMasker.maskUrlUserInfo('file:/opt/openboxes/openboxes-config.properties') ==
                'file:/opt/openboxes/openboxes-config.properties'
    }

    void 'maskUrlUserInfo masks a list of locations element-wise'() {
        expect:
        ConfigMasker.maskUrlUserInfo([
                'https://user:token@example.com/config.groovy',
                'file:/opt/openboxes/openboxes-config.properties'
        ]) == [
                'https://***@example.com/config.groovy',
                'file:/opt/openboxes/openboxes-config.properties'
        ]
    }

    void 'maskUrlUserInfo returns an empty string for null'() {
        expect:
        ConfigMasker.maskUrlUserInfo(null) == ''
    }
}
