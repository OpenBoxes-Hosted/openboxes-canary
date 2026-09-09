package org.pih.warehouse.core

import fr.opensagres.xdocreport.template.TemplateEngineKind
import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import org.pih.warehouse.requisition.Requisition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.zip.ZipInputStream

/**
 * The two document-template entry points are the reachable surface: /order/render reaches
 * renderOrderDocumentTemplate and /document/renderRequisitionTemplate reaches
 * renderRequisitionDocumentTemplate. Testing HardenedTemplateEngines alone would still pass if
 * someone put the unhardened XDocReportRegistry.loadReport call back at either call site.
 *
 * One mutation is caught only when this spec runs ALONE: deleting the discarded-result
 * HardenedTemplateEngines.freemarkerConfiguration() call inside hardened(), which leaves
 * hardened() returning the kind so the interaction features below still pass, while any sibling
 * spec that touched the holder has already hardened the process-wide configuration and so masks
 * the refusal features. Run this spec on its own to exercise that one.
 */
@Unroll
class DocumentTemplateServiceFreemarkerSpec extends Specification
        implements ServiceUnitTest<DocumentTemplateService>, DataTest {

    private static final String PAYLOAD = '${"freemarker.template.utility.Execute"?new()("id")}'

    void setupSpec() {
        mockDomains(Document)
    }

    private static Document template(String bodyText) {
        return new Document(
            name: "malicious",
            filename: "template.docx",
            fileContents: DocxFixtures.withBodyText(bodyText),
            contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }

    void "#entryPoint refuses a template that instantiates a class through ?new"() {
        given:
        Document documentTemplate = template(PAYLOAD)
        Requisition requisition = requiresRequisition ? Stub(Requisition) { toJson() >> [:] } : null

        when:
        service."${entryPoint}"(documentTemplate, requisition, null, new ByteArrayOutputStream())

        then: 'the render fails rather than running a command'
        Exception e = thrown()
        assert collectMessages(e).any {
            it.contains("freemarker.template.utility.Execute") && it.contains("not allowed")
        }

        where:
        entryPoint                          | requiresRequisition
        'renderOrderDocumentTemplate'       | false
        'renderRequisitionDocumentTemplate' | true
    }

    /**
     * The refusal above is a property of a process-wide Freemarker configuration, so it can be
     * satisfied by a sibling spec that hardened it first. This asserts the wiring itself, and so
     * fails whatever else has run in the same JVM.
     */
    void "#entryPoint takes the template engine kind from HardenedTemplateEngines"() {
        given:
        GroovyMock(HardenedTemplateEngines, global: true)
        Document documentTemplate = template('Order: ${1 + 1}')
        Requisition requisition = requiresRequisition ? Stub(Requisition) { toJson() >> [:] } : null

        when:
        service."${entryPoint}"(documentTemplate, requisition, null, new ByteArrayOutputStream())

        then:
        1 * HardenedTemplateEngines.hardened(TemplateEngineKind.Freemarker) >> TemplateEngineKind.Freemarker

        where:
        entryPoint                          | requiresRequisition
        'renderOrderDocumentTemplate'       | false
        'renderRequisitionDocumentTemplate' | true
    }

    /**
     * Hardening must not cost the document-kind configuration XDocReport attaches to the engine
     * it picks: without it a rendered value containing "&" produces an unopenable .docx, and a
     * newline stops becoming a line break.
     */
    void "a hardened render still XML-escapes values and turns newlines into line breaks"() {
        given:
        Document documentTemplate = template('${requisition.description}')
        Requisition requisition = Stub(Requisition) { toJson() >> [description: "R&D\nsecond line"] }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()

        when:
        service.renderRequisitionDocumentTemplate(documentTemplate, requisition, null, outputStream)

        then:
        String document = wordDocumentXml(outputStream.toByteArray())
        assert document.contains("R&amp;D")
        assert document.contains("<w:br/>")
    }

    private static String wordDocumentXml(byte[] docx) {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))
        try {
            def entry
            while ((entry = zip.nextEntry) != null) {
                if (entry.name == "word/document.xml") {
                    return zip.text
                }
            }
        } finally {
            zip.close()
        }
        return ""
    }

    private static List<String> collectMessages(Throwable throwable) {
        List<String> collected = []
        Throwable current = throwable
        while (current != null) {
            collected << (current.message ?: "")
            current = current.cause.is(current) ? null : current.cause
        }
        return collected
    }
}
