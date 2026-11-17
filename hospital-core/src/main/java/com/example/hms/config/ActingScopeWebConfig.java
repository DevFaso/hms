package com.example.hms.config;

import com.example.hms.security.tenant.HospitalIdNarrowingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link HospitalIdNarrowingInterceptor} ahead of every other
 * interceptor, so a handler's {@code hospitalId} narrows the request's scope
 * before anything else reads it (docs/security/tenant-resolution.md §3.4).
 *
 * <p>An {@link ObjectProvider}, like {@code PatientAccessAuditConfig}:
 * {@code @WebMvcTest} slices load every {@code WebMvcConfigurer} but not the
 * {@code @Component} interceptor, and must still start.
 */
@Slf4j
@Configuration
public class ActingScopeWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<HospitalIdNarrowingInterceptor> interceptorProvider;

    public ActingScopeWebConfig(ObjectProvider<HospitalIdNarrowingInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        HospitalIdNarrowingInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            log.warn("[ACTING-SCOPE] No HospitalIdNarrowingInterceptor bean — a handler's hospitalId will not "
                + "narrow the request scope. Expected in @WebMvcTest slices; a defect anywhere else.");
            return;
        }
        registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
    }
}
