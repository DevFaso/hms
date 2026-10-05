package com.example.hms.db;

import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.service.support.EducationProgressRows;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V176 on a real PostgreSQL, on the awkward shapes: a group of three rows
 * where the kept row is not the one holding the completion, rating or
 * provider note; a group whose two rows tie on both timestamps, so the id
 * decides, in {@code java.util.UUID}'s signed order, which is NOT
 * PostgreSQL's uuid order; a lone row that must not move; and a table some
 * other table references, which must stop the migration.
 *
 * <p>The full changelog is applied first (V176 included), then each test
 * removes the key, plants its fixture rows and runs the V176 file again, as
 * a deploy would on a database that still had duplicates. Foreign keys are
 * bypassed for the fixtures only (session_replication_role = replica), since
 * V169's patient key would otherwise need a whole patient graph.
 */
@Testcontainers
class EducationProgressDedupeMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v176")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V176 = "db/migration/V176__patient_education_progress_one_row_per_resource.sql";
    private static final String KEY = "uk_patient_education_progress_patient_resource";

    private static final String HOSPITAL = "33333333-0000-0000-0000-000000000001";
    private static final String PATIENT_1 = "11111111-0000-0000-0000-000000000001";
    private static final String PATIENT_2 = "11111111-0000-0000-0000-000000000002";
    private static final String RESOURCE_1 = "22222222-0000-0000-0000-000000000001";
    private static final String RESOURCE_2 = "22222222-0000-0000-0000-000000000002";
    private static final String PROVIDER = "44444444-0000-0000-0000-000000000001";

    // Group A (patient 1, resource 1): three rows.
    private static final String A1 = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String A2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String A3 = "aaaaaaaa-0000-0000-0000-000000000003";
    // Group B (patient 1, resource 2): a full tie on both timestamps. Java
    // orders B1 first (its high bit makes the most significant long
    // negative); PostgreSQL's uuid order would pick B2.
    private static final String B1 = "80000000-0000-0000-0000-000000000001";
    private static final String B2 = "00000000-0000-0000-0000-000000000002";
    // Patient 2, resource 1: alone, untouched.
    private static final String C1 = "cccccccc-0000-0000-0000-000000000001";

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
            stmt.execute("DROP TABLE IF EXISTS public.v176_child");
            stmt.execute("ALTER TABLE clinical.patient_education_progress DROP CONSTRAINT IF EXISTS " + KEY);
            stmt.execute("DELETE FROM clinical.patient_education_progress");
            stmt.execute("DELETE FROM clinical.education_resources WHERE id IN ('"
                + RESOURCE_1 + "', '" + RESOURCE_2 + "')");
        }
    }

    @Test
    void duplicatesAreFoldedIntoTheRowTheApplicationShowsAndNothingIsLost() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantFixtures(stmt);

            List<String> notices = runV176(stmt);

            assertThat(notices).anyMatch(n -> n.contains(
                "2 duplicate group(s) merged, 3 row(s) folded into the kept row, 2 resource rating aggregate(s) recomputed"));
            assertThat(notices).anyMatch(n -> n.contains(KEY + " added"));
            assertThat(ids(stmt)).containsExactlyInAnyOrder(A1, B1, C1);

            // Group A: kept A1 (most recently accessed; A3 was never accessed
            // and sorts last). Everything A2 and A3 held is on it now.
            try (ResultSet a = row(stmt, A1)) {
                assertThat(a.getInt("progress_percentage")).isEqualTo(100);
                assertThat(ts(a, "started_at")).isEqualTo(at("2025-12-01T09:00"));
                assertThat(ts(a, "completed_at")).isEqualTo(at("2025-12-05T09:00"));
                assertThat(ts(a, "last_accessed_at")).isEqualTo(at("2026-01-03T10:00"));
                assertThat(ts(a, "created_at")).isEqualTo(at("2025-12-01T09:00"));
                assertThat(a.getLong("time_spent_seconds")).isEqualTo(300L);
                assertThat(a.getInt("access_count")).isEqualTo(5);
                assertThat(a.getInt("rating")).isEqualTo(4);
                assertThat(a.getString("feedback")).isEqualTo("clear");
                assertThat(a.getBoolean("confirmed_understanding")).isTrue();
                assertThat(a.getBoolean("needs_clarification")).isTrue();
                assertThat(a.getString("clarification_request")).isEqualTo("what is a dose");
                assertThat(a.getString("provider_id")).isEqualTo(PROVIDER);
                assertThat(a.getString("provider_notes")).isEqualTo("went over it");
                assertThat(ts(a, "discussed_with_provider_at")).isEqualTo(at("2025-12-06T09:00"));
                assertThat(a.getString("comprehension_status")).isEqualTo("NEEDS_CLARIFICATION");
            }

            // Group B: kept B1 by the id tie-break. Its own rating and feedback
            // win; its blank clarification request does not hide B2's; B2's
            // completion carries over and upgrades the status.
            try (ResultSet b = row(stmt, B1)) {
                assertThat(b.getInt("rating")).isEqualTo(5);
                assertThat(b.getString("feedback")).isEqualTo("mine");
                assertThat(b.getString("clarification_request")).isEqualTo("help");
                assertThat(ts(b, "completed_at")).isEqualTo(at("2026-02-20T09:00"));
                assertThat(b.getInt("progress_percentage")).isEqualTo(100);
                assertThat(b.getString("comprehension_status")).isEqualTo("COMPLETED");
            }

            // The lone row is exactly as it was.
            try (ResultSet c = row(stmt, C1)) {
                assertThat(c.getInt("rating")).isEqualTo(2);
                assertThat(ts(c, "updated_at")).isEqualTo(at("2026-01-10T09:00"));
                assertThat(c.getString("comprehension_status")).isEqualTo("IN_PROGRESS");
            }

            // Rating aggregates as the application computes them: resource 1
            // has A1 (4) and C1 (2); resource 2 has B1 (5).
            assertRatingAggregate(stmt, RESOURCE_1, 3.0, 2);
            assertRatingAggregate(stmt, RESOURCE_2, 5.0, 1);

            // A second run finds nothing to do and changes nothing.
            List<String> again = runV176(stmt);
            assertThat(again).anyMatch(n -> n.contains("0 duplicate group(s) merged, 0 row(s) folded"));
            assertThat(again).anyMatch(n -> n.contains(KEY + " already present"));
            assertThat(ids(stmt)).containsExactlyInAnyOrder(A1, B1, C1);

            // And the key now refuses a new duplicate.
            assertThatThrownBy(() -> insert(stmt, "dddddddd-0000-0000-0000-000000000001", PATIENT_1, RESOURCE_1,
                "NULL", "'2026-03-01 09:00'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(KEY);
        }
    }

    @Test
    void theKeptRowIsTheOneEducationProgressRowsPicks() {
        // The SQL ordering stands in for EducationProgressRows.CANONICAL_FIRST;
        // the same fixture values, through the Java rule, pick the same rows.
        assertThat(EducationProgressRows.canonical(List.of(
                entity(A1, at("2026-01-03T10:00"), at("2026-01-01T09:00")),
                entity(A2, at("2026-01-02T10:00"), at("2025-12-01T09:00")),
                entity(A3, null, at("2026-02-01T09:00")))))
            .get().extracting(PatientEducationProgress::getId).isEqualTo(UUID.fromString(A1));
        assertThat(EducationProgressRows.canonical(List.of(
                entity(B2, at("2026-03-01T09:00"), at("2026-02-15T09:00")),
                entity(B1, at("2026-03-01T09:00"), at("2026-02-15T09:00")))))
            .get().extracting(PatientEducationProgress::getId).isEqualTo(UUID.fromString(B1));
    }

    @Test
    void aTableReferencingTheProgressRowsStopsTheMigrationBeforeAnythingIsDeleted() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantFixtures(stmt);
            stmt.execute("CREATE TABLE public.v176_child (id UUID PRIMARY KEY, "
                + "progress_id UUID REFERENCES clinical.patient_education_progress (id))");

            assertThatThrownBy(() -> runV176(stmt))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("foreign key(s) reference clinical.patient_education_progress");
            assertThat(ids(stmt)).hasSize(6);

            stmt.execute("DROP TABLE public.v176_child");
            runV176(stmt);
            assertThat(ids(stmt)).containsExactlyInAnyOrder(A1, B1, C1);
        }
    }

    // ------------------------------------------------------------------

    private static void plantFixtures(Statement stmt) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        resource(stmt, RESOURCE_1);
        resource(stmt, RESOURCE_2);

        insert(stmt, A1, PATIENT_1, RESOURCE_1, "'2026-01-03 10:00'", "'2026-01-01 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET comprehension_status = 'IN_PROGRESS', "
            + "progress_percentage = 40, started_at = '2026-01-01 09:00', access_count = 2, "
            + "time_spent_seconds = 100 WHERE id = '" + A1 + "'");
        insert(stmt, A2, PATIENT_1, RESOURCE_1, "'2026-01-02 10:00'", "'2025-12-01 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET comprehension_status = 'COMPLETED', "
            + "progress_percentage = 100, started_at = '2025-12-01 09:00', completed_at = '2025-12-05 09:00', "
            + "rating = 4, feedback = 'clear', confirmed_understanding = TRUE, access_count = 3, "
            + "time_spent_seconds = 200, provider_id = '" + PROVIDER + "', provider_notes = 'went over it', "
            + "discussed_with_provider_at = '2025-12-06 09:00' WHERE id = '" + A2 + "'");
        insert(stmt, A3, PATIENT_1, RESOURCE_1, "NULL", "'2026-02-01 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET needs_clarification = TRUE, "
            + "clarification_request = 'what is a dose' WHERE id = '" + A3 + "'");

        insert(stmt, B1, PATIENT_1, RESOURCE_2, "'2026-03-01 09:00'", "'2026-02-15 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET comprehension_status = 'IN_PROGRESS', "
            + "progress_percentage = 20, rating = 5, feedback = 'mine', clarification_request = '   ' "
            + "WHERE id = '" + B1 + "'");
        insert(stmt, B2, PATIENT_1, RESOURCE_2, "'2026-03-01 09:00'", "'2026-02-15 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET comprehension_status = 'COMPLETED', "
            + "progress_percentage = 100, completed_at = '2026-02-20 09:00', rating = 2, feedback = 'other', "
            + "clarification_request = 'help' WHERE id = '" + B2 + "'");

        insert(stmt, C1, PATIENT_2, RESOURCE_1, "'2026-01-10 09:00'", "'2026-01-05 09:00'");
        stmt.executeUpdate("UPDATE clinical.patient_education_progress SET comprehension_status = 'IN_PROGRESS', "
            + "progress_percentage = 50, rating = 2, updated_at = '2026-01-10 09:00' WHERE id = '" + C1 + "'");
        stmt.execute("SET session_replication_role = origin");
    }

    private static void insert(Statement stmt, String id, String patient, String resource,
                               String lastAccessedSql, String createdSql) throws SQLException {
        stmt.executeUpdate("INSERT INTO clinical.patient_education_progress (id, patient_id, resource_id, "
            + "hospital_id, comprehension_status, progress_percentage, time_spent_seconds, access_count, "
            + "needs_clarification, confirmed_understanding, last_accessed_at, created_at, updated_at) VALUES ('"
            + id + "', '" + patient + "', '" + resource + "', '" + HOSPITAL + "', 'NOT_STARTED', 0, 0, 0, "
            + "FALSE, FALSE, " + lastAccessedSql + ", " + createdSql + ", " + createdSql + ")");
    }

    private static void resource(Statement stmt, String id) throws SQLException {
        stmt.executeUpdate("INSERT INTO clinical.education_resources (id, title, category, resource_type, "
            + "primary_language, created_by, is_active, is_culturally_sensitive, is_evidence_based, "
            + "is_high_risk_relevant, is_warning_sign_content, average_rating, rating_count, completion_count, "
            + "view_count, created_at, updated_at) VALUES ('" + id + "', 'Fixture', 'NUTRITION', 'ARTICLE', 'en', "
            + "'it', TRUE, FALSE, FALSE, FALSE, FALSE, 1.0, 9, 7, 0, NOW(), NOW())");
    }

    private static List<String> runV176(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = EducationProgressDedupeMigrationIT.class.getClassLoader().getResourceAsStream(V176)) {
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

    private static List<String> ids(Statement stmt) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery("SELECT id FROM clinical.patient_education_progress")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static ResultSet row(Statement stmt, String id) throws SQLException {
        ResultSet rs = stmt.executeQuery("SELECT * FROM clinical.patient_education_progress WHERE id = '" + id + "'");
        assertThat(rs.next()).as("row %s survives", id).isTrue();
        return rs;
    }

    private static void assertRatingAggregate(Statement stmt, String resourceId, double average, long count)
            throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT average_rating, rating_count, completion_count "
                + "FROM clinical.education_resources WHERE id = '" + resourceId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble("average_rating")).isEqualTo(average);
            assertThat(rs.getLong("rating_count")).isEqualTo(count);
            assertThat(rs.getLong("completion_count")).as("completion events are not recounted").isEqualTo(7L);
        }
    }

    private static LocalDateTime ts(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDateTime at(String isoLocal) {
        return LocalDateTime.parse(isoLocal);
    }

    private static PatientEducationProgress entity(String id, LocalDateTime lastAccessedAt, LocalDateTime createdAt) {
        PatientEducationProgress p = new PatientEducationProgress();
        p.setId(UUID.fromString(id));
        p.setLastAccessedAt(lastAccessedAt);
        p.setCreatedAt(createdAt);
        return p;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
