package org.pih.warehouse.core

import fr.opensagres.xdocreport.core.document.DocumentKind
import fr.opensagres.xdocreport.document.IXDocReport
import fr.opensagres.xdocreport.document.registry.TemplateEngineInitializerRegistry
import fr.opensagres.xdocreport.document.registry.XDocReportRegistry
import fr.opensagres.xdocreport.template.IContext
import fr.opensagres.xdocreport.template.ITemplateEngine
import fr.opensagres.xdocreport.template.TemplateEngineKind
import fr.opensagres.xdocreport.template.velocity.discovery.VelocityTemplateEngineDiscovery
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.util.introspection.SecureUberspector
import spock.lang.Specification
import spock.lang.Unroll

import java.util.zip.ZipInputStream

/**
 * A document template whose filename extension is .vm or .vtl is rendered by Velocity.
 * With the stock introspector, template text can walk from any context object to
 * java.lang.Class and from there to any class on the classpath.
 */
@Unroll
class HardenedTemplateEnginesVelocitySpec extends Specification {

    private static final String PAYLOAD = '$order.class.forName("java.lang.Runtime")'

    /** What Class.toString() renders as, which is not a substring of the payload's own text. */
    private static final String RESOLVED = 'class java.lang.Runtime'

    private static String render(ITemplateEngine templateEngine, String templateContents) {
        IContext context = templateEngine.createContext()
        context.put("order", [description: "ACME"])
        StringWriter writer = new StringWriter()
        templateEngine.process("hardened-template-engines-spec", context,
            new StringReader(templateContents), writer)
        return writer.toString()
    }

    /**
     * Renders the way a customer's uploaded template is really rendered: a .docx handed to
     * XDocReportRegistry.loadReport with the Velocity kind, so the engine under test is the one
     * XDocReport itself selected for the document.
     */
    private static String renderDocx(String bodyText, Map<String, Object> model) {
        IXDocReport report = XDocReportRegistry.getRegistry().loadReport(
            new ByteArrayInputStream(DocxFixtures.withBodyText(bodyText)),
            HardenedTemplateEngines.hardened(TemplateEngineKind.Velocity))
        IContext context = report.createContext()
        model.each { String key, Object value -> context.put(key, value) }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        report.process(context, outputStream)
        return wordDocumentXml(outputStream.toByteArray())
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

    /**
     * The negative control, and it stays live: it asks XDocReport's own discovery for the engine
     * it would have built, so it keeps rendering through an unhardened introspector however many
     * times the hardening has already run in this JVM. If this ever starts passing on its own,
     * the features below stop proving anything.
     */
    void "the engine XDocReport builds today reaches java.lang.Runtime from template text"() {
        given:
        ITemplateEngine templateEngine = new VelocityTemplateEngineDiscovery().createTemplateEngine()

        expect:
        assert render(templateEngine, PAYLOAD).contains(RESOLVED)
    }

    void "the hardened properties select SecureUberspector under the Velocity 2 key"() {
        given:
        Properties properties = HardenedTemplateEngines.hardenedVelocityProperties()

        expect:
        assert properties.getProperty(RuntimeConstants.UBERSPECT_CLASSNAME) == SecureUberspector.name

        and: "the Velocity 1 spelling is gone, so Properties iteration order cannot decide"
        assert properties.getProperty("runtime.introspector.uberspect") == null

        and: "the restriction lists the shipped defaults already declare are carried over"
        assert properties.getProperty(RuntimeConstants.INTROSPECTOR_RESTRICT_PACKAGES)
        assert properties.getProperty(RuntimeConstants.INTROSPECTOR_RESTRICT_CLASSES)

        and: "so are the settings VelocityTemplateEngineDiscovery adds on top of them"
        assert properties.getProperty("introspector.conversion_handler.class") == "none"
        assert properties.getProperty("parser.space_gobbling") == "bc"
        assert properties.getProperty("directive.if.empty_check") == "false"
        assert properties.getProperty("parser.allow_hyphen_in_identifiers") == "true"
        assert properties.getProperty("velocimacro.enable_bc_mode") == "true"
        assert properties.getProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_QUIET) == "true"
        assert properties.getProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_NULL) == "true"
        assert properties.getProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_TESTED) == "true"
        assert properties.getProperty("report.resource.loader.class")
    }

    void "the hardened engine cannot reach java.lang.Runtime from template text"() {
        given:
        ITemplateEngine templateEngine = HardenedTemplateEngines.velocity()

        expect:
        assert !render(templateEngine, PAYLOAD).contains(RESOLVED)
    }

    void "the hardened engine still renders an ordinary field"() {
        given:
        ITemplateEngine templateEngine = HardenedTemplateEngines.velocity()

        expect:
        assert render(templateEngine, 'Order: $order.description') == "Order: ACME"
    }

    void "velocity() returns the same engine every time"() {
        expect: "one Velocity RuntimeInstance for the process, not one per rendered document (G3-3)"
        assert HardenedTemplateEngines.velocity().is(HardenedTemplateEngines.velocity())
    }

    /**
     * The wiring, not the engine: XDocReport picks the engine for a loaded report out of the
     * document registry, keyed by document kind. Hardening an engine nothing looks up would leave
     * every render exactly as unsafe as before.
     */
    void "the Velocity engine the document registry hands out for #documentKind is hardened"() {
        given: "the hardening has run"
        HardenedTemplateEngines.hardened(TemplateEngineKind.Velocity)

        and: "the engine loadReport would pick for a document of this kind"
        ITemplateEngine templateEngine = TemplateEngineInitializerRegistry.getRegistry()
            .getTemplateEngine(TemplateEngineKind.Velocity, documentKind)

        expect:
        assert !render(templateEngine, PAYLOAD).contains(RESOLVED)

        and: "and it still renders an ordinary field"
        assert render(templateEngine, 'Order: $order.description').contains("ACME")

        and: "and it kept the document-kind configuration XDocReport gave the engine it replaced"
        assert templateEngine.configuration != null
        assert templateEngine.configuration.escapeXML()

        where:
        documentKind << [DocumentKind.DOCX, DocumentKind.ODT]
    }

    void "a .docx loaded through XDocReport cannot reach java.lang.Runtime"() {
        expect:
        assert !renderDocx(PAYLOAD, [order: [description: "ACME"]]).contains(RESOLVED)
    }

    /**
     * Hardening must not cost the document-kind configuration XDocReport attaches to the engine
     * it picks - for Velocity that configuration is what installs the reference-insertion handler
     * that XML-escapes rendered values, so without it a value containing "&" produces an
     * unopenable .docx and a newline stops becoming a line break (E8).
     */
    void "a hardened Velocity render still XML-escapes values and turns newlines into line breaks"() {
        when:
        String document = renderDocx('$order.description', [order: [description: "R&D\nsecond line"]])

        then:
        assert document.contains("R&amp;D")
        assert document.contains("<w:br/>")
    }
}
