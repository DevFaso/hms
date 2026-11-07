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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D268, CR-06 class, on real PostgreSQL built by Liquibase: what keeps a plan on the 15-minute checks.
 * An indicator alert with a false flag, a true flag with or without an alert (a past row), yes. Another
 * kind of alert, another plan, an empty plan, no.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostpartumHaemorrhageOnPlanPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_postpartum")
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

    @Autowired private PostpartumObservationRepository repository;
    @Autowired private JdbcTemplate jdbc;

    /** The foreign keys are off for these inserts: the plan, patient and hospital rows say nothing about this query. */
    private UUID observation(UUID plan, boolean protocolActivated, String... alertTypes) {
        UUID id = UUID.randomUUID();
        jdbc.execute("ALTER TABLE clinical.postpartum_observations DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE clinical.postpartum_observation_alerts DISABLE TRIGGER ALL");
        try {
            jdbc.update("""
                INSERT INTO clinical.postpartum_observations (id, care_plan_id, patient_id, hospital_id,
                    observation_time, documented_at, created_at, updated_at,
                    hemorrhage_protocol_activated, chills_or_rigors, contact_info_verified,
                    discharge_checklist_complete, education_completed, excessive_bleeding, foul_lochia_odor,
                    hemorrhage_protocol_confirmed, immunizations_updated, late_entry,
                    mental_health_referral_suggested, pain_management_referral_suggested,
                    rh_immunoglobulin_completed, social_support_referral_suggested, uterine_atony_suspected,
                    uterine_tenderness, uterotonic_availability_confirmed, uterotonic_given)
                VALUES (?, ?, ?, ?, now(), now(), now(), now(), ?,
                    false, false, false, false, false, false, false, false, false, false, false,
                    false, false, false, false, false, false)
                """, id, plan, UUID.randomUUID(), UUID.randomUUID(), protocolActivated);
            for (int order = 0; order < alertTypes.length; order++) {
                jdbc.update("""
                    INSERT INTO clinical.postpartum_observation_alerts (observation_id, alert_order, alert_type,
                        alert_severity, alert_message, created_at)
                    VALUES (?, ?, ?, 'URGENT', 'text', now())
                    """, id, order, alertTypes[order]);
            }
        } finally {
            jdbc.execute("ALTER TABLE clinical.postpartum_observation_alerts ENABLE TRIGGER ALL");
            jdbc.execute("ALTER TABLE clinical.postpartum_observations ENABLE TRIGGER ALL");
        }
        return id;
    }

    @Test
    @DisplayName("an indicator alert with a false flag keeps the checks: nobody attested, the mother still bleeds")
    void anIndicatorAlertWithAFalseFlagCounts() {
        UUID plan = UUID.randomUUID();
        observation(plan, false, "HEMORRHAGE");
        assertThat(repository.existsHaemorrhageOnPlan(plan)).isTrue();
    }

    @Test
    @DisplayName("a past row forced to true, with its alert, keeps the checks")
    void aPastForcedTrueCounts() {
        UUID plan = UUID.randomUUID();
        observation(plan, true, "HEMORRHAGE");
        assertThat(repository.existsHaemorrhageOnPlan(plan)).isTrue();
    }

    @Test
    @DisplayName("a true attested with no alert keeps the checks")
    void anAttestedTrueWithNoAlertCounts() {
        UUID plan = UUID.randomUUID();
        observation(plan, true);
        assertThat(repository.existsHaemorrhageOnPlan(plan)).isTrue();
    }

    @Test
    @DisplayName("an earlier signal counts among later quiet rows")
    void oneRowOfManyCounts() {
        UUID plan = UUID.randomUUID();
        observation(plan, false);
        observation(plan, false, "HEMORRHAGE");
        observation(plan, false, "PAIN");
        assertThat(repository.existsHaemorrhageOnPlan(plan)).isTrue();
    }

    @Test
    @DisplayName("a row with several alerts counts when one of them is a haemorrhage, wherever it sits")
    void severalAlertsOnOneRow() {
        UUID plan = UUID.randomUUID();
        observation(plan, false, "INFECTION", "PAIN", "HEMORRHAGE");
        UUID without = UUID.randomUUID();
        observation(without, false, "INFECTION", "PAIN");

        assertThat(repository.existsHaemorrhageOnPlan(plan)).isTrue();
        assertThat(repository.existsHaemorrhageOnPlan(without)).isFalse();
    }

    @Test
    @DisplayName("another kind of alert, a false flag, an empty plan, or another plan's signal do not count")
    void nothingElseCounts() {
        UUID infection = UUID.randomUUID();
        observation(infection, false, "INFECTION");
        UUID quiet = UUID.randomUUID();
        observation(quiet, false);
        observation(UUID.randomUUID(), true, "HEMORRHAGE");

        assertThat(repository.existsHaemorrhageOnPlan(infection)).isFalse();
        assertThat(repository.existsHaemorrhageOnPlan(quiet)).isFalse();
        assertThat(repository.existsHaemorrhageOnPlan(UUID.randomUUID())).isFalse();
    }
}
