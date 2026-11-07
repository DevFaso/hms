package com.example.hms.repository;

import com.example.hms.model.MaternalHistory;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two reads the prenatal planner takes the recorded risk from, on a real PostgreSQL built by
 * Liquibase, for what the planner relies on: a read names one hospital and returns no other's rows; the
 * versions come newest first; only an active plan comes back, newest first; a patient with nothing comes
 * back empty; and the date that says which pregnancy a score belongs to is the column V267 added, which
 * a write that does not score leaves alone, and which a score equal to the old one still moves.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("The prenatal planner's recorded-risk reads, on PostgreSQL")
class PrenatalRecordedRiskPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_prenatal_recorded_risk")
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

    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2020, 1, 1, 9, 0);

    @Autowired private MaternalHistoryRepository histories;
    @Autowired private HighRiskPregnancyCarePlanRepository plans;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID patient = UUID.randomUUID();
    private final UUID patientWithNothing = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final List<UUID> historyRows = new ArrayList<>();
    private final List<UUID> planRows = new ArrayList<>();

    private UUID newestAtA;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            for (UUID hospital : List.of(hospitalA, hospitalB)) {
                entityManager.createNativeQuery("""
                    INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                    VALUES (:id, :code, :name, true, now(), now())
                    """).setParameter("id", hospital).setParameter("code", "R34-" + tag + (hospital == hospitalA ? "A" : "B"))
                    .setParameter("name", "Hopital R34 " + tag).executeUpdate();
            }
            for (UUID row : List.of(patient, patientWithNothing)) {
                entityManager.createNativeQuery("""
                    INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                        is_active, created_at, updated_at)
                    VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', '+226 70 00 00 34', true, now(), now())
                    """).setParameter("id", row).setParameter("u", UUID.randomUUID()).executeUpdate();
            }
            history(hospitalA, 1, "HIGH");
            newestAtA = history(hospitalA, 2, "HIGH");
            history(hospitalB, 3, "LOW");
            plan(hospitalA, false, "HIGH", LONG_AGO.plusDays(2));
            plan(hospitalA, true, "MODERATE", LONG_AGO.plusDays(1));
            plan(hospitalB, true, "HIGH", LONG_AGO.plusDays(3));
        });
    }

    private UUID history(UUID hospital, int version, String risk) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO clinical.maternal_history (id, patient_id, hospital_id, version_number, recorded_date, risk_category,
                risk_assessed_at, created_at, updated_at)
            VALUES (:id, :p, :h, :v, :at, :risk, CAST(:at AS timestamp), :at, :at)
            """).setParameter("id", id).setParameter("p", patient).setParameter("h", hospital).setParameter("v", version)
            .setParameter("at", LONG_AGO).setParameter("risk", risk).executeUpdate();
        historyRows.add(id);
        return id;
    }

    private void plan(UUID hospital, boolean active, String level, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO clinical.high_risk_pregnancy_plans (id, patient_id, hospital_id, active, risk_level, created_at, updated_at)
            VALUES (:id, :p, :h, :active, :level, :at, :at)
            """).setParameter("id", id).setParameter("p", patient).setParameter("h", hospital).setParameter("active", active)
            .setParameter("level", level).setParameter("at", createdAt).executeUpdate();
        planRows.add(id);
    }

    @AfterEach
    void clear() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM clinical.maternal_history WHERE id IN (:ids)")
                .setParameter("ids", historyRows).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical.high_risk_pregnancy_plans WHERE id IN (:ids)")
                .setParameter("ids", planRows).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical.patients WHERE id IN (:ids)")
                .setParameter("ids", List.of(patient, patientWithNothing)).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM hospital.hospitals WHERE id IN (:ids)")
                .setParameter("ids", List.of(hospitalA, hospitalB)).executeUpdate();
        });
    }

    private List<MaternalHistory> versions(UUID who, UUID hospital) {
        return histories.findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(who, Set.of(hospital));
    }

    @Test
    @DisplayName("history: one hospital's versions only, newest first, with their assessment date; nothing is empty")
    void theHistoryRead() {
        assertThat(versions(patient, hospitalA)).extracting(MaternalHistory::getVersionNumber).containsExactly(2, 1);
        assertThat(versions(patient, hospitalA)).extracting(MaternalHistory::getRiskAssessedAt).containsOnly(LONG_AGO);
        assertThat(versions(patient, hospitalB)).extracting(MaternalHistory::getRiskCategory).containsExactly("LOW");
        assertThat(versions(patientWithNothing, hospitalA)).isEmpty();
    }

    @Test
    @DisplayName("plan: the newest active plan of one hospital only; a closed one is never it; nothing is empty")
    void thePlanRead() {
        assertThat(plans.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(patient, Set.of(hospitalA)))
            .map(HighRiskPregnancyCarePlan::getRiskLevel).contains("MODERATE");
        assertThat(plans.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(patient, Set.of(hospitalB)))
            .map(HighRiskPregnancyCarePlan::getRiskLevel).contains("HIGH");
        assertThat(plans.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(patientWithNothing, Set.of(hospitalA)))
            .isEmpty();
    }

    @Test
    @DisplayName("a review moves updated_at and not the assessment date; a score equal to the old one moves it")
    void onlyAScoreMovesTheDateThePlannerReads() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            MaternalHistory version = histories.findById(newestAtA).orElseThrow();
            version.setReviewedByProvider(true);
            histories.save(version);
        });
        MaternalHistory reviewed = histories.findById(newestAtA).orElseThrow();
        assertThat(reviewed.getUpdatedAt().toLocalDate()).isAfter(LocalDate.of(2020, 1, 1));
        assertThat(reviewed.getRiskAssessedAt()).isEqualTo(LONG_AGO);

        LocalDateTime rescoredAt = LONG_AGO.plusYears(6);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            MaternalHistory version = histories.findById(newestAtA).orElseThrow();
            version.setRiskCategory("HIGH");
            version.setRiskAssessedAt(rescoredAt);
            histories.save(version);
        });
        assertThat(histories.findById(newestAtA).orElseThrow().getRiskAssessedAt()).isEqualTo(rescoredAt);
    }
}
