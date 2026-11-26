package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.BloodGroupStatusDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.service.transfusion.PatientBloodGroupStatusService;
import com.example.hms.service.transfusion.PositiveAntibodyScreenService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * QA D3901 (r77, High), on a real PostgreSQL built by Liquibase: the laboratory's grouping reaches the patient
 * record the moment it is saved, and the group a doctor typed never replaces it.
 *
 * <p>The record is read the way every patient response is built ({@link PatientMapper#toPatientDTO}) and the way the
 * chart and the safety strip read it ({@link PatientBloodGroupStatusService#withAntibodies}), each in a new
 * transaction after the laboratory's commit.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransfusionServiceImpl.class, com.example.hms.service.transfusion.OPositiveReleaseAuthorityService.class, com.example.hms.service.transfusion.OPositiveReleaseNotices.class, PositiveAntibodyScreenService.class, TransfusionMapper.class,
    PatientBloodGroupStatusService.class, PatientMapper.class,
    com.example.hms.security.EncryptionKeyHolder.class, PatientBloodGroupOnRecordPostgresIT.Config.class})
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@DisplayName("QA D3901: the laboratory's blood group on the patient record, on PostgreSQL")
class PatientBloodGroupOnRecordPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_blood_group_record")
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
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
            BloodGroupStatements.class::getName);
    }

    /** Counts real JDBC preparations of the laboratory lookup, independently of other mapper reads. */
    public static class BloodGroupStatements implements org.hibernate.resource.jdbc.spi.StatementInspector {
        private static final ThreadLocal<Integer> COUNT = ThreadLocal.withInitial(() -> 0);
        @Override
        public String inspect(String sql) {
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("patient_blood_groups")) {
                COUNT.set(COUNT.get() + 1);
            }
            return sql;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Clock bloodGroupRecordTestClock() {
            return Clock.systemDefaultZone();
        }
    }

    @Autowired private TransfusionService service;
    @Autowired private PatientMapper patientMapper;
    @Autowired private PatientBloodGroupStatusService bloodGroupStatus;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private PatientChartAccess patientChartAccess;
    @MockitoBean private InBasketService inBasketService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID scientistUser = UUID.randomUUID();
    private final UUID directorUser = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "D3901-" + tag)
                .setParameter("name", "Hopital D3901 " + tag).executeUpdate();
            // P03 of the QA run: a doctor typed A+ on the chart before the laboratory grouped him.
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, gender, phone_number_primary,
                    blood_type, is_active, created_at, updated_at)
                VALUES (:id, :u, 'Issouf', 'Kabore', DATE '1985-03-02', 'MALE', :phone, 'A+', true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 73 " + tag).executeUpdate();
            person(scientistUser, "ROLE_LAB_SCIENTIST");
            person(directorUser, "ROLE_LAB_DIRECTOR");
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(patientChartAccess.require(any(), any()))
            .thenAnswer(call -> entityManager.find(Patient.class, call.getArgument(0)));
    }

    private void person(UUID user, String role) {
        String tag = user.toString().substring(0, 8);
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, first_name, last_name, password_hash, email, phone_number, is_active, is_deleted,
                force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'Prenom', :last, 'x', :email, :phone, true, false, false, now(), now())
            """).setParameter("id", user).setParameter("username", "d3901-" + tag).setParameter("last", role)
            .setParameter("email", "d3901-" + tag + "@example.test").setParameter("phone", "+226" + tag)
            .executeUpdate();
        UUID assignment = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, hospital_id, is_active, assigned_at,
                created_at, updated_at, confirmation_code, confirmation_sent_at)
            SELECT :id, :u, r.id, :h, true, now(), now(), now(), :code, now() FROM security.roles r WHERE r.code = :role
            """).setParameter("id", assignment).setParameter("u", user).setParameter("h", hospital)
            .setParameter("code", tag).setParameter("role", role).executeUpdate();
        entityManager.createNativeQuery("""
            INSERT INTO hospital.staff (id, user_id, hospital_id, assignment_id, active, employment_type, job_title,
                created_at, updated_at)
            VALUES (:id, :u, :h, :a, true, 'FULL_TIME', 'NURSE', now(), now())
            """).setParameter("id", UUID.randomUUID()).setParameter("u", user).setParameter("h", hospital)
            .setParameter("a", assignment).executeUpdate();
    }

    private void grouped(AboGroup abo, RhFactor rh, AntibodyScreenResult screen, String correctionReason) {
        when(roleValidator.getCurrentUserId()).thenReturn(scientistUser);
        service.recordBloodGroup(PatientBloodGroupRequestDTO.builder().patientId(patient).aboGroup(abo).rhFactor(rh)
            .antibodyScreen(screen).specimenCollectedAt(LocalDateTime.now().minusMinutes(5))
            .expiresAt(LocalDateTime.now().plusHours(72)).correctionReason(correctionReason).build());
    }

    /** The record as every patient response is built from it, scoped to the hospital, after the commit. */
    private BloodGroupStatusDTO onTheRecord() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            PatientResponseDTO dto = patientMapper.toPatientDTO(entityManager.find(Patient.class, patient), hospital);
            return dto.getBloodGroup();
        });
    }

    /** The record as the chart and the safety strip read it. */
    private BloodGroupStatusDTO onTheChart() {
        return new TransactionTemplate(transactionManager).execute(status ->
            bloodGroupStatus.withAntibodies(entityManager.find(Patient.class, patient), hospital));
    }

    @Test
    @DisplayName("before the laboratory: the typed A+ is declared, not confirmed")
    void beforeTheLaboratory() {
        BloodGroupStatusDTO record = onTheRecord();

        assertThat(record.getDeclared()).isEqualTo("A+");
        assertThat(record.getConfirmed()).isNull();
        assertThat(record.isDiscrepancy()).isFalse();
        assertThat(onTheChart().getAlloImmunised()).isFalse();
    }

    @Test
    @DisplayName("the laboratory's B+ reaches the record at once, flagged against the typed A+, which stays as declared")
    void labGroupingUpdatesTheRecord() {
        grouped(AboGroup.B, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE, null);

        BloodGroupStatusDTO record = onTheRecord();
        assertThat(record.getConfirmed()).isEqualTo("B+");
        assertThat(record.getConfirmedAt()).isNotNull();
        assertThat(record.getConfirmedHospitalName()).startsWith("Hopital D3901");
        assertThat(record.getDeclared()).isEqualTo("A+");
        assertThat(record.isDiscrepancy()).isTrue();

        // The typed value was not touched by the grouping, and the grouping is not touched by a new typed value.
        String typed = new TransactionTemplate(transactionManager).execute(status ->
            (String) entityManager.createNativeQuery("SELECT blood_type FROM clinical.patients WHERE id = :id")
                .setParameter("id", patient).getSingleResult());
        assertThat(typed).isEqualTo("A+");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE clinical.patients SET blood_type = 'AB-' WHERE id = :id")
                .setParameter("id", patient).executeUpdate());
        BloodGroupStatusDTO retyped = onTheRecord();
        assertThat(retyped.getConfirmed()).isEqualTo("B+");
        assertThat(retyped.getDeclared()).isEqualTo("AB-");
        assertThat(retyped.isDiscrepancy()).isTrue();
    }

    @Test
    @DisplayName("a laboratory correction supersedes, and the record follows the current grouping")
    void correctionIsFollowed() {
        grouped(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE, null);
        assertThat(onTheRecord().isDiscrepancy()).isFalse();

        grouped(AboGroup.B, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE, "Sample mislabelled at collection");

        BloodGroupStatusDTO record = onTheRecord();
        assertThat(record.getConfirmed()).isEqualTo("B+");
        assertThat(record.isDiscrepancy()).isTrue();
    }

    @Test
    @DisplayName("the antibody the director identified (anti-E) is on the chart's read")
    void antibodyOnTheChart() {
        grouped(AboGroup.B, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE, null);
        assertThat(onTheChart().getAlloImmunised()).isTrue();
        assertThat(onTheChart().getAntibodySpecificities()).as("positive screen, not identified yet").isNull();

        when(roleValidator.getCurrentUserId()).thenReturn(directorUser);
        service.recordAntibodyWorkup(AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("anti-E").build());

        BloodGroupStatusDTO chart = onTheChart();
        assertThat(chart.getAlloImmunised()).isTrue();
        assertThat(chart.getAntibodySpecificities()).isEqualTo("anti-E");
        assertThat(chart.getConfirmed()).isEqualTo("B+");
        assertThat(onTheRecord().getAlloImmunised()).as("a list read does not look").isNull();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Patient knownPositive = entityManager.find(Patient.class, patient);
            var batch = patientMapper.toPatientDTOs(java.util.List.of(knownPositive), hospital).getFirst().getBloodGroup();
            var previousIndividual = patientMapper.toPatientDTO(knownPositive, hospital).getBloodGroup();
            assertThat(batch.getAlloImmunised()).as("bulk and previous individual mapper both leave antibodies unassessed")
                .isNull();
            assertThat(previousIndividual.getAlloImmunised()).isNull();
            assertThat(batch.getConfirmed()).isEqualTo(previousIndividual.getConfirmed());
        });
    }
    @Test
    @DisplayName("C-PERF: two and forty patient rows each prepare one scoped laboratory query, never forty")
    void patientCollectionsUseOneLaboratoryQuery() {
        grouped(AboGroup.B, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE, null);
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        ids.add(patient);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            for (int index = 1; index < 40; index++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                entityManager.createNativeQuery("""
                    INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth,
                        gender, phone_number_primary, blood_type, is_active, created_at, updated_at)
                    VALUES (:id, :u, 'Synthetic', 'Batch', DATE '1985-03-02', 'MALE', :phone,
                        'A+', true, now(), now())
                    """).setParameter("id", id).setParameter("u", UUID.randomUUID())
                    .setParameter("phone", "+226" + id.toString().substring(0, 8)).executeUpdate();
            }
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            java.util.List<Patient> patients = ids.stream().map(id -> entityManager.find(Patient.class, id)).toList();
            for (int size : new int[] {2, 40}) {
                BloodGroupStatements.COUNT.set(0);
                java.util.List<PatientResponseDTO> rows = patientMapper.toPatientDTOs(patients.subList(0, size), hospital);
                assertThat(BloodGroupStatements.COUNT.get()).as("laboratory queries for %s patients", size).isEqualTo(1);
                assertThat(rows).hasSize(size);
                assertThat(rows.getFirst().getBloodGroup().getConfirmed()).isEqualTo("B+");
                assertThat(rows.getFirst().getBloodGroup().isDiscrepancy()).isTrue();
                assertThat(rows.getLast().getBloodGroup().getConfirmed()).isNull();
                assertThat(rows.getLast().getBloodGroup().getDeclared()).isEqualTo("A+");
            }
            BloodGroupStatements.COUNT.set(0);
            assertThat(patientMapper.toPatientDTOs(patients, null))
                .allSatisfy(row -> assertThat(row.getBloodGroup().getConfirmed()).isNull());
            assertThat(BloodGroupStatements.COUNT.get()).isZero();
            BloodGroupStatements.COUNT.set(0);
            assertThat(patientMapper.toPatientDTOs(patients, UUID.randomUUID()))
                .allSatisfy(row -> assertThat(row.getBloodGroup().getConfirmed()).isNull());
            assertThat(BloodGroupStatements.COUNT.get()).isEqualTo(1);
            BloodGroupStatements.COUNT.remove();
        });
    }

}
