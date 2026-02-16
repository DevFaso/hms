// AuditingConfig.java
package com.example.hms.config;

import com.example.hms.security.CustomUserDetails;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

@EnableJpaAuditing
@Configuration
public class AuditingConfig {
    @Bean
    public AuditorAware<UUID> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(auth -> (CustomUserDetails) auth.getPrincipal())
            .map(CustomUserDetails::getUserId);
    }
}
