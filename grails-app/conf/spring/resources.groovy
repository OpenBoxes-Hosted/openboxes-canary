package spring

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.core.Ordered
import org.springframework.web.util.HttpSessionMutexListener

import org.pih.warehouse.monitoring.SentryGrailsTracingFilter

// This is where we can register spring-specific beans using the Spring Bean DSL.
// Regular beans that conform to Grails conventions don't need to be registered here.
// https://docs.grails.org/latest/guide/spring.html
beans = {

    // Override Sentry's default tracing filters since Grails behaves slightly differently than SpringBoot.
    sentryTracingFilter(SentryGrailsTracingFilter)
    sentryTracingFilterRegistration(FilterRegistrationBean) {
        filter = sentryTracingFilter
        urlPatterns = ['/*']
        order = Ordered.HIGHEST_PRECEDENCE + 1
    }

    // WebUtils.getSessionMutex(session), which UploadService.uploadMutex relies on to serialise
    // concurrent Phase-1/Phase-2 upload access within one HTTP session (I1), documents its fallback
    // (the HttpSession object itself, kept as one facade per session) as a common CONTAINER
    // BEHAVIOUR, not a Servlet-spec guarantee - which is exactly why Spring ships this listener:
    // registering it sets Spring's own SESSION_MUTEX_ATTRIBUTE on every session at creation, so the
    // mutex is a guaranteed one-per-session object regardless of container.
    httpSessionMutexListener(ServletListenerRegistrationBean) {
        listener = new HttpSessionMutexListener()
    }
}
