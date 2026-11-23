package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
import com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.service.support.PositiveAntibodyScreenPolicy;
import com.example.hms.service.transfusion.PositiveAntibodyScreenService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3351 (r70), rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN, on a real PostgreSQL built by Liquibase (V362's
 * table and columns). The rule's service opens no transaction of its own: it runs inside the transaction of the
 * {@code TransfusionServiceImpl} call it guards. This walks that, with no transaction around the test, so every
 * read below is a new transaction reading what the one before committed: the flag is on record, a refusal leaves
 * nothing behind, the biologist's record is kept, and the tasks are filed only after the commit.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransfusionServiceImpl.class, PositiveAntibodyScreenService.class, TransfusionMapper.class,
    com.example.hms.security.EncryptionKeyHolder.class, TransfusionPositiveAntibodyScreenPostgresIT.Config.class})
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@DisplayName("positive antibody screen, on PostgreSQL")
class TransfusionPositiveAntibodyScreenPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_antibody_screen")
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

    @TestConfiguration
    static class Config {
        @Bean
        Clock antibodyScreenTestClock() {
            return Clock.systemDefaultZone();
        }
    }

    @Autowired private TransfusionService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private PatientChartAccess patientChartAccess;
    @MockitoBean private InBasketService inBasketService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    /** The account of cross-hospital reads: in the slice as a double, so the reads it is handed can be checked. */
    @MockitoBean private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID doctorUser = UUID.randomUUID();
    private final UUID directorUser = UUID.randomUUID();
    private final UUID techUser = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "R70A-" + tag)
                .setParameter("name", "Hopital R70A " + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, gender, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', 'FEMALE', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 72 " + tag).executeUpdate();
            person(doctorUser, "ROLE_DOCTOR");
            person(directorUser, "ROLE_LAB_DIRECTOR");
            person(techUser, "ROLE_LAB_TECHNICIAN");
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(patientChartAccess.require(any(), any()))
            .thenAnswer(call -> entityManager.find(Patient.class, call.getArgument(0)));
        clearInvocations(inBasketService, auditEventLogService, reachRecorder);
    }

    private void person(UUID user, String role) {
        String tag = user.toString().substring(0, 8);
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, first_name, last_name, password_hash, email, phone_number, is_active, is_deleted,
                force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'Prenom', :last, 'x', :email, :phone, true, false, false, now(), now())
            """).setParameter("id", user).setParameter("username", "r70a-" + tag).setParameter("last", role)
            .setParameter("email", "r70a-" + tag + "@example.test").setParameter("phone", "+226" + tag)
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

    private void actingAs(UUID user) {
        when(roleValidator.getCurrentUserId()).thenReturn(user);
    }

    private TransfusionRequestResponseDTO request(TransfusionUrgency urgency) {
        actingAs(doctorUser);
        TransfusionRequestResponseDTO request = service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient).productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(1)
            .indication("Anaemia").urgency(urgency).build());
        actingAs(techUser);
        return request;
    }

    private BloodUnitResponseDTO bag() {
        return service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("R70A-" + UUID.randomUUID().toString().substring(0, 12))
            .productType(BloodProductType.PACKED_RED_CELLS).aboGroup(AboGroup.A).rhFactor(RhFactor.POSITIVE)
            .expiresOn(LocalDate.now().plusDays(20)).build());
    }

    private void screened(AntibodyScreenResult screen) {
        service.recordBloodGroup(PatientBloodGroupRequestDTO.builder().patientId(patient).aboGroup(AboGroup.A)
            .rhFactor(RhFactor.POSITIVE).antibodyScreen(screen).specimenCollectedAt(LocalDateTime.now().minusMinutes(5))
            .expiresAt(LocalDateTime.now().plusHours(72)).build());
    }

    private static CrossmatchRequestDTO compatibilised(UUID bagId) {
        return CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(Boolean.TRUE)
            .antiglobulinMethod(Boolean.TRUE).antigenNegative(Boolean.TRUE).rhKellMatched(Boolean.TRUE)
            .sampleCollectedAt(LocalDateTime.now().minusHours(2)).build();
    }

    private long rows(String table) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM clinical." + table + " WHERE hospital_id = :h")
            .setParameter("h", hospital).getSingleResult()).longValue();
    }

    private String bagStatus(UUID bagId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM clinical.blood_units WHERE id = :id")
            .setParameter("id", bagId).getSingleResult();
    }

    @Test
    @DisplayName("a positive screen is on record after the commit, a later negative screen clears nothing, and the biologist is asked once the screen is saved")
    void theFlagIsKept() {
        actingAs(techUser);
        assertThat(service.getAntibodyStatus(patient).isAlloImmunised()).isFalse();

        screened(AntibodyScreenResult.POSITIVE);

        AntibodyStatusResponseDTO flagged = service.getAntibodyStatus(patient);
        assertThat(flagged.isAlloImmunised()).isTrue();
        assertThat(flagged.isIdentified()).isFalse();
        assertThat(flagged.getPositiveScreenAt()).isNotNull();
        assertThat(service.getCurrentBloodGroup(patient).isAlloImmunised()).isTrue();
        ArgumentCaptor<CreateInBasketItemRequest> task = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(task.capture());
        assertThat(task.getValue().getRecipientUserId()).as("the biologist, nobody else").isEqualTo(directorUser);
        assertThat(task.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(task.getValue().getTitleKey()).isEqualTo(PositiveAntibodyScreenService.IDENTIFY_TITLE_KEY);
        assertThat(task.getValue().getPatientId()).isEqualTo(patient);

        // The repeat screen reads negative: the first one is superseded, and the patient stays allo-immunised.
        screened(AntibodyScreenResult.NEGATIVE);

        assertThat(service.getCurrentBloodGroup(patient).getAntibodyScreen()).isEqualTo(AntibodyScreenResult.NEGATIVE);
        assertThat(service.getCurrentBloodGroup(patient).isAlloImmunised()).isTrue();
        assertThat(service.getAntibodyStatus(patient).isAlloImmunised()).isTrue();
        assertThat(service.getRequest(request(TransfusionUrgency.ROUTINE).getId()).isAlloImmunised()).isTrue();
    }

    @Test
    @DisplayName("routine red cells: a refused crossmatch and a refused work-up write nothing; the biologist's record is kept and opens the compatibilised crossmatch and the issue")
    void theWorkupIsKeptAndLiftsTheHold() {
        actingAs(techUser);
        screened(AntibodyScreenResult.POSITIVE);
        UUID requestId = request(TransfusionUrgency.ROUTINE).getId();
        UUID bagId = bag().getId();
        CrossmatchRequestDTO complete = compatibilised(bagId);

        // No identification yet: even a complete crossmatch is refused, and nothing of it is left behind.
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, complete))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getMessageKey())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.WORKUP.messageKey()));
        assertThat(rows("transfusion_crossmatches")).isZero();
        assertThat(bagStatus(bagId)).isEqualTo("AVAILABLE");

        // The technician is not the biologist: refused, and no record is written.
        AntibodyWorkupRequestDTO identified = AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities(" Anti-K ").build();
        assertThatThrownBy(() -> service.recordAntibodyWorkup(identified)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThat(rows("patient_antibody_workups")).isZero();

        actingAs(directorUser);
        AntibodyStatusResponseDTO recorded = service.recordAntibodyWorkup(identified);
        actingAs(techUser);

        assertThat(recorded.isIdentified()).isTrue();
        assertThat(rows("patient_antibody_workups")).isEqualTo(1);
        AntibodyStatusResponseDTO readBack = service.getAntibodyStatus(patient);
        assertThat(readBack.getAntibodySpecificities()).isEqualTo("Anti-K");
        assertThat(readBack.getWorkups()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AntibodyWorkupStatus.IDENTIFIED);
            assertThat(row.getRecordedByDisplay()).contains("ROLE_LAB_DIRECTOR");
            assertThat(row.getRecordedAt()).isNotNull();
        });

        // Identified, but the crossmatch says nothing of the technique or the unit: still refused.
        CrossmatchRequestDTO bare = CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(Boolean.TRUE).build();
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, bare))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getField())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.ANTIGLOBULIN.field()));
        assertThat(rows("transfusion_crossmatches")).isZero();

        service.recordCrossmatch(requestId, complete);
        assertThat(rows("transfusion_crossmatches")).isEqualTo(1);
        assertThat(service.getRequest(requestId).getCrossmatches()).singleElement().satisfies(row -> {
            assertThat(row.getCompatible()).isTrue();
            assertThat(row.getAntiglobulinMethod()).isTrue();
            assertThat(row.getAntigenNegative()).isTrue();
            assertThat(row.getRhKellMatched()).isTrue();
            assertThat(row.getSampleCollectedAt()).isNotNull();
        });

        service.issueUnit(requestId, bagId);
        assertThat(bagStatus(bagId)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("a referral decision serves the one request it names, and is refused with nothing written when it names none")
    void aReferralServesOneRequest() {
        actingAs(techUser);
        screened(AntibodyScreenResult.POSITIVE);
        UUID served = request(TransfusionUrgency.ROUTINE).getId();
        UUID other = request(TransfusionUrgency.ROUTINE).getId();
        clearInvocations(inBasketService);
        actingAs(directorUser);

        AntibodyWorkupRequestDTO unnamed = AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.REFERRED).referralCentre("CNTS Ouagadougou")
            .decisionReason("Hb 5 g/dL, the answer takes three days").build();
        assertThatThrownBy(() -> service.recordAntibodyWorkup(unnamed))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("transfusionRequestId"));
        assertThat(rows("patient_antibody_workups")).isZero();

        service.recordAntibodyWorkup(AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.REFERRED).referralCentre("CNTS Ouagadougou")
            .decisionReason("Hb 5 g/dL, the answer takes three days").transfusionRequestId(served).build());
        actingAs(techUser);

        assertThat(rows("patient_antibody_workups")).isEqualTo(1);
        assertThat(service.getRequest(served).isAntibodyWorkupServes()).isTrue();
        assertThat(service.getRequest(other).isAntibodyWorkupServes()).isFalse();
        assertThat(service.getAntibodyStatus(patient).isIdentified()).isFalse();
        // Recording the biologist's decision asks nobody anything more.
        verify(inBasketService, never()).createItemInNewTransaction(any());

        UUID bagId = bag().getId();
        service.recordCrossmatch(served, compatibilised(bagId));
        UUID otherBag = bag().getId();
        CrossmatchRequestDTO forTheOther = compatibilised(otherBag);
        assertThatThrownBy(() -> service.recordCrossmatch(other, forTheOther))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getMessageKey())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.WORKUP.messageKey()));
        assertThat(rows("transfusion_crossmatches")).isEqualTo(1);
    }

    @Test
    @DisplayName("review L4: a positive screen after an identification asks the biologist again and holds routine red cells until the work-up of that screen is recorded")
    void aPositiveScreenAfterAnIdentificationHoldsAgain() {
        actingAs(techUser);
        screened(AntibodyScreenResult.POSITIVE);
        AntibodyWorkupRequestDTO identified = AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("Anti-K").build();
        actingAs(directorUser);
        service.recordAntibodyWorkup(identified);
        actingAs(techUser);
        UUID requestId = request(TransfusionUrgency.ROUTINE).getId();
        UUID bagId = bag().getId();
        assertThat(service.getRequest(requestId).isAntibodyWorkupServes()).isTrue();
        clearInvocations(inBasketService);

        // The repeat screen of the next sample is positive again: it may be a second antibody.
        screened(AntibodyScreenResult.POSITIVE);

        ArgumentCaptor<CreateInBasketItemRequest> task = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(task.capture());
        assertThat(task.getValue().getRecipientUserId()).isEqualTo(directorUser);
        assertThat(task.getValue().getTitleKey()).isEqualTo(PositiveAntibodyScreenService.IDENTIFY_TITLE_KEY);
        assertThat(service.getRequest(requestId).isAntibodyWorkupServes()).isFalse();
        // The antibody already known stays on the patient's record all the while.
        assertThat(service.getAntibodyStatus(patient).getAntibodySpecificities()).isEqualTo("Anti-K");
        CrossmatchRequestDTO complete = compatibilised(bagId);
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, complete))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getMessageKey())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.WORKUP.messageKey()));
        assertThat(rows("transfusion_crossmatches")).isZero();

        actingAs(directorUser);
        service.recordAntibodyWorkup(AntibodyWorkupRequestDTO.builder().patientId(patient)
            .status(AntibodyWorkupStatus.IDENTIFIED).antibodySpecificities("Anti-K, anti-E").build());
        actingAs(techUser);

        assertThat(service.getRequest(requestId).isAntibodyWorkupServes()).isTrue();
        service.recordCrossmatch(requestId, complete);
        assertThat(rows("transfusion_crossmatches")).isEqualTo(1);
        assertThat(service.getAntibodyStatus(patient).getAntibodySpecificities()).isEqualTo("Anti-K, anti-E");
    }

    @Test
    @DisplayName("review M3: an EMERGENCY request issued on a standing plain « compatible » crossmatch leaves at once, and the biologist is asked for the crossmatch afterwards")
    void anEmergencyOnAPlainCrossmatchOwesTheCrossmatchAfterwards() {
        actingAs(techUser);
        screened(AntibodyScreenResult.POSITIVE);
        actingAs(doctorUser);
        UUID requestId = service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient).productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(1)
            .indication("Haemorrhage").urgency(TransfusionUrgency.EMERGENCY)
            .vitalUrgencyLevel(com.example.hms.enums.VitalUrgencyLevel.UV).vitalUrgencyReason("Choc hémorragique")
            .preTransfusionSampleDrawn(Boolean.TRUE).build()).getId();
        actingAs(techUser);
        UUID bagId = bag().getId();
        // Around an emergency the result is recorded as found: a plain « compatible », no technique, no attestation.
        service.recordCrossmatch(requestId, CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(Boolean.TRUE).build());
        clearInvocations(inBasketService);

        service.issueUnit(requestId, bagId);

        assertThat(bagStatus(bagId)).isEqualTo("ISSUED");
        // It left on its crossmatch: no emergency release row. But that crossmatch is not « compatibilisé ».
        assertThat(service.getRequest(requestId).getEmergencyReleases()).isEmpty();
        ArgumentCaptor<CreateInBasketItemRequest> task = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(task.capture());
        assertThat(task.getValue().getRecipientUserId()).isEqualTo(directorUser);
        assertThat(task.getValue().getTitleKey()).isEqualTo(PositiveAntibodyScreenService.RETROSPECTIVE_TITLE_KEY);
        assertThat(task.getValue().getReferenceId()).isEqualTo(bagId);
        assertThat(task.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
    }

    // ── Owner decision 2026-11-22: the flag follows the patient across hospitals ───────────────────

    private static final String HOSPITAL_A = "CHU A (autre établissement)";

    /** Hospital A, where the patient screened positive seven months ago and anti-K was identified the next day. */
    private UUID anotherHospitalThatKnowsTheAntibody() {
        UUID hospitalA = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospitalA).setParameter("code", "R70X-" + hospitalA.toString().substring(0, 8))
                .setParameter("name", HOSPITAL_A).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patient_blood_groups (id, patient_id, hospital_id, abo_group, rh_factor,
                    antibody_screen, antibody_detail, performed_at, superseded, notes, created_at, updated_at)
                VALUES (:id, :p, :h, 'A', 'POSITIVE', 'POSITIVE', 'Panel A: réactions 2+ cellules 3 et 7',
                    now() - interval '210 days', false, 'Note interne de A', now(), now())
                """).setParameter("id", UUID.randomUUID()).setParameter("p", patient).setParameter("h", hospitalA)
                .executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patient_antibody_workups (id, patient_id, hospital_id, status,
                    antibody_specificities, recorded_by_user_id, recorded_by_display, recorded_at, notes,
                    created_at, updated_at)
                VALUES (:id, :p, :h, 'IDENTIFIED', 'anti-K', :u, 'Dr Biologiste de A', now() - interval '209 days',
                    'Titre 1/64, compte rendu interne de A', now(), now())
                """).setParameter("id", UUID.randomUUID()).setParameter("p", patient).setParameter("h", hospitalA)
                .setParameter("u", UUID.randomUUID()).executeUpdate();
        });
        return hospitalA;
    }

    /** The patient opted out of sharing their record between hospitals, and has not revoked it. */
    private void optedOutOfRecordSharing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patient_record_sharing_optouts (id, patient_id, opted_out_at, created_at, updated_at)
                VALUES (:id, :p, now() - interval '30 days', now(), now())
                """).setParameter("id", UUID.randomUUID()).setParameter("p", patient).executeUpdate());
    }

    @Test
    @DisplayName("owner decision: positive at hospital A, negative later at hospital B: still flagged at B, though the patient opted out of record sharing; B sees the antibody, the date and A's name and nothing of A's text, and each read is accounted against A")
    void theFlagFollowsThePatientAcrossHospitals() {
        UUID hospitalA = anotherHospitalThatKnowsTheAntibody();
        optedOutOfRecordSharing();
        actingAs(techUser);

        // Never screened at B: the flag is already known here (the request form reads it before any group exists).
        AntibodyStatusResponseDTO known = service.getAntibodyStatus(patient);
        assertThat(known.isAlloImmunised()).isTrue();
        assertThat(known.isIdentified()).isTrue();
        assertThat(known.getAntibodySpecificities()).isEqualTo("anti-K");
        assertThat(known.getIdentifiedHospitalName()).isEqualTo(HOSPITAL_A);
        assertThat(known.getIdentifiedAt()).isNotNull();
        assertThat(known.getPositiveScreenAt()).isNotNull();
        assertThat(known.getPositiveScreenHospitalName()).isEqualTo(HOSPITAL_A);
        assertThat(known.getOtherHospitalNames()).containsExactly(HOSPITAL_A);
        assertThat(known.getWorkups()).singleElement().satisfies(fromA -> {
            assertThat(fromA.isOtherHospital()).isTrue();
            assertThat(fromA.getAntibodySpecificities()).isEqualTo("anti-K");
            assertThat(fromA.getHospitalName()).isEqualTo(HOSPITAL_A);
            assertThat(fromA.getRecordedAt()).isNotNull();
            assertThat(fromA.getNotes()).isNull();
            assertThat(fromA.getRecordedByDisplay()).isNull();
            assertThat(fromA.getId()).isNull();
        });
        assertThat(known.toString()).doesNotContain("Titre").doesNotContain("Biologiste de A")
            .doesNotContain("Panel A").doesNotContain("Note interne");

        // B screens the patient: negative (the antibody is no longer detectable). Nobody is asked to identify.
        screened(AntibodyScreenResult.NEGATIVE);
        verify(inBasketService, never()).createItemInNewTransaction(any());
        assertThat(service.getCurrentBloodGroup(patient).getAntibodyScreen()).isEqualTo(AntibodyScreenResult.NEGATIVE);
        assertThat(service.getCurrentBloodGroup(patient).isAlloImmunised()).as("still flagged at B").isTrue();

        // A routine request at B is held by the rule: a plain « compatible » is refused, the compatibilised one
        // is written, because the antibody A identified is known here.
        UUID requestId = request(TransfusionUrgency.ROUTINE).getId();
        assertThat(service.getRequest(requestId).isAlloImmunised()).isTrue();
        assertThat(service.getRequest(requestId).isAntibodyWorkupServes()).isTrue();
        UUID bagId = bag().getId();
        CrossmatchRequestDTO plain = CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(Boolean.TRUE).build();
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, plain))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> assertThat(refusal.getField())
                .isEqualTo(PositiveAntibodyScreenPolicy.Missing.ANTIGLOBULIN.field()));
        service.recordCrossmatch(requestId, compatibilised(bagId));
        assertThat(rows("transfusion_crossmatches")).isEqualTo(1);

        // An emergency for this patient is declared at B too.
        actingAs(doctorUser);
        TransfusionRequestRequestDTO undeclared = TransfusionRequestRequestDTO.builder()
            .patientId(patient).productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(1)
            .indication("Haemorrhage").urgency(TransfusionUrgency.EMERGENCY).build();
        assertThatThrownBy(() -> service.createRequest(undeclared))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("vitalUrgencyLevel"));
        actingAs(techUser);

        // Every time the flag was shown at B, the read of A's record was accounted, against A and nobody else:
        // that account is also what the patient reads in « Qui a consulté mon dossier ».
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Long>> perSource = ArgumentCaptor.forClass(java.util.Map.class);
        verify(reachRecorder, org.mockito.Mockito.atLeast(5)).recordReach(org.mockito.ArgumentMatchers.eq(patient),
            org.mockito.ArgumentMatchers.eq(hospital), any(), org.mockito.ArgumentMatchers.isNull(),
            perSource.capture(), org.mockito.ArgumentMatchers.eq(PositiveAntibodyScreenService.REACH_DESCRIPTION));
        assertThat(perSource.getAllValues()).allSatisfy(reach ->
            assertThat(reach).containsOnlyKeys(hospitalA.toString()));
        // Nothing was written at A, and B wrote no antibody record of its own.
        assertThat(rows("patient_antibody_workups")).isZero();
    }

    @Test
    @DisplayName("a patient known at one hospital only: the flag names no other hospital, and no read of another hospital's record is accounted")
    void aFlagHeldHereNamesNoOtherHospital() {
        actingAs(techUser);
        screened(AntibodyScreenResult.POSITIVE);

        AntibodyStatusResponseDTO known = service.getAntibodyStatus(patient);

        assertThat(known.isAlloImmunised()).isTrue();
        assertThat(known.getOtherHospitalNames()).isEmpty();
        assertThat(known.getPositiveScreenHospitalName()).startsWith("Hopital R70A");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Long>> perSource = ArgumentCaptor.forClass(java.util.Map.class);
        verify(reachRecorder, org.mockito.Mockito.atLeastOnce()).recordReach(any(), any(), any(), any(),
            perSource.capture(), any());
        assertThat(perSource.getAllValues()).allSatisfy(reach -> assertThat(reach).isEmpty());
    }
}
