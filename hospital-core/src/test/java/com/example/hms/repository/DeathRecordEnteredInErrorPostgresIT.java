package com.example.hms.repository;

import com.example.hms.model.DeathRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1488, on real PostgreSQL built by Liquibase: a row entered in error is no death for any query of this
 * repository, and V275's partial index lets the patient have a real record later, and only one.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeathRecordEnteredInErrorPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_death_records")
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

    private static final LocalDateTime DIED = LocalDateTime.of(2026, 3, 10, 14, 0);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 3, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 4, 1, 0, 0);

    @Autowired private DeathRecordRepository repository;
    @Autowired private JdbcTemplate jdbc;

    /** The foreign keys are off for these inserts: the patient and hospital rows say nothing about this index. */
    private void insert(UUID patient, UUID hospital, LocalDateTime enteredInErrorAt) {
        jdbc.execute("ALTER TABLE clinical.death_records DISABLE TRIGGER ALL");
        try {
            jdbc.update("""
                INSERT INTO clinical.death_records (id, patient_id, hospital_id, died_at, immediate_cause,
                    maternal_death, maternal_death_timing, perinatal_death, perinatal_type, entered_in_error_at)
                VALUES (?, ?, ?, ?, 'Cause', true, 'DURING_PREGNANCY', true, 'STILLBIRTH', ?)
                """, UUID.randomUUID(), patient, hospital, Timestamp.valueOf(DIED),
                enteredInErrorAt == null ? null : Timestamp.valueOf(enteredInErrorAt));
        } finally {
            jdbc.execute("ALTER TABLE clinical.death_records ENABLE TRIGGER ALL");
        }
    }

    private static List<UUID> ids(List<DeathRecord> rows) {
        return rows.stream().map(DeathRecord::getId).toList();
    }

    @Test
    @DisplayName("a row entered in error is in no count and no lookup, and is listed apart")
    void aRowEnteredInErrorIsNoDeath() {
        UUID patient = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        insert(patient, hospital, DIED.plusDays(1));

        assertThat(repository.existsByPatient_Id(patient)).isFalse();
        assertThat(repository.findByPatient_Id(patient)).isEmpty();
        assertThat(repository.findRegister(hospital, FROM, TO)).isEmpty();
        assertThat(repository.findMaternalDeaths(hospital, FROM, TO)).isEmpty();
        assertThat(repository.findPerinatalDeaths(hospital, FROM, TO)).isEmpty();
        assertThat(repository.findEnteredInError(hospital, FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("a live row is in every query and not in the entered-in-error list")
    void aLiveRowCounts() {
        UUID patient = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        insert(patient, hospital, null);

        assertThat(repository.existsByPatient_Id(patient)).isTrue();
        assertThat(repository.findByPatient_Id(patient)).isPresent();
        assertThat(ids(repository.findRegister(hospital, FROM, TO))).hasSize(1);
        assertThat(repository.findMaternalDeaths(hospital, FROM, TO)).hasSize(1);
        assertThat(repository.findPerinatalDeaths(hospital, FROM, TO)).hasSize(1);
        assertThat(repository.findEnteredInError(hospital, FROM, TO)).isEmpty();
    }

    private void insertPerinatal(UUID hospital, String type, Integer gestationWeeks) {
        jdbc.execute("ALTER TABLE clinical.death_records DISABLE TRIGGER ALL");
        try {
            jdbc.update("""
                INSERT INTO clinical.death_records (id, patient_id, hospital_id, died_at, immediate_cause,
                    perinatal_death, perinatal_type, gestational_age_weeks)
                VALUES (?, ?, ?, ?, 'Cause', true, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), hospital, Timestamp.valueOf(DIED), type, gestationWeeks);
        } finally {
            jdbc.execute("ALTER TABLE clinical.death_records ENABLE TRIGGER ALL");
        }
    }

    @Test
    @DisplayName("CR-07: the perinatal numerator leaves out a late neonatal death and a known gestation under 22 weeks")
    void thePerinatalNumerator() {
        UUID hospital = UUID.randomUUID();
        insertPerinatal(hospital, "LATE_NEONATAL", null);
        insertPerinatal(hospital, "LATE_NEONATAL", 38);
        insertPerinatal(hospital, "EARLY_NEONATAL", 21);
        insertPerinatal(hospital, "STILLBIRTH", 21);
        insertPerinatal(hospital, "STILLBIRTH", 18);
        insertPerinatal(hospital, "EARLY_NEONATAL", 22);
        insertPerinatal(hospital, "STILLBIRTH", null);
        insertPerinatal(hospital, "EARLY_NEONATAL", null);

        List<DeathRecord> numerator = repository.findPerinatalDeaths(hospital, FROM, TO);

        assertThat(numerator).hasSize(3).allMatch(DeathRecord::isInWhoPerinatalNumerator);
        // The query and the entity's definition give the same rows.
        assertThat(ids(numerator)).containsExactlyInAnyOrderElementsOf(ids(
            repository.findRegister(hospital, FROM, TO).stream().filter(DeathRecord::isInWhoPerinatalNumerator).toList()));
    }

    @Test
    @DisplayName("after a row entered in error the patient can have a real record, but only one")
    void thePartialIndexAllowsOneLiveRow() {
        UUID patient = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        insert(patient, hospital, DIED.plusDays(1));
        insert(patient, hospital, null);

        assertThat(repository.findByPatient_Id(patient)).isPresent();
        assertThatThrownBy(() -> insert(patient, hospital, null)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
