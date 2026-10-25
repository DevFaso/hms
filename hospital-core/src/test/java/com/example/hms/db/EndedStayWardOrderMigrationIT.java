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
 * QA D792 (residual of D757): on dev, 15 ward orders of stays discharged
 * before the D757 fix were still SIGNED, PARTIALLY_FILLED or DRAFT on their
 * closed stay encounter, morphine among them. V233 on a real PostgreSQL,
 * with rows shaped as the legacy ones are:
 * <ul>
 *   <li>a SIGNED and a PARTIALLY_FILLED order on a COMPLETED stay encounter,
 *       and a morphine order written on it after the discharge, are
 *       DISCONTINUED with one SYSTEM audit row each;</li>
 *   <li>an order on an unlinked INPATIENT encounter written during an ended
 *       stay, and an emergency-department order carried over to it, too;</li>
 *   <li>a take-home order, an order of a stay still in progress, an
 *       outpatient order, an already dispensed one and orders held by a
 *       prepared fill or an open partner offer are left alone, the held
 *       ones counted by shape in the NOTICE;</li>
 *   <li>an open back order holds nothing: its order ends and it is
 *       CANCELLED;</li>
 *   <li>an order a partner refused (PARTNER_REJECTED, no offer left open)
 *       is ended too;</li>
 *   <li>a second run changes nothing.</li>
 * </ul>
 */
