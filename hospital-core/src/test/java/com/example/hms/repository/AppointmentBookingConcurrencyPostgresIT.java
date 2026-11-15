package com.example.hms.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the real Liquibase schema and unique index, not an H2 approximation. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentBookingConcurrencyPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_booking").withUsername("hms_test_user").withPassword("hms_test_pass");

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRetriesCannotCommitTwoAppointments(boolean explicit) throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch wrote = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx().executeWithoutResult(status -> {
            insert(key, explicit, "SCHEDULED");
            wrote.countDown();
            await(release);
        }));
        assertThat(wrote.await(30, TimeUnit.SECONDS)).isTrue();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> tx().executeWithoutResult(status -> {
            secondStarted.countDown();
            insert(key, explicit, "SCHEDULED");
        }));
        try {
            assertThat(secondStarted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }
        first.get(30, TimeUnit.SECONDS);
        assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(count(key)).isEqualTo(1);
    }

    @Test
    void aRolledBackAttemptDoesNotConsumeTheIdentity() {
        String key = UUID.randomUUID().toString();
        tx().executeWithoutResult(status -> {
            insert(key, true, "SCHEDULED");
            status.setRollbackOnly();
        });
        tx().executeWithoutResult(status -> insert(key, true, "SCHEDULED"));
        assertThat(count(key)).isEqualTo(1);
    }

    @Test
    void explicitIdentityRemainsUniqueAfterCancellationButLegacyCanBeRebooked() {
        for (String closed : List.of("CANCELLED", "NO_SHOW")) {
            String explicit = UUID.randomUUID().toString();
            tx().executeWithoutResult(status -> insert(explicit, true, "SCHEDULED"));
            close(explicit, closed);
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> insert(explicit, true, "SCHEDULED")))
                .isInstanceOf(DataIntegrityViolationException.class);
            String legacy = UUID.randomUUID().toString();
            tx().executeWithoutResult(status -> insert(legacy, false, "SCHEDULED"));
            close(legacy, closed);
            tx().executeWithoutResult(status -> insert(legacy, false, "SCHEDULED"));
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> insert(legacy, false, "SCHEDULED")))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(count(legacy)).isEqualTo(2);
        }
    }

    @Test
    void existingRowsWithoutRequestIdentityRemainUnchanged() {
        tx().executeWithoutResult(status -> insert(null, false, "SCHEDULED"));
        tx().executeWithoutResult(status -> insert(null, false, "SCHEDULED"));
    }

    private int count(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM clinical.appointments WHERE booking_request_key = ?",
            Integer.class, key);
    }

    private void close(String key, String status) {
        tx().executeWithoutResult(ignored -> jdbc.update(
            "UPDATE clinical.appointments SET status = ? WHERE booking_request_key = ?", status, key));
    }

    private void insert(String key, boolean explicit, String state) {
        // Deliberately bypass unrelated FK fixtures, retaining CHECK and unique constraints.
        jdbc.execute("SET LOCAL session_replication_role = replica");
        jdbc.update("""
            INSERT INTO clinical.appointments
                (id, patient_id, staff_id, hospital_id, department_id, assignment_id, created_at, updated_at,
                 appointment_date, start_time, end_time, status,
                 booking_request_key, booking_request_fingerprint, booking_request_durable)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_DATE + 1, '09:00', '09:30', ?, ?, ?, ?)
            """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), state, key, key, explicit);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
