package org.pih.warehouse.core

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import groovy.text.Template
import org.grails.gsp.GroovyPagesTemplateEngine
import spock.lang.Specification

/**
 * The GSP engine compiles template contents as Groovy, so it must only ever be
 * handed a document that an administrator deliberately marked as a GSP template.
 * Content-level safety for the non-GSP engines is tested elsewhere.
 */
class DocumentTemplateServiceGspGateSpec extends Specification
        implements ServiceUnitTest<DocumentTemplateService>, DataTest {

    private static final String SCRIPTLET = '<% Runtime.runtime.exec("id") %>'

    void setupSpec() {
        mockDomains(Document, DocumentType)
    }

    private Document createDocument(DocumentCode documentCode, String name) {
        DocumentType documentType = new DocumentType(
            name: "type-${documentCode}",
            documentCode: documentCode,
        ).save(failOnError: true, validate: false)

        return new Document(
            name: name,
            filename: "template.gsp",
            fileContents: SCRIPTLET.bytes,
            contentType: "text/plain",
            documentType: documentType,
        ).save(failOnError: true, validate: false)
    }

    private Document createUntypedDocument(String name) {
        return new Document(
            name: name,
            filename: "template.gsp",
            fileContents: SCRIPTLET.bytes,
            contentType: "text/plain",
            documentType: null,
        ).save(failOnError: true, validate: false)
    }

    void "renderGroovyServerPageDocumentTemplate should refuse a document of another code"() {
        given:
        Document document = createDocument(DocumentCode.PURCHASE_ORDER_TEMPLATE, "order:print")

        when:
        service.renderGroovyServerPageDocumentTemplate(document, [:])

        then:
        IllegalArgumentException e = thrown()
        assert e.message.contains(DocumentCode.GSP_TEMPLATE.name())
    }

    void "renderGroovyServerPageDocumentTemplate should refuse a document with no type"() {
        given:
        Document document = createUntypedDocument("order:print")

        when:
        service.renderGroovyServerPageDocumentTemplate(document, [:])

        then:
        thrown(IllegalArgumentException)
    }

    void "renderGroovyServerPageDocumentTemplate should render a GSP_TEMPLATE document"() {
        given:
        Document document = createDocument(DocumentCode.GSP_TEMPLATE, "order:print")
        service.groovyPagesTemplateEngine = Stub(GroovyPagesTemplateEngine) {
            createTemplate(SCRIPTLET, "order:print") >> Stub(Template) {
                make(_) >> ({ Writer writer -> writer << "rendered-content" } as Writable)
            }
        }

        expect:
        assert service.renderGroovyServerPageDocumentTemplate(document, [:]) == "rendered-content"
    }

    void "findGroovyServerPageTemplate should not return a document of another code"() {
        given:
        createDocument(DocumentCode.PURCHASE_ORDER_TEMPLATE, "order:print")

        expect:
        assert service.findGroovyServerPageTemplate("order:print") == null
    }

    void "findGroovyServerPageTemplate should return a GSP_TEMPLATE document by name"() {
        given:
        Document document = createDocument(DocumentCode.GSP_TEMPLATE, "order:print")

        expect:
        assert service.findGroovyServerPageTemplate("order:print")?.id == document.id
    }

    void "GSP_TEMPLATE should be a template code so ordinary uploads cannot set it"() {
        expect:
        assert DocumentCode.templateList().contains(DocumentCode.GSP_TEMPLATE)
    }
}
