package com.example.hms.config;

import com.example.hms.repository.support.TenantAwareJpaRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Configures Spring Data JPA to use the tenant-aware repository base class for all repositories in the project.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = "com.example.hms.repository",
    repositoryBaseClass = TenantAwareJpaRepository.class
)
public class TenantRepositoryConfig {
}
