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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-012, on real PostgreSQL built by Liquibase: the standing-result query for each combination its one caller
 * can pass, with the foreign keys off for the inserts. The rows it joins through say nothing else here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CrossmatchStandingResultPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_crossmatch")
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

    @Autowired private TransfusionCrossmatchRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private UUID requestOf(UUID patient) {
        UUID request = UUID.randomUUID();
        // The columns with no default; a new NOT NULL here fails loudly rather than silently.
        Integer columns = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'clinical' "
                + "AND table_name = 'transfusion_requests' AND column_name = 'patient_id'", Integer.class);
        assertThat(columns).isEqualTo(1);
        jdbc.update("""
            INSERT INTO clinical.transfusion_requests (id, patient_id, hospital_id, product_type, units_requested,
                indication, urgency, status, requested_at)
            VALUES (?, ?, ?, 'PACKED_RED_CELLS', 1, 'x', 'ROUTINE', 'ISSUED', now())
            """, request, patient, UUID.randomUUID());
        return request;
    }

    private void crossmatch(UUID request, UUID unit, boolean compatible, boolean issued) {
        jdbc.update("""
            INSERT INTO clinical.transfusion_crossmatches (id, request_id, blood_unit_id, hospital_id, compatible,
                performed_at, unit_issued)
            VALUES (?, ?, ?, ?, ?, now(), ?)
            """, UUID.randomUUID(), request, unit, UUID.randomUUID(), compatible, issued);
    }

    private void release(UUID request, UUID unit, String type) {
        jdbc.update("""
            INSERT INTO clinical.emergency_blood_releases (id, request_id, blood_unit_id, hospital_id, release_type,
                reason, authoriser_user_id, issued_by_user_id, issued_at)
            VALUES (?, ?, ?, ?, ?, 'x', ?, ?, now())
            """, UUID.randomUUID(), request, unit, UUID.randomUUID(), type, UUID.randomUUID(), UUID.randomUUID());
    }

    private void withForeignKeysOff(Runnable inserts) {
        List<String> tables = List.of("clinical.transfusion_requests", "clinical.transfusion_crossmatches",
            "clinical.emergency_blood_releases");
        tables.forEach(t -> jdbc.execute("ALTER TABLE " + t + " DISABLE TRIGGER ALL"));
        try {
            inserts.run();
        } finally {
            tables.forEach(t -> jdbc.execute("ALTER TABLE " + t + " ENABLE TRIGGER ALL"));
        }
    }

    @Test
    @DisplayName("a false result on file at a knowing release stands for the patient, with no flag on the row")
    void aKnowingReleaseCountsWithNoFlag() {
        UUID patient = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        UUID otherRelease = UUID.randomUUID();
        UUID[] requests = new UUID[2];
        withForeignKeysOff(() -> {
            requests[0] = requestOf(patient);
            requests[1] = requestOf(patient);
            crossmatch(requests[0], released, false, false);
            release(requests[0], released, "SEROLOGICALLY_INCOMPATIBLE");
            crossmatch(requests[0], otherRelease, false, false);
            release(requests[0], otherRelease, "O_POSITIVE_DECISION");
        });

        assertThat(repository.existsStandingIncompatibleForPatient(released, patient, requests[1])).isTrue();
        assertThat(repository.existsStandingIncompatibleForPatient(otherRelease, patient, requests[1])).isFalse();
    }

    @Test
    @DisplayName("V275's backfill: stands where a bag went out, correctable where it never did")
    void theBackfillStatement() {
        UUID patient = UUID.randomUUID();
        UUID issued = UUID.randomUUID();
        UUID requested = UUID.randomUUID();
        jdbc.execute("ALTER TABLE clinical.blood_units DISABLE TRIGGER ALL");
        try {
            withForeignKeysOff(() -> {
                UUID out = requestOf(patient);
                UUID notOut = requestOf(patient);
                jdbc.update("UPDATE clinical.transfusion_requests SET status = 'REQUESTED' WHERE id = ?", notOut);
                crossmatch(out, issued, false, false);
                crossmatch(notOut, requested, false, false);
                for (UUID unit : List.of(issued, requested)) {
                    jdbc.update("""
                        INSERT INTO clinical.blood_units (id, hospital_id, unit_number, product_type, abo_group, rh_factor,
                            expires_on, status)
                        VALUES (?, ?, ?, 'PACKED_RED_CELLS', 'O', 'POSITIVE', current_date + 9, 'RETURNED')
                        """, unit, UUID.randomUUID(), unit.toString());
                }
                backfill();
            });
        } finally {
            jdbc.execute("ALTER TABLE clinical.blood_units ENABLE TRIGGER ALL");
        }
        assertThat(flag(issued)).as("a request that reached ISSUED: the result stands").isTrue();
        assertThat(flag(requested)).as("never out: the laboratory may still correct it").isFalse();
    }

    private Boolean flag(UUID unit) {
        return jdbc.queryForObject("SELECT unit_issued FROM clinical.transfusion_crossmatches WHERE blood_unit_id = ?",
            Boolean.class, unit);
    }

    /** The UPDATE of V275, read from the migration itself: the tables are empty when Liquibase runs it. */
    private void backfill() {
        try (var migration = getClass().getResourceAsStream("/db/migration/V275__r36_death_record_entered_in_error.sql")) {
            String sql = new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int update = sql.indexOf("UPDATE clinical.transfusion_crossmatches");
            jdbc.update(sql.substring(update, sql.indexOf(';', update)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("a standing result on another request of the patient is found; nothing else is")
    void onlyAStandingResultOnAnotherRequestOfThePatient() {
        UUID patient = UUID.randomUUID();
        UUID otherPatient = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID correctable = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        UUID[] requests = new UUID[2];
        withForeignKeysOff(() -> {
            requests[0] = requestOf(patient);
            requests[1] = requestOf(patient);
            crossmatch(requests[0], unit, false, true);
            crossmatch(requests[0], correctable, false, false);
            crossmatch(requests[0], good, true, true);
        });

        assertThat(repository.existsStandingIncompatibleForPatient(unit, patient, requests[1])).isTrue();
        assertThat(repository.existsStandingIncompatibleForPatient(unit, patient, requests[0]))
            .as("its own request is judged on its own row").isFalse();
        assertThat(repository.existsStandingIncompatibleForPatient(unit, otherPatient, requests[1]))
            .as("a finding is about this patient").isFalse();
        assertThat(repository.existsStandingIncompatibleForPatient(correctable, patient, requests[1]))
            .as("never issued: the laboratory may still correct it").isFalse();
        assertThat(repository.existsStandingIncompatibleForPatient(good, patient, requests[1])).isFalse();
    }
}
