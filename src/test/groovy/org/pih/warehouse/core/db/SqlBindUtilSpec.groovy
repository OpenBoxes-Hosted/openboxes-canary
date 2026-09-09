package org.pih.warehouse.core.db

import spock.lang.Specification
import spock.lang.Unroll

import org.pih.warehouse.core.db.SqlBindUtil

@Unroll
class SqlBindUtilSpec extends Specification {

    void 'bindValue should bind the value and return only a parameter reference'() {
        given: 'an attacker-controlled value'
        String payload = "1); DROP TABLE `order`; -- "
        Map params = [:]

        when:
        String reference = SqlBindUtil.bindValue('orderId', payload, params)

        then: 'the SQL fragment names a parameter and never contains the payload'
        reference == ':orderId'
        !reference.contains('DROP TABLE')

        and: 'the payload is carried as a bound value, verbatim'
        params == ['orderId': payload]
    }

    void 'bindList should bind one parameter per item and never emit the item text'() {
        given: 'an id list where the second id is an injection attempt'
        String payload = "x' OR 1=1 -- "
        Map params = [:]

        when:
        String reference = SqlBindUtil.bindList('categoryId', ['CAT-1', payload], params)

        then: 'the SQL fragment is placeholders only'
        reference == ':categoryId0, :categoryId1'
        !reference.contains('OR 1=1')
        !reference.contains("'")

        and: 'every item is bound, verbatim, one binding per item'
        params == ['categoryId0': 'CAT-1', 'categoryId1': payload]
    }

    void 'bindList should return #expectedReference and bind #expectedParams for #values'() {
        given:
        Map params = [:]

        when:
        String reference = SqlBindUtil.bindList('id', values, params)

        then:
        reference == expectedReference
        params == expectedParams

        where:
        values     || expectedReference | expectedParams
        null       || 'null'            | [:]
        []         || 'null'            | [:]
        ['a']      || ':id0'            | ['id0': 'a']
        ['a', 'b'] || ':id0, :id1'      | ['id0': 'a', 'id1': 'b']
    }
}
