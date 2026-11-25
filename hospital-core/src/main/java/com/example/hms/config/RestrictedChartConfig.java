package com.example.hms.config;

import com.example.hms.security.chart.RestrictedChartGuardInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RestrictedChartGuardInterceptor} (QA D3863) on every handler.
 *
 * <p>Through an {@link ObjectProvider}, like {@code PatientAccessAuditConfig}:
 * {@code @WebMvcTest} slices load {@code WebMvcConfigurer}s but not the
 * interceptor's services, and a hard dependency here fails every controller
 * slice. A missing interceptor outside a slice is logged at startup.
 */
@Slf4j
@Configuration
public class RestrictedChartConfig implements WebMvcConfigurer {

    private final ObjectProvider<RestrictedChartGuardInterceptor> interceptorProvider;

    public RestrictedChartConfig(ObjectProvider<RestrictedChartGuardInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RestrictedChartGuardInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            log.warn("[RESTRICTED-CHART] No RestrictedChartGuardInterceptor bean: a GET naming a restricted chart "
                + "is NOT refused up front. Expected in @WebMvcTest slices; a defect anywhere else.");
            return;
        }
        // No path pattern: the interceptor reads the matched handler and its route.
        registry.addInterceptor(interceptor);
    }
}
