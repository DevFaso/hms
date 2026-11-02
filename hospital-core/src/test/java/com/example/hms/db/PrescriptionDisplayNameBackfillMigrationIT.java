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
 * QA D1147 (round 22): prescriptions edited before #1001 kept the first drug's
 * display name - dev row c251ffc9 read medication_name 'Morphine' with display
 * 'Amoxicilline' - and the old drug's code when the edit sent none. V258 on a
 * real PostgreSQL, with rows shaped as the legacy ones:
 * <ul>
 *   <li>the display name follows the drug's name;</li>
 *   <li>a pre-fix edit linked to a catalogue item takes the item's code;</li>
 *   <li>an unlinked pre-fix edit whose code is the OLD drug's catalogue code
 *       loses it; a code no catalogue item explains is kept;</li>
 *   <li>a row already right, and a case-only edit, keep their code;</li>
 *   <li>a second run changes nothing.</li>
 * </ul>
 */
@Testcontainers
class PrescriptionDisplayNameBackfillMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v258")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V258 = "db/migration/V258__r22_prescription_display_name_backfill.sql";
    private static final String HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String OTHER_HOSPITAL = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String STAFF = "dddddddd-0000-0000-0000-000000000001";
    private static final String ASSIGNMENT = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String ENCOUNTER = "11111111-0000-0000-0000-000000000001";
    private static final String AMOXICILLINE = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String MORPHINE = "aaaaaaaa-0000-0000-0000-000000000002";

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
    void plantCatalogue() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM clinical.prescriptions");
            stmt.execute("DELETE FROM clinical.medication_catalog_items");
            stmt.execute("INSERT INTO clinical.medication_catalog_items (id, hospital_id, code, name_fr, generic_name) "
                + "VALUES ('" + AMOXICILLINE + "', '" + HOSPITAL + "', 'AMOX500', 'Amoxicilline', 'Amoxicilline')");
            stmt.execute("INSERT INTO clinical.medication_catalog_items (id, hospital_id, code, name_fr, generic_name) "
                + "VALUES ('" + MORPHINE + "', '" + HOSPITAL + "', 'MORPH10', 'Morphine', 'Morphine')");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void theLegacyRowsFollowTheCurrentDrug() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // c251ffc9's shape: edited from Amoxicilline to Morphine, the old code kept, no item.
            plant(stmt, 1, "Morphine", "Amoxicilline", "AMOX500", null, HOSPITAL);
            // Edited to Morphine picked from the catalogue, the old code kept.
            plant(stmt, 2, "Morphine", "Amoxicilline", "AMOX500", MORPHINE, HOSPITAL);
            // Edited, the code is an external one no catalogue item explains: kept.
            plant(stmt, 3, "Morphine", "Amoxicilline", "RX-723", null, HOSPITAL);
            // The code is the old drug's at ANOTHER hospital's catalogue: not this row's evidence, kept.
            plant(stmt, 4, "Morphine", "Amoxicilline", "AMOX500", null, OTHER_HOSPITAL);
            // Already right, and a case-only edit: untouched codes.
            plant(stmt, 5, "Morphine", "Morphine", "MORPH10", MORPHINE, HOSPITAL);
            plant(stmt, 6, "Amoxicilline", "amoxicilline", "AMOX500", null, HOSPITAL);
            // Written with no display at all.
            plant(stmt, 7, "Paracétamol", null, null, null, HOSPITAL);

            runV258(stmt);

            for (int rx = 1; rx <= 7; rx++) {
                assertThat(column(stmt, rx, "medication_display_name")).as("display of %d", rx)
                    .isEqualTo(column(stmt, rx, "medication_name"));
            }
            assertThat(column(stmt, 1, "medication_code")).as("the old drug's code is dropped").isNull();
            assertThat(column(stmt, 2, "medication_code")).as("the linked item's code").isEqualTo("MORPH10");
            assertThat(column(stmt, 3, "medication_code")).isEqualTo("RX-723");
            assertThat(column(stmt, 4, "medication_code")).isEqualTo("AMOX500");
            assertThat(column(stmt, 5, "medication_code")).isEqualTo("MORPH10");
            assertThat(column(stmt, 6, "medication_code")).isEqualTo("AMOX500");
            assertThat(column(stmt, 6, "medication_display_name")).isEqualTo("Amoxicilline");
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, 1, "Morphine", "Amoxicilline", "AMOX500", null, HOSPITAL);
            plant(stmt, 2, "Morphine", "Amoxicilline", "AMOX500", MORPHINE, HOSPITAL);
            runV258(stmt);
            // A later legitimate edit sets an external code on a now-consistent row.
            stmt.executeUpdate("UPDATE clinical.prescriptions SET medication_code = 'RX-1' WHERE id = '" + rxId(1) + "'");

            assertThat(runV258(stmt)).isZero();
            assertThat(column(stmt, 1, "medication_code")).isEqualTo("RX-1");
            assertThat(column(stmt, 2, "medication_code")).isEqualTo("MORPH10");
        }
    }

    private static String rxId(int n) {
        return String.format("ffffffff-0000-0000-0000-%012d", n);
    }

    private static String quoted(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static void plant(Statement stmt, int n, String name, String display, String code, String itemId,
                              String hospitalId) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO clinical.prescriptions (id, patient_id, hospital_id, staff_id, "
                + "assignment_id, encounter_id, medication_name, medication_display_name, medication_code, "
                + "medication_catalog_item_id, status, take_home, allergies_reviewed, contraindications_reviewed, "
                + "controlled_substance, inpatient_order, interactions_reviewed, requires_cosign, created_at, "
                + "updated_at) VALUES ('" + rxId(n) + "', '" + PATIENT + "', '" + hospitalId + "', '" + STAFF
                + "', '" + ASSIGNMENT + "', '" + ENCOUNTER + "', " + quoted(name) + ", " + quoted(display) + ", "
                + quoted(code) + ", " + quoted(itemId) + ", 'SIGNED', false, false, false, false, false, false, "
                + "false, now(), now())");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static String column(Statement stmt, int rx, String column) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT " + column + " FROM clinical.prescriptions WHERE id = '"
                + rxId(rx) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Runs V258 again on the planted rows; the number of rows its three statements changed. */
    private static int runV258(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = PrescriptionDisplayNameBackfillMigrationIT.class.getClassLoader()
                .getResourceAsStream(V258)) {
            assertThat(in).as(V258).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int changed = 0;
        for (String statement : sql.split(";")) {
            String code = statement.lines().filter(l -> !l.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b).trim();
            if (!code.isEmpty()) {
                changed += stmt.executeUpdate(code);
            }
        }
        return changed;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
