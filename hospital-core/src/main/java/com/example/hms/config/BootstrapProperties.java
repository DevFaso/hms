package com.example.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "app.bootstrap")
@Getter
@Setter
public class BootstrapProperties {
    /** Optional shared secret required for first user bootstrap; blank disables requirement */
    private String token;
}
