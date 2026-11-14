package com.example.hms.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rex on #1152, on real PostgreSQL built by Liquibase: the sign of life recorded for a delivery on the MOTHER's chart
 * (a baby the delivery did not register) that keeps a stillbirth uncertain
 * ({@link NewbornAssessmentRepository#anyUnattributedSignOfLifeRecorded}). If this predicate were wrong, the
 * stillborn rule would silently suppress alerts against a recorded sign of life.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NewbornUnattributedSignOfLifePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_newborn_sign_of_life")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private NewbornAssessmentRepository repository;
    @Autowired private JdbcTemplate jdbc;

    /**
     * One row with the given values; every other NOT NULL column without a default gets a neutral value of its
     * type. The foreign keys are off for these inserts: the hospital, patient and staff rows say nothing here.
     */
    private void insert(String table, Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (value != null) {
                row.put(name, value);
            }
        });
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'clinical' AND table_name = ? AND is_nullable = 'NO' AND column_default IS NULL
                """, table)) {
            String name = (String) column.get("column_name");
            if (!row.containsKey(name)) {
                row.put(name, neutral((String) column.get("data_type")));
            }
        }
        List<String> names = new ArrayList<>(row.keySet());
        String sql = "INSERT INTO clinical." + table + " (" + String.join(", ", names) + ") VALUES ("
            + String.join(", ", names.stream().map(n -> "?").toList()) + ")";
        jdbc.execute("ALTER TABLE clinical." + table + " DISABLE TRIGGER ALL");
        try {
            jdbc.update(sql, row.values().toArray());
        } finally {
            jdbc.execute("ALTER TABLE clinical." + table + " ENABLE TRIGGER ALL");
        }
    }

    private static Object neutral(String dataType) {
        return switch (dataType) {
            case "uuid" -> UUID.randomUUID();
            case "boolean" -> false;
            case "integer", "smallint", "bigint" -> 0;
            case "numeric", "double precision", "real" -> java.math.BigDecimal.ZERO;
            case "date" -> java.sql.Date.valueOf(java.time.LocalDate.now());
            case "timestamp without time zone", "timestamp with time zone" ->
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
            default -> "x";
        };
    }

    private UUID delivery(UUID mother) {
        UUID id = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("patient_id", mother);
        values.put("delivery_mode", "SPONTANEOUS_VAGINAL");
        values.put("number_of_infants", 1);
        insert("delivery_records", values);
        return id;
    }

    /** A neonatal assessment of {@code delivery} recorded on {@code patient}'s chart. */
    private void assessment(UUID delivery, UUID patient, Integer apgarOneMinute, Integer heartRate) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("delivery_record_id", delivery);
        values.put("patient_id", patient);
        values.put("apgar_one_minute", apgarOneMinute);
        values.put("heart_rate_bpm", heartRate);
        for (String sign : List.of("poor_feeding", "convulsions", "severe_chest_indrawing",
                "absent_spontaneous_movement")) {
            values.put(sign, "NOT_ASSESSED");
        }
        insert("newborn_assessments", values);
    }

    @Test
    @DisplayName("a heart rate recorded on the mother's chart for this delivery is a sign of life")
    void aHeartRateOnTheMothersChartCounts() {
        UUID mother = UUID.randomUUID();
        UUID delivery = delivery(mother);
        assessment(delivery, mother, 0, 110);

        assertThat(repository.anyUnattributedSignOfLifeRecorded(delivery)).isTrue();
    }

    @Test
    @DisplayName("an Apgar of 0 and nothing else on the mother's chart is not")
    void anApgarOfZeroAloneDoesNotCount() {
        UUID mother = UUID.randomUUID();
        UUID delivery = delivery(mother);
        assessment(delivery, mother, 0, null);

        assertThat(repository.anyUnattributedSignOfLifeRecorded(delivery)).isFalse();
    }

    @Test
    @DisplayName("a sign of life on the baby's own chart belongs to that baby, not to the delivery")
    void theBabysOwnChartDoesNotCount() {
        UUID mother = UUID.randomUUID();
        UUID delivery = delivery(mother);
        assessment(delivery, UUID.randomUUID(), 0, 110);

        assertThat(repository.anyUnattributedSignOfLifeRecorded(delivery)).isFalse();
    }

    @Test
    @DisplayName("a sign of life recorded for another delivery of the same mother does not count")
    void anotherDeliveryOfTheSameMotherDoesNotCount() {
        UUID mother = UUID.randomUUID();
        UUID earlier = delivery(mother);
        UUID delivery = delivery(mother);
        assessment(earlier, mother, 8, 140);

        assertThat(repository.anyUnattributedSignOfLifeRecorded(delivery)).isFalse();
        assertThat(repository.anyUnattributedSignOfLifeRecorded(earlier)).isTrue();
    }
}
