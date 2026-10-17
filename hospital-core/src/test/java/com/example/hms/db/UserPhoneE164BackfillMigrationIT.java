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

/**
 * V185 on a real PostgreSQL: legacy desk spellings of a Burkina Faso number
 * become E.164, anything unparseable is left alone, a row whose E.164 form is
 * already taken (or claimed by another row in the same run) is skipped and
 * counted, and a second run changes nothing.
 */
@Testcontainers
class UserPhoneE164BackfillMigrationIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_v185")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String V185 = "db/migration/V185__users_phone_e164_backfill.sql";

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
    void noUsers() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM security.users");
            stmt.execute("SET session_replication_role = origin");
        }
    }

    @Test
    void legacySpellingsBecomeE164AndTheRestIsLeftAlone() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, 1, "70 70 70 70");
            plant(stmt, 2, "71-71-71-71");
            plant(stmt, 3, "22672727272");
            plant(stmt, 4, "0022673737373");
            plant(stmt, 5, "+226 74.74.74.74");
            plant(stmt, 6, "+22675757575");
            plant(stmt, 7, "+33 6 12 34 56 78");
            plant(stmt, 8, "7676 poste 12");
            plant(stmt, 9, "1234567");
            plant(stmt, 10, "070707070");

            List<String> notices = runV185(stmt);

            assertThat(phone(stmt, 1)).isEqualTo("+22670707070");
            assertThat(phone(stmt, 2)).isEqualTo("+22671717171");
            assertThat(phone(stmt, 3)).isEqualTo("+22672727272");
            assertThat(phone(stmt, 4)).isEqualTo("+22673737373");
            assertThat(phone(stmt, 5)).isEqualTo("+22674747474");
            assertThat(phone(stmt, 6)).isEqualTo("+22675757575");
            assertThat(phone(stmt, 7)).isEqualTo("+33612345678");
            assertThat(phone(stmt, 8)).isEqualTo("7676 poste 12");
            assertThat(phone(stmt, 9)).isEqualTo("1234567");
            assertThat(phone(stmt, 10)).isEqualTo("070707070");
            assertThat(notices).anyMatch(n -> n.contains("6 phone number(s) rewritten") && n.contains("0 left"));
        }
    }

    @Test
    void neverCollapsesTwoRowsOntoOneNumber() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, 1, "+22670707070");
            plant(stmt, 2, "70 70 70 70");
            plant(stmt, 3, "71717171");
            plant(stmt, 4, "226 71 71 71 71");
            plant(stmt, 5, "72 72 72 72");

            List<String> notices = runV185(stmt);

            assertThat(phone(stmt, 1)).isEqualTo("+22670707070");
            assertThat(phone(stmt, 2)).isEqualTo("70 70 70 70");
            assertThat(phone(stmt, 3)).isEqualTo("71717171");
            assertThat(phone(stmt, 4)).isEqualTo("226 71 71 71 71");
            assertThat(phone(stmt, 5)).isEqualTo("+22672727272");
            assertThat(notices).anyMatch(n -> n.contains("1 phone number(s) rewritten") && n.contains("3 left"));
            assertThat(notices).noneMatch(n -> n.contains("7070") || n.contains("7171"));
        }
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            plant(stmt, 1, "70 70 70 70");
            runV185(stmt);

            List<String> notices = runV185(stmt);

            assertThat(phone(stmt, 1)).isEqualTo("+22670707070");
            assertThat(notices).anyMatch(n -> n.contains("0 phone number(s) rewritten"));
        }
    }

    // ------------------------------------------------------------------

    private static String id(int n) {
        return String.format("aaaaaaaa-0000-0000-0000-%012d", n);
    }

    private static void plant(Statement stmt, int n, String phone) throws SQLException {
        stmt.execute("SET session_replication_role = replica");
        try {
            stmt.executeUpdate("INSERT INTO security.users (id, username, password_hash, email, phone_number, "
                + "is_active, is_deleted, force_password_change, created_at, updated_at) VALUES ('"
                + id(n) + "', 'u" + n + "', 'x', 'u" + n + "@example.test', '" + phone + "', "
                + "TRUE, FALSE, FALSE, now(), now())");
        } finally {
            stmt.execute("SET session_replication_role = origin");
        }
    }

    private static String phone(Statement stmt, int n) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT phone_number FROM security.users WHERE id = '" + id(n) + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> runV185(Statement stmt) throws Exception {
        String sql;
        try (InputStream in = UserPhoneE164BackfillMigrationIT.class.getClassLoader().getResourceAsStream(V185)) {
            assertThat(in).as(V185).isNotNull();
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
