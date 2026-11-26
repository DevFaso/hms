package com.example.hms.service.pharmacy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class StockReceiptMetadataMigrationPostgresIT {
    @Container @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_receipt_migration").withUsername("hms_test_user").withPassword("hms_test_pass");
    @Test void wideningKeepsTheLargestPreviouslyValidReceiptCost() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA clinical");
            statement.execute("CREATE TABLE clinical.stock_transactions (supplier varchar(200), unit_cost numeric(12,2))");
            statement.execute("INSERT INTO clinical.stock_transactions VALUES ('Synthetic supplier', 9999999999.99)");
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V403__stock_receipt_metadata.sql")));
            try (var rows = statement.executeQuery("SELECT unit_cost FROM clinical.stock_transactions")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(new BigDecimal("9999999999.99"));
            }
            statement.execute("INSERT INTO clinical.stock_transactions (unit_cost) VALUES (9999999999.9999)");
        }
    }
}
