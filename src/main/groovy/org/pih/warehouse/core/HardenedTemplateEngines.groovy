/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.core

import fr.opensagres.xdocreport.core.document.DocumentKind
import fr.opensagres.xdocreport.document.registry.TemplateEngineInitializerRegistry
import fr.opensagres.xdocreport.template.ITemplateEngine
import fr.opensagres.xdocreport.template.TemplateEngineKind
import fr.opensagres.xdocreport.template.freemarker.FreemarkerTemplateEngine
import fr.opensagres.xdocreport.template.velocity.cache.XDocReportEntryResourceLoader
import fr.opensagres.xdocreport.template.velocity.internal.VelocityTemplateEngine
import freemarker.core.TemplateClassResolver
import freemarker.template.Configuration
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.util.introspection.SecureUberspector

/**
 * Switches off the code-execution features of the template engines that render uploaded
 * document templates.
 *
 * Document templates are uploaded files, not application resources, so the engine that
 * renders them is running text the uploader chose. XDocReport's stock engines are
 * configured for trusted templates.
 */
class HardenedTemplateEngines {

    /**
     * Hardens the engines XDocReport renders the given kind with - once, process-wide - and
     * returns the kind unchanged, so the caller keeps XDocReport's own engine selection:
     *
     *     XDocReportRegistry.getRegistry().loadReport(inputStream, HardenedTemplateEngines.hardened(kind))
     *
     * The kind is deliberately passed straight through rather than swapped for an engine
     * instance. XDocReport picks a different engine per document kind (DOCX and ODT each get
     * their own, carrying an ITemplateEngineConfiguration that XML-escapes rendered values and
     * turns newlines and tabs into <w:br/> and <w:tab/>), and that choice can only be made after
     * the document has been read. Handing loadReport a pre-built engine would replace it with one
     * that has no configuration at all, so an order whose description contains "&" would render
     * an unopenable document.
     *
     * So each half hardens what XDocReport itself would have used, keeping that configuration:
     * Freemarker through the one Configuration every Freemarker engine shares, Velocity by
     * putting a hardened engine carrying the same configuration into the registry loadReport
     * reads. Neither is done per render.
     */
    static TemplateEngineKind hardened(TemplateEngineKind templateEngineKind) {
        if (templateEngineKind == TemplateEngineKind.Freemarker) {
            // Reading the configuration is what applies the hardening, exactly once, on the first
            // call. Nothing is done with the value here; do not remove the call.
            freemarkerConfiguration()
        } else if (templateEngineKind == TemplateEngineKind.Velocity) {
            // Builds the hardened engines on the first call and, on every call after that, checks
            // they are still the ones XDocReport would hand out. Do not remove the call.
            HardenedVelocity.ensureRegistered()
        }
        return templateEngineKind
    }

    /**
     * The one Freemarker configuration every XDocReport Freemarker engine renders with,
     * already hardened.
     *
     * Freemarker's ?new built-in instantiates a class named by the template. With the default
     * TemplateClassResolver.UNRESTRICTED_RESOLVER that includes
     * freemarker.template.utility.Execute, which runs an operating system command.
     * SAFER_RESOLVER refuses exactly the three classes that make ?new dangerous.
     *
     * api_builtin_enabled already defaults to false, and is pinned here because ?api exposes
     * getClass() on every object placed in the context.
     *
     * XDocReport keeps ONE static Configuration shared by every FreemarkerTemplateEngine it
     * creates, so this hardens Freemarker rendering process-wide rather than for one caller -
     * which is exactly why it is configured ONCE, here, and never touched again. A Configuration
     * is documented as write-once-then-share: reconfiguring it on every render would be mutating
     * an object other threads are reading mid-render.
     */
    static Configuration freemarkerConfiguration() {
        return HardenedFreemarker.CONFIGURATION
    }

    /*
     * Initialisation-on-demand holder. The JVM guarantees the class is initialised once, and only
     * when it is first touched, with no lock on the way out - so the hardening happens exactly
     * once, before the first render, and every caller afterwards reads an already-hardened
     * configuration.
     */
    private static class HardenedFreemarker {

        static final Configuration CONFIGURATION = harden()

        private static Configuration harden() {
            Configuration configuration = renderingEngine().getFreemarkerConfiguration()
            configuration.setNewBuiltinClassResolver(TemplateClassResolver.SAFER_RESOLVER)
            configuration.setAPIBuiltinEnabled(false)
            return configuration
        }

