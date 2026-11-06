package com.example.hms.repository;

import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.service.impl.PlatformAnalyticsServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-002 / CX-003 (round 33) on a real PostgreSQL built by Liquibase: the
 * super-admin analytics, through the real service and every grouped query it
 * runs. Hospital A has three staff (one of them inactive) plus one on a deleted
 * account, and fourteen departments, more than the ten the table used to stop
 * at; a department of hospital B bears the same name as one of A's. The staff
 * count is held to {@code GET /staff/hospital/{id}}'s own query, the number
 * Codex compared with.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("CX-002 / CX-003: the analytics count real rows on PostgreSQL")
class PlatformAnalyticsCountsPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_platform_analytics")
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

    private static final int DEPARTMENTS_AT_A = 14;
    private static final int TREND_DAYS = 3;

    @Autowired private PatientRepository patients;
    @Autowired private AppointmentRepository appointments;
    @Autowired private EncounterRepository encounters;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private LabOrderRepository labOrders;
    @Autowired private PrescriptionRepository prescriptions;
    @Autowired private UserRepository users;
    @Autowired private HospitalRepository hospitals;
    @Autowired private DepartmentRepository departments;
    @Autowired private StaffRepository staff;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID busyDepartment = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final List<UUID> staffRows = new ArrayList<>();
    private final List<UUID> userRows = new ArrayList<>();
    private final List<UUID> departmentRows = new ArrayList<>();
    private final List<UUID> encounterRows = new ArrayList<>();
    private final List<UUID> appointmentRows = new ArrayList<>();
    private String tag;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            tag = UUID.randomUUID().toString().substring(0, 8);
            hospital(hospitalA, "A");
            hospital(hospitalB, "B");

            staffMember(hospitalA, true, false);
            staffMember(hospitalA, true, false);
            staffMember(hospitalA, false, false);
            staffMember(hospitalA, true, true);
            staffMember(hospitalB, true, false);

            department(busyDepartment, hospitalA, "Urgences");
            for (int i = 1; i < DEPARTMENTS_AT_A; i++) {
                department(UUID.randomUUID(), hospitalA, "Service " + i);
            }
            department(UUID.randomUUID(), hospitalB, "Urgences");

            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, hospital_id, first_name, last_name, date_of_birth,
                    phone_number_primary, is_active, created_at, updated_at)
                VALUES (:id, :u, :h, 'Awa', 'Kaboré', DATE '1990-06-15', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID()).setParameter("h", hospitalA)
                .setParameter("phone", "+226 70 " + tag).executeUpdate();

            LocalDateTime today = LocalDate.now().atTime(9, 0);
            encounter(busyDepartment, "COMPLETED", null, today);
            encounter(busyDepartment, "IN_PROGRESS", "a visit note", today.minusDays(1));
            // An anchor is not a visit (QA D791), and a visit before the window is not in the trend.
            encounter(busyDepartment, "COMPLETED", Encounter.PRESCRIPTION_ANCHOR_NOTE, today);
            encounter(busyDepartment, "COMPLETED", null, today.minusDays(TREND_DAYS + 5L));

            appointment(busyDepartment, LocalDate.now());
            appointment(null, LocalDate.now().minusDays(TREND_DAYS + 5L));
        });
    }

    @AfterEach
    void clear() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            delete("clinical.appointments", appointmentRows);
            delete("clinical.encounters", encounterRows);
            delete("clinical.patients", List.of(patient));
            delete("hospital.departments", departmentRows);
            delete("hospital.staff", staffRows);
            delete("security.users", userRows);
            delete("hospital.hospitals", List.of(hospitalA, hospitalB));
        });
    }

    private PlatformAnalyticsDTO analytics() {
        return new TransactionTemplate(transactionManager).execute(status -> new PlatformAnalyticsServiceImpl(
            patients, appointments, encounters, invoices, labOrders, prescriptions, users, hospitals,
            departments, staff).getAnalytics(TREND_DAYS));
    }

    private PlatformAnalyticsDTO.HospitalMetric metricOf(PlatformAnalyticsDTO result, UUID hospital) {
        return result.getHospitalMetrics().stream().filter(m -> hospital.equals(m.getHospitalId())).findFirst()
            .orElseThrow();
    }

    @Test
    @DisplayName("staff per hospital equals what GET /staff/hospital/{id} counts")
    void staffCountIsTheHospitalPagesCount() {
        PlatformAnalyticsDTO result = analytics();

        for (UUID hospital : List.of(hospitalA, hospitalB)) {
            long scoped = staff.findByHospitalIdExcludingDeletedUsers(hospital, PageRequest.of(0, 1)).getTotalElements();
            assertThat(metricOf(result, hospital).getStaffCount()).as("analytics against the hospital's page")
                .isEqualTo(scoped).isPositive();
        }
        assertThat(metricOf(result, hospitalA).getStaffCount()).isEqualTo(3);
        assertThat(metricOf(result, hospitalB).getStaffCount()).isEqualTo(1);
        assertThat(metricOf(result, hospitalA).getPatientCount()).isEqualTo(1);
        assertThat(metricOf(result, hospitalA).getAppointmentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("every department is listed, with its real visits; a department with none is a true zero")
    void everyDepartmentWithItsRealCounts() {
        PlatformAnalyticsDTO result = analytics();

        List<PlatformAnalyticsDTO.DepartmentUtilization> ours = result.getDepartmentUtilization().stream()
            .filter(d -> departmentRows.contains(d.getDepartmentId())).toList();
        assertThat(ours).as("nothing is cut at ten").hasSize(DEPARTMENTS_AT_A + 1);
        PlatformAnalyticsDTO.DepartmentUtilization busy = ours.get(0);
        assertThat(busy.getDepartmentId()).isEqualTo(busyDepartment);
        assertThat(busy.getEncounterCount()).as("three visits, the anchor left out").isEqualTo(3);
        assertThat(busy.getAppointmentCount()).isEqualTo(1);
        assertThat(ours.stream().filter(d -> "Urgences".equals(d.getDepartmentName()))
            .map(PlatformAnalyticsDTO.DepartmentUtilization::getHospitalName).distinct())
            .as("two hospitals' Urgences are told apart").hasSize(2);
        assertThat(ours.subList(1, ours.size()))
            .allSatisfy(d -> assertThat(d.getEncounterCount()).isZero());
    }

    @Test
    @DisplayName("the status breakdowns and the three trends run on PostgreSQL and hold real counts")
    void breakdownsAndTrendsAreReal() {
        PlatformAnalyticsDTO result = analytics();

        assertThat(result.getEncountersByStatus()).containsEntry("IN_PROGRESS", 1L).containsEntry("COMPLETED", 2L);
        assertThat(result.getAppointmentsByStatus()).containsEntry("SCHEDULED", 2L);
        assertThat(result.getInvoicesByStatus()).isNotNull();

        LocalDate today = LocalDate.now();
        assertThat(result.getEncounterTrend()).hasSize(TREND_DAYS);
        assertThat(result.getEncounterTrend().get(TREND_DAYS - 1).getDate()).isEqualTo(today);
        assertThat(result.getEncounterTrend().get(TREND_DAYS - 1).getCount()).as("today, the anchor left out").isEqualTo(1);
        assertThat(result.getEncounterTrend().get(TREND_DAYS - 2).getCount()).as("yesterday").isEqualTo(1);
        assertThat(result.getEncounterTrend().get(0).getCount()).isZero();
        assertThat(result.getAppointmentTrend()).hasSize(TREND_DAYS);
        assertThat(result.getAppointmentTrend().get(TREND_DAYS - 1).getCount()).isEqualTo(1);
        assertThat(result.getPatientRegistrationTrend()).hasSize(TREND_DAYS);
        assertThat(result.getPatientRegistrationTrend().get(TREND_DAYS - 1).getCount()).isPositive();
    }

    private void hospital(UUID id, String suffix) {
        entityManager.createNativeQuery("""
            INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
            VALUES (:id, :code, :name, true, now(), now())
            """).setParameter("id", id).setParameter("code", "CX2-" + tag + suffix)
            .setParameter("name", "Hôpital CX2 " + tag + suffix).executeUpdate();
    }

    private void staffMember(UUID hospital, boolean active, boolean accountDeleted) {
        UUID user = UUID.randomUUID();
        String handle = "cx2-" + user;
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, password_hash, email, phone_number, is_active, is_deleted,
                force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'x', :email, :phone, true, :deleted, false, now(), now())
            """).setParameter("id", user).setParameter("username", handle).setParameter("email", handle + "@example.test")
            .setParameter("phone", "+226" + Math.abs(handle.hashCode())).setParameter("deleted", accountDeleted)
            .executeUpdate();
        userRows.add(user);
        UUID row = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO hospital.staff (id, user_id, hospital_id, assignment_id, active, employment_type, job_title,
                created_at, updated_at)
            VALUES (:id, :u, :h, :a, :active, 'FULL_TIME', 'DOCTOR', now(), now())
            """).setParameter("id", row).setParameter("u", user).setParameter("h", hospital)
            .setParameter("a", UUID.randomUUID()).setParameter("active", active).executeUpdate();
        staffRows.add(row);
    }

    private void department(UUID id, UUID hospital, String name) {
        entityManager.createNativeQuery("""
            INSERT INTO hospital.departments (id, hospital_id, assignment_id, code, email, name, is_active,
                created_at, updated_at)
            VALUES (:id, :h, :a, :code, 'dept@example.test', :name, true, now(), now())
            """).setParameter("id", id).setParameter("h", hospital).setParameter("a", UUID.randomUUID())
            .setParameter("code", id.toString().substring(0, 12)).setParameter("name", name).executeUpdate();
        departmentRows.add(id);
    }

    private void encounter(UUID department, String encounterStatus, String notes, LocalDateTime at) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO clinical.encounters (id, patient_id, staff_id, hospital_id, department_id, assignment_id, code,
                encounter_type, status, encounter_date, notes, created_at, updated_at)
            VALUES (:id, :p, :s, :h, :d, :a, :code, 'CONSULTATION', :status, :at, CAST(:notes AS text), now(), now())
            """).setParameter("id", id).setParameter("p", patient).setParameter("s", staffRows.get(0))
            .setParameter("h", hospitalA).setParameter("d", department).setParameter("a", UUID.randomUUID())
            .setParameter("code", "ENC-" + id.toString().substring(0, 12)).setParameter("status", encounterStatus)
            .setParameter("at", at).setParameter("notes", notes).executeUpdate();
        encounterRows.add(id);
    }

    private void appointment(UUID department, LocalDate day) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO clinical.appointments (id, patient_id, staff_id, hospital_id, department_id, assignment_id,
                appointment_date, start_time, end_time, status, pre_checked_in, created_at, updated_at)
            VALUES (:id, :p, :s, :h, :d, :a, :day, TIME '09:00', TIME '09:30', 'SCHEDULED', false, now(), now())
            """).setParameter("id", id).setParameter("p", patient).setParameter("s", staffRows.get(0))
            .setParameter("h", hospitalA).setParameter("d", department).setParameter("a", UUID.randomUUID())
            .setParameter("day", day).executeUpdate();
        appointmentRows.add(id);
    }

    private void delete(String table, List<UUID> ids) {
        entityManager.createNativeQuery("DELETE FROM " + table + " WHERE id IN (:ids)")
            .setParameter("ids", ids).executeUpdate();
    }
}
