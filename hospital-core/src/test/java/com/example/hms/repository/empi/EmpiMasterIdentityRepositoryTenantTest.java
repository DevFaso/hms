package com.example.hms.repository.empi;

import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.repository.support.TenantAwareJpaRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
    "spring.jpa.defer-datasource-initialization=true",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "spring.sql.init.continue-on-error=true",
    "spring.liquibase.enabled=false"
})
@Import(EmpiMasterIdentityRepositoryTenantTest.JpaConfig.class)
class EmpiMasterIdentityRepositoryTenantTest {

    @Autowired
    private EmpiMasterIdentityRepository repository;

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void findAllRespectsTenantScopeFromContext() {
        UUID permittedOrganization = UUID.randomUUID();
        UUID permittedHospital = UUID.randomUUID();

        seedIdentity(HospitalContext.builder().superAdmin(true).build(),
            EmpiMasterIdentity.builder()
                .empiNumber("EMPI-ALLOWED")
                .organizationId(permittedOrganization)
                .hospitalId(permittedHospital)
                .build());

        seedIdentity(HospitalContext.builder().superAdmin(true).build(),
            EmpiMasterIdentity.builder()
                .empiNumber("EMPI-DENIED")
                .organizationId(UUID.randomUUID())
                .hospitalId(UUID.randomUUID())
                .build());

        HospitalContext scopedContext = HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedOrganizationIds(Set.of(permittedOrganization))
            .permittedHospitalIds(Set.of(permittedHospital))
            .build();
        HospitalContextHolder.setContext(scopedContext);

        List<EmpiMasterIdentity> results = repository.findAll();

        assertThat(results)
            .hasSize(1)
            .first()
            .extracting(EmpiMasterIdentity::getOrganizationId, EmpiMasterIdentity::getHospitalId)
            .containsExactly(permittedOrganization, permittedHospital);
    }

    @Test
    void findAllReturnsEmptyWhenContextHasNoTenantScope() {
        seedIdentity(HospitalContext.builder().superAdmin(true).build(),
            EmpiMasterIdentity.builder()
                .empiNumber("EMPI-OUTSIDE")
                .organizationId(UUID.randomUUID())
                .hospitalId(UUID.randomUUID())
                .build());

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .build());

        List<EmpiMasterIdentity> results = repository.findAll();

        assertThat(results).isEmpty();
    }

    private void seedIdentity(HospitalContext context, EmpiMasterIdentity identity) {
        HospitalContextHolder.setContext(context);
        repository.save(identity);
        HospitalContextHolder.clear();
    }

    @Configuration
    @EnableJpaRepositories(basePackages = "com.example.hms.repository.empi",
        repositoryBaseClass = TenantAwareJpaRepository.class)
    @EntityScan(basePackageClasses = EmpiMasterIdentity.class)
    static class JpaConfig {
    }
}
