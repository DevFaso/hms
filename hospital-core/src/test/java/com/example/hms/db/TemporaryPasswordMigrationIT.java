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
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V366 and V367 on a real PostgreSQL, through the real changelog: accounts still on a password someone else
 * issued and never signed in get 72 hours from the release; an account that has signed in (on either path), a
 * rotation of the holder's own password and a password the holder chose get no expiry; a second run changes
 * nothing; and the legacy clear-text column on assignments stays empty whatever writes to it.
 */
@Testcontainers
class TemporaryPasswordMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v366")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V366 = "db/migration/V366__temporary_password_expiry.sql";

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
    void empty() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM security.user_role_hospital_assignment");
            stmt.execute("DELETE FROM security.users");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void anIssuedPasswordNeverUsedGetsSeventyTwoHours_signedInAndOwnPasswordsDoNot() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // Registered by a desk, never signed in: the issued password.
            plantUser(stmt, 1, true, "now() - interval '200 days'", null, null);
            // Signed in on the issued password, change still pending: it keeps working as before (the apps).
            plantUser(stmt, 2, true, "now() - interval '3 days'", null, "now() - interval '1 day'");
            // The 90-day rotation of the holder's own password: must change it, but it does not expire.
            plantUser(stmt, 3, true, "now() - interval '120 days'", "now() - interval '25 days'",
                "now() - interval '2 days'");
            // A password the holder chose.
            plantUser(stmt, 4, false, "now() - interval '10 days'", null, "now() - interval '1 day'");
            // A rotation with no password change recorded: measured from the account's creation.
            plantUser(stmt, 5, true, "NULL", "now() - interval '20 days'", null);
            stmt.executeUpdate("UPDATE security.users SET created_at = now() - interval '200 days' WHERE id = '"
                + id(5) + "'");
            // Signed in through the other sign-in path only.
            plantUser(stmt, 6, true, "now() - interval '3 days'", null, null);
            stmt.executeUpdate("UPDATE security.users SET last_oidc_login_at = now() - interval '1 day' WHERE id = '"
                + id(6) + "'");

            runV366(stmt);

            Timestamp first = expiry(stmt, 1);
            assertThat(first).isNotNull();
            assertThat(hoursFromNow(stmt, first)).isBetween(71.0, 72.1);
            assertThat(expiry(stmt, 2)).as("an account that has signed in").isNull();
            assertThat(expiry(stmt, 3)).as("a rotation of the holder's own password").isNull();
            assertThat(expiry(stmt, 4)).as("the holder's own password").isNull();
            assertThat(expiry(stmt, 5)).as("a rotation measured from the account's creation").isNull();
            assertThat(expiry(stmt, 6)).as("an account that has signed in on the other path").isNull();
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plantUser(stmt, 1, true, "now() - interval '5 days'", null, null);
            stmt.execute("SET session_replication_role = replica");
            stmt.executeUpdate("UPDATE security.users SET temp_password_expires_at = TIMESTAMP '2026-01-01 10:00'"
                + " WHERE id = '" + id(1) + "'");
            stmt.execute("SET session_replication_role = origin");

            runV366(stmt);

            assertThat(expiry(stmt, 1)).isEqualTo(Timestamp.valueOf("2026-01-01 10:00:00"));
        }
    }

    @Test
    void theClearTextColumnOnAssignmentsStaysEmptyWhateverWritesIt() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // A row written before V366 (triggers off, as a restore would): V366 empties it.
            plantAssignment(stmt, 1, "Temp@Legacy123");
            runV366(stmt);
            assertThat(plainPassword(stmt, 1)).isNull();

            // A write from an instance still on the previous release, during the rollout: V367 empties it.
            stmt.executeUpdate("UPDATE security.user_role_hospital_assignment SET temp_plain_password = 'Temp@Rollout1'"
                + " WHERE id = '" + id(1) + "'");
            assertThat(plainPassword(stmt, 1)).isNull();
        }
    }

    // ------------------------------------------------------------------

    private static String id(int n) {
        return String.format("bbbbbbbb-0000-0000-0000-%012d", n);
    }

    private static void plantUser(Statement stmt, int n, boolean forced, String changedAt, String rotationForcedAt,
                                  String lastLoginAt) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO security.users (id, username, password_hash, email, phone_number, "
                + "is_active, is_deleted, force_password_change, password_changed_at, password_rotation_forced_at, "
                + "last_login_at, created_at, updated_at) VALUES ('"
                + id(n) + "', 'u" + n + "', '$2a$10$hash', 'u" + n + "@example.test', '+2267000000" + n + "', "
                + "TRUE, FALSE, " + forced + ", " + changedAt + ", " + rotationForcedAt + ", " + lastLoginAt
                + ", now(), now())");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static void plantAssignment(Statement stmt, int n, String plain) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, "
                + "is_active, assigned_at, created_at, updated_at, temp_plain_password) VALUES ('" + id(n) + "', '"
                + id(n) + "', '" + id(n) + "', FALSE, now(), now(), now(), '" + plain + "')");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static Timestamp expiry(Statement stmt, int n) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT temp_password_expires_at FROM security.users WHERE id = '" + id(n) + "'")) {
            rs.next();
            return rs.getTimestamp(1);
        }
    }

    private static double hoursFromNow(Statement stmt, Timestamp at) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT EXTRACT(EPOCH FROM (TIMESTAMP '" + at
                + "' - LOCALTIMESTAMP)) / 3600.0")) {
            rs.next();
            return rs.getDouble(1);
        }
    }

    private static String plainPassword(Statement stmt, int n) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT temp_plain_password FROM security.user_role_hospital_assignment WHERE id = '" + id(n) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void runV366(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = TemporaryPasswordMigrationIT.class.getClassLoader().getResourceAsStream(V366)) {
            assertThat(in).as(V366).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        stmt.execute(sql);
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
