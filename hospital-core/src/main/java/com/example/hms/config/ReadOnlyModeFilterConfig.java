package com.example.hms.config;

import com.example.hms.security.DowntimeStateService;
import com.example.hms.security.ReadOnlyModeFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the downtime write-blocking filter (P3 #23a). A plain
 * {@code @Configuration} rather than {@code @Component} on the filter:
 * {@code @WebMvcTest} slices scan Filter components (and would then demand
 * the DowntimeStateService bean in every controller slice), but they never
 * scan user configuration classes.
 */
@Configuration
public class ReadOnlyModeFilterConfig {

    @Bean
    public FilterRegistrationBean<ReadOnlyModeFilter> readOnlyModeFilterRegistration(
            DowntimeStateService downtimeStateService) {
        FilterRegistrationBean<ReadOnlyModeFilter> registration =
            new FilterRegistrationBean<>(new ReadOnlyModeFilter(downtimeStateService));
        // Just after the rate limiter — before authentication, so a downtime
        // 503 costs no token validation work.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }
}
