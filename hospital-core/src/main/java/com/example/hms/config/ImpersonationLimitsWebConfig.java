package com.example.hms.config;

import com.example.hms.security.context.ImpersonationLimits;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ImpersonationLimits} on the caller's own account paths.
 * It has no dependencies, so {@code @WebMvcTest} slices load it as they load
 * every {@code WebMvcConfigurer}, and enforce it too.
 */
@Configuration
public class ImpersonationLimitsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImpersonationLimits())
            .addPathPatterns(ImpersonationLimits.ACCOUNT_PATHS.toArray(String[]::new));
    }
}
