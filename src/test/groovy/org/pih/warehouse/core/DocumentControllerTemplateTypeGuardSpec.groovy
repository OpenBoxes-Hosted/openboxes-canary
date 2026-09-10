package org.pih.warehouse.core

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import org.springframework.mock.web.MockMultipartFile
import spock.lang.Specification
import spock.lang.Unroll

/**
 * uploadDocument already refuses template document types. saveDocument re-assigns
 * documentType from the same command object and must refuse them too, otherwise a
 * document uploaded as an attachment can be promoted to a template afterwards.
 */
@Unroll
class DocumentControllerTemplateTypeGuardSpec extends Specification
        implements ControllerUnitTest<DocumentController>, DataTest {

    void setupSpec() {
        mockDomains(Document, DocumentType)
    }

    void setup() {
        // warehouse:message is a deprecated custom namespace whose tag library reaches for beans a
        // controller unit test does not have. Only saveDocument's success branch calls it, and
        // nothing here asserts on the flash message it produces.
        controller.metaClass.getWarehouse = { -> new Expando(message: { Map attrs -> attrs?.code }) }
    }

    private DocumentType createDocumentType(DocumentCode documentCode) {
        return new DocumentType(
            name: "type-${documentCode}",
            documentCode: documentCode,
        ).save(failOnError: true, validate: false)
    }

    private Document createAttachment(DocumentType documentType) {
        return new Document(
            name: "invoice.pdf",
            filename: "invoice.pdf",
            fileContents: "harmless".bytes,
            contentType: "application/pdf",
            documentType: documentType,
        ).save(failOnError: true, validate: false)
    }

    private Document createTemplate(DocumentType documentType) {
        return new Document(
            name: "order:print",
            filename: "template.gsp",
            fileContents: "harmless".bytes,
            contentType: "text/plain",
            documentType: documentType,
        ).save(failOnError: true, validate: false)
    }

    void "saveDocument should refuse to re-type an attachment as #documentCode"() {
        given:
        DocumentType attachmentType = createDocumentType(DocumentCode.SHIPPING_DOCUMENT)
        DocumentType templateType = createDocumentType(documentCode)
        Document document = createAttachment(attachmentType)
        controller.params.documentId = document.id

        when:
        controller.saveDocument(new DocumentCommand(name: document.name, typeId: templateType.id))

        then:
        document.documentType.documentCode == DocumentCode.SHIPPING_DOCUMENT

        where:
        documentCode << [DocumentCode.GSP_TEMPLATE, DocumentCode.PURCHASE_ORDER_TEMPLATE]
    }

    void "saveDocument should still allow an ordinary document type"() {
        given:
        DocumentType attachmentType = createDocumentType(DocumentCode.SHIPPING_DOCUMENT)
        DocumentType otherType = createDocumentType(DocumentCode.PRODUCT_MANUAL)
        Document document = createAttachment(attachmentType)
        controller.params.documentId = document.id

        when:
        controller.saveDocument(new DocumentCommand(name: document.name, typeId: otherType.id))

        then:
        document.documentType.documentCode == DocumentCode.PRODUCT_MANUAL
    }

    /**
     * The write the refusal exists to stop. saveDocument loads an EXISTING document by
     * documentId and replaces its bytes from the command object, so a caller that leaves
     * typeId out never supplies a template type at all - the type check has nothing to
     * look at, and the file contents of a document that is already a template are
     * replaced anyway. New Groovy, planted by a non-superuser, through an action no
     * template check ever ran in.
     */
    void "saveDocument should not replace the contents of an existing #documentCode document"() {
        given:
        DocumentType templateType = createDocumentType(documentCode)
        Document template = createTemplate(templateType)
        controller.params.documentId = template.id

        when: 'a new file is uploaded over the template, with no typeId supplied'
        controller.saveDocument(new DocumentCommand(
            name: template.name,
            fileContents: new MockMultipartFile(
                "fileContents", "evil.gsp", "text/plain", '<% "id".execute() %>'.bytes),
        ))

        then: 'the bytes are untouched'
        new String(template.fileContents) == "harmless"

        and: 'and so is the type'
        template.documentType.documentCode == documentCode

        and: 'and the reason is recorded on the document, not swallowed'
        template.errors.hasErrors()
        template.errors.allErrors.any { it.codes.toList().contains('documentType') }

        where:
        documentCode << [DocumentCode.GSP_TEMPLATE, DocumentCode.PURCHASE_ORDER_TEMPLATE]
    }

    void "saveDocument should still replace the contents of an ordinary document"() {
        given:
        DocumentType attachmentType = createDocumentType(DocumentCode.SHIPPING_DOCUMENT)
        Document document = createAttachment(attachmentType)
        controller.params.documentId = document.id

        when:
        controller.saveDocument(new DocumentCommand(
            name: document.name,
            typeId: attachmentType.id,
            fileContents: new MockMultipartFile(
                "fileContents", "replacement.pdf", "application/pdf", "replacement".bytes),
        ))

        then:
        new String(document.fileContents) == "replacement"
        !document.errors.hasErrors()
    }

    /**
     * The bypass G3-1 found, at the level of the shared helper: omit typeId and the incoming
     * documentType is null, so a check that looks only at the incoming type waves the request
     * through - onto a document that is ALREADY a template. The helper therefore rejects when
     * either side names a template code.
     */
    void "an upload to an existing #documentCode document is rejected even with no typeId"() {
        given:
        DocumentType templateType = createDocumentType(documentCode)
        Document template = createTemplate(templateType)

        expect: 'the helper refuses on the document CURRENT type, with nothing supplied'
        controller.rejectTemplateDocumentType(template, null)

        and: 'and the reason is recorded on the document, not swallowed'
        template.errors.hasErrors()
        template.errors.allErrors.any { it.codes.toList().contains('documentType') }

        where:
        documentCode << [DocumentCode.GSP_TEMPLATE, DocumentCode.PURCHASE_ORDER_TEMPLATE]
    }

    void "an upload to an ordinary document with no typeId is still allowed"() {
        given:
        DocumentType attachmentType = createDocumentType(DocumentCode.SHIPPING_DOCUMENT)
        Document document = createAttachment(attachmentType)

        expect:
        !controller.rejectTemplateDocumentType(document, null)
    }

    void "an upload of a #documentCode type onto an ordinary document is rejected"() {
        given:
        DocumentType attachmentType = createDocumentType(DocumentCode.SHIPPING_DOCUMENT)
        DocumentType templateType = createDocumentType(documentCode)
        Document document = createAttachment(attachmentType)

        expect:
        controller.rejectTemplateDocumentType(document, templateType)

        where:
        documentCode << [DocumentCode.GSP_TEMPLATE, DocumentCode.PURCHASE_ORDER_TEMPLATE]
    }
}
