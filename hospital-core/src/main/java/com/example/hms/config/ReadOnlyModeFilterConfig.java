package com.example.hms.config;

import com.example.hms.security.DowntimeStateService;
import com.example.hms.security.ReadOnlyModeFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Registers the downtime write-blocking filter (P3 #23a). A plain
 * {@code @Configuration} rather than {@code @Component} on the filter:
 * {@code @WebMvcTest} slices scan Filter components (and would then demand
 * the DowntimeStateService bean in every controller slice), but they never
 * scan user configuration classes.
 *
 * <p>QA D1404: the MVC mapping is a provider, read at the first POST during a
 * downtime: a filter is built long before the MVC configuration and its graph.
 */
@Configuration
public class ReadOnlyModeFilterConfig {

    @Bean
    public FilterRegistrationBean<ReadOnlyModeFilter> readOnlyModeFilterRegistration(
            DowntimeStateService downtimeStateService,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<HandlerMapping> handlerMapping,
            MessageSource messageSource,
            @Qualifier("localeResolver") LocaleResolver localeResolver) {
        FilterRegistrationBean<ReadOnlyModeFilter> registration = new FilterRegistrationBean<>(
            new ReadOnlyModeFilter(downtimeStateService, handlerMapping, messageSource, localeResolver));
        // Just after the rate limiter — before authentication, so a downtime
        // 503 costs no token validation work.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }
}
