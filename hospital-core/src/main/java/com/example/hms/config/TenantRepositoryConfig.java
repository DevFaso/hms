package com.example.hms.config;

import com.example.hms.repository.support.TenantAwareJpaRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Configures Spring Data JPA to use the tenant-aware repository base class for all repositories in the project.
 *
 * <p>{@code entityManagerFactoryRef} and {@code transactionManagerRef} are set
 * explicitly so that Spring Data's strict-mode repository configuration (which
 * is activated whenever a second Spring Data module — e.g. Spring Data Redis —
 * is present on the classpath) can unambiguously wire the shared
 * {@code EntityManager} into every repository. Without the explicit refs,
 * strict mode fails with
 * {@code Cannot resolve reference to bean 'jpaSharedEM_entityManagerFactory'}.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = {
        "com.example.hms.repository",
        "com.example.hms.patient.repository"
    },
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager",
    repositoryBaseClass = TenantAwareJpaRepository.class
)
public class TenantRepositoryConfig {
}
