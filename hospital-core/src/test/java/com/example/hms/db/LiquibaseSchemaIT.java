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

            // V68: DHIS2 ADX export integration tables
            assertSchemaExists(stmt, "integration");
            assertTableExists(stmt, "integration", "dhis2_facility_config");
            assertTableExists(stmt, "integration", "dhis2_dataelement_mapping");
            assertTableExists(stmt, "integration", "dhis2_export_run");
            assertTableExists(stmt, "integration", "dhis2_export_outbox");
            assertColumnExists(stmt, "hospital", "hospitals", "dhis2_org_unit_uid");

            // V71: referral state-machine audit trail
            final String publicSchema = "public";
            final String referralEventsTable = "referral_events";
            assertTableExists(stmt, publicSchema, referralEventsTable);
            assertColumnExists(stmt, publicSchema, referralEventsTable, "actor_label");
            assertColumnExists(stmt, publicSchema, referralEventsTable, "from_status");
            assertColumnExists(stmt, publicSchema, referralEventsTable, "to_status");

            // V72: optimistic-lock @Version on general_referrals
            assertColumnExists(stmt, publicSchema, "general_referrals", "version");
        }
    }

    /**
     * V68 regression: the outbox UNIQUE INDEX uses
     * {@code COALESCE(category_option_combo_uid, '__DEFAULT_COC__')} so two
     * "default-COC" rows for the same (run, period, orgUnit, dataElement)
     * collide as a duplicate even when both have NULL category_option_combo_uid.
     * A plain UNIQUE constraint would have allowed both rows because Postgres
     * treats NULLs as distinct.
     */
    @Test
    void v68OutboxNullCocDuplicateIsRejected() throws Exception {
        runLiquibaseUpdate();

        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO hospital.hospitals "
                + "(id, name, code, active, created_at, updated_at) "
                + "VALUES ('a0000000-0000-0000-0000-000000000001', "
                + "'IT-Hospital', 'IT-NULLCOC', TRUE, NOW(), NOW())");
            stmt.executeUpdate("INSERT INTO integration.dhis2_export_run "
                + "(id, hospital_id, dataset_uid, period_iso, started_at, status, "
                + " value_count, skipped_count, request_id, created_at, updated_at) "
                + "VALUES ('b0000000-0000-0000-0000-000000000001', "
                + "'a0000000-0000-0000-0000-000000000001', 'DS00000DEFK', '202604', "
                + "NOW(), 'PENDING', 0, 0, "
                + "'c0000000-0000-0000-0000-000000000001', NOW(), NOW())");
            stmt.executeUpdate("INSERT INTO integration.dhis2_export_outbox "
                + "(id, run_id, period_iso, org_unit_uid, dataelement_uid, "
                + " data_value, status, attempts, created_at, updated_at) "
                + "VALUES ('d0000000-0000-0000-0000-000000000001', "
                + "'b0000000-0000-0000-0000-000000000001', '202604', "
                + "'OU000000001', 'DE000000001', '42', 'PENDING', 0, NOW(), NOW())");

            String dupSql = "INSERT INTO integration.dhis2_export_outbox "
                + "(id, run_id, period_iso, org_unit_uid, dataelement_uid, "
                + " data_value, status, attempts, created_at, updated_at) "
                + "VALUES ('d0000000-0000-0000-0000-000000000002', "
                + "'b0000000-0000-0000-0000-000000000001', '202604', "
                + "'OU000000001', 'DE000000001', '99', 'PENDING', 0, NOW(), NOW())";
            assertThatCode(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate(dupSql);
                }
            }).isInstanceOf(java.sql.SQLException.class);
        }
    }

    /**
     * V95 regression: medication catalog becomes a platform / LNME catalog.
     * Two invariants worth pinning end-to-end (Hibernate's H2 dialect can't
     * model PostgreSQL partial unique indexes, so this Testcontainers run
     * is the only thing that proves the migration actually does what the
     * SQL says it does):
     *
     * <ol>
     *   <li>{@code hospital_id} is nullable on {@code clinical
     *       .medication_catalog_items} — a global row inserts cleanly with
     *       {@code hospital_id = NULL}.</li>
     *   <li>The two partial unique indexes ({@code uq_mci_code_global} and
     *       {@code uq_mci_code_hospital}) reject duplicates inside their
     *       own scope without shadowing the other scope. Postgres treats
     *       {@code NULL} as distinct in a multi-column {@code UNIQUE}, so
     *       without the partial-index split, two globals with the same code
     *       would silently coexist — which is the whole bug this migration
     *       is supposed to prevent.</li>
     * </ol>
     */
    @Test
    void v95MedicationCatalogPlatformScopeIndexesHold() throws Exception {
        runLiquibaseUpdate();

        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // hospital_id must be nullable on medication_catalog_items.
            try (ResultSet rs = stmt.executeQuery(
                "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'clinical' "
                    + "AND table_name = 'medication_catalog_items' "
                    + "AND column_name = 'hospital_id'")) {
                assertThat(rs.next()).as("hospital_id column exists").isTrue();
                assertThat(rs.getString("is_nullable"))
                    .as("V95 must make medication_catalog_items.hospital_id nullable")
                    .isEqualTo("YES");
            }

            // Insert a tenant for the hospital-scoped half of the test.
            stmt.executeUpdate("INSERT INTO hospital.hospitals "
                + "(id, name, code, active, created_at, updated_at) "
                + "VALUES ('e0000000-0000-0000-0000-000000000001', "
                + "'IT-MedCatalog', 'IT-MEDCAT', TRUE, NOW(), NOW())");

            // (1) A global row inserts cleanly with hospital_id = NULL.
            stmt.executeUpdate("INSERT INTO clinical.medication_catalog_items "
                + "(id, code, name_fr, generic_name, essential_list, controlled, "
                + " active, hospital_id, created_at, updated_at) "
                + "VALUES ('e0000000-0000-0000-0000-000000000002', "
                + "'AMOX500-GLOBAL', 'Amoxicilline', 'Amoxicillin', "
                + "FALSE, FALSE, TRUE, NULL, NOW(), NOW())");

            // (2) A second global row with the SAME code must be rejected by
            //     uq_mci_code_global (partial unique on code WHERE hospital_id IS NULL).
            String dupGlobalSql = "INSERT INTO clinical.medication_catalog_items "
                + "(id, code, name_fr, generic_name, essential_list, controlled, "
                + " active, hospital_id, created_at, updated_at) "
                + "VALUES ('e0000000-0000-0000-0000-000000000003', "
                + "'AMOX500-GLOBAL', 'Amoxicilline dup', 'Amoxicillin', "
                + "FALSE, FALSE, TRUE, NULL, NOW(), NOW())";
            assertThatCode(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate(dupGlobalSql);
                }
            }).as("duplicate global code must be rejected by uq_mci_code_global")
                .isInstanceOf(java.sql.SQLException.class);

            // (3) A hospital-scoped row reusing the same code in a DIFFERENT
            //     scope must SUCCEED — uq_mci_code_global only covers
            //     hospital_id IS NULL, and uq_mci_code_hospital covers
            //     (hospital_id, code) so a different (or here, any) hospital_id
            //     leaves the (NULL, code) global row untouched.
            stmt.executeUpdate("INSERT INTO clinical.medication_catalog_items "
                + "(id, code, name_fr, generic_name, essential_list, controlled, "
                + " active, hospital_id, created_at, updated_at) "
                + "VALUES ('e0000000-0000-0000-0000-000000000004', "
                + "'AMOX500-GLOBAL', 'Amoxicilline Hospital A', 'Amoxicillin', "
                + "FALSE, FALSE, TRUE, "
                + "'e0000000-0000-0000-0000-000000000001', NOW(), NOW())");

            // (4) A duplicate within the same hospital is rejected by
            //     uq_mci_code_hospital.
            String dupHospitalSql = "INSERT INTO clinical.medication_catalog_items "
                + "(id, code, name_fr, generic_name, essential_list, controlled, "
                + " active, hospital_id, created_at, updated_at) "
                + "VALUES ('e0000000-0000-0000-0000-000000000005', "
                + "'AMOX500-GLOBAL', 'Amoxicilline Hospital A dup', 'Amoxicillin', "
                + "FALSE, FALSE, TRUE, "
                + "'e0000000-0000-0000-0000-000000000001', NOW(), NOW())";
            assertThatCode(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate(dupHospitalSql);
                }
            }).as("duplicate hospital-scoped code must be rejected by uq_mci_code_hospital")
                .isInstanceOf(java.sql.SQLException.class);
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
