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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D669/D676 on a real PostgreSQL: the operator over-return report lists
 * only a fill whose returns exceed what it delivered, with the excess
 * (returned - dispensed), never a normally cancelled fill whose own automatic
 * RETURN gave back exactly its quantity; the paired repair takes off only
 * that excess and is idempotent. Foreign keys are bypassed for the fixtures
 * (session_replication_role = replica); NOT NULL columns the test does not
 * care about get a typed placeholder.
 */
@Testcontainers
class OverReturnOperatorSqlIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_d669")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static final String LOT = "11111111-0000-0000-0000-000000000001";
    private static final String ITEM = "22222222-0000-0000-0000-000000000001";
    private static final String CANCELLED_A = "33333333-0000-0000-0000-00000000000a";
    private static final String CANCELLED_B = "33333333-0000-0000-0000-00000000000b";
    private static final String PARTIAL = "33333333-0000-0000-0000-00000000000c";
    private static final String OVER = "33333333-0000-0000-0000-00000000000d";
    private static final String OPERATOR = "44444444-0000-0000-0000-000000000001";
    private static final String WITNESS = "44444444-0000-0000-0000-000000000002";
    private static final String LOT_REMAINING =
        "SELECT remaining_quantity FROM clinical.stock_lots WHERE id = '" + LOT + "'";

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
    void plantNormalCancelsAPartialReturnAndOneOverReturn() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            stmt.execute("DELETE FROM clinical.stock_transactions");
            stmt.execute("DELETE FROM clinical.dispenses");
            stmt.execute("DELETE FROM clinical.stock_lots");
            stmt.execute("DELETE FROM clinical.inventory_items");
            insert(stmt, "clinical.inventory_items", Map.of("id", ITEM, "quantity_on_hand", "100"));
            insert(stmt, "clinical.stock_lots", Map.of("id", LOT, "inventory_item_id", ITEM,
                "initial_quantity", "200", "remaining_quantity", "100"));
            dispense(stmt, CANCELLED_A, "CANCELLED", "4");
            ret(stmt, CANCELLED_A, "4");
            dispense(stmt, CANCELLED_B, "CANCELLED", "1.5");
            ret(stmt, CANCELLED_B, "1.5");
            dispense(stmt, PARTIAL, "COMPLETED", "4");
            ret(stmt, PARTIAL, "2");
            // the dev case 63a1f3fb: 4 delivered, 5 back (a return, then a full cancel)
            dispense(stmt, OVER, "CANCELLED", "4");
            ret(stmt, OVER, "1");
            ret(stmt, OVER, "4");
        }
    }

    @Test
    void theReportListsOnlyTheRealOverReturnWithItsExcess() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            assertThat(report(stmt)).containsExactly(OVER + "=1");
        }
    }

    @Test
    void theRepairTakesOffOnlyTheExcessAndRunsOnce() throws Exception {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET session_replication_role = replica");
            repair(stmt, OVER);
            assertThat(scalar(stmt, LOT_REMAINING)).isEqualByComparingTo("99");
            assertThat(scalar(stmt,
                "SELECT quantity_on_hand FROM clinical.inventory_items WHERE id = '" + ITEM + "'"))
                .isEqualByComparingTo("99");
            assertThat(report(stmt)).isEmpty();

            repair(stmt, OVER);
            repair(stmt, CANCELLED_A);
            assertThat(scalar(stmt, LOT_REMAINING)).isEqualByComparingTo("99");
            assertThat(scalar(stmt,
                "SELECT COUNT(*) FROM clinical.stock_transactions WHERE transaction_type = 'ADJUSTMENT'"))
                .isEqualByComparingTo("1");
        }
    }

    private static List<String> report(Statement stmt) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(resource("ops/d669_over_return_report.sql"))) {
            while (rs.next()) {
                rows.add(rs.getString("dispense_id") + "="
                    + rs.getBigDecimal("excess").stripTrailingZeros().toPlainString());
            }
        }
        return rows;
    }

    private static void repair(Statement stmt, String dispenseId) throws Exception {
        stmt.execute(resource("ops/d669_over_return_repair.sql")
            .replace(":dispense_id", "'" + dispenseId + "'::uuid")
            .replace(":operator", "'" + OPERATOR + "'::uuid")
            .replace(":witness", "'" + WITNESS + "'::uuid"));
    }

    private static void dispense(Statement stmt, String id, String status, String qty) throws SQLException {
        insert(stmt, "clinical.dispenses", Map.of("id", id, "status", status, "stock_lot_id", LOT,
            "quantity_dispensed", qty, "quantity_requested", qty));
    }

    private static void ret(Statement stmt, String dispenseId, String qty) throws SQLException {
        insert(stmt, "clinical.stock_transactions", Map.of("id", UUID.randomUUID().toString(),
            "inventory_item_id", ITEM, "stock_lot_id", LOT, "transaction_type", "RETURN",
            "quantity", qty, "reference_id", dispenseId));
    }

    /** Inserts the given columns and a typed placeholder in every other NOT NULL column with no default. */
    private static void insert(Statement stmt, String table, Map<String, String> given) throws SQLException {
        String[] parts = table.split("\\.");
        Map<String, String> values = new LinkedHashMap<>(given);
        try (Statement meta = stmt.getConnection().createStatement();
             ResultSet rs = meta.executeQuery(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = '"
                    + parts[0] + "' AND table_name = '" + parts[1]
                    + "' AND is_nullable = 'NO' AND column_default IS NULL")) {
            while (rs.next()) {
                values.putIfAbsent(rs.getString(1), placeholder(rs.getString(2)));
            }
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!cols.isEmpty()) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(e.getKey());
            vals.append('\'').append(e.getValue()).append('\'');
        }
        stmt.execute("INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")");
    }

    private static String placeholder(String type) {
        return switch (type) {
            case "uuid" -> UUID.randomUUID().toString();
            case "boolean" -> "false";
            case "integer", "bigint", "smallint", "numeric", "double precision", "real" -> "0";
            case "date" -> "2026-01-01";
            case "timestamp without time zone", "timestamp with time zone" -> "2026-01-01 00:00:00";
            case "jsonb", "json" -> "{}";
            default -> "x";
        };
    }

    private static BigDecimal scalar(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = OverReturnOperatorSqlIT.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
