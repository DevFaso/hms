package com.example.hms.repository;

import com.example.hms.model.Prescription;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.MedicationCourseStatus;
import com.example.hms.service.impl.PatientMedicationServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Pageable;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1346 (round 30): "My medications" was the 20 newest prescriptions,
 * cancelled ones included, so PATIENT_A2 (20 cancelled orders newer than her
 * three running medicines) saw no current medication. The two reads behind
 * the list (the patient's own, unpaged; the staff read over the readable
 * hospitals) run on a real PostgreSQL built by Liquibase with that shape -
 * three active orders older than twenty-five cancelled ones - and the list
 * the service builds from them keeps every active order, first, at every
 * limit the callers pass (the portal's 20, the summary's 100, the staff
 * default 50, and 1).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("D1346: every active medication survives the list's limit, on PostgreSQL")
class PatientMedicationActiveFirstPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_medications_active_first")
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

    private static final int ACTIVE = 3;
    private static final int CANCELLED = 25;

    @Autowired private PrescriptionRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID patient = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID staffMember = UUID.randomUUID();
    private final UUID assignment = UUID.randomUUID();
    private final Map<UUID, UUID> encounterAt = new HashMap<>();
    private final List<UUID> rows = new ArrayList<>();
    private final List<UUID> active = new ArrayList<>();

    /**
     * The reads fetch the patient, the prescriber (and their user), the
     * encounter (and its hospital) and the hospital as inner joins, so the
     * graph they join is seeded for real; only the assignment stays a bare id.
     */
    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            for (UUID hospital : List.of(hospitalA, hospitalB)) {
                entityManager.createNativeQuery("""
                    INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                    VALUES (:id, :code, :name, true, now(), now())
                    """).setParameter("id", hospital).setParameter("code", "R30-" + tag + (hospital == hospitalA ? "A" : "B"))
                    .setParameter("name", "Hôpital R30 " + tag).executeUpdate();
            }
            entityManager.createNativeQuery("""
                INSERT INTO security.users (id, username, password_hash, email, phone_number, is_active, is_deleted,
                    force_password_change, created_at, updated_at)
                VALUES (:id, :username, 'x', :email, :phone, true, false, false, now(), now())
                """).setParameter("id", user).setParameter("username", "r30-" + tag)
                .setParameter("email", "r30-" + tag + "@example.test").setParameter("phone", "+2267" + tag.hashCode())
                .executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO hospital.staff (id, user_id, hospital_id, assignment_id, active, employment_type, job_title,
                    created_at, updated_at)
                VALUES (:id, :u, :h, :a, true, 'FULL_TIME', 'DOCTOR', now(), now())
                """).setParameter("id", staffMember).setParameter("u", user).setParameter("h", hospitalA)
                .setParameter("a", assignment).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kaboré', DATE '1990-06-15', '+226 70 00 00 30', true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID()).executeUpdate();
            for (UUID hospital : List.of(hospitalA, hospitalB)) {
                UUID encounter = UUID.randomUUID();
                entityManager.createNativeQuery("""
                    INSERT INTO clinical.encounters (id, patient_id, staff_id, hospital_id, assignment_id, code,
                        encounter_type, status, encounter_date, created_at, updated_at)
                    VALUES (:id, :p, :s, :h, :a, :code, 'CONSULTATION', 'COMPLETED', now(), now(), now())
                    """).setParameter("id", encounter).setParameter("p", patient).setParameter("s", staffMember)
                    .setParameter("h", hospital).setParameter("a", assignment)
                    .setParameter("code", "ENC-R30-" + tag + (hospital == hospitalA ? "A" : "B")).executeUpdate();
                encounterAt.put(hospital, encounter);
            }
            LocalDateTime start = LocalDateTime.now().minusDays(2);
            for (int i = 0; i < ACTIVE; i++) {
                active.add(prescription(i % 2 == 0 ? hospitalA : hospitalB, "SIGNED", start.plusMinutes(i)));
            }
            for (int i = 0; i < CANCELLED; i++) {
                prescription(i % 2 == 0 ? hospitalA : hospitalB, "CANCELLED", start.plusHours(1).plusMinutes(i));
            }
        });
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM clinical.prescriptions WHERE id IN (:ids)")
                .setParameter("ids", rows).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical.encounters WHERE id IN (:ids)")
                .setParameter("ids", encounterAt.values()).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical.patients WHERE id = :id")
                .setParameter("id", patient).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM hospital.staff WHERE id = :id")
                .setParameter("id", staffMember).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM security.users WHERE id = :id")
                .setParameter("id", user).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM hospital.hospitals WHERE id IN (:ids)")
                .setParameter("ids", List.of(hospitalA, hospitalB)).executeUpdate();
        });
        rows.clear();
        active.clear();
        encounterAt.clear();
    }

    @Test
    @DisplayName("the patient's own read and the staff read keep every active order first, at every caller's limit")
    void everyActiveOrderSurvivesEveryLimit() {
        List<String> wrong = new ArrayList<>();
        // The tenant-aware repositories scope every read by the caller's context;
        // the reads themselves are what is tested here, so the context lets them through.
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<Prescription> own = repository.findByPatient_Id(patient, Pageable.unpaged()).getContent();
            List<Prescription> staff = repository.findByPatient_IdAndHospital_IdIn(patient, Set.of(hospitalA, hospitalB));
            assertThat(own).as("the patient's own read").hasSize(ACTIVE + CANCELLED);
            assertThat(staff).as("the staff read over both readable hospitals").hasSize(ACTIVE + CANCELLED);
            for (List<Prescription> read : List.of(own, staff)) {
                for (int limit : new int[] {1, 20, 50, 100}) {
                    List<Prescription> shown = PatientMedicationServiceImpl.activeFirst(read, List.of(), limit);
                    List<UUID> firstIds = shown.subList(0, ACTIVE).stream().map(Prescription::getId).toList();
                    if (!firstIds.containsAll(active)
                            || !shown.subList(0, ACTIVE).stream()
                                .allMatch(p -> MedicationCourseStatus.isActive(p, List.of()))) {
                        wrong.add("limit " + limit + ": the active orders are not all first");
                    }
                    if (shown.size() != ACTIVE + Math.min(limit, CANCELLED)) {
                        wrong.add("limit " + limit + ": " + shown.size() + " rows, expected every active order"
                            + " and at most " + limit + " past ones");
                    }
                }
            }
        });
        assertThat(wrong).isEmpty();
    }

    private UUID prescription(UUID hospital, String status, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO clinical.prescriptions (id, patient_id, staff_id, encounter_id, hospital_id, assignment_id,
                medication_name, refills_used, controlled_substance, inpatient_order, take_home, allergies_reviewed,
                interactions_reviewed, contraindications_reviewed, requires_cosign, pediatric_dose_review_required,
                status, created_at, updated_at)
            VALUES (:id, :p, :s, :e, :h, :a, 'Amoxicilline 500 mg', 0, false, false, false, false, false, false,
                false, false, :status, :at, :at)
            """).setParameter("id", id).setParameter("p", patient).setParameter("s", staffMember)
            .setParameter("e", encounterAt.get(hospital)).setParameter("h", hospital).setParameter("a", assignment)
            .setParameter("status", status).setParameter("at", createdAt).executeUpdate();
        rows.add(id);
        return id;
    }
}
