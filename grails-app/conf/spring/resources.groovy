package spring

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.core.Ordered
import org.springframework.web.util.HttpSessionMutexListener

import org.pih.warehouse.auth.RequestThreadStateFilter
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

    // Detaches the request thread from the request on the way out. The interceptors do this too,
    // but Spring skips every interceptor's afterCompletion when one of them refuses the request,
    // and a filter's finally block cannot be skipped. Ordered just inside the Sentry tracing
    // filter, so the MDC keys are still attached to anything Sentry captures for this request.
    requestThreadStateFilter(RequestThreadStateFilter)
    requestThreadStateFilterRegistration(FilterRegistrationBean) {
        filter = requestThreadStateFilter
        urlPatterns = ['/*']
        order = Ordered.HIGHEST_PRECEDENCE + 2
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
