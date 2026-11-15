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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1901: V301 on a real PostgreSQL. A code is set only where the stored
 * text equals a template the server wrote, exactly; everything a person typed
 * differently keeps no code, every stored text is left as it was, no row is
 * added or removed, the NOTICEs carry counts only, and a second run changes
 * nothing.
 */
@Testcontainers
class BillingTextCodeBackfillMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v301")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V301 = "db/migration/V301__billing_text_code_and_args.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String ENCOUNTER = "dddddddd-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String SERVICE = "ffffffff-0000-0000-0000-000000000001";

    private static final String DRAFT_NOTE =
        "Visite ENC-20261029-9F86F7 clôturée : brouillon à vérifier, tarifer et émettre par la caisse.";

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
            stmt.execute("DELETE FROM billing.invoice_items");
            stmt.execute("DELETE FROM billing.billing_invoices");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void theServerTemplatesGetTheirCodesAndEverythingTypedStaysAsItWas() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // Server-written, in the language they were written in.
            invoice(conn, 1, true, false, DRAFT_NOTE, null);
            invoice(conn, 2, false, true, null, "Copay carried onto invoice CHWF2396-2026-000004.");
            invoice(conn, 3, false, true, null, "Ticket modérateur reporté sur la facture CHWF2396-2026-000005.");
            invoice(conn, 4, false, false, "Birth charge: Delivery (caesarean section), born Nov 5, 2026. "
                + "Price it if the hospital has no priced delivery service.", null);
            invoice(conn, 5, false, false, "Frais de naissance : Accouchement (voie basse), né(e) le 23 oct. 2026. "
                + "À tarifer si l'établissement n'a pas de prestation d'accouchement tarifée.", null);
            invoice(conn, 6, false, false, "Frais de naissance : Accouchement (voie basse), né(e) le 2026-10-20. "
                + "À tarifer si l'établissement n'a pas de prestation d'accouchement tarifée.", null);
            invoice(conn, 7, false, false, "Cargo de nacimiento: Parto (cesárea), nacido el 5 sept 2026. "
                + "Tarifíquelo si el establecimiento no tiene un servicio de parto con precio.", null);
            line(conn, 101, 1, "Consultation", "SERVICE", false);
            line(conn, 102, 1, "Hospitalisation (journées)", "SERVICE", false);
            line(conn, 103, 1, "Transfusion : plaquettes", "SERVICE", false);
            line(conn, 104, 7, "Parto (cesárea)", "SERVICE", true);
            // Typed by a person, or not exactly a template.
            invoice(conn, 11, true, false, "Visite ENC-1 clôturée : à revoir avec le médecin.", null);
            invoice(conn, 12, false, true, null, "Erreur de saisie");
            invoice(conn, 13, false, false, "Copay carried onto invoice X.", "Copay carried onto invoice X.");
            invoice(conn, 14, false, false, "Birth charge: Delivery (vaginal birth), born someday. "
                + "Price it if the hospital has no priced delivery service.", null);
            line(conn, 111, 11, "Pansement simple", "SERVICE", false);
            line(conn, 112, 11, "Consultation", "SERVICE", true);
            line(conn, 113, 11, "Transfusion : sang de licorne", "SERVICE", false);
            line(conn, 114, 11, "Consultation", "GENERAL", false);
            long invoicesBefore = count(stmt, "billing.billing_invoices");
            long linesBefore = count(stmt, "billing.invoice_items");
            String textsBefore = texts(stmt);

            List<String> notices = runV301(stmt);

            assertThat(count(stmt, "billing.billing_invoices")).isEqualTo(invoicesBefore);
            assertThat(count(stmt, "billing.invoice_items")).isEqualTo(linesBefore);
            assertThat(texts(stmt)).as("no stored text is rewritten").isEqualTo(textsBefore);

            assertThat(notesCode(stmt, 1)).containsExactly("billing.checkout.draftNote",
                "[{\"value\" : \"ENC-20261029-9F86F7\"}]");
            assertThat(reasonCode(stmt, 2)).containsExactly("billing.invoice.copayCreditedReason",
                "[{\"value\" : \"CHWF2396-2026-000004\"}]");
            assertThat(reasonCode(stmt, 3)).containsExactly("billing.invoice.copayCreditedReason",
                "[{\"value\" : \"CHWF2396-2026-000005\"}]");
            assertThat(notesCode(stmt, 4)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.caesarean\"}, {\"date\" : \"2026-11-05\"}]");
            assertThat(notesCode(stmt, 5)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.vaginal\"}, {\"date\" : \"2026-10-23\"}]");
            assertThat(notesCode(stmt, 6)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.vaginal\"}, {\"date\" : \"2026-10-20\"}]");
            assertThat(notesCode(stmt, 7)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.caesarean\"}, {\"date\" : \"2026-09-05\"}]");
            assertThat(lineCode(stmt, 101)).containsExactly("billing.checkout.consultation", null);
            assertThat(lineCode(stmt, 102)).containsExactly("billing.checkout.bedDays", null);
            assertThat(lineCode(stmt, 103)).containsExactly("billing.checkout.transfusion",
                "[{\"code\" : \"transfusion.productType.PLATELETS\"}]");
            assertThat(lineCode(stmt, 104)).containsExactly("maternity.delivery.charge.caesarean", null);

            for (int typed : new int[] {11, 12, 13, 14}) {
                assertThat(notesCode(stmt, typed)).as("invoice %s", typed).containsExactly(null, null);
                assertThat(reasonCode(stmt, typed)).as("invoice %s", typed).containsExactly(null, null);
            }
            for (int typed : new int[] {111, 112, 113, 114}) {
                assertThat(lineCode(stmt, typed)).as("line %s", typed).containsExactly(null, null);
            }

            assertThat(notices).contains(
                "V301: 1 checkout draft note(s) coded",
                "V301: 2 consultation/bed-day line(s) coded",
                "V301: 1 transfusion line(s) coded",
                "V301: 2 copay cancel reason(s) coded",
                "V301: 1 delivery line(s) coded",
                "V301: 4 delivery charge note(s) coded");
            assertThat(String.join("\n", notices)).doesNotContain("ENC-", "CHWF", "Pansement", PATIENT);
        }
    }

    /**
     * Rex P2 on #1176: a note matching the delivery template but holding a day
     * that does not exist (typed, or damaged) once reached make_date(), which
     * raised, aborted Liquibase and stopped the app starting. Such a row now
     * keeps no code and the migration completes; a real 29 February still gets one.
     */
    @Test
    void anImpossibleBirthDayLeavesTheNoteUncodedAndNeverStopsTheMigration() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            invoice(conn, 21, false, false, frenchDeliveryNote("2026-02-31"), null);  // 31/02
            invoice(conn, 22, false, false, frenchDeliveryNote("30 févr. 2026"), null); // 30/02
            invoice(conn, 23, false, false, frenchDeliveryNote("2026-01-00"), null);  // 00/01
            invoice(conn, 24, false, false, frenchDeliveryNote("2026-13-01"), null);  // 01/13
            invoice(conn, 25, false, false, "Birth charge: Delivery (vaginal birth), born Feb 29, 2026. "
                + "Price it if the hospital has no priced delivery service.", null);    // 29/02, common year
            invoice(conn, 26, false, false, frenchDeliveryNote("0000-01-01"), null);  // year 0
            invoice(conn, 27, false, false, "Cargo de nacimiento: Parto (cesárea), nacido el 0 ene 2026. "
                + "Tarifíquelo si el establecimiento no tiene un servicio de parto con precio.", null); // day 0
            invoice(conn, 28, false, false, frenchDeliveryNote("29 févr. 2028"), null); // 29/02, leap year
            invoice(conn, 29, false, false, frenchDeliveryNote("2000-02-29"), null);  // 29/02, 400-year leap
            invoice(conn, 30, false, false, frenchDeliveryNote("1900-02-29"), null);  // 29/02, century, not leap
            String textsBefore = texts(stmt);

            List<String> notices = runV301(stmt);

            assertThat(texts(stmt)).as("no stored text is rewritten").isEqualTo(textsBefore);
            for (int impossible : new int[] {21, 22, 23, 24, 25, 26, 27, 30}) {
                assertThat(notesCode(stmt, impossible)).as("invoice %s", impossible).containsExactly(null, null);
            }
            assertThat(notesCode(stmt, 28)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.vaginal\"}, {\"date\" : \"2028-02-29\"}]");
            assertThat(notesCode(stmt, 29)).containsExactly("maternity.delivery.charge.note",
                "[{\"code\" : \"maternity.delivery.charge.vaginal\"}, {\"date\" : \"2000-02-29\"}]");
            assertThat(notices).contains("V301: 2 delivery charge note(s) coded");
        }
    }

    private static String frenchDeliveryNote(String day) {
        return "Frais de naissance : Accouchement (voie basse), né(e) le " + day
            + ". À tarifer si l'établissement n'a pas de prestation d'accouchement tarifée.";
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            invoice(conn, 1, true, false, DRAFT_NOTE, null);
            line(conn, 101, 1, "Consultation", "SERVICE", false);
            runV301(stmt);

            List<String> notices = runV301(stmt);

            assertThat(notesCode(stmt, 1)).first().isEqualTo("billing.checkout.draftNote");
            assertThat(notices).contains("V301: 0 checkout draft note(s) coded",
                "V301: 0 consultation/bed-day line(s) coded");
        }
    }

    // ------------------------------------------------------------------

    private static String id(String prefix, int n) {
        return prefix + String.format("%012d", n);
    }

    private static String invoiceId(int n) {
        return id("aaaaaaaa-0000-0000-0000-", n);
    }

    private static void invoice(Connection conn, int n, boolean visit, boolean copay, String notes, String reason)
            throws SQLException {
        try (Statement replica = conn.createStatement()) {
            replica.execute("SET session_replication_role = replica");
        }
        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO billing.billing_invoices (id, hospital_id, "
                + "patient_id, encounter_id, invoice_number, invoice_date, due_date, total_amount, amount_paid, status, "
                + "check_in_copay, notes, cancel_reason, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, "
                + "?::uuid, ?, current_date, current_date, 0, 0, ?, ?, ?, ?, now(), now())")) {
            insert.setString(1, invoiceId(n));
            insert.setString(2, HOSPITAL);
            insert.setString(3, PATIENT);
            insert.setString(4, visit ? ENCOUNTER : null);
            insert.setString(5, "TEST-2026-" + String.format("%06d", n));
            insert.setString(6, reason != null ? "CANCELLED" : "DRAFT");
            insert.setBoolean(7, copay);
            insert.setString(8, notes);
            insert.setString(9, reason);
            insert.executeUpdate();
        } finally {
            try (Statement origin = conn.createStatement()) {
                origin.execute("SET session_replication_role = origin");
            }
        }
    }

    private static void line(Connection conn, int n, int invoice, String description, String category,
                             boolean priced) throws SQLException {
        try (Statement replica = conn.createStatement()) {
            replica.execute("SET session_replication_role = replica");
        }
        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO billing.invoice_items (id, "
                + "billing_invoice_id, assignment_id, related_service_id, item_description, item_category, quantity, "
                + "unit_price, total_price, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, "
                + "1, 0, 0, now(), now())")) {
            insert.setString(1, id("99999999-0000-0000-0000-", n));
            insert.setString(2, invoiceId(invoice));
            insert.setString(3, ASSIGNMENT);
            insert.setString(4, priced ? SERVICE : null);
            insert.setString(5, description);
            insert.setString(6, category);
            insert.executeUpdate();
        } finally {
            try (Statement origin = conn.createStatement()) {
                origin.execute("SET session_replication_role = origin");
            }
        }
    }

    private static List<String> pair(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            List<String> values = new ArrayList<>();
            values.add(rs.getString(1));
            values.add(rs.getString(2));
            return values;
        }
    }

    private static List<String> notesCode(Statement stmt, int n) throws SQLException {
        return pair(stmt, "SELECT notes_code, notes_args FROM billing.billing_invoices WHERE id = '"
            + invoiceId(n) + "'");
    }

    private static List<String> reasonCode(Statement stmt, int n) throws SQLException {
        return pair(stmt, "SELECT cancel_reason_code, cancel_reason_args FROM billing.billing_invoices WHERE id = '"
            + invoiceId(n) + "'");
    }

    private static List<String> lineCode(Statement stmt, int n) throws SQLException {
        return pair(stmt, "SELECT text_code, text_args FROM billing.invoice_items WHERE id = '"
            + id("99999999-0000-0000-0000-", n) + "'");
    }

    private static long count(Statement stmt, String table) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Every stored text, in a stable order, to prove none is rewritten. */
    private static String texts(Statement stmt) throws SQLException {
        StringBuilder all = new StringBuilder();
        try (ResultSet rs = stmt.executeQuery("SELECT id, notes, cancel_reason FROM billing.billing_invoices "
                + "ORDER BY id")) {
            while (rs.next()) {
                all.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                    .append(rs.getString(3)).append('\n');
            }
        }
        try (ResultSet rs = stmt.executeQuery("SELECT id, item_description FROM billing.invoice_items ORDER BY id")) {
            while (rs.next()) {
                all.append(rs.getString(1)).append('|').append(rs.getString(2)).append('\n');
            }
        }
        return all.toString();
    }

    private static List<String> runV301(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = BillingTextCodeBackfillMigrationIT.class.getClassLoader().getResourceAsStream(V301)) {
            assertThat(in).as(V301).isNotNull();
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
