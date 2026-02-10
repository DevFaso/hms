package com.example.hms.config;

import com.example.hms.service.platform.event.NoopPlatformRegistryEventPublisher;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Local development configuration to override certain beans when needed for
 * local testing
 */
@Configuration
@Profile("dev")
public class LocalDevConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformRegistryEventPublisher.class)
    public PlatformRegistryEventPublisher platformRegistryEventPublisher() {
        return new NoopPlatformRegistryEventPublisher();
    }
}