package com.chrainx.compliance_tracker.config;

import com.chrainx.compliance_tracker.logging.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// CorrelationIdFilter is a plain @Component, which Spring Boot would otherwise auto-register as
// a servlet filter at its default order (Ordered.LOWEST_PRECEDENCE - i.e. last). That's the
// wrong place for it here: Spring Security's own filter chain (registered separately, at a much
// earlier order) can reject a request outright (401/403) before it ever reaches a
// LOWEST_PRECEDENCE filter, meaning a rejected request's log lines would never get a
// correlation ID at all - exactly the requests most worth being able to trace (issue #51). This
// bean explicitly registers it at HIGHEST_PRECEDENCE instead, so it runs before Security's own
// chain and every request, accepted or rejected, gets one.
@Configuration
public class LoggingConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
