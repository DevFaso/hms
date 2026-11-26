package com.example.hms.db;

import com.example.hms.HmsApplication;
import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.repository.AuditEventLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V382 on a real PostgreSQL, booted the way dev and prod boot (Liquibase, then Hibernate validating): the audit
 * tables accept INSERT and refuse UPDATE, DELETE and TRUNCATE; the retention function removes only rows past
 * the retention period and records the run; the application's own write paths (the audit service's insert, a
 * signature's trail growing) keep working on top of the triggers.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
class AuditTrailAppendOnlyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v382")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");   // schema-h2.sql is H2-only
    }

    private static final String APPEND_ONLY = "audit rows are append-only";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private AuditEventLogRepository auditRepository;

    // ------------------------------------------------------------------ the four tables

    @Test
    void backendAuditRowsAcceptInsertAndRefuseUpdateDeleteTruncate() {
        UUID id = plantBackendRow(LocalDateTime.now());

        assertRefused(() -> jdbc.update("UPDATE support.audit_event_logs SET details = 'changed' WHERE id = ?", id));
        assertRefused(() -> jdbc.update("DELETE FROM support.audit_event_logs WHERE id = ?", id));
        assertRefused(() -> jdbc.execute("TRUNCATE support.audit_event_logs"));
        assertThat(jdbc.queryForObject("SELECT details FROM support.audit_event_logs WHERE id = ?", String.class, id))
            .isEqualTo("planted");
    }

    @Test
    void portalAuditRowsAcceptInsertAndRefuseUpdateDeleteTruncate() {
        UUID id = plantPortalRow(LocalDateTime.now());

        assertRefused(() -> jdbc.update("UPDATE support.frontend_audit_events SET actor = 'x' WHERE id = ?", id));
        assertRefused(() -> jdbc.update("DELETE FROM support.frontend_audit_events WHERE id = ?", id));
        assertRefused(() -> jdbc.execute("TRUNCATE support.frontend_audit_events"));
        assertThat(count("support.frontend_audit_events", id)).isOne();
    }

    @Test
    void permissionMatrixAuditRowsAcceptInsertAndRefuseUpdateDeleteTruncate() {
        UUID id = plantMatrixRow("now()");

        assertRefused(() -> jdbc.update(
            "UPDATE public.permission_matrix_audit_events SET description = 'x' WHERE id = ?", id));
        assertRefused(() -> jdbc.update("DELETE FROM public.permission_matrix_audit_events WHERE id = ?", id));
        assertRefused(() -> jdbc.execute("TRUNCATE public.permission_matrix_audit_events"));
        assertThat(count("public.permission_matrix_audit_events", id)).isOne();
    }

    @Test
    void signatureTrailRowsAcceptInsertAndRefuseUpdateDeleteTruncate() {
        UUID signature = UUID.randomUUID();
        jdbc.update("INSERT INTO clinical.digital_signature_audit_log (signature_id, log_order, action, "
            + "performed_by_user_id, performed_at) VALUES (?, 0, 'SIGNED', ?, now())", signature, UUID.randomUUID());

        assertRefused(() -> jdbc.update(
            "UPDATE clinical.digital_signature_audit_log SET action = 'X' WHERE signature_id = ?", signature));
        assertRefused(() -> jdbc.update(
            "DELETE FROM clinical.digital_signature_audit_log WHERE signature_id = ?", signature));
        assertRefused(() -> jdbc.execute("TRUNCATE clinical.digital_signature_audit_log"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical.digital_signature_audit_log "
            + "WHERE signature_id = ?", Long.class, signature)).isOne();
    }

    @Test
    void aRowInsideTheRetentionPeriodStaysEvenWithThePurgeFlagSet() {
        UUID id = plantBackendRow(LocalDateTime.now().minusYears(5));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT set_config('hms.audit_retention_purge', 'on', true)");
            jdbc.update("DELETE FROM support.audit_event_logs WHERE id = ?", id);
        })).isInstanceOf(DataAccessException.class).hasStackTraceContaining(APPEND_ONLY);
        assertThat(count("support.audit_event_logs", id)).isOne();
    }

    // ------------------------------------------------------------------ the retention function

    @Test
    void theRetentionFunctionRemovesOnlyRowsPastTheRetentionPeriodAndRecordsTheRun() {
        UUID oldBackend = plantBackendRow(LocalDateTime.now().minusYears(7));
        UUID youngBackend = plantBackendRow(LocalDateTime.now().minusYears(5));
        UUID oldPortal = plantPortalRow(LocalDateTime.now().minusYears(7));
        UUID youngPortal = plantPortalRow(LocalDateTime.now().minusDays(1));
        UUID oldMatrix = plantMatrixRow("now() - interval '7 years'");
        UUID youngMatrix = plantMatrixRow("now()");
        long runsBefore = purgeRuns();

        Long removed = jdbc.queryForObject("SELECT support.purge_expired_audit_rows(6, 1000)", Long.class);

        assertThat(removed).isGreaterThanOrEqualTo(3);
        assertThat(count("support.audit_event_logs", oldBackend)).isZero();
        assertThat(count("support.frontend_audit_events", oldPortal)).isZero();
        assertThat(count("public.permission_matrix_audit_events", oldMatrix)).isZero();
        assertThat(count("support.audit_event_logs", youngBackend)).isOne();
        assertThat(count("support.frontend_audit_events", youngPortal)).isOne();
        assertThat(count("public.permission_matrix_audit_events", youngMatrix)).isOne();

        assertThat(purgeRuns()).isEqualTo(runsBefore + 1);
        Map<String, Object> run = jdbc.queryForMap("SELECT actor_type, status, details FROM support.audit_event_logs "
            + "WHERE event_type = 'AUDIT_RETENTION_PURGED' ORDER BY event_timestamp DESC LIMIT 1");
        assertThat(run.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat((String) run.get("details")).contains("retention_years=6");

        // Nothing left past the period: a second run removes nothing and records nothing.
        assertThat(jdbc.queryForObject("SELECT support.purge_expired_audit_rows(6, 1000)", Long.class)).isZero();
        assertThat(purgeRuns()).isEqualTo(runsBefore + 1);

        // The run's own record is an audit row like any other.
        UUID runId = jdbc.queryForObject("SELECT id FROM support.audit_event_logs "
            + "WHERE event_type = 'AUDIT_RETENTION_PURGED' ORDER BY event_timestamp DESC LIMIT 1", UUID.class);
        assertRefused(() -> jdbc.update("DELETE FROM support.audit_event_logs WHERE id = ?", runId));
    }

    @Test
    void theRetentionFunctionRefusesAPeriodUnderSixYearsAndAnEmptyBatch() {
        plantBackendRow(LocalDateTime.now().minusYears(7));

        assertThatThrownBy(() -> jdbc.queryForObject("SELECT support.purge_expired_audit_rows(5, 1000)", Long.class))
            .isInstanceOf(DataAccessException.class).hasStackTraceContaining("at least 6 years");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT support.purge_expired_audit_rows(6, 0)", Long.class))
            .isInstanceOf(DataAccessException.class).hasStackTraceContaining("batch_limit");
    }

    @Test
    void theRetentionFunctionHonoursItsBatchLimit() {
        for (int i = 0; i < 3; i++) {
            plantBackendRow(LocalDateTime.now().minusYears(8).plusDays(i));
        }
        long oldBefore = oldBackendRows();

        Long removed = jdbc.queryForObject("SELECT support.purge_expired_audit_rows(6, 1)", Long.class);

        assertThat(removed).isBetween(1L, 3L);
        assertThat(oldBackendRows()).isEqualTo(oldBefore - 1);
    }

    // ------------------------------------------------------------------ the application's write paths

    @Test
    void theApplicationWritesAuditRowsAndCannotChangeThem() {
        UUID id = transactions.execute(status -> auditRepository.save(AuditEventLog.builder()
            .actorType(ActorType.SYSTEM)
            .actorLabel("IT")
            .eventType(AuditEventType.OTHER)
            .status(AuditStatus.SUCCESS)
            .eventDescription("written through the repository")
            .resourceId("r")
            .resourceName("r")
            .eventTimestamp(LocalDateTime.now())
            .build()).getId());
        assertThat(count("support.audit_event_logs", id)).isOne();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            AuditEventLog row = entityManager.find(AuditEventLog.class, id);
            row.setDetails("changed");
            entityManager.flush();
        })).hasStackTraceContaining(APPEND_ONLY);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            entityManager.remove(entityManager.find(AuditEventLog.class, id));
            entityManager.flush();
        })).hasStackTraceContaining(APPEND_ONLY);
        assertThat(count("support.audit_event_logs", id)).isOne();
    }

    @Test
    void aSignaturesTrailGrowsByInsertOnly() {
        UUID signatureId = plantSignature();

        transactions.executeWithoutResult(status -> {
            DigitalSignature signature = entityManager.find(DigitalSignature.class, signatureId);
            signature.addAuditEntry("VERIFIED_SUCCESS", UUID.randomUUID(), "First", "first", null, null);
            entityManager.flush();
        });
        transactions.executeWithoutResult(status -> {
            DigitalSignature signature = entityManager.find(DigitalSignature.class, signatureId);
            assertThat(signature.getAuditLog()).hasSize(1);
            signature.addAuditEntry("VERIFIED_FAILURE", UUID.randomUUID(), "Second", "second", null, null);
            entityManager.flush();
        });

        List<String> actions = jdbc.queryForList("SELECT action FROM clinical.digital_signature_audit_log "
            + "WHERE signature_id = ? ORDER BY log_order", String.class, signatureId);
        assertThat(actions).containsExactly("VERIFIED_SUCCESS", "VERIFIED_FAILURE");
    }

    // ------------------------------------------------------------------

    private void assertRefused(Runnable statement) {
        assertThatThrownBy(statement::run)
            .isInstanceOf(DataAccessException.class)
            .hasStackTraceContaining(APPEND_ONLY);
    }

    private UUID plantBackendRow(LocalDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support.audit_event_logs (id, created_at, updated_at, event_timestamp, user_id, "
            + "actor_type, actor_label, event_type, status, event_description, details) "
            + "VALUES (?, ?, ?, ?, NULL, 'SYSTEM', 'IT', 'OTHER', 'SUCCESS', 'planted', 'planted')", id, at, at, at);
        return id;
    }

    private UUID plantPortalRow(LocalDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO support.frontend_audit_events (id, created_at, updated_at, occurred_at, event_type, "
            + "actor) VALUES (?, ?, ?, ?, 'PAGE_VIEW', 'it')", id, at, at, at);
        return id;
    }

    private UUID plantMatrixRow(String createdAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.permission_matrix_audit_events (id, action, created_at, description) "
            + "VALUES (?, 'SNAPSHOT_PUBLISHED', " + createdAtSql + ", 'planted')", id);
        return id;
    }

    private UUID plantSignature() {
        UUID id = UUID.randomUUID();
        // Parents planted without their own rows: this test is about the trail, not the signature's references.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("INSERT INTO clinical.digital_signatures (id, verification_count, created_at, updated_at, "
                + "signature_date_time, version, hospital_id, report_id, signed_by_staff_id, signature_hash, "
                + "signature_value, report_type, status) VALUES (?, 0, now(), now(), now(), 0, ?, ?, ?, ?, 'sig', "
                + "'DISCHARGE_SUMMARY', 'SIGNED')", id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "0".repeat(64));
        });
        return id;
    }

    private long count(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private long purgeRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM support.audit_event_logs "
            + "WHERE event_type = 'AUDIT_RETENTION_PURGED'", Long.class);
    }

    private long oldBackendRows() {
        return jdbc.queryForObject("SELECT count(*) FROM support.audit_event_logs "
            + "WHERE event_timestamp < LOCALTIMESTAMP - interval '6 years'", Long.class);
    }
}
