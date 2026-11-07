package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.impl.TransfusionServiceImpl;
import com.example.hms.service.support.EmergencyReleasePolicy;
import com.example.hms.service.support.PatientChartAccess;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The emergency release walks on a real PostgreSQL built by Liquibase: V266's table and constraints, the
 * new queries with every parameter their callers use, lazy associations read in the transaction, and
 * callbacks after the commit with no session.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransfusionServiceImpl.class, TransfusionMapper.class, com.example.hms.security.EncryptionKeyHolder.class,
    TransfusionEmergencyReleasePostgresIT.Config.class})
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@DisplayName("emergency blood release, on PostgreSQL")
class TransfusionEmergencyReleasePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_emergency_release")
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
        Clock emergencyReleaseTestClock() {
            return Clock.systemDefaultZone();
        }
    }

    private static final String REASON = "Massive haemorrhage";

    @Autowired private TransfusionService service;
    @Autowired private BloodUnitRepository unitRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private PatientChartAccess patientChartAccess;
    @MockitoBean private InBasketService inBasketService;
    @MockitoBean private AuditEventLogService auditEventLogService;

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID doctorUser = UUID.randomUUID();
    private final UUID directorUser = UUID.randomUUID();
    private final UUID techUser = UUID.randomUUID();
    private final UUID nurseUser = UUID.randomUUID();
    private final UUID verifierUser = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "R34E-" + tag)
                .setParameter("name", "Hopital R34E " + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, gender, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', 'FEMALE', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 71 " + tag).executeUpdate();
            person(doctorUser, "ROLE_DOCTOR");
            person(directorUser, "ROLE_LAB_DIRECTOR");
            person(techUser, "ROLE_LAB_TECHNICIAN");
            person(nurseUser, "ROLE_NURSE");
            person(verifierUser, "ROLE_NURSE");
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(patientChartAccess.require(any(), any()))
            .thenAnswer(call -> entityManager.find(Patient.class, call.getArgument(0)));
        clearInvocations(inBasketService, auditEventLogService);
    }

    private void person(UUID user, String role) {
        String tag = user.toString().substring(0, 8);
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, first_name, last_name, password_hash, email, phone_number, is_active, is_deleted,
                force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'Prenom', :last, 'x', :email, :phone, true, false, false, now(), now())
            """).setParameter("id", user).setParameter("username", "r34e-" + tag).setParameter("last", role)
            .setParameter("email", "r34e-" + tag + "@example.test").setParameter("phone", "+226" + tag)
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

    private TransfusionRequestResponseDTO emergencyRequest() {
        actingAs(doctorUser);
        TransfusionRequestResponseDTO request = service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient).productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(2)
            .indication("Haemorrhage").urgency(TransfusionUrgency.EMERGENCY).build());
        actingAs(techUser);
        return request;
    }

    private BloodUnitResponseDTO bag(AboGroup abo, RhFactor rh) {
        return service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("R34E-" + UUID.randomUUID().toString().substring(0, 12))
            .productType(BloodProductType.PACKED_RED_CELLS).aboGroup(abo).rhFactor(rh)
            .expiresOn(LocalDate.now().plusDays(20)).build());
    }

    private void typed(AboGroup abo, RhFactor rh, AntibodyScreenResult screen) {
        service.recordBloodGroup(PatientBloodGroupRequestDTO.builder().patientId(patient).aboGroup(abo).rhFactor(rh)
            .antibodyScreen(screen).specimenCollectedAt(LocalDateTime.now().minusMinutes(5))
            .expiresAt(LocalDateTime.now().plusHours(72)).build());
    }

    private void result(UUID requestId, UUID bagId, boolean compatible) {
        service.recordCrossmatch(requestId, CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(compatible)
            .incompatibilityReason(compatible ? null : "Anti-K").build());
    }

    private EmergencyReleaseRequestDTO documented(UUID bagId, EmergencyReleaseType type) {
        return EmergencyReleaseRequestDTO.builder().bloodUnitId(bagId).releaseType(type).reason(REASON)
            .authoriserUserId(doctorUser).build();
    }

    private TransfusionAdministrationResponseDTO hang(UUID requestId, UUID bagId) {
        actingAs(nurseUser);
        TransfusionAdministrationResponseDTO running = service.startAdministration(TransfusionAdministrationRequestDTO.builder()
            .requestId(requestId).bloodUnitId(bagId).verifiedByUserId(verifierUser).build());
        actingAs(techUser);
        return running;
    }

    private String status(UUID bagId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM clinical.blood_units WHERE id = :id")
            .setParameter("id", bagId).getSingleResult();
    }

    @Test
    @DisplayName("the queries answer for this hospital, this role, and this kind of bag")
    void theQueries() {
        actingAs(techUser);
        assertThat(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospital, EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES))
            .extracting(com.example.hms.model.User::getId).containsExactlyInAnyOrder(doctorUser, directorUser);
        assertThat(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(UUID.randomUUID(), EmergencyReleasePolicy.INCOMPATIBLE_AUTHORISER_ROLE_CODES)).isEmpty();
        assertThat(assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospital, EmergencyReleasePolicy.O_POSITIVE_DECIDER_ROLE_CODES))
            .extracting(com.example.hms.model.User::getId).containsExactlyInAnyOrder(doctorUser, directorUser, techUser);

        LocalDate today = LocalDate.now();
        assertThat(unitRepository.existsAvailable(hospital, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        bag(AboGroup.O, RhFactor.POSITIVE);
        assertThat(unitRepository.existsAvailable(hospital, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        bag(AboGroup.O, RhFactor.NEGATIVE);
        assertThat(unitRepository.existsAvailable(hospital, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isTrue();
        assertThat(unitRepository.existsAvailable(hospital, BloodProductType.WHOLE_BLOOD, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        assertThat(unitRepository.existsAvailable(UUID.randomUUID(), BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        assertThat(unitRepository.existsAvailable(hospital, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today.plusDays(20)))
            .as("a bag's last day counts as expired").isFalse();
    }

    @Test
    @DisplayName("O negative first for a woman; with none left a documented O positive goes out, hangs, and the detail shows it all, the reason encrypted at rest")
    void oPositiveDecisionWalk() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        BloodUnitResponseDTO negative = bag(AboGroup.O, RhFactor.NEGATIVE);
        BloodUnitResponseDTO positive = bag(AboGroup.O, RhFactor.POSITIVE);
        UUID requestId = request.getId();
        UUID positiveId = positive.getId();
        EmergencyReleaseRequestDTO decision = documented(positiveId, EmergencyReleaseType.O_POSITIVE_DECISION);

        EmergencyReleaseOptionsDTO first = service.emergencyReleaseOptions(requestId);
        assertThat(first.isONegativeFirst()).isTrue();
        assertThat(first.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).containsExactly(negative.getId());
        assertThatThrownBy(() -> service.emergencyRelease(requestId, decision)).hasMessageContaining("first");

        service.issueUnit(requestId, negative.getId());
        EmergencyReleaseOptionsDTO after = service.emergencyReleaseOptions(requestId);
        assertThat(after.isONegativeFirst()).isFalse();
        assertThat(after.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).contains(positiveId);

        service.emergencyRelease(requestId, decision);
        assertThat(status(positiveId)).isEqualTo("ISSUED");

        hang(requestId, positiveId);

        TransfusionRequestResponseDTO detail = service.getRequest(requestId);
        assertThat(detail.getEmergencyReleases()).hasSize(2);
        assertThat(detail.getEmergencyReleases().get(0)).satisfies(row -> {
            assertThat(row.getReleaseType()).isEqualTo(EmergencyReleaseType.O_POSITIVE_DECISION);
            assertThat(row.getReason()).isEqualTo(REASON);
            assertThat(row.getAuthoriserName()).contains("ROLE_DOCTOR");
            assertThat(row.getIssuedByName()).contains("ROLE_LAB_TECHNICIAN");
            assertThat(row.getBloodUnitId()).isEqualTo(positiveId);
        });
        assertThat(detail.getEmergencyReleases().get(1).getReleaseType()).isEqualTo(EmergencyReleaseType.UNCROSSMATCHED);
        Object raw = entityManager.createNativeQuery(
                "SELECT reason FROM clinical.emergency_blood_releases WHERE blood_unit_id = :id AND request_id = :r")
            .setParameter("id", positiveId).setParameter("r", requestId).getSingleResult();
        assertThat(raw.toString()).startsWith("gcm1:").doesNotContain("haemorrhage");
    }

    @Test
    @DisplayName("incompatible: ordinary issue refused, the action releases the returned bag, the result still reads incompatible, the director is told, the bag hangs, and no later result replaces it")
    void serologicallyIncompatibleWalk() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.POSITIVE);
        BloodUnitResponseDTO bag = bag(AboGroup.A, RhFactor.POSITIVE);
        BloodUnitResponseDTO excluded = bag(AboGroup.B, RhFactor.POSITIVE);
        UUID requestId = request.getId();
        UUID bagId = bag.getId();
        UUID excludedId = excluded.getId();
        result(requestId, bagId, true);
        result(requestId, bagId, false);
        result(requestId, excludedId, false);
        assertThat(status(bagId)).as("found incompatible, back at the lab").isEqualTo("RETURNED");
        assertThatThrownBy(() -> service.issueUnit(requestId, bagId)).isInstanceOf(BusinessException.class);

        EmergencyReleaseOptionsDTO options = service.emergencyReleaseOptions(requestId);
        assertThat(options.isAntibodyWarning()).isTrue();
        assertThat(options.getIncompatibleUnits()).filteredOn(EmergencyReleaseOptionsDTO.IncompatibleUnit::isReleasable)
            .extracting(EmergencyReleaseOptionsDTO.IncompatibleUnit::getId).containsExactly(bagId);
        assertThat(options.getAuthorisers()).extracting(com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO::userId)
            .containsExactlyInAnyOrder(doctorUser, directorUser);

        EmergencyReleaseRequestDTO groupExcluded = documented(excludedId, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, groupExcluded)).hasMessageContaining("group excludes");

        clearInvocations(inBasketService, auditEventLogService);
        service.emergencyRelease(requestId, documented(bagId, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE));

        assertThat(status(bagId)).isEqualTo("ISSUED");
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(items.capture());
        assertThat(items.getValue().getRecipientUserId()).isEqualTo(directorUser);
        assertThat(items.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(items.getValue().getBodyArgs()[2].toString()).contains("ROLE_DOCTOR");
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.EMERGENCY_BLOOD_RELEASED);
        assertThat(audit.getValue().getEventDescription()).contains("serologically incompatible").doesNotContain("haemorrhage");

        hang(requestId, bagId);

        TransfusionRequestResponseDTO detail = service.getRequest(requestId);
        assertThat(detail.getCrossmatches()).filteredOn(row -> row.getBloodUnitId().equals(bagId)).singleElement()
            .satisfies(row -> {
                assertThat(row.getCompatible()).as("still reads incompatible").isFalse();
                assertThat(row.getIncompatibilityReason()).isEqualTo("Anti-K");
            });
        assertThat(detail.getEmergencyReleases()).singleElement().satisfies(row -> {
            assertThat(row.getReleaseType()).isEqualTo(EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE);
            assertThat(row.getReason()).isEqualTo(REASON);
            assertThat(row.getAuthoriserName()).contains("ROLE_DOCTOR");
            assertThat(row.getIssuedByName()).contains("ROLE_LAB_TECHNICIAN");
            assertThat(row.getIssuedAt()).isNotNull();
            assertThat(row.getBloodUnitId()).isEqualTo(bagId);
            assertThat(row.isAntibodyWarning()).isTrue();
        });

        CrossmatchRequestDTO overwrite = CrossmatchRequestDTO.builder().bloodUnitId(bagId).compatible(Boolean.TRUE).build();
        assertThatThrownBy(() -> service.recordCrossmatch(requestId, overwrite)).hasMessageContaining("result stands");
    }

    @Test
    @DisplayName("a failed notification never undoes the release")
    void aFailedNotificationLeavesTheRelease() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE);
        UUID bagId = bag(AboGroup.A, RhFactor.POSITIVE).getId();
        result(request.getId(), bagId, false);
        doThrow(new IllegalStateException("down")).when(inBasketService).createItemInNewTransaction(any());

        service.emergencyRelease(request.getId(), documented(bagId, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE));

        assertThat(status(bagId)).isEqualTo("ISSUED");
        assertThat(service.getRequest(request.getId()).getEmergencyReleases()).hasSize(1);
    }

    @Test
    @DisplayName("gap 1: a result after the bag finished is recorded on the closed request, moves nothing, tells the requester and the nurse; a late compatible one cannot then replace it")
    void aResultAfterTheBagFinished() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(requestId, bagId);
        TransfusionAdministrationResponseDTO running = hang(requestId, bagId);
        service.completeAdministration(running.getId(), 300);
        assertThat(service.getRequest(requestId).getStatus().name()).isEqualTo("COMPLETED");
        clearInvocations(inBasketService);

        result(requestId, bagId, false);

        assertThat(status(bagId)).isEqualTo("TRANSFUSED");
        TransfusionRequestResponseDTO detail = service.getRequest(requestId);
        assertThat(detail.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(detail.getCrossmatches()).singleElement().satisfies(row -> assertThat(row.getCompatible()).isFalse());
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService, times(2)).createItemInNewTransaction(items.capture());
        assertThat(items.getAllValues()).extracting(CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(doctorUser, nurseUser);
        assertThat(items.getAllValues()).allSatisfy(item -> assertThat(item.getBodyKey())
            .isEqualTo(TransfusionServiceImpl.INCOMPATIBLE_FINISHED_BODY_KEY));
    }

    @Test
    @DisplayName("gap 1: the same on a stopped bag")
    void aResultAfterTheBagWasStopped() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(requestId, bagId);
        TransfusionAdministrationResponseDTO running = hang(requestId, bagId);
        service.stopAdministration(running.getId(), "Fever");
        clearInvocations(inBasketService);

        result(requestId, bagId, false);

        assertThat(status(bagId)).isEqualTo("DISCARDED");
        verify(inBasketService, times(2)).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("gap 3: no requester on the request, the bag not hanging: the lab director is told")
    void noRequesterTellsTheDirector() {
        actingAs(UUID.randomUUID());
        TransfusionRequestResponseDTO request = service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient).productType(BloodProductType.PACKED_RED_CELLS).unitsRequested(1)
            .indication("Haemorrhage").urgency(TransfusionUrgency.EMERGENCY).build());
        actingAs(techUser);
        UUID bagId = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(request.getId(), bagId);
        verify(inBasketService, never()).createItemInNewTransaction(any());

        result(request.getId(), bagId, false);

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItemInNewTransaction(items.capture());
        assertThat(items.getValue().getRecipientUserId()).isEqualTo(directorUser);
        assertThat(List.of(status(bagId))).containsExactly("RETURNED");
    }
}
