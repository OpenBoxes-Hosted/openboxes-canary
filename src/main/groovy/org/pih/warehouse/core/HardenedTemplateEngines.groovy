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
import freemarker.core.TemplateClassResolver
import freemarker.template.Configuration

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
     * Hardens the Freemarker configuration that XDocReport renders with - once, process-wide -
     * and returns the kind unchanged, so the caller keeps XDocReport's own engine selection:
     *
     *     XDocReportRegistry.getRegistry().loadReport(inputStream, HardenedTemplateEngines.hardened(kind))
     *
     * The kind is deliberately passed straight through rather than swapped for an engine
     * instance. XDocReport picks a different engine per document kind (DOCX and ODT each get
     * their own, carrying an ITemplateEngineConfiguration that XML-escapes rendered values and
     * turns newlines and tabs into <w:br/> and <w:tab/>), and that choice can only be made after
     * the document has been read. Handing loadReport a pre-built engine would replace it with one
     * that has no configuration at all, so an order whose description contains "&" would render
     * an unopenable document. Hardening the shared configuration instead leaves every engine
     * exactly as XDocReport made it.
     */
    static TemplateEngineKind hardened(TemplateEngineKind templateEngineKind) {
        if (templateEngineKind == TemplateEngineKind.Freemarker) {
            // Reading the configuration is what applies the hardening, exactly once, on the first
            // call. Nothing is done with the value here; do not remove the call.
            freemarkerConfiguration()
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
}
