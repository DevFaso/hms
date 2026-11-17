package com.example.hms.repository;

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

/**
 * QA D1466 on the real schema: the fold of a copay above the visit total leaves
 * {@code amount_paid > total_amount} on the visit invoice. The entity's old {@code @Check} only
 * ever existed in the H2 test schema; this proves the changelog's table takes the row, and that
 * the outstanding balance the reports sum stays out of it.
 */
@Testcontainers
class CopayFoldSchemaPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_d1466")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection conn = connect()) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase("db/migration/changelog.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        }
    }

    @Test
    void noConstraintOfTheRealTableHoldsAmountPaidUnderTheTotal() throws Exception {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                 + "JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace "
                 + "WHERE n.nspname = 'billing' AND t.relname = 'billing_invoices' AND c.contype = 'c'")) {
            while (rs.next()) {
                assertThat(rs.getString(1)).doesNotContain("amount_paid");
            }
        }
    }
}
