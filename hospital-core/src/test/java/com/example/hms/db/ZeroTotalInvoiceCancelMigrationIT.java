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
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D783 (round-11 re-test): CHWF5AAD-2026-000030 and -000020 were issued at
 * 0 before the D767 guard and stayed SENT, so the patient saw them as
 * outstanding forever. V229 on a real PostgreSQL: an issued zero-total invoice
 * with nothing paid and no payment row is cancelled with one SYSTEM audit row;
 * a draft, a priced invoice, an already cancelled one and one with any payment
 * row are left alone; a second run changes nothing.
 */
@Testcontainers
class ZeroTotalInvoiceCancelMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v229")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V229 = "db/migration/V229__cancel_issued_zero_total_invoices.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";

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
    void noInvoices() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM billing.payment_transactions");
            stmt.execute("DELETE FROM billing.billing_invoices");
            stmt.execute("DELETE FROM support.audit_event_logs");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void theReTestsIssuedZeroInvoicesAreCancelledWithAnAuditRow() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // The re-test's two rows: SENT, total 0, nothing paid, no payment row.
            plantInvoice(stmt, 30, "SENT", "0", "0");
            plantInvoice(stmt, 20, "SENT", "0", "0");
            // A zero invoice marked paid with no money behind it is issued too.
            plantInvoice(stmt, 21, "PAID", "0", "0");

            List<String> notices = runV229(stmt);

            assertThat(status(stmt, 30)).isEqualTo("CANCELLED");
            assertThat(status(stmt, 20)).isEqualTo("CANCELLED");
            assertThat(status(stmt, 21)).isEqualTo("CANCELLED");
            assertThat(notices).anyMatch(n -> n.contains("3 issued zero-total invoice(s) cancelled"));

            try (ResultSet rs = stmt.executeQuery("SELECT actor_type, user_id, event_type, status, "
                    + "target_entity_type, event_description, details FROM support.audit_event_logs "
                    + "WHERE target_resource_id = '" + invoiceId(30) + "'")) {
                assertThat(rs.next()).as("one audit row for the cancelled invoice").isTrue();
                assertThat(rs.getString("actor_type")).isEqualTo("SYSTEM");
                assertThat(rs.getString("user_id")).isNull();
                assertThat(rs.getString("event_type")).isEqualTo("INVOICE_UPDATED");
                assertThat(rs.getString("status")).isEqualTo("SUCCESS");
                assertThat(rs.getString("target_entity_type")).isEqualTo("BillingInvoice");
                // Ids and statuses only: no patient, no invoice number tied to a person.
                assertThat(rs.getString("event_description")).contains("V229").doesNotContain(PATIENT);
                assertThat(rs.getString("details")).doesNotContain(PATIENT);
                assertThat(rs.next()).as("exactly one row").isFalse();
            }
            assertThat(auditCount(stmt)).isEqualTo(3);
        }
    }

    @Test
    void draftsPricedCancelledAndAnythingWithAPaymentRowAreLeftAlone() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantInvoice(stmt, 1, "DRAFT", "0", "0");
            plantInvoice(stmt, 2, "SENT", "5000", "0");
            plantInvoice(stmt, 3, "CANCELLED", "0", "0");
            plantInvoice(stmt, 4, "SENT", "0", "0");
            plantPayment(stmt, 4, "REVERSED");
            plantInvoice(stmt, 5, "SENT", "0", "0");
            plantPayment(stmt, 5, "PENDING");
            plantInvoice(stmt, 6, "PARTIALLY_PAID", "2000", "1000");

            List<String> notices = runV229(stmt);

            assertThat(status(stmt, 1)).isEqualTo("DRAFT");
            assertThat(status(stmt, 2)).isEqualTo("SENT");
            assertThat(status(stmt, 3)).isEqualTo("CANCELLED");
            assertThat(status(stmt, 4)).isEqualTo("SENT");
            assertThat(status(stmt, 5)).isEqualTo("SENT");
            assertThat(status(stmt, 6)).isEqualTo("PARTIALLY_PAID");
            assertThat(notices).anyMatch(n -> n.contains("0 issued zero-total invoice(s) cancelled"));
            assertThat(auditCount(stmt)).isZero();
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantInvoice(stmt, 30, "SENT", "0", "0");
            runV229(stmt);

            List<String> notices = runV229(stmt);

            assertThat(status(stmt, 30)).isEqualTo("CANCELLED");
            assertThat(notices).anyMatch(n -> n.contains("0 issued zero-total invoice(s) cancelled"));
            assertThat(auditCount(stmt)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------

    private static String invoiceId(int n) {
        return String.format("aaaaaaaa-0000-0000-0000-%012d", n);
    }

    private static void plantInvoice(Statement stmt, int n, String status, String total, String paid)
            throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO billing.billing_invoices (id, hospital_id, patient_id, invoice_number, "
                + "invoice_date, due_date, total_amount, amount_paid, status, created_at, updated_at) VALUES ('"
                + invoiceId(n) + "', '" + HOSPITAL + "', '" + PATIENT + "', 'TEST-2026-" + String.format("%06d", n)
                + "', current_date, current_date, " + total + ", " + paid + ", '" + status + "', now(), now())");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static void plantPayment(Statement stmt, int invoice, String status) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO billing.payment_transactions (invoice_id, amount, payment_date, "
                + "payment_method, status) VALUES ('" + invoiceId(invoice) + "', 1, current_date, 'MOBILE_MONEY', '"
                + status + "')");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static String status(Statement stmt, int n) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT status FROM billing.billing_invoices WHERE id = '"
                + invoiceId(n) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int auditCount(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM support.audit_event_logs")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> runV229(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = ZeroTotalInvoiceCancelMigrationIT.class.getClassLoader().getResourceAsStream(V229)) {
            assertThat(in).as(V229).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        stmt.clearWarnings();
        stmt.execute(sql);
        List<String> notices = new ArrayList<>();
        for (SQLWarning w = stmt.getWarnings(); w != null; w = w.getNextWarning()) {
            notices.add(w.getMessage());
        }
        return notices;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
