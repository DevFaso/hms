package com.example.hms.config;

import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class TestPlatformRegistryEventPublisherConfig {

    @Bean
    public PlatformRegistryEventPublisher platformRegistryEventPublisher() {
        return payload -> {
            // no-op for tests
        };
    }
}
