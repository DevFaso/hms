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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V176 on a real PostgreSQL: with no duplicate (patient, resource) rows it
 * adds the unique key, which then refuses a duplicate; with duplicates it
 * stops and leaves the table exactly as it was, for a person to resolve.
 *
 * <p>The full changelog is applied first (V176 included), then each test
 * removes the key, plants its rows and runs the V176 file again, as a deploy
 * would. Foreign keys are bypassed for the fixtures only
 * (session_replication_role = replica), since V169's patient key would
 * otherwise need a whole patient graph.
 */
@Testcontainers
class EducationProgressUniqueKeyMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v176")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V176 = "db/migration/V176__patient_education_progress_one_row_per_resource.sql";
    private static final String KEY = "uk_patient_education_progress_patient_resource";

    private static final String PATIENT_1 = "11111111-0000-0000-0000-000000000001";
    private static final String PATIENT_2 = "11111111-0000-0000-0000-000000000002";
    private static final String RESOURCE_1 = "22222222-0000-0000-0000-000000000001";
    private static final String RESOURCE_2 = "22222222-0000-0000-0000-000000000002";

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
    void startWithoutTheKeyAndWithoutRows() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE clinical.patient_education_progress DROP CONSTRAINT IF EXISTS " + KEY);
            stmt.execute("DELETE FROM clinical.patient_education_progress");
        }
    }

    @Test
    void withoutDuplicatesTheKeyIsAddedAndRefusesASecondRow() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, "aaaaaaaa-0000-0000-0000-000000000001", PATIENT_1, RESOURCE_1);
            plant(stmt, "aaaaaaaa-0000-0000-0000-000000000002", PATIENT_1, RESOURCE_2);
            plant(stmt, "aaaaaaaa-0000-0000-0000-000000000003", PATIENT_2, RESOURCE_1);

            assertThat(runV176(stmt)).anyMatch(n -> n.contains(KEY + " added"));

            assertThat(keyExists(stmt)).isTrue();
            assertThat(snapshot(stmt)).hasSize(3);
            assertThatThrownBy(() -> plant(stmt, "aaaaaaaa-0000-0000-0000-000000000004", PATIENT_1, RESOURCE_1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(KEY);
        }
    }

    @Test
    void withDuplicatesItStopsAndTheTableIsUnchanged() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // Two groups with duplicates, and one row on its own.
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000001", PATIENT_1, RESOURCE_1);
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000002", PATIENT_1, RESOURCE_1);
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000003", PATIENT_1, RESOURCE_1);
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000004", PATIENT_2, RESOURCE_2);
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000005", PATIENT_2, RESOURCE_2);
            plant(stmt, "bbbbbbbb-0000-0000-0000-000000000006", PATIENT_2, RESOURCE_1);
            List<String> before = snapshot(stmt);

            assertThatThrownBy(() -> runV176(stmt))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("2 (patient, resource) group(s)")
                .hasMessageContaining("Nothing was changed")
                .hasMessageNotContaining(PATIENT_1)
                .hasMessageNotContaining(RESOURCE_1);

            assertThat(snapshot(stmt)).containsExactlyElementsOf(before);
            assertThat(keyExists(stmt)).isFalse();
        }
    }

    @Test
    void aSecondRunFindsTheKeyAndChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, "cccccccc-0000-0000-0000-000000000001", PATIENT_1, RESOURCE_1);
            runV176(stmt);
            List<String> before = snapshot(stmt);

            assertThat(runV176(stmt)).anyMatch(n -> n.contains(KEY + " already present"));

            assertThat(keyExists(stmt)).isTrue();
            assertThat(snapshot(stmt)).containsExactlyElementsOf(before);
        }
    }

    // ------------------------------------------------------------------

    private static void plant(Statement stmt, String id, String patient, String resource) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO clinical.patient_education_progress (id, patient_id, resource_id, "
                + "hospital_id, comprehension_status, progress_percentage, time_spent_seconds, access_count, "
                + "needs_clarification, confirmed_understanding, rating, feedback, created_at, updated_at) VALUES ('"
                + id + "', '" + patient + "', '" + resource + "', '33333333-0000-0000-0000-000000000001', "
                + "'IN_PROGRESS', 40, 120, 2, FALSE, TRUE, 4, 'fixture " + id.charAt(id.length() - 1) + "', "
                + "'2026-01-01 09:00', '2026-01-02 09:00')");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    /** Every row, every column, in a stable order. */
    private static List<String> snapshot(Statement stmt) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT p::text FROM clinical.patient_education_progress p ORDER BY p.id")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static boolean keyExists(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname = '" + KEY + "'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }

    private static List<String> runV176(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = EducationProgressUniqueKeyMigrationIT.class.getClassLoader().getResourceAsStream(V176)) {
            assertThat(in).as(V176).isNotNull();
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
