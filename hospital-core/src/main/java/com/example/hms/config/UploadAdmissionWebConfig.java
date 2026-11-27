package com.example.hms.config;

import com.example.hms.upload.UploadAdmissionInterceptor;
import com.example.hms.upload.UploadLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link UploadAdmissionInterceptor} for every path; it acts only on
 * multipart requests that carry a file. The limiter is taken through an
 * {@link ObjectProvider}, so the {@code @WebMvcTest} slices that load every
 * {@code WebMvcConfigurer} still start (#536), and still check file content.
 */
@Configuration
public class UploadAdmissionWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<UploadLimiter> limiter;

    public UploadAdmissionWebConfig(ObjectProvider<UploadLimiter> limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UploadAdmissionInterceptor(limiter));
    }
}
