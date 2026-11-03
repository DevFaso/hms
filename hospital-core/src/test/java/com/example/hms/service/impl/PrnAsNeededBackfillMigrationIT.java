package com.example.hms.service.impl;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1378 / D1360 (round 32): V260 on a real PostgreSQL. As-needed became the
 * prescriber's explicit tick; the migration backfills {@code as_needed = true}
 * ONLY where the round-31 phrase list already read the frequency as as-needed
 * ({@link MarDoseSchedule#readByRound31Phrases}, the reader the MAR used until
 * V260), and ticks the order-set MEDICATION items the same way.
 *
 * <ul>
 *   <li>every frequency of the shared fixture (all four lists, ~180 wordings,
 *       accents, punctuation, STAT, blank) gets exactly the Java reader's answer;</li>
 *   <li>an order-set item: only a MEDICATION item the list read, without its
 *       own key, gets {@code "asNeeded": true}; order and other keys kept;</li>
 *   <li>D1381: the admission's cancellation columns exist;</li>
 *   <li>a second run changes nothing it should not.</li>
 * </ul>
 */
@Testcontainers
class PrnAsNeededBackfillMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v260")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V260 = "db/migration/V260__r32_prescription_as_needed_flag.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String STAFF = "dddddddd-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String ENCOUNTER = "11111111-0000-0000-0000-000000000001";
    private static final String ORDER_SET = "22222222-0000-0000-0000-000000000001";

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
    void clean() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM clinical.prescriptions");
            stmt.execute("DELETE FROM admission_order_sets");
        }
    }

    /** Every fixture wording, plus a few shapes the fixture has no reason to hold. */
    private static List<String> frequencies() throws Exception {
        Set<String> all = new LinkedHashSet<>();
        for (String key : List.of("asNeeded", "notAsNeeded", "conditional", "fixedSchedule")) {
            all.addAll(MarDoseScheduleTest.fixtureList(key));
        }
        all.addAll(List.of("Q4H PRN", "prn stat", "Stat puis si besoin", "SI-BESOIN", "à LA DEMANDE", "aprn",
            "1 cp 3 fois par jour pendant 5 jours", "Si Besoin"));
        return new ArrayList<>(all);
    }

    @Test
    void theBackfillTicksExactlyWhatTheRound31ListRead() throws Exception {
        List<String> frequencies = frequencies();
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            for (int i = 0; i < frequencies.size(); i++) {
                plant(conn, i, frequencies.get(i));
            }
            runV260(stmt);
            Map<String, String> wrong = new LinkedHashMap<>();
            int ticked = 0;
            for (int i = 0; i < frequencies.size(); i++) {
                String f = frequencies.get(i);
                boolean expected = MarDoseSchedule.readByRound31Phrases(f);
                boolean actual = asNeeded(stmt, i);
                ticked += actual ? 1 : 0;
                if (expected != actual) {
                    wrong.put("\"" + f + "\"", "java " + expected + " / sql " + actual);
                }
            }
            assertThat(wrong).as("V260 and the round-31 Java reader disagree").isEmpty();
            assertThat(ticked).as("the backfill ticked the round-31 as-needed rows").isGreaterThan(40);
            assertThat(asNeeded(stmt, frequencies.indexOf("SOS"))).as("SOS was not read in round 31").isFalse();
            assertThat(asNeeded(stmt, frequencies.indexOf("PRN STAT"))).as("STAT wins").isFalse();

            // A second run: nothing becomes true that should not, nothing is unticked.
            stmt.executeUpdate("UPDATE clinical.prescriptions SET as_needed = true WHERE id = '" + rxId(
                frequencies.indexOf("SOS")) + "'");
            runV260(stmt);
            assertThat(asNeeded(stmt, frequencies.indexOf("SOS"))).as("a tick is never removed").isTrue();
            assertThat(asNeeded(stmt, frequencies.indexOf("3 fois par jour"))).isFalse();
        }
    }

    @Test
    void theOrderSetMedicationItemsTheListReadAreTicked() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ArrayNode items = json.createArrayNode();
        List<String> frequencies = frequencies();
        for (String f : frequencies) {
            items.add(json.createObjectNode().put("orderType", "MEDICATION").put("medicationName", "Paracetamol")
                .put("frequency", f).put("prnMinIntervalHours", 4));
        }
        items.add(json.createObjectNode().put("orderType", "LAB").put("orderName", "NFS").put("frequency", "PRN"));
        items.add(json.createObjectNode().put("orderType", "MEDICATION").put("medicationName", "Morphine")
            .put("frequency", "si besoin").put("asNeeded", false));
        items.add(json.createObjectNode().put("orderType", "MEDICATION").put("medicationName", "Nofreq"));
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO admission_order_sets (id, hospital_id,"
                    + " name, admission_type, active, version, created_at, updated_at, order_items)"
                    + " VALUES (?::uuid, ?::uuid, 'Protocole douleur', 'EMERGENCY', true, 1, now(), now(), ?::jsonb)")) {
                ps.setString(1, ORDER_SET);
                ps.setString(2, HOSPITAL);
                ps.setString(3, json.writeValueAsString(items));
                ps.executeUpdate();
            } finally {
                stmt.execute("SET session_replication_role = origin");
            }
            runV260(stmt);
            JsonNode after;
            try (ResultSet rs = stmt.executeQuery("SELECT order_items::text FROM admission_order_sets")) {
                rs.next();
                after = json.readTree(rs.getString(1));
            }
            assertThat(after.size()).isEqualTo(items.size());
            Map<String, String> wrong = new LinkedHashMap<>();
            for (int i = 0; i < frequencies.size(); i++) {
                ObjectNode item = (ObjectNode) after.get(i);
                boolean expected = MarDoseSchedule.readByRound31Phrases(frequencies.get(i));
                assertThat(item.path("frequency").asText()).isEqualTo(frequencies.get(i));
                assertThat(item.path("prnMinIntervalHours").asInt()).isEqualTo(4);
                if (item.path("asNeeded").asBoolean(false) != expected || (!expected && item.has("asNeeded"))) {
                    wrong.put("\"" + frequencies.get(i) + "\"", "java " + expected + " / sql " + item.get("asNeeded"));
                }
            }
            assertThat(wrong).as("V260 ticked order-set items the round-31 list did not read, or missed one").isEmpty();
            int n = frequencies.size();
            assertThat(after.get(n).has("asNeeded")).as("a LAB item is untouched").isFalse();
            assertThat(after.get(n + 1).path("asNeeded").asBoolean(true)).as("an item's own key is kept").isFalse();
            assertThat(after.get(n + 2).has("asNeeded")).as("no frequency, nothing ticked").isFalse();
        }
    }

    @Test
    void d1381_theAdmissionKeepsItsCancellation() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT column_name FROM information_schema.columns"
                 + " WHERE table_name = 'admissions' AND column_name IN"
                 + " ('cancellation_reason', 'cancelled_at', 'cancelled_by_user_id')")) {
            Set<String> columns = new LinkedHashSet<>();
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
            assertThat(columns).containsExactlyInAnyOrder("cancellation_reason", "cancelled_at", "cancelled_by_user_id");
        }
    }

    private static String rxId(int n) {
        return String.format("ffffffff-0000-0000-0000-%012d", n);
    }

    private static void plant(Connection conn, int n, String frequency) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO clinical.prescriptions (id, patient_id,"
                    + " hospital_id, staff_id, assignment_id, encounter_id, medication_name, frequency, status,"
                    + " take_home, allergies_reviewed, contraindications_reviewed, controlled_substance,"
                    + " inpatient_order, interactions_reviewed, requires_cosign, created_at, updated_at) VALUES"
                    + " (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'Paracetamol', ?, 'SIGNED', false,"
                    + " false, false, false, false, false, false, now(), now())")) {
                ps.setString(1, rxId(n));
                ps.setString(2, PATIENT);
                ps.setString(3, HOSPITAL);
                ps.setString(4, STAFF);
                ps.setString(5, ASSIGNMENT);
                ps.setString(6, ENCOUNTER);
                ps.setString(7, frequency);
                ps.executeUpdate();
            } finally {
                stmt.execute("SET session_replication_role = origin");
            }
        }
    }

    private static boolean asNeeded(Statement stmt, int n) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT as_needed FROM clinical.prescriptions WHERE id = '"
                + rxId(n) + "'")) {
            assertThat(rs.next()).as("row " + n).isTrue();
            return rs.getBoolean(1);
        }
    }

    /** Runs V260 again, statement by statement, as Liquibase does (splitStatements, no semicolon in a literal). */
    private static void runV260(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = PrnAsNeededBackfillMigrationIT.class.getClassLoader().getResourceAsStream(V260)) {
            assertThat(in).as(V260).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : sql.split(";")) {
            String code = statement.lines().filter(l -> !l.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b).trim();
            if (!code.isEmpty()) {
                stmt.executeUpdate(code);
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
