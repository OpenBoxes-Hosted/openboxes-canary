package org.pih.warehouse

import spock.lang.Specification
import spock.lang.Unroll

/**
 * The document CRUD screens can set an arbitrary document type and replace an
 * arbitrary document's contents, which is how a template document is created.
 */
@Unroll
class RoleInterceptorTemplateDocumentSpec extends Specification {

    void "needSuperuser should be true for document:#actionName"() {
        expect:
        RoleInterceptor.needSuperuser('document', actionName)

        where:
        actionName << ['create', 'save', 'update', 'upload']
    }

    void "needSuperuser should stay false for the attachment actions"() {
        expect:
        !RoleInterceptor.needSuperuser('document', actionName)

        where:
        actionName << ['uploadDocument', 'saveDocument', 'download', 'list', 'show']
    }
}
