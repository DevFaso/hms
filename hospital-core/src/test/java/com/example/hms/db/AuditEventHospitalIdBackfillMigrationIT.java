package com.example.hms.db;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D786 (review of #954): V235's backfill on a real PostgreSQL, on the rows
 * dev actually holds. The V229 cancellation row is produced by running V229
 * itself (it writes no hospital_id), and the care-gap trace row has the shape
 * the sweep wrote before the application stamped hospital_id. V235 must stamp
 * each from the row it targets (the invoice, the recall), and leave alone a
 * row that only looks similar: another actor, a human actor, a row that
 * already carries a hospital, a target that does not exist.
 */
@Testcontainers
class AuditEventHospitalIdBackfillMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v235")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V229 = "db/migration/V229__cancel_issued_zero_total_invoices.sql";
    private static final String V235 = "db/migration/V235__audit_event_hospital_id.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String OTHER_HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String INVOICE = "aaaaaaaa-0000-0000-0000-000000000030";
    private static final String RECALL = "dddddddd-0000-0000-0000-000000000001";
    private static final String USER = "eeeeeeee-0000-0000-0000-000000000001";

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection conn = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase("db/migration/changelog.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    @BeforeEach
    void empty() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM support.audit_event_logs");
            stmt.execute("DELETE FROM billing.payment_transactions");
            stmt.execute("DELETE FROM billing.billing_invoices");
            stmt.execute("DELETE FROM scheduling.patient_recalls");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void theV229CancellationRowIsStampedWithTheInvoicesHospital() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            replica(stmt, "INSERT INTO billing.billing_invoices (id, hospital_id, patient_id, invoice_number, "
                + "invoice_date, due_date, total_amount, amount_paid, status, created_at, updated_at) VALUES ('"
                + INVOICE + "', '" + HOSPITAL + "', '" + PATIENT + "', 'TEST-2026-000030', current_date, "
                + "current_date, 0, 0, 'SENT', now(), now())");
            run(stmt, V229);
            assertThat(hospitalIdOfRowTargeting(stmt, INVOICE)).as("V229 writes no hospital_id").isNull();

            run(stmt, V235);

            assertThat(hospitalIdOfRowTargeting(stmt, INVOICE)).isEqualTo(HOSPITAL);
        }
    }

    @Test
    void theCareGapTraceRowIsStampedWithTheRecallsHospital() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantRecall(stmt);
            plantAuditRow(stmt, "NULL", "SYSTEM", "PROGRAM_DEFAULTER_TRACED", "PatientRecall", RECALL, "NULL");

            run(stmt, V235);

            assertThat(hospitalIdOfRowTargeting(stmt, RECALL)).isEqualTo(HOSPITAL);
        }
    }

    @Test
    void rowsThatOnlyLookSimilarAreLeftAlone() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            replica(stmt, "INSERT INTO billing.billing_invoices (id, hospital_id, patient_id, invoice_number, "
                + "invoice_date, due_date, total_amount, amount_paid, status, created_at, updated_at) VALUES ('"
                + INVOICE + "', '" + HOSPITAL + "', '" + PATIENT + "', 'TEST-2026-000030', current_date, "
                + "current_date, 0, 0, 'CANCELLED', now(), now())");
            plantRecall(stmt);
            // Another SYSTEM actor on the same invoice: not a V229 row.
            String otherJob = plantAuditRow(stmt, "NULL", "Some Other Job", "INVOICE_UPDATED", "BillingInvoice",
                INVOICE, "NULL");
            // A human actor on the recall: its hospital comes from its assignment.
            String human = plantAuditRow(stmt, "'" + USER + "'", "SYSTEM", "PROGRAM_DEFAULTER_TRACED",
                "PatientRecall", RECALL, "NULL");
            // A trace whose recall does not exist.
            String orphan = plantAuditRow(stmt, "NULL", "SYSTEM", "PROGRAM_DEFAULTER_TRACED", "PatientRecall",
                "dddddddd-0000-0000-0000-000000000099", "NULL");
            // Already attributed: never overwritten.
            String attributed = plantAuditRow(stmt, "NULL", "Migration V229", "INVOICE_UPDATED", "BillingInvoice",
                INVOICE, "'" + OTHER_HOSPITAL + "'");

            run(stmt, V235);
            run(stmt, V235);

            assertThat(hospitalIdOf(stmt, otherJob)).isNull();
            assertThat(hospitalIdOf(stmt, human)).isNull();
            assertThat(hospitalIdOf(stmt, orphan)).isNull();
            assertThat(hospitalIdOf(stmt, attributed)).isEqualTo(OTHER_HOSPITAL);
        }
    }

    // ------------------------------------------------------------------

    private static void plantRecall(Statement stmt) throws SQLException {
        replica(stmt, "INSERT INTO scheduling.patient_recalls (id, patient_id, hospital_id, due_date, reason) "
            + "VALUES ('" + RECALL + "', '" + PATIENT + "', '" + HOSPITAL + "', current_date, 'Tracing')");
    }

    /** An audit row with no assignment, as written before hospital_id existed; returns its id. */
    private static String plantAuditRow(Statement stmt, String userIdSql, String actorLabel, String eventType,
                                        String entityType, String resourceId, String hospitalIdSql)
            throws SQLException {
        String id;
        try (ResultSet rs = stmt.executeQuery("SELECT gen_random_uuid()::text")) {
            rs.next();
            id = rs.getString(1);
        }
        String actorType = "NULL".equals(userIdSql) ? "SYSTEM" : "USER";
        replica(stmt, "INSERT INTO support.audit_event_logs (id, created_at, updated_at, event_timestamp, "
            + "user_id, actor_type, actor_label, event_type, status, event_description, "
            + "target_entity_type, target_resource_id, hospital_id) VALUES ('" + id + "', now(), now(), now(), "
            + userIdSql + ", '" + actorType + "', '" + actorLabel + "', '" + eventType + "', 'SUCCESS', 'test', '"
            + entityType + "', '" + resourceId + "', " + hospitalIdSql + ")");
        return id;
    }

    private static void replica(Statement stmt, String sql) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate(sql);
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static String hospitalIdOfRowTargeting(Statement stmt, String resourceId) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT hospital_id::text FROM support.audit_event_logs "
                + "WHERE target_resource_id = '" + resourceId + "'")) {
            assertThat(rs.next()).as("an audit row targets " + resourceId).isTrue();
            String value = rs.getString(1);
            assertThat(rs.next()).as("exactly one row targets " + resourceId).isFalse();
            return value;
        }
    }

    private static String hospitalIdOf(Statement stmt, String auditId) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT hospital_id::text FROM support.audit_event_logs "
                + "WHERE id = '" + auditId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static void run(Statement stmt, String path) throws Exception {
        String sql;
        try (InputStream in = AuditEventHospitalIdBackfillMigrationIT.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        stmt.execute(sql);
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