@Testcontainers
class EndedStayWardOrderMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v233")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V233 = "db/migration/V233__discontinue_ended_stay_ward_orders.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String STAFF = "dddddddd-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT = "eeeeeeee-0000-0000-0000-000000000001";

    /** The discharged stay: 2026-10-24 04:00 to 04:43, as the re-test's 70f9c0b6. */
    private static final String ADMITTED = "2026-10-24 04:00:00";
    private static final String DISCHARGED = "2026-10-24 04:43:46";

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
    void theLegacyOrdersOfADischargedStayAreDiscontinuedWithAnAuditRowEach() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            plantEncounter(stmt, 1, "INPATIENT", "COMPLETED", id(1, "aaaa"), null);
            plantPrescription(stmt, 1, 1, "SIGNED", false, "2026-10-24 04:40:47");
            plantPrescription(stmt, 2, 1, "PARTIALLY_FILLED", false, "2026-10-24 04:41:00");
            plantPrescription(stmt, 3, 1, "DRAFT", false, "2026-10-24 04:42:00");
            // Morphine written on the closed stay encounter after the discharge (ab17c200).
            plantPrescription(stmt, 4, 1, "SIGNED", false, "2026-10-24 04:50:24");

            List<String> notices = runV233(stmt);

            for (int rx = 1; rx <= 4; rx++) {
                assertThat(status(stmt, rx)).as("order %d", rx).isEqualTo("DISCONTINUED");
                assertThat(withdrawalReason(stmt, rx)).isNotBlank();
            }
            assertThat(notices).anyMatch(n -> n.contains("4 ward order(s) of ended stays discontinued (0 open back order(s) cancelled), 0 left"));

            try (ResultSet rs = stmt.executeQuery("SELECT actor_type, user_id, event_type, status, "
                    + "target_entity_type, event_description, details FROM support.audit_event_logs "
                    + "WHERE target_resource_id = '" + rxId(2) + "'")) {
                assertThat(rs.next()).as("one audit row for the discontinued order").isTrue();
                assertThat(rs.getString("actor_type")).isEqualTo("SYSTEM");
                assertThat(rs.getString("user_id")).isNull();
                assertThat(rs.getString("event_type")).isEqualTo("PRESCRIPTION_DISCONTINUED");
                assertThat(rs.getString("status")).isEqualTo("SUCCESS");
                assertThat(rs.getString("target_entity_type")).isEqualTo("Prescription");
                assertThat(rs.getString("event_description")).contains("V233").doesNotContain(PATIENT);
                assertThat(rs.getString("details")).contains("PARTIALLY_FILLED").doesNotContain(PATIENT)
                    .doesNotContainIgnoringCase("morphine");
                assertThat(rs.next()).as("exactly one row").isFalse();
            }
            assertThat(auditCount(stmt)).isEqualTo(4);
        }
    }

    @Test
    void ordersOfAnEndedStayFiledOnAnUnlinkedOrAnEmergencyEncounterAreDiscontinued() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            // An INPATIENT encounter with no admission link (V193 did not backfill).
            plantEncounter(stmt, 2, "INPATIENT", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 5, 2, "SIGNED", false, "2026-10-24 04:20:00");
            // The emergency visit that led to the stay, never checked out (D760).
            plantEncounter(stmt, 3, "EMERGENCY", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 6, 3, "SIGNED", false, "2026-10-24 02:00:00");
            // A linked stay encounter left open although its admission is discharged.
            plantEncounter(stmt, 4, "INPATIENT", "IN_PROGRESS", id(1, "aaaa"), null);
            plantPrescription(stmt, 7, 4, "TRANSMITTED", false, "2026-10-24 04:30:00");

            runV233(stmt);

            assertThat(status(stmt, 5)).isEqualTo("DISCONTINUED");
            assertThat(status(stmt, 6)).isEqualTo("DISCONTINUED");
            assertThat(status(stmt, 7)).isEqualTo("DISCONTINUED");
            assertThat(auditCount(stmt)).isEqualTo(3);
        }
    }

    @Test
    void takeHomeCurrentStayOutpatientAndHeldOrdersAreLeftAlone() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            plantEncounter(stmt, 1, "INPATIENT", "COMPLETED", id(1, "aaaa"), null);
            // The re-test's a197bc9a / 091ef805: take-home Amoxicilline, meant to go home.
            plantPrescription(stmt, 10, 1, "SIGNED", true, "2026-10-24 04:42:00");
            // Already handed over: nothing live to end.
            plantPrescription(stmt, 11, 1, "DISPENSED", false, "2026-10-24 04:10:00");
            // A prepared fill waits at the counter.
            plantPrescription(stmt, 12, 1, "SIGNED", false, "2026-10-24 04:11:00");
            plantDispense(stmt, 12, "PENDING");
            // A partner pharmacy holds an open offer.
            plantPrescription(stmt, 13, 1, "SIGNED", false, "2026-10-24 04:12:00");
            plantRouting(stmt, 13, "ACCEPTED");

            // A stay still in progress.
            plantAdmission(stmt, 2, "ACTIVE", "2026-10-25 08:00:00", null);
            plantEncounter(stmt, 5, "INPATIENT", "IN_PROGRESS", id(2, "aaaa"), null);
            plantPrescription(stmt, 14, 5, "SIGNED", false, "2026-10-25 09:00:00");
            // An outpatient consultation the same day as the ended stay.
            plantEncounter(stmt, 6, "CONSULTATION", "COMPLETED", null, null);
            plantPrescription(stmt, 15, 6, "SIGNED", false, "2026-10-24 04:20:00");
            // An unlinked INPATIENT encounter's order written AFTER the stay ended.
            plantEncounter(stmt, 7, "INPATIENT", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 16, 7, "SIGNED", false, "2026-10-24 06:00:00");
            // An emergency visit checked out before the admission (went home, came back).
            plantEncounter(stmt, 8, "EMERGENCY", "COMPLETED", null, "2026-10-24 03:00:00");
            plantPrescription(stmt, 17, 8, "SIGNED", false, "2026-10-24 02:30:00");

            List<String> notices = runV233(stmt);

            assertThat(status(stmt, 10)).isEqualTo("SIGNED");
            assertThat(status(stmt, 11)).isEqualTo("DISPENSED");
            assertThat(status(stmt, 12)).isEqualTo("SIGNED");
            assertThat(status(stmt, 13)).isEqualTo("SIGNED");
            assertThat(status(stmt, 14)).isEqualTo("SIGNED");
            assertThat(status(stmt, 15)).isEqualTo("SIGNED");
            assertThat(status(stmt, 16)).isEqualTo("SIGNED");
            assertThat(status(stmt, 17)).isEqualTo("SIGNED");
            assertThat(notices).anyMatch(n -> n.contains("0 ward order(s) of ended stays discontinued (0 open back order(s) cancelled), 2 left")
                && n.contains("(closed_stay_encounter 2, open_stay_encounter 0, "
                    + "unlinked_inpatient 0, emergency_carry_over 0)"));
            assertThat(auditCount(stmt)).isZero();
        }
    }

    @Test
    void heldOrdersAreLeftAloneAndCountedByShape() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            // A linked stay encounter left open although its admission is discharged.
            plantEncounter(stmt, 4, "INPATIENT", "IN_PROGRESS", id(1, "aaaa"), null);
            plantPrescription(stmt, 30, 4, "SIGNED", false, "2026-10-24 04:30:00");
            plantDispense(stmt, 30, "PENDING");
            // A closed stay encounter, offered to a partner.
            plantEncounter(stmt, 1, "INPATIENT", "COMPLETED", id(1, "aaaa"), null);
            plantPrescription(stmt, 31, 1, "TRANSMITTED", false, "2026-10-24 04:31:00");
            plantRouting(stmt, 31, "PENDING");
            // An unlinked INPATIENT encounter.
            plantEncounter(stmt, 2, "INPATIENT", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 32, 2, "SIGNED", false, "2026-10-24 04:32:00");
            plantDispense(stmt, 32, "PENDING");
            // The emergency visit carried over to the stay.
            plantEncounter(stmt, 3, "EMERGENCY", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 33, 3, "SIGNED", false, "2026-10-24 02:00:00");
            plantRouting(stmt, 33, "ACCEPTED");

            List<String> notices = runV233(stmt);

            for (int rx = 30; rx <= 33; rx++) {
                assertThat(status(stmt, rx)).as("order %d", rx).isNotEqualTo("DISCONTINUED");
            }
            assertThat(notices).anyMatch(n -> n.contains("0 ward order(s) of ended stays discontinued (0 open back order(s) cancelled), 4 left")
                && n.contains("(closed_stay_encounter 1, open_stay_encounter 1, "
                    + "unlinked_inpatient 1, emergency_carry_over 1)"));
            assertThat(auditCount(stmt)).isZero();
        }
    }

    /**
     * #953 review, round 2: an open back order is no hold. Nobody outside is
     * told of it and only a fill (which the guard refuses) would close it, so
     * holding the order for it left it PENDING_STOCK in the work queue for
     * good. The order ends and the back order is CANCELLED with it; a partner
     * offer still holds.
     */
    @Test
    void anOpenBackOrderIsNoHoldAndIsCancelledWithItsOrder() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            // A linked stay encounter left open although its admission is discharged.
            plantEncounter(stmt, 4, "INPATIENT", "IN_PROGRESS", id(1, "aaaa"), null);
            plantPrescription(stmt, 40, 4, "PENDING_STOCK", false, "2026-10-24 04:30:00");
            plantRouting(stmt, 40, "BACKORDER", "PENDING");
            // The same shape, offered to a partner as well: the offer holds it.
            plantPrescription(stmt, 41, 4, "PENDING_STOCK", false, "2026-10-24 04:31:00");
            plantRouting(stmt, 41, "BACKORDER", "PENDING");
            plantRouting(stmt, 41, "PARTNER", "PENDING");

            List<String> notices = runV233(stmt);

            assertThat(status(stmt, 40)).isEqualTo("DISCONTINUED");
            assertThat(routingStatus(stmt, 40, "BACKORDER")).isEqualTo("CANCELLED");
            assertThat(status(stmt, 41)).isEqualTo("PENDING_STOCK");
            assertThat(routingStatus(stmt, 41, "BACKORDER")).isEqualTo("PENDING");
            assertThat(routingStatus(stmt, 41, "PARTNER")).isEqualTo("PENDING");
            assertThat(notices).anyMatch(n -> n.contains(
                "1 ward order(s) of ended stays discontinued (1 open back order(s) cancelled), 1 left")
                && n.contains("open_stay_encounter 1,"));
            assertThat(auditCount(stmt)).isEqualTo(1);
        }
    }

    /**
     * #953 review, round 3: a partner refused the order (by SMS, recorded by a
     * pharmacist, or the timeout sweep), so it went back to the in-house work
     * queue as PARTNER_REJECTED with its decision REJECTED. No hold is left
     * and no partner event will ever come for it: it is ended like any live
     * order. A refused order re-offered to another partner is still held.
     */
    @Test
    void anOrderRefusedByItsPartnerIsDiscontinuedWithAnAuditRow() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            // An INPATIENT encounter with no admission link (V193 did not backfill).
            plantEncounter(stmt, 2, "INPATIENT", "IN_PROGRESS", null, null);
            plantPrescription(stmt, 50, 2, "PARTNER_REJECTED", false, "2026-10-24 04:20:00");
            plantRouting(stmt, 50, "PARTNER", "REJECTED");
            // Refused by one partner, then offered to another that has not answered.
            plantPrescription(stmt, 51, 2, "PARTNER_REJECTED", false, "2026-10-24 04:21:00");
            plantRouting(stmt, 51, "PARTNER", "REJECTED");
            plantRouting(stmt, 51, "PARTNER", "PENDING");

            List<String> notices = runV233(stmt);

            assertThat(status(stmt, 50)).isEqualTo("DISCONTINUED");
            assertThat(withdrawalReason(stmt, 50)).isNotBlank();
            assertThat(status(stmt, 51)).isEqualTo("PARTNER_REJECTED");
            try (ResultSet rs = stmt.executeQuery("SELECT actor_type, event_type, details "
                    + "FROM support.audit_event_logs WHERE target_resource_id = '" + rxId(50) + "'")) {
                assertThat(rs.next()).as("one audit row for the refused order").isTrue();
                assertThat(rs.getString("actor_type")).isEqualTo("SYSTEM");
                assertThat(rs.getString("event_type")).isEqualTo("PRESCRIPTION_DISCONTINUED");
                assertThat(rs.getString("details")).contains("PARTNER_REJECTED -> DISCONTINUED");
                assertThat(rs.next()).as("exactly one row").isFalse();
            }
            assertThat(notices).anyMatch(n -> n.contains(
                "1 ward order(s) of ended stays discontinued (0 open back order(s) cancelled), 1 left")
                && n.contains("unlinked_inpatient 1,"));
            assertThat(auditCount(stmt)).isEqualTo(1);
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantAdmission(stmt, 1, "DISCHARGED", ADMITTED, DISCHARGED);
            plantEncounter(stmt, 1, "INPATIENT", "COMPLETED", id(1, "aaaa"), null);
            plantPrescription(stmt, 1, 1, "SIGNED", false, "2026-10-24 04:40:47");
            runV233(stmt);

            List<String> notices = runV233(stmt);

            assertThat(status(stmt, 1)).isEqualTo("DISCONTINUED");
            assertThat(notices).anyMatch(n -> n.contains("0 ward order(s) of ended stays discontinued"));
            assertThat(auditCount(stmt)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------

    private static String id(int n, String prefix) {
        return String.format("%s%s-0000-0000-0000-%012d", prefix, prefix, n);
    }

    private static String rxId(int n) {
        return id(n, "ffff");
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

    private static void plantAdmission(Statement stmt, int n, String status, String admitted, String discharged)
            throws SQLException {
        replica(stmt, "INSERT INTO public.admissions (id, patient_id, hospital_id, admitting_provider_id, "
            + "admission_date_time, actual_discharge_date_time, chief_complaint, acuity_level, admission_type, "
            + "status, created_at, updated_at) VALUES ('" + id(n, "aaaa") + "', '" + PATIENT + "', '" + HOSPITAL
            + "', '" + STAFF + "', '" + admitted + "', " + quoted(discharged) + ", 'test', 'LEVEL_3_MAJOR', "
            + "'EMERGENCY', '" + status + "', now(), now())");
    }

    private static void plantEncounter(Statement stmt, int n, String type, String status, String admissionId,
                                       String checkout) throws SQLException {
        replica(stmt, "INSERT INTO clinical.encounters (id, patient_id, hospital_id, staff_id, assignment_id, "
            + "code, encounter_type, status, encounter_date, admission_id, checkout_timestamp, created_at, "
            + "updated_at) VALUES ('" + id(n, "1111") + "', '" + PATIENT + "', '" + HOSPITAL + "', '" + STAFF
            + "', '" + ASSIGNMENT + "', 'ENC-" + n + "', '" + type + "', '" + status + "', '2026-10-24 01:00:00', "
            + quoted(admissionId) + ", " + quoted(checkout) + ", now(), now())");
    }

    private static void plantPrescription(Statement stmt, int n, int encounter, String status, boolean takeHome,
                                          String createdAt) throws SQLException {
        replica(stmt, "INSERT INTO clinical.prescriptions (id, patient_id, hospital_id, staff_id, assignment_id, "
            + "encounter_id, medication_name, status, take_home, allergies_reviewed, "
            + "contraindications_reviewed, controlled_substance, inpatient_order, interactions_reviewed, "
            + "requires_cosign, created_at, updated_at) VALUES ('" + rxId(n) + "', '" + PATIENT + "', '"
            + HOSPITAL + "', '" + STAFF + "', '" + ASSIGNMENT + "', '" + id(encounter, "1111") + "', "
            + "'Morphine 10 mg/ml', '" + status + "', " + takeHome + ", false, false, false, false, false, false, '"
            + createdAt + "', '" + createdAt + "')");
    }

    private static void plantDispense(Statement stmt, int rx, String status) throws SQLException {
        replica(stmt, "INSERT INTO clinical.dispenses (prescription_id, patient_id, pharmacy_id, dispensed_by, "
            + "medication_name, quantity_requested, quantity_dispensed, status) VALUES ('" + rxId(rx) + "', '"
            + PATIENT + "', '" + HOSPITAL + "', '" + STAFF + "', 'Morphine', 1, 1, '" + status + "')");
    }

    private static void plantRouting(Statement stmt, int rx, String status) throws SQLException {
        plantRouting(stmt, rx, "PARTNER", status);
    }

    private static void plantRouting(Statement stmt, int rx, String type, String status) throws SQLException {
        replica(stmt, "INSERT INTO clinical.prescription_routing_decisions (prescription_id, routing_type, "
            + "decided_by_user_id, decided_for_patient_id, status) VALUES ('" + rxId(rx) + "', '" + type + "', '"
            + STAFF + "', '" + PATIENT + "', '" + status + "')");
    }

    private static String routingStatus(Statement stmt, int rx, String type) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT status FROM clinical.prescription_routing_decisions "
                + "WHERE prescription_id = '" + rxId(rx) + "' AND routing_type = '" + type + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String status(Statement stmt, int rx) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT status FROM clinical.prescriptions WHERE id = '"
                + rxId(rx) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String withdrawalReason(Statement stmt, int rx) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT withdrawal_reason FROM clinical.prescriptions WHERE id = '"
                + rxId(rx) + "'")) {
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

    private static List<String> runV233(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = EndedStayWardOrderMigrationIT.class.getClassLoader().getResourceAsStream(V233)) {
            assertThat(in).as(V233).isNotNull();
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
