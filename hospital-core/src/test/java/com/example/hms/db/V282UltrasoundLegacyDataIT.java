package com.example.hms.db;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1525: V282's one-shot data step on real PostgreSQL. Rows that existed
 * before V282 (marker NULL) become legacy (FALSE) and lose exactly the values
 * the old code could have defaulted; typed values stay; a stated row is never
 * touched; a second run changes nothing.
 *
 * <p>The changelog is applied first (so the table has every column), then the
 * marker is put back to its pre-V282 shape (nullable, no default), rows are
 * seeded with foreign-key triggers off ({@code session_replication_role =
 * replica}, the container user is a superuser), and the V282 file is run as
 * written, twice.
 */
@Testcontainers
class V282UltrasoundLegacyDataIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_test")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final UUID DEFAULTS = UUID.randomUUID();
    private static final UUID TYPED = UUID.randomUUID();
    private static final UUID TEMPLATE_GA_WITH_TEXT = UUID.randomUUID();
    private static final UUID STATED = UUID.randomUUID();

    @Test
    void theDataStepClearsExactlyTheOldDefaultsOfLegacyRows() throws Exception {
        try (Connection conn = newConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase("db/migration/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
        try (Connection conn = newConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE clinical.ultrasound_reports ALTER COLUMN observations_stated DROP NOT NULL");
            st.execute("ALTER TABLE clinical.ultrasound_reports ALTER COLUMN observations_stated DROP DEFAULT");
            st.execute("SET session_replication_role = replica");
            // The old defaults: three TRUE flags, no anomalies, one fetus, the NT template's 12 weeks, nothing typed.
            insert(st, DEFAULTS, "NULL", "TRUE", "TRUE", "TRUE", "FALSE", "1", "12", "NULL", "NULL");
            // Typed values the old code could not have defaulted.
            insert(st, TYPED, "NULL", "FALSE", "FALSE", "FALSE", "TRUE", "2", "14", "NULL", "'Hygroma'");
            // A template age kept while text was typed: not the signature, kept.
            insert(st, TEMPLATE_GA_WITH_TEXT, "NULL", "TRUE", "NULL", "NULL", "NULL", "NULL", "20", "'Morphologie normale'", "NULL");
            // A report saved after D1525: never touched, even with the same values.
            insert(st, STATED, "TRUE", "TRUE", "TRUE", "TRUE", "FALSE", "1", "12", "NULL", "NULL");
            st.execute("SET session_replication_role = origin");
        }

        runV282();
        assertRows();
        runV282(); // idempotent and re-runnable
        assertRows();
    }

    private static void assertRows() throws Exception {
        try (Connection conn = newConnection(); Statement st = conn.createStatement()) {
            assertThat(row(st, DEFAULTS)).as("legacy defaults cleared")
                .containsExactly("false", null, null, null, null, null, null);
            assertThat(row(st, TYPED)).as("typed values kept")
                .containsExactly("false", "f", "f", "f", "t", "2", "14");
            assertThat(row(st, TEMPLATE_GA_WITH_TEXT)).as("TRUE flag cleared, the age with text kept")
                .containsExactly("false", null, null, null, null, null, "20");
            assertThat(row(st, STATED)).as("a stated row is never touched")
                .containsExactly("true", "t", "t", "t", "f", "1", "12");
            try (ResultSet rs = st.executeQuery("SELECT column_default, is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'clinical' AND table_name = 'ultrasound_reports' "
                    + "AND column_name = 'observations_stated'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("true");
                assertThat(rs.getString(2)).isEqualTo("NO");
            }
        }
    }

    private static List<String> row(Statement st, UUID id) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT observations_stated::text, fetal_cardiac_activity::text, "
                + "fetal_movement_observed::text, fetal_tone_normal::text, anomalies_detected::text, "
                + "number_of_fetuses::text, gestational_age_at_scan::text FROM clinical.ultrasound_reports WHERE id = '"
                + id + "'")) {
            assertThat(rs.next()).isTrue();
            String marker = rs.getString(1);
            return Arrays.asList(marker == null ? null : (marker.startsWith("t") ? "true" : "false"),
                bool(rs.getString(2)), bool(rs.getString(3)), bool(rs.getString(4)), bool(rs.getString(5)),
                rs.getString(6), rs.getString(7));
        }
    }

    private static String bool(String value) {
        return value == null ? null : value.substring(0, 1);
    }

    private static void insert(Statement st, UUID id, String stated, String heart, String movement, String tone,
                               String anomalies, String fetuses, String weeks, String summary, String anomaly)
            throws Exception {
        st.execute("INSERT INTO clinical.ultrasound_reports (id, ultrasound_order_id, hospital_id, scan_date, "
            + "finding_category, created_at, updated_at, observations_stated, fetal_cardiac_activity, "
            + "fetal_movement_observed, fetal_tone_normal, anomalies_detected, number_of_fetuses, "
            + "gestational_age_at_scan, findings_summary, anomaly_description) VALUES ('" + id + "', '"
            + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', CURRENT_DATE, 'NORMAL', NOW(), NOW(), "
            + stated + ", " + heart + ", " + movement + ", " + tone + ", " + anomalies + ", " + fetuses + ", "
            + weeks + ", " + summary + ", " + anomaly + ")");
    }

    /** Runs the V282 file as written: comment lines dropped, split on ';' (it has no DO block). */
    private static void runV282() throws Exception {
        String sql;
        try (InputStream in = V282UltrasoundLegacyDataIT.class.getClassLoader()
                .getResourceAsStream("db/migration/V282__ultrasound_observations_stated.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = sql.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
        try (Connection conn = newConnection(); Statement st = conn.createStatement()) {
            for (String statement : body.split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        }
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
