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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 13 on a real PostgreSQL, after the whole changelog ran:
 * <ul>
 *   <li>V236 moves an encounter closed unseen with outcome
 *       LEFT_WITHOUT_BEING_SEEN from CANCELLED to the new status, and nothing
 *       else (a close-unseen CANCELLED, a plain cancellation, a completed
 *       visit); it gives a stay encounter its admission's current department
 *       only when that department is the encounter's hospital's (QA D804);
 *       it is idempotent.</li>
 *   <li>The QA D819 operator report lists exactly the patient records whose
 *       login is a staff account (staff record, staff assignment, or a global
 *       staff role), never a patient-only account.</li>
 * </ul>
 * Foreign keys are bypassed for the fixtures (session_replication_role =
 * replica); NOT NULL columns the test does not care about get a typed
 * placeholder.
 */
@Testcontainers
class Round13PatientsEncountersSqlIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_r13")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String HOSPITAL = "10000000-0000-0000-0000-000000000001";
    private static final String OTHER_HOSPITAL = "10000000-0000-0000-0000-000000000002";
    private static final String MEDICINE = "20000000-0000-0000-0000-000000000001";
    private static final String CARDIOLOGY = "20000000-0000-0000-0000-000000000002";
    private static final String FOREIGN_DEPT = "20000000-0000-0000-0000-000000000003";

    private static final String LWBS = "30000000-0000-0000-0000-000000000001";
    private static final String UNSEEN_CANCELLED = "30000000-0000-0000-0000-000000000002";
    private static final String PLAIN_CANCELLED = "30000000-0000-0000-0000-000000000003";
    private static final String COMPLETED = "30000000-0000-0000-0000-000000000004";
    private static final String TRANSFERRED_STAY = "30000000-0000-0000-0000-000000000005";
    private static final String FOREIGN_DEPT_STAY = "30000000-0000-0000-0000-000000000006";

    private static final String ADMISSION_TRANSFERRED = "40000000-0000-0000-0000-000000000001";
    private static final String ADMISSION_FOREIGN = "40000000-0000-0000-0000-000000000002";

    private static final String ROLE_PATIENT = "50000000-0000-0000-0000-000000000001";
    private static final String ROLE_RECEPTIONIST = "50000000-0000-0000-0000-000000000002";
    private static final String ROLE_SUPER_ADMIN = "50000000-0000-0000-0000-000000000003";

    private static final String U_STAFF_RECORD = "60000000-0000-0000-0000-000000000001";
    private static final String U_RECEPTIONIST = "60000000-0000-0000-0000-000000000002";
    private static final String U_SUPER_ADMIN = "60000000-0000-0000-0000-000000000003";
    private static final String U_PATIENT = "60000000-0000-0000-0000-000000000004";

    private static final String P_STAFF_RECORD = "70000000-0000-0000-0000-000000000001";
    private static final String P_RECEPTIONIST = "70000000-0000-0000-0000-000000000002";
    private static final String P_SUPER_ADMIN = "70000000-0000-0000-0000-000000000003";
    private static final String P_PATIENT = "70000000-0000-0000-0000-000000000004";

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
    void plant() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM clinical.encounters");
            stmt.execute("DELETE FROM public.admissions");
            stmt.execute("DELETE FROM hospital.departments");
            stmt.execute("DELETE FROM clinical.patients");
            stmt.execute("DELETE FROM hospital.staff");
            stmt.execute("DELETE FROM \"security\".user_role_hospital_assignment");
            stmt.execute("DELETE FROM \"security\".user_roles");
            stmt.execute("DELETE FROM \"security\".users");
            stmt.execute("DELETE FROM \"security\".roles");

            department(stmt, MEDICINE, HOSPITAL, "MED");
            department(stmt, CARDIOLOGY, HOSPITAL, "CAR");
            department(stmt, FOREIGN_DEPT, OTHER_HOSPITAL, "FOR");

            encounter(stmt, LWBS, "CANCELLED", MEDICINE, null,
                "{\"unseenOutcome\":\"LEFT_WITHOUT_BEING_SEEN\",\"unseenReason\":\"Parti\",\"unseenClosedAt\":\"2026-10-25T10:00\"}");
            encounter(stmt, UNSEEN_CANCELLED, "CANCELLED", MEDICINE, null,
                "{\"unseenOutcome\":\"CANCELLED\",\"unseenReason\":\"Erreur\"}");
            encounter(stmt, PLAIN_CANCELLED, "CANCELLED", MEDICINE, null, null);
            encounter(stmt, COMPLETED, "COMPLETED", MEDICINE, null,
                "{\"unseenOutcome\":\"LEFT_WITHOUT_BEING_SEEN\"}");

            admission(stmt, ADMISSION_TRANSFERRED, CARDIOLOGY);
            admission(stmt, ADMISSION_FOREIGN, FOREIGN_DEPT);
            encounter(stmt, TRANSFERRED_STAY, "COMPLETED", MEDICINE, ADMISSION_TRANSFERRED, null);
            encounter(stmt, FOREIGN_DEPT_STAY, "IN_PROGRESS", MEDICINE, ADMISSION_FOREIGN, null);

            role(stmt, ROLE_PATIENT, "ROLE_PATIENT");
            role(stmt, ROLE_RECEPTIONIST, "ROLE_RECEPTIONIST");
            role(stmt, ROLE_SUPER_ADMIN, "ROLE_SUPER_ADMIN");
            user(stmt, U_STAFF_RECORD, "staffrec");
            user(stmt, U_RECEPTIONIST, "recep");
            user(stmt, U_SUPER_ADMIN, "super");
            user(stmt, U_PATIENT, "pat");
            insert(stmt, "hospital.staff", Map.of("id", UUID.randomUUID().toString(), "user_id", U_STAFF_RECORD,
                "hospital_id", HOSPITAL, "license_number", "LIC-R13"));
            assignment(stmt, U_RECEPTIONIST, ROLE_RECEPTIONIST, "R13-A1");
            assignment(stmt, U_PATIENT, ROLE_PATIENT, "R13-A2");
            insert(stmt, "\"security\".user_roles", Map.of("user_id", U_SUPER_ADMIN, "role_id", ROLE_SUPER_ADMIN));
            insert(stmt, "\"security\".user_roles", Map.of("user_id", U_PATIENT, "role_id", ROLE_PATIENT));
            patient(stmt, P_STAFF_RECORD, U_STAFF_RECORD, "1");
            patient(stmt, P_RECEPTIONIST, U_RECEPTIONIST, "2");
            patient(stmt, P_SUPER_ADMIN, U_SUPER_ADMIN, "3");
            patient(stmt, P_PATIENT, U_PATIENT, "4");
        }
    }

    @Test
    void v236MovesOnlyTheLeftWithoutBeingSeenRowsAndIsIdempotent() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(resource("db/migration/V236__encounter_left_without_being_seen.sql"));
            stmt.execute(resource("db/migration/V236__encounter_left_without_being_seen.sql"));

            assertThat(status(stmt, LWBS)).isEqualTo("LEFT_WITHOUT_BEING_SEEN");
            assertThat(status(stmt, UNSEEN_CANCELLED)).isEqualTo("CANCELLED");
            assertThat(status(stmt, PLAIN_CANCELLED)).isEqualTo("CANCELLED");
            assertThat(status(stmt, COMPLETED)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void v236GivesAStayEncounterItsAdmissionsDepartmentOnlyWithinItsHospital() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(resource("db/migration/V236__encounter_left_without_being_seen.sql"));

            assertThat(department(stmt, TRANSFERRED_STAY)).isEqualTo(CARDIOLOGY);
            assertThat(department(stmt, FOREIGN_DEPT_STAY)).isEqualTo(MEDICINE);
            assertThat(department(stmt, LWBS)).isEqualTo(MEDICINE);
        }
    }

    @Test
    void theD819ReportListsOnlyPatientRecordsLoggedInByAStaffAccount() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(resource("ops/d819_staff_linked_patients_report.sql"))) {
                while (rs.next()) {
                    rows.add(rs.getString("patient_id") + "|" + rs.getBoolean("has_staff_record") + "|"
                        + rs.getString("staff_assignment_roles") + "|" + rs.getString("staff_global_roles"));
                }
            }
            assertThat(rows).containsExactlyInAnyOrder(
                P_STAFF_RECORD + "|true|null|null",
                P_RECEPTIONIST + "|false|ROLE_RECEPTIONIST|null",
                P_SUPER_ADMIN + "|false|null|ROLE_SUPER_ADMIN");
        }
    }

    private static String status(Statement stmt, String encounterId) throws SQLException {
        return scalar(stmt, "SELECT status FROM clinical.encounters WHERE id = '" + encounterId + "'");
    }

    private static String department(Statement stmt, String encounterId) throws SQLException {
        return scalar(stmt, "SELECT department_id FROM clinical.encounters WHERE id = '" + encounterId + "'");
    }

    private static void department(Statement stmt, String id, String hospitalId, String code) throws SQLException {
        insert(stmt, "hospital.departments", Map.of("id", id, "hospital_id", hospitalId, "code", "R13" + code,
            "name", "Dept " + code));
    }

    private static void encounter(Statement stmt, String id, String status, String departmentId,
                                  String admissionId, String extra) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("hospital_id", HOSPITAL);
        values.put("department_id", departmentId);
        values.put("status", status);
        values.put("code", "ENC-R13-" + id.substring(id.length() - 4));
        values.put("encounter_type", admissionId != null ? "INPATIENT" : "OUTPATIENT");
        if (admissionId != null) {
            values.put("admission_id", admissionId);
        }
        if (extra != null) {
            values.put("extra_fields", extra);
        }
        insert(stmt, "clinical.encounters", values);
    }

    private static void admission(Statement stmt, String id, String departmentId) throws SQLException {
        insert(stmt, "public.admissions", Map.of("id", id, "hospital_id", HOSPITAL, "department_id", departmentId));
    }

    private static void role(Statement stmt, String id, String code) throws SQLException {
        insert(stmt, "\"security\".roles", Map.of("id", id, "code", code, "name", code));
    }

    private static void user(Statement stmt, String id, String name) throws SQLException {
        insert(stmt, "\"security\".users", Map.of("id", id, "username", "r13" + name,
            "email", "r13" + name + "@example.test", "phone_number", "+2265000" + id.substring(id.length() - 4)));
    }

    private static void assignment(Statement stmt, String userId, String roleId, String code) throws SQLException {
        insert(stmt, "\"security\".user_role_hospital_assignment", Map.of("id", UUID.randomUUID().toString(),
            "user_id", userId, "role_id", roleId, "hospital_id", HOSPITAL, "assignment_code", code));
    }

    private static void patient(Statement stmt, String id, String userId, String n) throws SQLException {
        insert(stmt, "clinical.patients", Map.of("id", id, "user_id", userId, "hospital_id", HOSPITAL,
            "email", "r13p" + n + "@example.test", "phone_number_primary", "+2266000000" + n));
    }

    /** Inserts the given columns and a typed placeholder in every other NOT NULL column with no default. */
    private static void insert(Statement stmt, String table, Map<String, String> given) throws SQLException {
        String[] parts = table.replace("\"", "").split("\\.");
        Map<String, String> values = new LinkedHashMap<>(given);
        try (Statement meta = stmt.getConnection().createStatement();
             ResultSet rs = meta.executeQuery(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = '"
                    + parts[0] + "' AND table_name = '" + parts[1]
                    + "' AND is_nullable = 'NO' AND column_default IS NULL")) {
            while (rs.next()) {
                values.putIfAbsent(rs.getString(1), placeholder(rs.getString(2)));
            }
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!cols.isEmpty()) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(e.getKey());
            vals.append(e.getValue() == null ? "NULL" : "'" + e.getValue().replace("'", "''") + "'");
        }
        stmt.execute("INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")");
    }

    private static String placeholder(String type) {
        return switch (type) {
            case "uuid" -> UUID.randomUUID().toString();
            case "boolean" -> "false";
            case "integer", "bigint", "smallint", "numeric", "double precision", "real" -> "0";
            case "date" -> "2026-01-01";
            case "timestamp without time zone", "timestamp with time zone" -> "2026-01-01 00:00:00";
            case "jsonb", "json" -> "{}";
            default -> "x" + UUID.randomUUID().toString().substring(0, 8);
        };
    }

    private static String scalar(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = Round13PatientsEncountersSqlIT.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
