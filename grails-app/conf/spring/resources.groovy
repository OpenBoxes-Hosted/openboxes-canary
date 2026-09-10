package spring

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered

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
}
