package com.example.hms.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D418: the cooldown on the anonymous activation-code re-send lives on the
 * assignment row, not in one instance's memory. Each claim below runs in its
 * own transaction, as two instances of the API would, on a real PostgreSQL
 * built by Liquibase.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AssignmentActivationResendClaimPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_resend_claim")
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

    @Autowired private UserRoleHospitalAssignmentRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final List<UUID> saved = new ArrayList<>();

    @AfterEach
    void removeRows() {
        tx(() -> entityManager.createNativeQuery(
                "DELETE FROM security.user_role_hospital_assignment WHERE id IN (:ids)")
            .setParameter("ids", saved).executeUpdate());
    }

    @Test
    void secondClaimWithinTheCooldownIsRefusedAcrossTransactions() {
        LocalDateTime now = LocalDateTime.now();
        UUID id = insert(false, "111111", now.minusHours(1));

        int first = claim(id, "222222", now);
        int second = claim(id, "333333", now.plusMinutes(1));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(code(id)).isEqualTo("222222");
    }

    @Test
    void claimAfterTheCooldownIssuesANewCodeEvenWhenTheOldOneHadExpired() {
        LocalDateTime now = LocalDateTime.now();
        // Registered three days ago and never activated: the 48 h code lapsed.
        UUID id = insert(false, "111111", now.minusDays(3));

        assertThat(claim(id, "444444", now)).isEqualTo(1);
        assertThat(code(id)).isEqualTo("444444");
        assertThat(claim(id, "555555", now.plusMinutes(6))).isEqualTo(1);
        assertThat(code(id)).isEqualTo("555555");
    }

    @Test
    void activeOrRevokedRowsAreNeverClaimed() {
        LocalDateTime now = LocalDateTime.now();
        UUID active = insert(true, "111111", now.minusDays(1));
        UUID revoked = insert(false, null, now.minusDays(1));

        assertThat(claim(active, "666666", now)).isZero();
        assertThat(claim(revoked, "777777", now)).isZero();
        assertThat(code(revoked)).isNull();
    }

    private int claim(UUID id, String code, LocalDateTime at) {
        int[] result = {0};
        tx(() -> result[0] = repository.claimActivationResend(id, code, at, at.minusMinutes(5)));
        return result[0];
    }

    private String code(UUID id) {
        Object[] out = {null};
        tx(() -> out[0] = entityManager.createNativeQuery(
                "SELECT confirmation_code FROM security.user_role_hospital_assignment WHERE id = :id")
            .setParameter("id", id).getSingleResult());
        return (String) out[0];
    }

    private UUID insert(boolean active, String code, LocalDateTime sentAt) {
        UUID id = UUID.randomUUID();
        Timestamp stamp = Timestamp.valueOf(sentAt);
        tx(() -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, is_active, "
                        + "assigned_at, created_at, updated_at, confirmation_code, confirmation_sent_at) "
                        + "VALUES (:id, :user, :role, :active, :at, :at, :at, :code, :at)")
                .setParameter("id", id)
                .setParameter("user", UUID.randomUUID())
                .setParameter("role", UUID.randomUUID())
                .setParameter("active", active)
                .setParameter("at", stamp)
                .setParameter("code", code)
                .executeUpdate();
        });
        saved.add(id);
        return id;
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
