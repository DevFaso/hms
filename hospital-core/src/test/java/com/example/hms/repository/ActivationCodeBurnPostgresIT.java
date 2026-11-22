package com.example.hms.repository;

import com.example.hms.service.support.ActivationCodeBurner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA r70 review of #1281: an invitation code burned after too many wrong tries stays burned although the code
 * check that burns it then refuses, and its transaction rolls back. The burn runs in a transaction of its own
 * ({@link ActivationCodeBurner}, REQUIRES_NEW through the bean's proxy), on a real PostgreSQL built by Liquibase.
 */
@Testcontainers
@DataJpaTest
@Import(ActivationCodeBurner.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivationCodeBurnPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_code_burn")
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

    @Autowired private ActivationCodeBurner burner;
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
    void theBurnSurvivesTheRollbackOfTheCheckThatTriggeredIt() {
        UUID id = insert("123456", false);

        TransactionTemplate checking = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> checking.executeWithoutResult(status -> {
            burner.burn(id);
            // The code check then refuses: its transaction rolls back.
            throw new IllegalStateException("too many wrong codes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(code(id)).isNull();
    }

    @Test
    void aVerifiedInvitationIsNeverBurned() {
        UUID id = insert("123456", true);

        assertThat(inTx(() -> burner.burn(id))).isFalse();
        assertThat(code(id)).isEqualTo("123456");
    }

    private String code(UUID id) {
        return inTx(() -> (String) entityManager.createNativeQuery(
                "SELECT confirmation_code FROM security.user_role_hospital_assignment WHERE id = :id")
            .setParameter("id", id).getSingleResult());
    }

    private UUID insert(String code, boolean verified) {
        UUID id = UUID.randomUUID();
        Timestamp stamp = Timestamp.valueOf(LocalDateTime.now(ZoneId.systemDefault()));
        inTx(() -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            return entityManager.createNativeQuery(
                    "INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, hospital_id, "
                        + "is_active, assigned_at, created_at, updated_at, confirmation_code, confirmation_sent_at, "
                        + "confirmation_verified_at, temp_plain_password) "
                        + "VALUES (:id, :user, :role, NULL, FALSE, :at, :at, :at, :code, :at, "
                        + "CAST(:verified AS timestamp), 'Temp@123')")
                .setParameter("id", id)
                .setParameter("user", UUID.randomUUID())
                .setParameter("role", UUID.randomUUID())
                .setParameter("at", stamp)
                .setParameter("code", code)
                .setParameter("verified", verified ? stamp.toString() : null)
                .executeUpdate();
        });
        saved.add(id);
        return id;
    }

    private <R> R inTx(java.util.function.Supplier<R> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
