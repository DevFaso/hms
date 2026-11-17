package com.example.hms.config;

import com.example.hms.controller.support.StrictQueryParamsInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link StrictQueryParamsInterceptor} for every path; it acts only
 * on handlers marked {@code @StrictQueryParams} (QA D798, D801). It has no
 * dependencies, so the {@code @WebMvcTest} slices that load every
 * {@code WebMvcConfigurer} still start, and enforce it.
 */
@Configuration
public class StrictQueryParamsWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StrictQueryParamsInterceptor());
    }
}