        /*
         * The engine XDocReport hands a .docx report. Reaching the shared configuration through
         * an engine XDocReport itself created, rather than a throwaway one, matters: the first
         * engine to ask for the configuration is the one whose template loader gets installed on
         * it, and that engine should be one that actually renders.
         */
        private static FreemarkerTemplateEngine renderingEngine() {
            ITemplateEngine templateEngine = TemplateEngineInitializerRegistry.getRegistry()
                .getTemplateEngine(TemplateEngineKind.Freemarker, DocumentKind.DOCX)
            return (FreemarkerTemplateEngine) (templateEngine ?: new FreemarkerTemplateEngine())
        }
    }

    /*
     * The two classpath resources VelocityTemplateEngineDiscovery reads. The second is XDocReport's
     * own Velocity defaults, merged last by the discovery, so today it is that file which decides
     * which introspector Velocity uses.
     */
    private static final String VELOCITY_PROPERTIES = "velocity.properties"
    private static final String XDOCREPORT_VELOCITY_PROPERTIES = "xdocreport-velocity.properties"

    /*
     * The Velocity 1 spelling of RuntimeConstants.UBERSPECT_CLASSNAME, which is what
     * xdocreport-velocity.properties uses. Velocity 2 still accepts it: RuntimeInstance feeds the
     * properties through DeprecationAwareExtProperties, which rewrites the old key to the modern
     * one. Both spellings therefore write the same setting, and RuntimeInstance applies them in
     * whatever order Properties.keys() enumerates - unspecified, and measured here as the shipped
     * entry winning every time. Removing it is what lets the line below take effect at all.
     */
    private static final String LEGACY_UBERSPECT_KEY = "runtime.introspector.uberspect"

    /**
     * The hardened Velocity engine XDocReport renders a .docx with - the same instance every time.
     * A plain accessor: it builds the engines on first use, but it is hardened() that keeps them
     * registered, so read this to identify the engine and call hardened() to render through it.
     *
     * Velocity's default introspector lets a template walk from any context object to
     * java.lang.Class and instantiate or invoke anything on the classpath. SecureUberspector
     * blocks Class, ClassLoader and Thread traversal and is the only introspector that honours
     * introspector.restrict.packages and introspector.restrict.classes - both of which
     * XDocReport's shipped defaults already declare, and neither of which anything reads today.
     */
    static ITemplateEngine velocity() {
        return HardenedVelocity.ENGINE
    }

