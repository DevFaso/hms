package com.example.hms.service.impl;

import com.example.hms.mapper.SubscriptionMapper;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionRequestDTO;
import com.example.hms.payload.dto.superadmin.OrganizationSubscriptionResponseDTO;
import com.example.hms.service.SubscriptionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-011 sibling: assigning a plan cancels the organization's ACTIVE
 * subscription and inserts the new one. Hibernate runs INSERTs before UPDATEs, so
 * without a flush between them the new row reaches the partial unique index
 * {@code uq_orgsub_active_per_org} while the old one still holds it. H2 tables are
 * built from the entities and carry no such index. Each call runs in its own
 * transaction, as the API does, on a PostgreSQL built by Liquibase.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SubscriptionServiceImpl.class, SubscriptionMapper.class})
@DisplayName("CX-011 sibling: re-assigning a plan replaces the active subscription, on PostgreSQL")
class SubscriptionAssignPlanPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_assign_plan")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private SubscriptionService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID organization = UUID.randomUUID();
    private final UUID firstPlan = UUID.randomUUID();
    private final UUID secondPlan = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.organizations (id, code, name, type, active, created_at, updated_at)
                VALUES (:id, :code, :name, 'HOSPITAL_CHAIN', true, now(), now())
                """).setParameter("id", organization).setParameter("code", "CX11-" + tag)
                .setParameter("name", "Org CX11 " + tag).executeUpdate();
            plan(firstPlan, "first-" + tag);
            plan(secondPlan, "second-" + tag);
        });
    }

    private void plan(UUID id, String tier) {
        entityManager.createNativeQuery("""
            INSERT INTO platform.subscription_plans (id, name, tier_code)
            VALUES (:id, :name, :tier)
            """).setParameter("id", id).setParameter("name", tier).setParameter("tier", tier).executeUpdate();
    }

    private OrganizationSubscriptionRequestDTO assign(UUID plan) {
        OrganizationSubscriptionRequestDTO request = new OrganizationSubscriptionRequestDTO();
        request.setPlanId(plan);
        request.setSeatLimit(10);
        return request;
    }

    private long rows(String status) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM platform.organization_subscriptions WHERE organization_id = :o AND status = :s")
            .setParameter("o", organization).setParameter("s", status).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("the second plan lands, the first is cancelled and kept, and one is active")
    void reassigningReplacesTheActiveSubscription() {
        OrganizationSubscriptionResponseDTO first = service.assignPlan(organization, assign(firstPlan));
        OrganizationSubscriptionResponseDTO second = service.assignPlan(organization, assign(secondPlan));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(rows("ACTIVE")).isOne();
        assertThat(rows("CANCELLED")).isOne();
    }
}
