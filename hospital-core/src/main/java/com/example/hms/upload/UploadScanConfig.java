package com.example.hms.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies {@link NoOpUploadScanner} unless another {@link UploadScanner} bean is declared. */
@Configuration
public class UploadScanConfig {

    @Bean
    @ConditionalOnMissingBean(UploadScanner.class)
    public UploadScanner noOpUploadScanner() {
        return new NoOpUploadScanner();
    }
}
