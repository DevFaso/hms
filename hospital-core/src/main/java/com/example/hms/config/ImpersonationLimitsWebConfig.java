package com.example.hms.config;

import com.example.hms.security.context.ImpersonationLimits;
import com.example.hms.service.AuditEventLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ImpersonationLimits} on the caller's own account paths.
 * The audit service comes through an {@link ObjectProvider}, like
 * {@code ActingScopeWebConfig}'s interceptor, so {@code @WebMvcTest} slices
 * (which load every {@code WebMvcConfigurer}) start, and enforce the rule.
 */
@Configuration
public class ImpersonationLimitsWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<AuditEventLogService> auditProvider;

    public ImpersonationLimitsWebConfig(ObjectProvider<AuditEventLogService> auditProvider) {
        this.auditProvider = auditProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImpersonationLimits(auditProvider::getIfAvailable))
            .addPathPatterns(ImpersonationLimits.ACCOUNT_PATHS.toArray(String[]::new));
    }
}
