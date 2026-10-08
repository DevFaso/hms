package com.example.hms.db;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V180 on a real PostgreSQL (provider plan AC-2, AC-3). H2 builds tables from
 * the entities and never sees the partial unique indexes or the CHECKs, so
 * these are the only tests that hold them:
 *
 * <ul>
 *   <li>every existing hospital row is a HOSPITAL, and only three types exist;</li>
 *   <li>one current (SUBMITTED or VERIFIED) verification per facility;</li>
 *   <li>among VERIFIED rows: one (authority, licence) pair, one RCCM, one IFU;</li>
 *   <li>a VERIFIED row carries both consistency confirmations;</li>
 *   <li>ROLE_PROVIDER_ADMIN is seeded.</li>
 * </ul>
 */
@Testcontainers
class ProviderVerificationSchemaIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v180")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection conn = connection()) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase("db/migration/changelog.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    @Test
    @DisplayName("an existing hospital row is a HOSPITAL; an unknown type is refused")
    void facilityTypeDefaultAndCheck() throws Exception {
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            UUID id = hospital(stmt, null);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT facility_type FROM hospital.hospitals WHERE id = '" + id + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("HOSPITAL");
            }
            assertThatThrownBy(() -> hospital(stmt, "CLINIC"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_hospital_facility_type");
        }
    }

    @Test
    @DisplayName("VERIFIED needs both consistency confirmations")
    void verifiedNeedsConsistency() throws Exception {
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            UUID facility = hospital(stmt, "PHARMACY");
            assertThatThrownBy(() -> verification(stmt, facility, "VERIFIED", unique(), true, false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_provider_verified_consistent");
            assertThatThrownBy(() -> verification(stmt, facility, "VERIFIED", unique(), false, true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_provider_verified_consistent");
            verification(stmt, facility, "VERIFIED", unique(), true, true);
        }
    }

    @Test
    @DisplayName("one current verification per facility; a rejected one does not count")
    void oneCurrentPerFacility() throws Exception {
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            UUID facility = hospital(stmt, "LABORATORY");
            verification(stmt, facility, "REJECTED", unique(), false, false);
            verification(stmt, facility, "SUBMITTED", unique(), false, false);
            assertThatThrownBy(() -> verification(stmt, facility, "SUBMITTED", unique(), false, false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_provider_verification_current");
        }
    }

    @Test
    @DisplayName("among VERIFIED rows: one licence pair, one RCCM, one IFU; SUBMITTED duplicates are allowed")
    void verifiedBusinessIsUnique() throws Exception {
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            String shared = unique();
            verification(stmt, hospital(stmt, "PHARMACY"), "VERIFIED", shared, true, true);
            // The same numbers may sit in SUBMITTED evidence elsewhere: AC-3 is checked at VERIFY.
            verification(stmt, hospital(stmt, "PHARMACY"), "SUBMITTED", shared, false, false);

            UUID other = hospital(stmt, "PHARMACY");
            assertThatThrownBy(() -> stmt.executeUpdate(insert(other, "VERIFIED", unique(), true, true)
                .replaceFirst("LIC-[A-Z0-9]+", "LIC-" + shared)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_provider_licence_verified");
            assertThatThrownBy(() -> stmt.executeUpdate(insert(other, "VERIFIED", unique(), true, true)
                .replaceFirst("RCCM-[A-Z0-9]+", "RCCM-" + shared)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_provider_rccm_verified");
            assertThatThrownBy(() -> stmt.executeUpdate(insert(other, "VERIFIED", unique(), true, true)
                .replaceFirst("IFU-[A-Z0-9]+", "IFU-" + shared)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_provider_ifu_verified");
        }
    }

    @Test
    @DisplayName("ROLE_PROVIDER_ADMIN is seeded by V180")
    void roleSeeded() throws Exception {
        try (Connection conn = connection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM \"security\".roles WHERE code = 'ROLE_PROVIDER_ADMIN'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static UUID hospital(Statement stmt, String facilityType) throws SQLException {
        UUID id = UUID.randomUUID();
        String code = "IT-" + unique();
        if (facilityType == null) {
            stmt.executeUpdate("INSERT INTO hospital.hospitals (id, name, code, active, created_at, updated_at) "
                + "VALUES ('" + id + "', 'IT " + code + "', '" + code + "', TRUE, NOW(), NOW())");
        } else {
            stmt.executeUpdate("INSERT INTO hospital.hospitals "
                + "(id, name, code, active, facility_type, created_at, updated_at) "
                + "VALUES ('" + id + "', 'IT " + code + "', '" + code + "', FALSE, '" + facilityType
                + "', NOW(), NOW())");
        }
        return id;
    }

    private static void verification(Statement stmt, UUID facility, String status, String numbers,
                                     boolean ifuMatches, boolean cnssMatches) throws SQLException {
        stmt.executeUpdate(insert(facility, status, numbers, ifuMatches, cnssMatches));
    }

    /** Every business number is derived from {@code numbers}, so two rows share them exactly when asked to. */
    private static String insert(UUID facility, String status, String numbers, boolean ifuMatches,
                                 boolean cnssMatches) {
        return "INSERT INTO hospital.provider_verifications (id, hospital_id, status, legal_name, "
            + "legal_structure, rccm_number, ifu_number, cnss_number, address_city, address_region, "
            + "company_phone, manager_name, manager_title, business_started_on, ifu_matches_rccm, "
            + "cnss_matches_rccm, licence_number, licence_authority, responsible_professional_name, "
            + "responsible_professional_registration, created_at, updated_at) VALUES ('"
            + UUID.randomUUID() + "', '" + facility + "', '" + status + "', 'Legal', 'SARL', 'RCCM-" + numbers
            + "', 'IFU-" + numbers + "', 'CNSS-" + numbers + "', 'Ouagadougou', 'Centre', '+22670000000', "
            + "'Manager', 'Gerant', DATE '2020-01-01', " + ifuMatches + ", " + cnssMatches + ", 'LIC-" + numbers
            + "', 'Authority', 'Responsible', 'ORD-" + numbers + "', NOW(), NOW())";
    }
}
