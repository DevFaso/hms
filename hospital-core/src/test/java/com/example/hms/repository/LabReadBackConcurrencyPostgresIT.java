package com.example.hms.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1553 (round 43, Rex P2) on PostgreSQL, against the schema Liquibase
 * builds: the read-back writes are conditional UPDATEs, so of two doctors
 * confirming the same critical value at once the second WAITS on the first's
 * row lock, re-reads {@code critical_readback_at IS NULL} after it commits and
 * touches 0 rows; and a mismatch record never lands on a confirmed row. The
 * first author always stands. Modelled on the pharmacy package's
 * {@code QueueClaimConcurrencyPostgresIT}.
 *
 * <p>The row is planted with foreign-key triggers off (the
 * {@code CancelledStayMigrationIT} idiom): what is under test is one row's
 * lock and WHERE, not the order graph around it.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("D1553: a confirmed critical-value read-back is never overwritten, even concurrently")
class LabReadBackConcurrencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_readback")
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Autowired private LabResultRepository labResults;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theSecondOfTwoConcurrentConfirmationsWaitsAndTouchesNothing() throws Exception {
        UUID id = plantCriticalResult();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> tx().execute(status -> {
            int rows = labResults.confirmReadBackIfUnconfirmed(id, "520", first, "Dr A", LocalDateTime.now());
            firstWrote.countDown();
            await(releaseFirst);
            return rows;
        }));
        assertThat(firstWrote.await(20, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> tx().execute(status ->
            labResults.confirmReadBackIfUnconfirmed(id, "520", second, "Dr B", LocalDateTime.now())));
        // The second writer is blocked on the first one's row lock.
        assertThatThrownBy(() -> b.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(b.get(20, TimeUnit.SECONDS)).as("re-read the WHERE after the commit").isZero();

        Map<String, Object> row = row(id);
        assertThat(row.get("critical_readback_by_user_id")).isEqualTo(first);
        assertThat(row.get("critical_readback_by_display")).isEqualTo("Dr A");
        assertThat(row.get("acknowledged_by_display")).isEqualTo("Dr A");
        assertThat(row.get("critical_readback_at")).isNotNull();
    }

    @Test
    void aMismatchRecordWaitingOnAConfirmationTouchesNothing() throws Exception {
        UUID id = plantCriticalResult();
        UUID first = UUID.randomUUID();
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Integer> confirm = CompletableFuture.supplyAsync(() -> tx().execute(status -> {
            int rows = labResults.confirmReadBackIfUnconfirmed(id, "520", first, "Dr A", LocalDateTime.now());
            firstWrote.countDown();
            await(releaseFirst);
            return rows;
        }));
        assertThat(firstWrote.await(20, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> mismatch = CompletableFuture.supplyAsync(() -> tx().execute(status ->
            labResults.recordReadBackMismatchIfUnconfirmed(id, "250", UUID.randomUUID(), "Dr B", LocalDateTime.now())));
        assertThatThrownBy(() -> mismatch.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        assertThat(confirm.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(mismatch.get(20, TimeUnit.SECONDS)).isZero();

        Map<String, Object> row = row(id);
        assertThat(row.get("critical_readback_value")).isEqualTo("520");
        assertThat(row.get("critical_readback_by_display")).isEqualTo("Dr A");
    }

    @Test
    void aMismatchOnAnUnconfirmedResultIsRecordedWithoutConfirmingIt() {
        UUID id = plantCriticalResult();

        int rows = tx().execute(status ->
            labResults.recordReadBackMismatchIfUnconfirmed(id, "250", UUID.randomUUID(), "Dr B", LocalDateTime.now()));

        assertThat(rows).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("critical_readback_value")).isEqualTo("250");
        assertThat(row.get("critical_readback_at")).isNull();
        assertThat(row.get("acknowledged")).isEqualTo(false);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM lab.lab_results WHERE id = ?", id);
    }

    /**
     * One unconfirmed lab result, foreign keys off. Every NOT NULL column with no
     * default gets a value of its type, so the plant follows the schema Liquibase
     * builds rather than a copy of it.
     */
    private UUID plantCriticalResult() {
        UUID id = UUID.randomUUID();
        List<Map<String, Object>> columns = jdbc.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_schema = 'lab' AND table_name = 'lab_results' "
                + "AND is_nullable = 'NO' AND column_default IS NULL");
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = (String) column.get("column_name");
            names.add(name);
            values.add(valueFor(name, (String) column.get("data_type"), id));
        }
        for (Map.Entry<String, Object> fixed : Map.<String, Object>of(
                "result_value", "520", "acknowledged", false, "released", true,
                // chk_labresult_user_or_system: a SYSTEM row carries no assignment.
                "actor_type", "SYSTEM").entrySet()) {
            int at = names.indexOf(fixed.getKey());
            if (at >= 0) {
                values.set(at, fixed.getValue());
            } else {
                names.add(fixed.getKey());
                values.add(fixed.getValue());
            }
        }
        String sql = "INSERT INTO lab.lab_results (" + String.join(", ", names) + ") VALUES ("
            + String.join(", ", java.util.Collections.nCopies(names.size(), "?")) + ")";
        tx().executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(sql, values.toArray());
        });
        return id;
    }

    private static Object valueFor(String name, String type, UUID id) {
        if ("id".equals(name)) {
            return id;
        }
        return switch (type) {
            case "uuid" -> UUID.randomUUID();
            case "boolean" -> false;
            case "smallint", "integer", "bigint", "numeric", "double precision", "real" -> 0;
            case "timestamp without time zone", "timestamp with time zone", "date" ->
                Timestamp.valueOf(LocalDateTime.now());
            default -> "x";
        };
    }
}
