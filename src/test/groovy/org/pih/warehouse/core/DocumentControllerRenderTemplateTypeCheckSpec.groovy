package org.pih.warehouse.core

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.shipping.Shipment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * renderInvoiceTemplate, renderShipmentXlsTemplate and renderRequisitionTemplate all render a
 * document through a template engine (JXLS/commons-jexl, with no sandbox) when the caller passes
 * an explicit document id. Each action's fallback/lookup path (used when no id is given) already
 * restricts itself to one document code; the explicit-id path must enforce the same restriction,
 * otherwise any user who can upload an ordinary attachment can have it evaluated as a template of
 * their choosing.
 */
@Unroll
class DocumentControllerRenderTemplateTypeCheckSpec extends Specification
        implements ControllerUnitTest<DocumentController>, DataTest {

    void setupSpec() {
        mockDomains(Document, DocumentType, Shipment, Requisition)
    }

    private DocumentType createDocumentType(DocumentCode documentCode) {
        return new DocumentType(
            name: "type-${documentCode}",
            documentCode: documentCode,
        ).save(failOnError: true, validate: false)
    }

    private Document createDocument(DocumentType documentType, String filename = "doc.xlsx") {
        return new Document(
            name: filename,
            filename: filename,
            fileContents: "harmless".bytes,
            contentType: "application/octet-stream",
            documentType: documentType,
        ).save(failOnError: true, validate: false)
    }

    private Shipment createShipment() {
        return new Shipment(
            name: "test-shipment",
            shipmentNumber: "SHIP-1",
        ).save(failOnError: true, validate: false)
    }

    private Requisition createRequisition() {
        return new Requisition(
            name: "test-requisition",
            requestNumber: "REQ-1",
        ).save(failOnError: true, validate: false)
    }

    // -------------------- renderInvoiceTemplate --------------------

    void "renderInvoiceTemplate renders an explicitly-requested INVOICE_TEMPLATE document"() {
        given:
        Shipment shipment = createShipment()
        Document document = createDocument(createDocumentType(DocumentCode.INVOICE_TEMPLATE))
        controller.params.shipmentId = shipment.id
        controller.params.id = document.id
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        controller.renderInvoiceTemplate()

        then:
        1 * controller.documentTemplateService.renderInvoiceTemplate(document, shipment, _ as ByteArrayOutputStream)
    }

    void "renderInvoiceTemplate refuses an explicitly-requested #documentCode document"() {
        given:
        Shipment shipment = createShipment()
        Document document = createDocument(createDocumentType(documentCode))
        controller.params.shipmentId = shipment.id
        controller.params.id = document.id
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        controller.renderInvoiceTemplate()

        then:
        thrown(Exception)
        0 * controller.documentTemplateService._

        where:
        documentCode << [DocumentCode.PURCHASE_ORDER_TEMPLATE, DocumentCode.SHIPPING_TEMPLATE, DocumentCode.GSP_TEMPLATE]
    }

    // -------------------- renderShipmentXlsTemplate --------------------
    // (documented for completeness: this action's explicit-id path already enforced
    // SHIPPING_XLS_TEMPLATE before this change - see 11b0ab8cc8 - so these two specs are
    // green both before and after the fix.)

    void "renderShipmentXlsTemplate renders an explicitly-requested SHIPPING_XLS_TEMPLATE document"() {
        given:
        Shipment shipment = createShipment()
        Document document = createDocument(createDocumentType(DocumentCode.SHIPPING_XLS_TEMPLATE))
        controller.params.shipmentId = shipment.id
        controller.params.id = document.id
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        controller.renderShipmentXlsTemplate()

        then:
        1 * controller.documentTemplateService.renderShipmentXlsTemplate(document, shipment, _ as ByteArrayOutputStream)
    }

    void "renderShipmentXlsTemplate refuses an explicitly-requested #documentCode document"() {
        given:
        Shipment shipment = createShipment()
        Document document = createDocument(createDocumentType(documentCode))
        controller.params.shipmentId = shipment.id
        controller.params.id = document.id
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        controller.renderShipmentXlsTemplate()

        then:
        thrown(IllegalArgumentException)
        0 * controller.documentTemplateService._

        where:
        documentCode << [DocumentCode.PURCHASE_ORDER_TEMPLATE, DocumentCode.SHIPPING_TEMPLATE, DocumentCode.GSP_TEMPLATE]
    }

    // -------------------- renderRequisitionTemplate --------------------

    void "renderRequisitionTemplate renders an explicitly-requested REQUISITION_TEMPLATE document"() {
        given:
        Requisition requisition = createRequisition()
        Document document = createDocument(createDocumentType(DocumentCode.REQUISITION_TEMPLATE))
        controller.params.id = requisition.id
        controller.params.documentTemplate = [id: document.id]
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        controller.renderRequisitionTemplate()

        then:
        1 * controller.documentTemplateService.renderRequisitionDocumentTemplate(
                document, requisition, null, _ as ByteArrayOutputStream)
    }

    void "renderRequisitionTemplate refuses an explicitly-requested #documentCode document"() {
        given:
        Requisition requisition = createRequisition()
        Document document = createDocument(createDocumentType(documentCode))
        controller.params.id = requisition.id
        controller.params.documentTemplate = [id: document.id]
        controller.documentTemplateService = Mock(DocumentTemplateService)

        when:
        Map model = controller.renderRequisitionTemplate() as Map

        then: 'it fails the same way as when the document template cannot be found at all'
        0 * controller.documentTemplateService._
        model?.requisitionInstance == requisition

        where:
        documentCode << [DocumentCode.PURCHASE_ORDER_TEMPLATE, DocumentCode.SHIPPING_TEMPLATE, DocumentCode.GSP_TEMPLATE]
    }
}
