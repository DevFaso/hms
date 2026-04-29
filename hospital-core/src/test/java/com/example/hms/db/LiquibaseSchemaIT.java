package com.example.hms.db;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs the full Liquibase changelog against a real PostgreSQL instance.
 *
 * <p>Why this exists: the standard {@code @SpringBootTest} suite uses H2 with
 * {@code spring.liquibase.enabled=false} (see TestPostgresConfig), so PG-specific
 * SQL — PL/pgSQL DO blocks, dollar quoting, partial indexes, the
 * {@code splitStatements} XML attribute — never executes during CI. V63 shipped
 * with two latent bugs (chopped DO block on internal {@code ;}; missing
 * {@code seed RECORD} declaration) that only surfaced on the dev deploy. This
 * test closes that gap by applying every changeSet against a freshly-spun
 * {@code postgres:16-alpine} container.
 */
@Testcontainers
class LiquibaseSchemaIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("hms_test")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String CHANGELOG = "db/migration/changelog.xml";

    @BeforeAll
    static void verifyContainerRunning() {
        assertThat(POSTGRES.isRunning()).isTrue();
    }

    @Test
    void migrationsApplyCleanly() {
        assertThatCode(() -> runLiquibaseUpdate()).doesNotThrowAnyException();
    }

    @Test
    void migrationsAreIdempotent() throws Exception {
        runLiquibaseUpdate();
        // Second invocation must be a no-op: every changeSet is recorded in
        // databasechangelog and skipped. If any sqlFile is missing
        // runOnChange="false" or the checksum drifts, this fails.
        assertThatCode(() -> runLiquibaseUpdate()).doesNotThrowAnyException();
    }

    @Test
    void keySchemaObjectsExist() throws Exception {
        runLiquibaseUpdate();

        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            assertSchemaExists(stmt, "clinical");
            assertSchemaExists(stmt, "lab");

            // V61: SYSTEM-actor support on lab_results
            assertColumnExists(stmt, "lab", "lab_results", "actor_type");
            assertColumnExists(stmt, "lab", "lab_results", "actor_label");

            // V62: MLLP allowed-senders allowlist
            assertTableExists(stmt, "platform", "mllp_allowed_senders");

            // V63: CDS rule-engine reference data + seeded interactions
            assertTableExists(stmt, "clinical", "drug_interactions");
            assertColumnExists(stmt, "clinical", "medication_catalog_items",
                "pediatric_max_dose_mg_per_kg");
            assertSeedRowsPresent(stmt, "clinical", "drug_interactions", 12);
        }
    }

    private static void runLiquibaseUpdate() throws Exception {
        try (Connection conn = newConnection()) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    private static Connection newConnection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }

    private static void assertSchemaExists(Statement stmt, String schema) throws Exception {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT 1 FROM information_schema.schemata WHERE schema_name = '" + schema + "'")) {
            assertThat(rs.next()).as("schema %s exists", schema).isTrue();
        }
    }

    private static void assertTableExists(Statement stmt, String schema, String table) throws Exception {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'")) {
            assertThat(rs.next()).as("table %s.%s exists", schema, table).isTrue();
        }
    }

    private static void assertColumnExists(Statement stmt, String schema, String table, String column) throws Exception {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = '" + schema + "' "
                + "AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'")) {
            assertThat(rs.next()).as("column %s.%s.%s exists", schema, table, column).isTrue();
        }
    }

    private static void assertSeedRowsPresent(Statement stmt, String schema, String table, int minimumRows) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + schema + "." + table)) {
            assertThat(rs.next()).isTrue();
            int count = rs.getInt(1);
            assertThat(count)
                .as("%s.%s should be seeded with at least %d rows", schema, table, minimumRows)
                .isGreaterThanOrEqualTo(minimumRows);
        }
    }
}