    /**
     * The properties a hardened Velocity engine is built from: everything
     * VelocityTemplateEngineDiscovery would have used, with the introspector switched.
     *
     * Public so a spec can assert on it. Callers get a copy; the engines' own properties are
     * written once, at construction, and never touched again.
     */
    static Properties hardenedVelocityProperties() {
        Properties properties = new Properties()

        /*
         * Faithful to XDocReport 2.0.4's
         * VelocityTemplateEngineDiscovery.getVelocityEngineProperties(Properties, Properties), in
         * its order: an optional velocity.properties from the classpath, then the settings the
         * discovery adds, then XDocReport's own defaults last. Reproducing it is what keeps a
         * hardened engine and a stock one identical in everything but the introspector - the
         * discovery hands its Properties straight to a VelocityTemplateEngine constructor, so
         * there is no other way to reach them. If that method changes in a later XDocReport, this
         * one has to change with it; nothing fails if it does not.
         */
        Properties velocityDefaults = classpathProperties(VELOCITY_PROPERTIES)
        if (velocityDefaults != null) {
            properties.putAll(velocityDefaults)
        }

        if (!properties.containsKey("report.resource.loader.class")) {
            properties.setProperty("resource.loader", "file, class, jar ,report")
            properties.setProperty("report.resource.loader.class", XDocReportEntryResourceLoader.name)
            properties.setProperty("report.resource.loader.cache", "true")
            properties.setProperty("report.resource.loader.modificationCheckInterval", "1")
        }
        properties.setProperty("introspector.conversion_handler.class", "none")
        properties.setProperty("parser.space_gobbling", "bc")
        properties.setProperty("directive.if.empty_check", "false")
        properties.setProperty("parser.allow_hyphen_in_identifiers", "true")
        properties.setProperty("velocimacro.enable_bc_mode", "true")
        properties.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_QUIET, "true")
        properties.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_NULL, "true")
        properties.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_TESTED, "true")

        Properties xdocReportDefaults = classpathProperties(XDOCREPORT_VELOCITY_PROPERTIES)
        if (xdocReportDefaults == null) {
            throw new IllegalStateException("Cannot configure the Velocity template engine: " +
                "${XDOCREPORT_VELOCITY_PROPERTIES} is not on the classpath")
        }
        properties.putAll(xdocReportDefaults)

        properties.remove(LEGACY_UBERSPECT_KEY)
        properties.setProperty(RuntimeConstants.UBERSPECT_CLASSNAME, SecureUberspector.name)
        return properties
    }

    private static Properties classpathProperties(String resourceName) {
        InputStream inputStream = VelocityTemplateEngine.classLoader.getResourceAsStream(resourceName)
        if (inputStream == null) {
            return null
        }
        Properties properties = new Properties()
        inputStream.withStream { properties.load(it) }
        return properties
    }

    /*
     * Initialisation-on-demand holder, as above: the engines are built exactly once, on the first
     * .vm or .vtl render, and never again. Constructing a VelocityTemplateEngine is not the
     * expensive part - VelocityEngine.init() is, and it is deferred to the engine's first use -
     * but building one per rendered document would still mean a fresh RuntimeInstance, a fresh
     * parser pool and an empty introspector cache every time (G3-3).
     *
     * Failure here is deliberately fatal and stays fatal: a throw from a static initialiser is an
     * ExceptionInInitializerError on the first touch and a NoClassDefFoundError on every touch
     * afterwards, so a build that cannot be hardened cannot render at all. XDocReport's own
     * discovery is tolerant instead - it returns null for a missing properties resource and
     * carries on - which is the right default for a vendor and the wrong one for a control that
     * exists to stop template text executing code.
     */
    private static class HardenedVelocity {

        static final Map<DocumentKind, ITemplateEngine> ENGINES = harden()

        static final ITemplateEngine ENGINE =
            ENGINES.get(DocumentKind.DOCX) ?: ENGINES.values().iterator().next()

        /*
         * Re-registers the hardened engines if XDocReport is no longer handing them out, and is a
         * pair of map reads in the ordinary case.
         *
         * This is where Velocity differs from Freemarker, and the asymmetry is worth stating:
         * TemplateEngineInitializerRegistry.dispose() is public and final, and its doDispose()
         * CLEARS the engine cache, so the next lookup re-runs the discoveries and rebuilds stock,
         * unhardened engines. Freemarker's hardening cannot be lost that way because it lives on
         * FreemarkerTemplateEngine's own static Configuration, which no registry owns. Nothing in
         * OpenBoxes calls dispose() today; this makes the guarantee independent of that.
         *
         * The register() call underneath mutates a plain unsynchronised HashMap. That is
         * XDocReport's own pattern - its registries build and mutate those maps the same way - and
         * it is reached here only on the first render and after a dispose, never in the steady
         * state, which is why this method reads before it writes.
         */
        static void ensureRegistered() {
            TemplateEngineInitializerRegistry registry = TemplateEngineInitializerRegistry.getRegistry()
            ENGINES.each { DocumentKind documentKind, ITemplateEngine engine ->
                if (!engine.is(registry.getTemplateEngine(TemplateEngineKind.Velocity, documentKind))) {
                    registry.register(engine, documentKind)
                }
            }
        }

        /*
         * XDocReport keeps one Velocity engine per document kind, and loadReport looks the engine
         * up there by the kind of the document it has just read. So hardening is a matter of
         * putting an engine that carries SecureUberspector where XDocReport will find it, with the
         * document-kind configuration and template cache the stock engine was given - dropping
         * either would cost XML escaping (E8) or the report cache. Both of those are stable
         * singletons, so the same hardened engines stay correct across a dispose and rebuild.
         */
        private static Map<DocumentKind, ITemplateEngine> harden() {
            TemplateEngineInitializerRegistry registry = TemplateEngineInitializerRegistry.getRegistry()
            Map<DocumentKind, ITemplateEngine> hardened = new LinkedHashMap<DocumentKind, ITemplateEngine>()

            for (DocumentKind documentKind : DocumentKind.values()) {
                ITemplateEngine stock =
                    registry.getTemplateEngine(TemplateEngineKind.Velocity, documentKind)
                if (stock == null) {
                    continue
                }
                VelocityTemplateEngine engine = new VelocityTemplateEngine(hardenedVelocityProperties())
                engine.setTemplateCacheInfoProvider(stock.getTemplateCacheInfoProvider())
                // Adds XDocReport's XML-escaping reference handler to the engine's properties, so
                // it has to happen before the engine is first used and its runtime initialised.
                engine.setConfiguration(stock.getConfiguration())
                registry.register(engine, documentKind)
                hardened.put(documentKind, engine)
            }

            if (hardened.isEmpty()) {
                throw new IllegalStateException("Cannot harden the Velocity template engine: " +
                    "XDocReport has no Velocity engine registered for any document kind")
            }
            return hardened
        }
    }
}
