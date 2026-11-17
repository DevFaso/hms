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
 * QA D1393: dev holds encounters left IN_PROGRESS by cancelled admissions, and
 * orders filed on them that the pharmacy refuses (A1's 5758e4f3, Mariam
 * Ouédraogo, Adama Kaboré, Habibou Compaoré). V263 on a real PostgreSQL, with
 * rows shaped as those are:
 * <ul>
 *   <li>an order written after the cancel moves to its own finished outpatient
 *       anchor, keeps its status (dispensable again) and gets an audit row;</li>
 *   <li>a live order written before the cancel is DISCONTINUED with a reason,
 *       its back order closed, unless a fill or a partner offer holds it;</li>
 *   <li>the encounter becomes CANCELLED with an audit row;</li>
 *   <li>a take-home order, a dispensed one, an active stay's and a discharged
 *       stay's are left alone;</li>
 *   <li>a cancel from before V260 (no cancelled_at) reads updated_at;</li>
 *   <li>a second run changes nothing.</li>
 * </ul>
 */
@Testcontainers
class CancelledStayMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v263")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V263 = "db/migration/V263__r33_end_encounters_of_cancelled_stays.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String STAFF = "dddddddd-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT = "eeeeeeee-0000-0000-0000-000000000001";

    private static final String ADMITTED = "2026-11-05 08:00:00";
    private static final String CANCELLED_AT = "2026-11-05 10:00:00";
    private static final String BEFORE_CANCEL = "2026-11-05 09:00:00";
    private static final String AFTER_CANCEL = "2026-11-05 11:00:00";

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
    void noRows() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM clinical.prescription_routing_decisions");
            stmt.execute("DELETE FROM clinical.dispenses");
            stmt.execute("DELETE FROM clinical.prescriptions");
            stmt.execute("DELETE FROM clinical.encounters");
            stmt.execute("DELETE FROM public.admissions");
            stmt.execute("DELETE FROM support.audit_event_logs");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void anOrderWrittenAfterTheCancelBecomesAnOutpatientOrderItsStatusUntouched() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "CANCELLED", CANCELLED_AT);
            plantEncounter(stmt, 1, "IN_PROGRESS", id(1, "aaaa"));
            plantPrescription(stmt, 1, 1, "SIGNED", false, AFTER_CANCEL);

            List<String> notices = runV263(stmt);

            assertThat(status(stmt, 1)).isEqualTo("SIGNED");
            try (ResultSet rs = stmt.executeQuery("SELECT e.id, e.admission_id, e.encounter_type, e.status, e.notes, "
                    + "e.staff_id, e.assignment_id, e.hospital_id, e.patient_id, e.code, e.encounter_date "
                    + "FROM clinical.encounters e JOIN clinical.prescriptions p ON p.encounter_id = e.id "
                    + "WHERE p.id = '" + rxId(1) + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isNotEqualTo(id(1, "1111"));
                assertThat(rs.getString("admission_id")).isNull();
                assertThat(rs.getString("encounter_type")).isEqualTo("CONSULTATION");
                assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                assertThat(rs.getString("notes")).isEqualTo("Auto-generated for prescription entry");
                assertThat(rs.getString("staff_id")).isEqualTo(STAFF);
                assertThat(rs.getString("assignment_id")).isEqualTo(ASSIGNMENT);
                assertThat(rs.getString("hospital_id")).isEqualTo(HOSPITAL);
                assertThat(rs.getString("patient_id")).isEqualTo(PATIENT);
                assertThat(rs.getString("code")).startsWith("ENC-V263-");
                assertThat(rs.getTimestamp("encounter_date").toLocalDateTime().toString()).startsWith("2026-11-05T11:00");
            }
            assertThat(encounterStatus(stmt, 1)).isEqualTo("CANCELLED");
            assertThat(audits(stmt, "PRESCRIPTION_UPDATED", rxId(1))).isEqualTo(1);
            assertThat(audits(stmt, "ENCOUNTER_UPDATE", id(1, "1111"))).isEqualTo(1);
            assertThat(notices).anyMatch(n -> n.contains("1 order(s) written after the cancel moved")
                && n.contains("0 order(s) of cancelled stays discontinued") && n.contains("1 encounter(s)"));
        }
    }

    @Test
    void aLiveOrderOfTheCancelledStayEndsAndTheOthersAreLeftAlone() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "CANCELLED", CANCELLED_AT);
            plantEncounter(stmt, 1, "IN_PROGRESS", id(1, "aaaa"));
            plantPrescription(stmt, 1, 1, "SIGNED", false, BEFORE_CANCEL);
            plantPrescription(stmt, 2, 1, "PARTIALLY_FILLED", false, BEFORE_CANCEL);
            plantRouting(stmt, 2, "BACKORDER", "PENDING");
            plantPrescription(stmt, 3, 1, "SIGNED", true, BEFORE_CANCEL);
            plantPrescription(stmt, 4, 1, "DISPENSED", false, BEFORE_CANCEL);
            plantPrescription(stmt, 5, 1, "SIGNED", false, BEFORE_CANCEL);
            plantDispense(stmt, 5, "PENDING");
            plantPrescription(stmt, 6, 1, "SIGNED", false, BEFORE_CANCEL);
            plantRouting(stmt, 6, "PARTNER", "PENDING");

            List<String> notices = runV263(stmt);

            assertThat(status(stmt, 1)).isEqualTo("DISCONTINUED");
            assertThat(withdrawalReason(stmt, 1)).contains("annulée");
            assertThat(status(stmt, 2)).isEqualTo("DISCONTINUED");
            assertThat(routingStatus(stmt, 2)).isEqualTo("CANCELLED");
            assertThat(status(stmt, 3)).as("take-home").isEqualTo("SIGNED");
            assertThat(status(stmt, 4)).as("handed over").isEqualTo("DISPENSED");
            assertThat(status(stmt, 5)).as("held by a prepared fill").isEqualTo("SIGNED");
            assertThat(status(stmt, 6)).as("held by a partner offer").isEqualTo("SIGNED");
            for (int rx = 1; rx <= 6; rx++) {
                assertThat(encounterOf(stmt, rx)).as("written before the cancel: it stays filed on the stay")
                    .isEqualTo(id(1, "1111"));
            }
            assertThat(audits(stmt, "PRESCRIPTION_DISCONTINUED", rxId(1))).isEqualTo(1);
            assertThat(notices).anyMatch(n -> n.contains("2 order(s) of cancelled stays discontinued (1 open back order(s)")
                && n.contains("2 left for a prepared fill"));
            assertThat(encounterStatus(stmt, 1)).isEqualTo("CANCELLED");
        }
    }

    @Test
    void aStayThatWasNotCancelledIsNotTouched() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "ACTIVE", null);
            plantEncounter(stmt, 1, "IN_PROGRESS", id(1, "aaaa"));
            plantPrescription(stmt, 1, 1, "SIGNED", false, AFTER_CANCEL);
            plantAdmission(stmt, 2, "DISCHARGED", null);
            plantEncounter(stmt, 2, "IN_PROGRESS", id(2, "aaaa"));
            plantPrescription(stmt, 2, 2, "SIGNED", true, AFTER_CANCEL);
            plantEncounter(stmt, 3, "IN_PROGRESS", null);
            plantPrescription(stmt, 3, 3, "SIGNED", false, AFTER_CANCEL);

            runV263(stmt);

            assertThat(encounterStatus(stmt, 1)).isEqualTo("IN_PROGRESS");
            assertThat(encounterStatus(stmt, 2)).isEqualTo("IN_PROGRESS");
            assertThat(encounterStatus(stmt, 3)).isEqualTo("IN_PROGRESS");
            for (int rx = 1; rx <= 3; rx++) {
                assertThat(status(stmt, rx)).isEqualTo("SIGNED");
                assertThat(encounterOf(stmt, rx)).isEqualTo(id(rx, "1111"));
            }
            assertThat(auditCount(stmt)).isZero();
        }
    }

    @Test
    void aCancelFromBeforeV260ReadsTheAdmissionsUpdatedAt() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "CANCELLED", null);
            stmt.executeUpdate("UPDATE public.admissions SET updated_at = '" + CANCELLED_AT + "'");
            plantEncounter(stmt, 1, "IN_PROGRESS", id(1, "aaaa"));
            plantPrescription(stmt, 1, 1, "SIGNED", false, AFTER_CANCEL);
            plantPrescription(stmt, 2, 1, "SIGNED", false, BEFORE_CANCEL);

            runV263(stmt);

            assertThat(encounterOf(stmt, 1)).isNotEqualTo(id(1, "1111"));
            assertThat(status(stmt, 1)).isEqualTo("SIGNED");
            assertThat(status(stmt, 2)).isEqualTo("DISCONTINUED");
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "CANCELLED", CANCELLED_AT);
            plantEncounter(stmt, 1, "IN_PROGRESS", id(1, "aaaa"));
            plantPrescription(stmt, 1, 1, "SIGNED", false, AFTER_CANCEL);
            plantPrescription(stmt, 2, 1, "SIGNED", false, BEFORE_CANCEL);
            plantPrescription(stmt, 3, 1, "SIGNED", false, BEFORE_CANCEL);
            plantDispense(stmt, 3, "PENDING");

            runV263(stmt);
            int audits = auditCount(stmt);
            String anchor = encounterOf(stmt, 1);
            List<String> second = runV263(stmt);

            assertThat(auditCount(stmt)).isEqualTo(audits);
            assertThat(encounterOf(stmt, 1)).isEqualTo(anchor);
            assertThat(second).anyMatch(n -> n.contains("0 order(s) written after the cancel moved")
                && n.contains("0 order(s) of cancelled stays discontinued") && n.contains("1 left for a prepared fill")
                && n.contains("0 encounter(s)"));
        }
    }

    private static String id(int n, String prefix) {
        return prefix + prefix + "-0000-0000-0000-" + String.format("%012d", n);
    }

    private static String rxId(int n) {
        return id(n, "2222");
    }

    private static String quoted(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static void replica(Statement stmt, String sql) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate(sql);
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static void plantAdmission(Statement stmt, int n, String status, String cancelledAt) throws SQLException {
        replica(stmt, "INSERT INTO public.admissions (id, patient_id, hospital_id, admitting_provider_id, "
            + "admission_date_time, chief_complaint, acuity_level, admission_type, status, cancelled_at, "
            + "created_at, updated_at) VALUES ('" + id(n, "aaaa") + "', '" + PATIENT + "', '" + HOSPITAL + "', '"
            + STAFF + "', '" + ADMITTED + "', 'test', 'LEVEL_3_MAJOR', 'EMERGENCY', '" + status + "', "
            + quoted(cancelledAt) + ", now(), now())");
    }

    private static void plantEncounter(Statement stmt, int n, String status, String admissionId) throws SQLException {
        replica(stmt, "INSERT INTO clinical.encounters (id, patient_id, hospital_id, staff_id, assignment_id, "
            + "code, encounter_type, status, encounter_date, admission_id, created_at, updated_at) VALUES ('"
            + id(n, "1111") + "', '" + PATIENT + "', '" + HOSPITAL + "', '" + STAFF + "', '" + ASSIGNMENT
            + "', 'ENC-" + n + "', 'INPATIENT', '" + status + "', '" + ADMITTED + "', " + quoted(admissionId)
            + ", now(), now())");
    }

    private static void plantPrescription(Statement stmt, int n, int encounter, String status, boolean takeHome,
                                          String createdAt) throws SQLException {
        replica(stmt, "INSERT INTO clinical.prescriptions (id, patient_id, hospital_id, staff_id, assignment_id, "
            + "encounter_id, medication_name, status, take_home, allergies_reviewed, "
            + "contraindications_reviewed, controlled_substance, inpatient_order, interactions_reviewed, "
            + "requires_cosign, created_at, updated_at) VALUES ('" + rxId(n) + "', '" + PATIENT + "', '"
            + HOSPITAL + "', '" + STAFF + "', '" + ASSIGNMENT + "', '" + id(encounter, "1111") + "', "
            + "'Paracetamol', '" + status + "', " + takeHome + ", false, false, false, false, false, false, '"
            + createdAt + "', '" + createdAt + "')");
    }

    private static void plantDispense(Statement stmt, int rx, String status) throws SQLException {
        replica(stmt, "INSERT INTO clinical.dispenses (prescription_id, patient_id, pharmacy_id, dispensed_by, "
            + "medication_name, quantity_requested, quantity_dispensed, status) VALUES ('" + rxId(rx) + "', '"
            + PATIENT + "', '" + HOSPITAL + "', '" + STAFF + "', 'Paracetamol', 1, 1, '" + status + "')");
    }

    private static void plantRouting(Statement stmt, int rx, String type, String status) throws SQLException {
        replica(stmt, "INSERT INTO clinical.prescription_routing_decisions (prescription_id, routing_type, "
            + "decided_by_user_id, decided_for_patient_id, status) VALUES ('" + rxId(rx) + "', '" + type + "', '"
            + STAFF + "', '" + PATIENT + "', '" + status + "')");
    }

    private static String one(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String status(Statement stmt, int rx) throws SQLException {
        return one(stmt, "SELECT status FROM clinical.prescriptions WHERE id = '" + rxId(rx) + "'");
    }

    private static String withdrawalReason(Statement stmt, int rx) throws SQLException {
        return one(stmt, "SELECT withdrawal_reason FROM clinical.prescriptions WHERE id = '" + rxId(rx) + "'");
    }

    private static String encounterOf(Statement stmt, int rx) throws SQLException {
        return one(stmt, "SELECT encounter_id FROM clinical.prescriptions WHERE id = '" + rxId(rx) + "'");
    }

    private static String encounterStatus(Statement stmt, int n) throws SQLException {
        return one(stmt, "SELECT status FROM clinical.encounters WHERE id = '" + id(n, "1111") + "'");
    }

    private static String routingStatus(Statement stmt, int rx) throws SQLException {
        return one(stmt, "SELECT status FROM clinical.prescription_routing_decisions WHERE prescription_id = '"
            + rxId(rx) + "'");
    }

    private static int audits(Statement stmt, String event, String resource) throws SQLException {
        return Integer.parseInt(one(stmt, "SELECT count(*) FROM support.audit_event_logs WHERE event_type = '" + event
            + "' AND target_resource_id = '" + resource + "' AND actor_type = 'SYSTEM'"));
    }

    private static int auditCount(Statement stmt) throws SQLException {
        return Integer.parseInt(one(stmt, "SELECT count(*) FROM support.audit_event_logs"));
    }

    private static List<String> runV263(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = CancelledStayMigrationIT.class.getClassLoader().getResourceAsStream(V263)) {
            assertThat(in).as(V263).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The planted rows name no real patient or staff, so the keys are off; the
        // anchor's references are copied from a prescription that satisfies the same ones.
        stmt.execute("SET session_replication_role = replica");
        List<String> notices = new ArrayList<>();
        try {
            stmt.execute(sql);
            // Read before the next statement, which clears them.
            for (SQLWarning w = stmt.getWarnings(); w != null; w = w.getNextWarning()) {
                notices.add(w.getMessage());
            }
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
        assertThat(notices).as("the migration's own summary").anyMatch(n -> n.contains("V263:"));
        return notices;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
