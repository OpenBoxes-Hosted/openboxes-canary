package org.pih.warehouse.core

import fr.opensagres.xdocreport.template.TemplateEngineKind
import freemarker.core.TemplateClassResolver
import freemarker.template.Configuration
import freemarker.template.Template
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Order and requisition document templates are uploaded files rendered by Freemarker,
 * so whatever Freemarker can do from template text, an uploader can do.
 */
@Unroll
class HardenedTemplateEnginesFreemarkerSpec extends Specification {

    // The three classes TemplateClassResolver.SAFER_RESOLVER refuses.
    private static final String EXECUTE = "freemarker.template.utility.Execute"
    private static final String OBJECT_CONSTRUCTOR = "freemarker.template.utility.ObjectConstructor"
    private static final String JYTHON_RUNTIME = "freemarker.template.utility.JythonRuntime"

    // XDocReport puts its Freemarker configuration in SQUARE_BRACKET_TAG_SYNTAX, so an uploaded
    // template writes directives as [#assign]; ${...} interpolation works in either syntax.
    private static String interpolated(String className) {
        return '${"' + className + '"?new()("id")}'
    }

    private static String directive(String className) {
        return '[#assign command = "' + className + '"?new()]${command("id")}'
    }

    /** Renders the way XDocReport renders an uploaded template: a Template over the shared configuration. */
    private static String render(String templateContents) {
        Template template = new Template("hardened-template-engines-spec",
            new StringReader(templateContents), HardenedTemplateEngines.freemarkerConfiguration())
        StringWriter writer = new StringWriter()
        template.process([order: [description: "ACME"]], writer)
        return writer.toString()
    }

    private static List<String> messages(Throwable throwable) {
        List<String> collected = []
        Throwable current = throwable
        while (current != null) {
            collected << (current.message ?: "")
            current = current.cause.is(current) ? null : current.cause
        }
        return collected
    }

    void "an unconfigured Freemarker configuration is the reason this hardening exists"() {
        expect: "freemarker's own default resolver allows ?new on any class"
        assert new Configuration().newBuiltinClassResolver == TemplateClassResolver.UNRESTRICTED_RESOLVER
    }

    /**
     * SAFER_RESOLVER refuses exactly three classes, and refuses them by name before it tries to
     * load them - so all three are asserted here even though JythonRuntime needs a jython that is
     * not on the classpath. Both template forms an uploader can write are covered.
     */
    void "the hardened configuration refuses to instantiate #className through ?new [#payload]"() {
        when:
        render(payload)

        then:
        Exception e = thrown()
        assert messages(e).any { it.contains(className) && it.contains("not allowed") }

        where:
        className          | payload
        EXECUTE            | interpolated(EXECUTE)
        OBJECT_CONSTRUCTOR | interpolated(OBJECT_CONSTRUCTOR)
        JYTHON_RUNTIME     | interpolated(JYTHON_RUNTIME)
        EXECUTE            | directive(EXECUTE)
        OBJECT_CONSTRUCTOR | directive(OBJECT_CONSTRUCTOR)
        JYTHON_RUNTIME     | directive(JYTHON_RUNTIME)
    }

    /* Regression guard, not a fix: api_builtin_enabled already defaults to false in
     * freemarker 2.3.28. Pinning it keeps ?api (which reaches getClass() on any exposed
     * object) off if the default or the Configuration ever changes. */
    void "the hardened configuration refuses the ?api built-in"() {
        when:
        render('${order?api.class.name}')

        then:
        Exception e = thrown()
        assert messages(e).any { it.contains("api_builtin_enabled") }
    }

    void "the hardened configuration still renders an ordinary field"() {
        expect:
        assert render('Order: ${order.description}') == "Order: ACME"
    }

    void "the configuration is built once and shared, not reconfigured per render"() {
        expect: "no per-render mutation of a shared Configuration - see C7"
        assert HardenedTemplateEngines.freemarkerConfiguration()
                .is(HardenedTemplateEngines.freemarkerConfiguration())

        and: "and it is already hardened before anyone renders anything"
        assert HardenedTemplateEngines.freemarkerConfiguration().newBuiltinClassResolver ==
                TemplateClassResolver.SAFER_RESOLVER

        and: "api_builtin_enabled is pinned off rather than left on freemarker's default"
        assert !HardenedTemplateEngines.freemarkerConfiguration().isAPIBuiltinEnabled()
    }

    void "hardened() returns the kind it was given, so XDocReport still picks its own engine"() {
        expect: "the Freemarker kind is passed through, having triggered the hardening"
        assert HardenedTemplateEngines.hardened(TemplateEngineKind.Freemarker) == TemplateEngineKind.Freemarker

        and: "and this is a strict no-op for .vm/.vtl templates"
        assert HardenedTemplateEngines.hardened(TemplateEngineKind.Velocity) == TemplateEngineKind.Velocity
    }

    /**
     * The regression C7 is really about: with the configuration mutated per render, two concurrent
     * renders interleave on one shared Configuration object. This drives a benign and a malicious
     * template at the same time, repeatedly, and requires that the benign one always renders
     * correctly and the malicious one always fails.
     */
    void "a malicious render running concurrently cannot affect a benign one"() {
        given:
        int rounds = 50
        ExecutorService pool = Executors.newFixedThreadPool(2)

        when:
        List<Future<String>> benignResults = []
        List<Future<Boolean>> maliciousRefused = []
        rounds.times {
            benignResults << pool.submit({ render('Order: ${order.description}') } as Callable<String>)
            maliciousRefused << pool.submit({
                try {
                    render(interpolated(EXECUTE))
                    return false
                } catch (Exception ignored) {
                    return true
                }
            } as Callable<Boolean>)
        }

        then:
        assert benignResults.every { it.get() == "Order: ACME" }
        assert maliciousRefused.every { it.get() }

        cleanup:
        pool.shutdownNow()
    }
}
