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
@Import({TransfusionServiceImpl.class, com.example.hms.service.transfusion.OPositiveReleaseAuthorityService.class, com.example.hms.service.transfusion.OPositiveReleaseNotices.class,
    com.example.hms.service.transfusion.PositiveAntibodyScreenService.class, TransfusionMapper.class, com.example.hms.security.EncryptionKeyHolder.class,
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

    @Autowired private com.example.hms.service.transfusion.OPositiveReleaseNotices positiveNotices;
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

    private boolean stockShows(UUID hospitalId, UUID patientId, BloodProductType product, AboGroup abo, RhFactor rh, LocalDate today) {
        return !unitRepository.findStockForPatient(hospitalId, patientId, product, abo, rh, today).isEmpty();
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
        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        bag(AboGroup.O, RhFactor.POSITIVE);
        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        bag(AboGroup.O, RhFactor.NEGATIVE);
        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isTrue();
        assertThat(stockShows(hospital, patient, BloodProductType.WHOLE_BLOOD, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        assertThat(stockShows(UUID.randomUUID(), patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today)).isFalse();
        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, today.plusDays(20)))
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
        assertThat(first.getNegativeStock()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).containsExactly(negative.getId());
        assertThat(first.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).containsExactlyInAnyOrder(negative.getId(), positiveId);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, decision)).hasMessageContaining("cannot be obtained in time");

        service.issueUnit(requestId, negative.getId());
        EmergencyReleaseOptionsDTO after = service.emergencyReleaseOptions(requestId);
        assertThat(after.getNegativeStock()).isEmpty();
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
    @DisplayName("independent review: an incompatible result on one request stops the bag on the patient's next request, and O negative found incompatible no longer holds O positive back")
    void incompatibleFollowsThePatient() {
        TransfusionRequestResponseDTO first = emergencyRequest();
        UUID negative = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        result(first.getId(), negative, false);
        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, LocalDate.now()))
            .as("not available to this patient").isFalse();
        assertThat(stockShows(hospital, UUID.randomUUID(), BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, LocalDate.now()))
            .as("still available to anyone else").isTrue();

        TransfusionRequestResponseDTO second = emergencyRequest();
        UUID secondId = second.getId();
        assertThatThrownBy(() -> service.issueUnit(secondId, negative)).hasMessageContaining("on another request");
        EmergencyReleaseOptionsDTO options = service.emergencyReleaseOptions(secondId);
        assertThat(options.getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).doesNotContain(negative);
        assertThat(options.getNegativeStock()).isEmpty();

        UUID positive = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        service.emergencyRelease(secondId, documented(positive, EmergencyReleaseType.O_POSITIVE_DECISION));
        assertThat(status(positive)).isEqualTo("ISSUED");

        result(secondId, negative, false);
        service.emergencyRelease(secondId, documented(negative, EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE));
        assertThat(status(negative)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("independent review: an O negative bag returned to the lab is still in stock, and holds O positive back")
    void aReturnedONegativeCounts() {
        TransfusionRequestResponseDTO first = emergencyRequest();
        UUID negative = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(first.getId(), negative);
        signedInAsDoctor(() -> service.cancelRequest(first.getId(), "Bleeding controlled"));
        assertThat(status(negative)).isEqualTo("RETURNED");

        TransfusionRequestResponseDTO second = emergencyRequest();
        UUID secondId = second.getId();
        UUID positive = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        EmergencyReleaseRequestDTO decision = documented(positive, EmergencyReleaseType.O_POSITIVE_DECISION);
        assertThat(service.emergencyReleaseOptions(secondId).getNegativeStock()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).containsExactly(negative);
        assertThatThrownBy(() -> service.emergencyRelease(secondId, decision)).hasMessageContaining("cannot be obtained in time");
    }

    @Test
    @DisplayName("O negative showing and stated not obtainable in time: the bag goes out and hangs, row, detail and audit say so with the units shown")
    void theStatementReleasesTheBag() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        BloodUnitResponseDTO shown = bag(AboGroup.O, RhFactor.NEGATIVE);
        UUID positive = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        EmergencyReleaseRequestDTO stated = documented(positive, EmergencyReleaseType.O_POSITIVE_DECISION);
        stated.setNegativeUnobtainable(Boolean.TRUE);
        clearInvocations(auditEventLogService);

        service.emergencyRelease(requestId, stated);

        assertThat(status(positive)).isEqualTo("ISSUED");
        assertThat(status(shown.getId())).isEqualTo("AVAILABLE");
        // Two rows since V380: the release, and the O positive decision behind it.
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(audit.capture());
        assertThat(audit.getAllValues()).extracting(AuditEventRequestDTO::getEventType).containsExactlyInAnyOrder(
            com.example.hms.enums.AuditEventType.EMERGENCY_BLOOD_RELEASED, com.example.hms.enums.AuditEventType.O_POSITIVE_RELEASE_DECIDED);
        assertThat(audit.getAllValues()).filteredOn(row -> row.getEventType() == com.example.hms.enums.AuditEventType.EMERGENCY_BLOOD_RELEASED)
            .singleElement().satisfies(row -> assertThat(row.getEventDescription())
                .contains("stated not obtainable in time").contains(shown.getId().toString()));
        hang(requestId, positive);
        assertThat(service.getRequest(requestId).getEmergencyReleases()).singleElement().satisfies(row -> {
            assertThat(row.isNegativeUnobtainableStated()).isTrue();
            assertThat(row.getNegativeUnitsShown()).isEqualTo(shown.getUnitNumber());
        });
    }

    @Test
    @DisplayName("independent review: a release of a stock bag waits for a cancel of the request, then finds it closed")
    void aReleaseWaitsForTheCancel() throws Exception {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> release = new java.util.concurrent.atomic.AtomicReference<>();
            signedInAsDoctor(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.cancelRequest(requestId, "Bleeding controlled");
                release.set(other.submit(() -> service.issueUnit(requestId, bagId)));
                assertThatThrownBy(() -> release.get().get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .as("waits on the request's lock").isInstanceOf(java.util.concurrent.TimeoutException.class);
            }));
            assertThatThrownBy(() -> release.get().get(20, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class).hasMessageContaining("closed");
        } finally {
            other.shutdownNow();
        }
        assertThat(status(bagId)).isEqualTo("AVAILABLE");
        assertThat(service.getRequest(requestId).getStatus().name()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("independent review: finishing the last bag waits for a release in flight, so the request stays open and the new bag can hang")
    void aCompletionWaitsForTheRelease() throws Exception {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID first = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        UUID second = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(requestId, first);
        TransfusionAdministrationResponseDTO running = hang(requestId, first);
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> completion = new java.util.concurrent.atomic.AtomicReference<>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.issueUnit(requestId, second);
                completion.set(other.submit(() -> service.completeAdministration(running.getId(), 300)));
                assertThatThrownBy(() -> completion.get().get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .as("waits on the request's lock").isInstanceOf(java.util.concurrent.TimeoutException.class);
            });
            completion.get().get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertThat(service.getRequest(requestId).getStatus().name()).isEqualTo("ISSUED");
        hang(requestId, second);
    }

    @Test
    @DisplayName("independent review: a repeat screen waits for a cancel and does not write over it")
    void aRepeatScreenDoesNotUndoACancel() throws Exception {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> screen = new java.util.concurrent.atomic.AtomicReference<>();
            signedInAsDoctor(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.cancelRequest(requestId, "Bleeding controlled");
                screen.set(other.submit(() -> typed(AboGroup.A, RhFactor.POSITIVE, AntibodyScreenResult.NEGATIVE)));
                assertThatThrownBy(() -> screen.get().get(1, java.util.concurrent.TimeUnit.SECONDS))
                    .as("waits on the request's lock").isInstanceOf(java.util.concurrent.TimeoutException.class);
            }));
            screen.get().get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        TransfusionRequestResponseDTO detail = service.getRequest(requestId);
        assertThat(detail.getStatus().name()).isEqualTo("CANCELLED");
        assertThat(detail.getCancelReason()).isEqualTo("Bleeding controlled");
    }

    @Test
    @DisplayName("independent review: a bag that was ever hung is not O negative in stock")
    void aHungBagIsNotStock() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID negative = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(request.getId(), negative);
        hang(request.getId(), negative);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE clinical.blood_units SET status = 'RETURNED' WHERE id = :id")
                .setParameter("id", negative).executeUpdate());

        assertThat(stockShows(hospital, patient, BloodProductType.PACKED_RED_CELLS, AboGroup.O, RhFactor.NEGATIVE, LocalDate.now())).isFalse();
        assertThat(service.emergencyReleaseOptions(request.getId()).getUnits()).extracting(EmergencyReleaseOptionsDTO.Unit::getId).doesNotContain(negative);
    }

    private void signedInAsDoctor(Runnable work) {
        com.example.hms.model.User requester = new com.example.hms.model.User();
        requester.setId(doctorUser);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(requester, null, List.of()));
        try {
            work.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("cancelling a request takes the bag locks with its held bags and still returns them")
    void cancelTakesTheLocks() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID bagId = bag(AboGroup.O, RhFactor.NEGATIVE).getId();
        service.issueUnit(request.getId(), bagId);
        actingAs(doctorUser);
        com.example.hms.model.User requester = new com.example.hms.model.User();
        requester.setId(doctorUser);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(requester, null, List.of()));
        try {
            service.cancelRequest(request.getId(), "Bleeding controlled");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(status(bagId)).isEqualTo("RETURNED");
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

    // ── V380: the O positive authority rule, on the tables and constraints Liquibase built ──

    private List<CreateInBasketItemRequest> filed() {
        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService, org.mockito.Mockito.atLeast(0)).createItem(items.capture());
        return items.getAllValues();
    }

    private void sql(String statement, UUID id) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery(statement).setParameter("id", id).executeUpdate());
    }

    @Test
    @DisplayName("V380: a woman's O positive is refused to the issuer alone, goes out on a physician's order, is countersigned, reviewed, followed up, and flags her for good")
    void oPositiveAuthorityWalk() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.POSITIVE).getId();

        // This hospital wrote no protocol: every patient is the high-risk row.
        EmergencyReleaseOptionsDTO options = service.emergencyReleaseOptions(requestId);
        assertThat(options.getPositiveRule().getRiskTier()).isEqualTo(com.example.hms.enums.OPositiveRiskTier.HIGH_RISK);
        assertThat(options.getPositiveRule().getTierReason()).isEqualTo(com.example.hms.enums.OPositiveTierReason.NO_ACTIVE_PROTOCOL);
        assertThat(options.getPositiveRule().getPhysicians())
            .extracting(com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO::userId)
            .containsExactlyInAnyOrder(doctorUser, directorUser);

        EmergencyReleaseRequestDTO alone = documented(bagId, EmergencyReleaseType.O_POSITIVE_DECISION);
        alone.setAuthoriserUserId(techUser);
        assertThatThrownBy(() -> service.emergencyRelease(requestId, alone)).hasMessageContaining("not the person issuing");
        assertThat(status(bagId)).isEqualTo("AVAILABLE");

        clearInvocations(inBasketService, auditEventLogService);
        service.emergencyRelease(requestId, documented(bagId, EmergencyReleaseType.O_POSITIVE_DECISION));
        assertThat(status(bagId)).isEqualTo("ISSUED");
        assertThat(filed()).extracting(CreateInBasketItemRequest::getReferenceType, CreateInBasketItemRequest::getRecipientUserId)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("TRANSFUSION_O_POSITIVE_REVIEW", directorUser),
                org.assertj.core.groups.Tuple.tuple("TRANSFUSION_O_POSITIVE_COUNTERSIGN", doctorUser),
                org.assertj.core.groups.Tuple.tuple("TRANSFUSION_RHD_EXPOSURE_FOLLOW_UP", doctorUser));

        TransfusionRequestResponseDTO detail = service.getRequest(requestId);
        com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO decision =
            detail.getEmergencyReleases().get(0).getPositiveDecision();
        assertThat(decision).isNotNull();
        assertThat(decision.getRiskTier()).isEqualTo(com.example.hms.enums.OPositiveRiskTier.HIGH_RISK);
        assertThat(decision.getDecisionBasis()).isEqualTo(com.example.hms.enums.OPositiveDecisionBasis.PHYSICIAN_ORDER);
        assertThat(decision.getDecidedByName()).contains("ROLE_DOCTOR");
        assertThat(decision.getIssuedByName()).contains("ROLE_LAB_TECHNICIAN");
        assertThat(decision.isVerbalOrder()).isTrue();
        assertThat(decision.getPatientSex()).isEqualTo("FEMALE");
        assertThat(decision.getPatientAgeYears()).isEqualTo(java.time.Period.between(LocalDate.of(1990, 6, 15), LocalDate.now()).getYears());
        assertThat(decision.getNegativeStatus()).isEqualTo(com.example.hms.enums.ONegativeStatus.OUT_OF_STOCK);
        assertThat(decision.getCountersignDueAt()).isEqualTo(decision.getIssuedAt().plusHours(24));
        assertThat(decision.isRhdExposure()).isTrue();
        assertThat(detail.isRhdPositiveExposed()).isTrue();
        assertThat(detail.getRhdPositiveExposedHospitalName()).startsWith("Hopital R34E");
        UUID decisionId = decision.getId();
        assertThat(service.listPositiveReleases(true))
            .extracting(com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO::getId).containsExactly(decisionId);

        // The issuer never countersigns; a physician does; the biologist reviews; a physician follows up.
        assertThatThrownBy(() -> service.countersignPositiveRelease(decisionId, false))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        actingAs(doctorUser);
        assertThat(service.countersignPositiveRelease(decisionId, false).getCountersignedByName()).contains("ROLE_DOCTOR");
        assertThatThrownBy(() -> service.reviewPositiveRelease(decisionId, "seen", false))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        actingAs(directorUser);
        assertThat(service.reviewPositiveRelease(decisionId, "Anti-D discussed with the obstetrician", false).getReviewedByName())
            .contains("ROLE_LAB_DIRECTOR");
        Object rawNote = entityManager.createNativeQuery(
            "SELECT review_note FROM clinical.o_positive_release_decisions WHERE id = :id").setParameter("id", decisionId).getSingleResult();
        assertThat((String) rawNote).as("the biologist's words are encrypted at rest").doesNotContain("Anti-D discussed");
        actingAs(doctorUser);
        com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO followed = service.recordPositiveFollowUp(decisionId,
            com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO.builder()
                .antiDDecision(com.example.hms.enums.AntiDDecision.ANTI_D_IMMUNOGLOBULIN).patientInformed(Boolean.TRUE).build());
        assertThat(followed.getAntiDDecision()).isEqualTo(com.example.hms.enums.AntiDDecision.ANTI_D_IMMUNOGLOBULIN);
        assertThat(followed.getPatientInformedAt()).isNotNull();
        assertThat(followed.getAntibodyScreenDueFrom()).isEqualTo(decision.getIssuedAt().toLocalDate().plusMonths(3));

        // An antibody screen the next week is too early for the follow-up; the flag stays whatever is done.
        actingAs(techUser);
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        TransfusionRequestResponseDTO after = service.getRequest(requestId);
        assertThat(after.getEmergencyReleases().get(0).getPositiveDecision().getAntibodyScreenDoneAt()).isNull();
        assertThat(after.isRhdPositiveExposed()).isTrue();
        assertThat(service.getCurrentBloodGroup(patient).isRhdPositiveExposed()).isTrue();

        // Once the window has opened, a real screen closes that part; a grouping with the screen not done does not.
        sql("UPDATE clinical.o_positive_release_decisions SET antibody_screen_due_from = current_date - 1 WHERE id = :id", decisionId);
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NOT_DONE);
        assertThat(service.getRequest(requestId).getEmergencyReleases().get(0).getPositiveDecision().getAntibodyScreenDoneAt()).isNull();
        typed(AboGroup.A, RhFactor.NEGATIVE, AntibodyScreenResult.NEGATIVE);
        assertThat(service.getRequest(requestId).getEmergencyReleases().get(0).getPositiveDecision().getAntibodyScreenDoneAt()).isNotNull();
        assertThat(service.getRequest(requestId).isRhdPositiveExposed()).as("the flag is permanent").isTrue();
    }

    @Test
    @DisplayName("V380: the biologist sets the protocol; with no physician reachable the bag still goes out, and the overdue countersignature reaches the director once")
    void protocolAndNoPhysicianReachable() {
        actingAs(techUser);
        assertThatThrownBy(() -> service.setPositiveProtocol(
            new com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO(null, true, 50, false, 12)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        actingAs(directorUser);
        service.setPositiveProtocol(new com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO(null, true, 50, false, 12));
        assertThat(service.getPositiveProtocol())
            .isEqualTo(new com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO(hospital, true, 50, false, 12));
        // Set again: the one row of the hospital is changed, not duplicated.
        service.setPositiveProtocol(new com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO(null, true, 50, false, 12));

        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        assertThat(service.emergencyReleaseOptions(requestId).getPositiveRule().getTierReason())
            .isEqualTo(com.example.hms.enums.OPositiveTierReason.FEMALE_AT_OR_BELOW_CUTOFF);

        clearInvocations(inBasketService);
        service.emergencyRelease(requestId, EmergencyReleaseRequestDTO.builder().bloodUnitId(bagId)
            .releaseType(EmergencyReleaseType.O_POSITIVE_DECISION).reason(REASON).physicianUnreachable(Boolean.TRUE).build());
        assertThat(status(bagId)).as("never blocks: the technician alone released it").isEqualTo("ISSUED");

        com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO decision =
            service.getRequest(requestId).getEmergencyReleases().get(0).getPositiveDecision();
        assertThat(decision.getDecisionBasis()).isEqualTo(com.example.hms.enums.OPositiveDecisionBasis.AUTHORITY_PENDING);
        assertThat(decision.getDecidedByName()).isNull();
        // Nobody authorised it yet: the technician is its issuer, never its authoriser (V266's constraint, relaxed for this).
        assertThat(service.getRequest(requestId).getEmergencyReleases().get(0).getAuthoriserName()).isNull();
        assertThat(service.getRequest(requestId).getEmergencyReleases().get(0).getIssuedByName()).contains("ROLE_LAB_TECHNICIAN");
        assertThat(decision.getCountersignDueAt()).isEqualTo(decision.getIssuedAt().plusHours(12));
        assertThat(filed()).anySatisfy(item -> {
            assertThat(item.getReferenceType()).isEqualTo("TRANSFUSION_O_POSITIVE_REVIEW");
            assertThat(item.getRecipientUserId()).isEqualTo(directorUser);
            assertThat(item.getBodyKey()).isEqualTo(
                com.example.hms.service.transfusion.OPositiveReleaseNotices.REVIEW_PENDING_BODY_KEY);
        });

        // The notices were filed after the commit, in their own transaction, and the row says so.
        assertThat(positiveNotices.pendingIds()).isEmpty();
        // The bag is out and nobody recorded hanging it: the flag is shown, as unconfirmed, with or without a grouping.
        TransfusionRequestResponseDTO out = service.getRequest(requestId);
        assertThat(out.isRhdPositiveExposed()).isTrue();
        assertThat(out.getRhdPositiveExposureConfirmed()).isFalse();
        assertThat(out.getEmergencyReleases().get(0).getPositiveDecision().getExposureState()).isEqualTo("UNCONFIRMED");
        assertThat(service.getAntibodyStatus(patient)).satisfies(status -> {
            assertThat(status.isRhdPositiveExposed()).as("no grouping at this hospital: the flag still shows").isTrue();
            assertThat(status.getRhdPositiveExposureConfirmed()).isFalse();
        });

        assertThat(positiveNotices.overdueCountersignIds()).as("not yet overdue").isEmpty();
        sql("UPDATE clinical.o_positive_release_decisions SET countersign_due_at = now() - interval '1 hour' WHERE id = :id",
            decision.getId());
        assertThat(positiveNotices.overdueCountersignIds()).containsExactly(decision.getId());

        // An alert that cannot be delivered rolls the mark back: the row is still due at the next sweep.
        when(inBasketService.createItem(any())).thenThrow(new IllegalStateException("in-basket down"));
        UUID decisionId = decision.getId();
        assertThatThrownBy(() -> positiveNotices.escalateOverdueCountersign(decisionId)).isInstanceOf(IllegalStateException.class);
        assertThat(positiveNotices.overdueCountersignIds()).as("nobody was told: not marked as told").containsExactly(decisionId);

        org.mockito.Mockito.reset(inBasketService);
        assertThat(positiveNotices.escalateOverdueCountersign(decisionId)).isEqualTo(1);
        ArgumentCaptor<CreateInBasketItemRequest> escalated = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService).createItem(escalated.capture());
        assertThat(escalated.getValue().getReferenceType()).isEqualTo("TRANSFUSION_O_POSITIVE_COUNTERSIGN_OVERDUE");
        assertThat(escalated.getValue().getRecipientUserId()).isEqualTo(directorUser);
        assertThat(escalated.getValue().getTitle()).doesNotContain("{");
        assertThat(positiveNotices.overdueCountersignIds()).as("told once").isEmpty();
        assertThat(positiveNotices.escalateOverdueCountersign(decisionId)).isZero();
        assertThat(service.listPositiveReleases(true).get(0).isCountersignOverdue()).isTrue();

        // Hung: the exposure is confirmed, whatever becomes of the bag afterwards.
        hang(requestId, bagId);
        assertThat(service.getRequest(requestId).getRhdPositiveExposureConfirmed()).isTrue();
        assertThat(service.getAntibodyStatus(patient).getRhdPositiveExposureConfirmed()).isTrue();
    }

    @Test
    @DisplayName("V380: a bag issued and returned unused leaves no exposure on the patient; a delivery that failed is owed and delivered once")
    void aReturnedBagExposedNobodyAndAnOwedNoticeIsDelivered() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        // The in-basket is down when the release commits: the release stands, its notices stay owed.
        when(inBasketService.createItem(any())).thenThrow(new IllegalStateException("in-basket down"));
        service.emergencyRelease(requestId, documented(bagId, EmergencyReleaseType.O_POSITIVE_DECISION));
        assertThat(status(bagId)).isEqualTo("ISSUED");
        UUID decisionId = service.getRequest(requestId).getEmergencyReleases().get(0).getPositiveDecision().getId();
        assertThat(positiveNotices.pendingIds()).containsExactly(decisionId);
        assertThatThrownBy(() -> positiveNotices.deliver(decisionId)).isInstanceOf(IllegalStateException.class);
        assertThat(positiveNotices.pendingIds()).as("rolled back: still owed").containsExactly(decisionId);

        org.mockito.Mockito.reset(inBasketService);
        assertThat(positiveNotices.deliver(decisionId)).isEqualTo(3);
        assertThat(positiveNotices.pendingIds()).isEmpty();
        assertThat(positiveNotices.deliver(decisionId)).as("filed once").isZero();
        verify(inBasketService, times(3)).createItem(any());

        assertThat(service.getRequest(requestId).isRhdPositiveExposed()).isTrue();
        // The bag comes back to the laboratory unused (never hung): nobody was exposed.
        sql("UPDATE clinical.blood_units SET status = 'RETURNED', request_id = NULL WHERE id = :id", bagId);
        assertThat(status(bagId)).isEqualTo("RETURNED");
        assertThat(service.getAntibodyStatus(patient).isRhdPositiveExposed()).isFalse();
        assertThat(service.listPositiveReleases(false)).singleElement()
            .satisfies(row -> assertThat(row.getExposureState()).isEqualTo("NOT_GIVEN"));
        // Recording a follow-up on a bag that came back closes it instead of refusing the only action left.
        actingAs(doctorUser);
        assertThat(service.recordPositiveFollowUp(decisionId,
            com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO.builder().patientInformed(Boolean.TRUE).build()))
            .satisfies(row -> {
                assertThat(row.getDisposition()).as("closed with its reason, not refused").isEqualTo("RETURNED_UNUSED");
                assertThat(row.getPatientInformedAt()).isNull();
            });
        actingAs(techUser);

        // The same bag goes out again on another request and is hung there: that says nothing about this release.
        UUID other = emergencyRequest().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE clinical.blood_units SET status = 'ISSUED', request_id = :r WHERE id = :id")
                .setParameter("r", other).setParameter("id", bagId).executeUpdate());
        hang(other, bagId);
        assertThat(service.getAntibodyStatus(patient).isRhdPositiveExposed())
            .as("the exposure is the release's, not the bag's").isFalse();
        assertThat(service.listPositiveReleases(false)).singleElement()
            .satisfies(row -> assertThat(row.getExposureState()).isEqualTo("NOT_GIVEN"));
    }

    @Test
    @DisplayName("V380: under the protocol an adult man's O positive leaves on the plain issue, with its routine review and no flag")
    void lowRiskUnderTheProtocol() {
        actingAs(directorUser);
        service.setPositiveProtocol(new com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO(null, true, 50, false, 24));
        sql("UPDATE clinical.patients SET gender = 'MALE' WHERE id = :id", patient);
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID bagId = bag(AboGroup.O, RhFactor.POSITIVE).getId();

        clearInvocations(inBasketService);
        service.issueUnit(request.getId(), bagId);

        assertThat(status(bagId)).isEqualTo("ISSUED");
        TransfusionRequestResponseDTO detail = service.getRequest(request.getId());
        com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO decision =
            detail.getEmergencyReleases().get(0).getPositiveDecision();
        assertThat(decision.getRiskTier()).isEqualTo(com.example.hms.enums.OPositiveRiskTier.LOW_RISK);
        assertThat(decision.getDecisionBasis()).isEqualTo(com.example.hms.enums.OPositiveDecisionBasis.PROTOCOL);
        assertThat(decision.getDecidedByName()).contains("ROLE_LAB_TECHNICIAN");
        assertThat(decision.getCountersignDueAt()).isNull();
        assertThat(decision.getReviewPriority()).isEqualTo(com.example.hms.enums.OPositiveReviewPriority.ROUTINE);
        assertThat(detail.isRhdPositiveExposed()).isFalse();
        assertThat(filed()).singleElement().satisfies(item -> {
            assertThat(item.getReferenceType()).isEqualTo("TRANSFUSION_O_POSITIVE_REVIEW");
            assertThat(item.getRecipientUserId()).isEqualTo(directorUser);
            assertThat(item.getPriority()).isEqualTo(com.example.hms.enums.InBasketPriority.NORMAL);
        });
    }

    @Test
    @DisplayName("pr1346: a bag discarded unused closes its follow-up; released again on the SAME request it is a new release, and only that one is confirmed when hung")
    void aReissueOnTheSameRequestIsANewRelease() {
        TransfusionRequestResponseDTO request = emergencyRequest();
        UUID requestId = request.getId();
        UUID bagId = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        service.emergencyRelease(requestId, documented(bagId, EmergencyReleaseType.O_POSITIVE_DECISION));
        UUID first = service.getRequest(requestId).getEmergencyReleases().get(0).getPositiveDecision().getId();

        // A follow-up recorded while the bag is out is accepted (the anti-D window does not wait for the hang).
        actingAs(doctorUser);
        service.recordPositiveFollowUp(first, com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO.builder()
            .antiDDecision(com.example.hms.enums.AntiDDecision.ANTI_D_IMMUNOGLOBULIN).build());
        actingAs(techUser);

        // The bag comes back to the laboratory unused, by hand in the database: no hook wrote it.
        sql("UPDATE clinical.blood_units SET status = 'RETURNED', request_id = NULL WHERE id = :id", bagId);
        assertThat(service.getAntibodyStatus(patient).isRhdPositiveExposed()).isFalse();

        // Released again on the same request: a new release row and a new decision; the first is closed.
        service.emergencyRelease(requestId, documented(bagId, EmergencyReleaseType.O_POSITIVE_DECISION));
        List<com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO> both = service.listPositiveReleases(false);
        assertThat(both).hasSize(2);
        assertThat(both).filteredOn(row -> row.getId().equals(first)).singleElement().satisfies(row -> {
            assertThat(row.getDisposition()).isEqualTo("SUPERSEDED");
            assertThat(row.getExposureState()).isEqualTo("NOT_GIVEN");
            assertThat(row.getAntiDDecision()).as("what was written stays as written").isNotNull();
        });

        hang(requestId, bagId);
        both = service.listPositiveReleases(false);
        assertThat(both).filteredOn(row -> row.getId().equals(first)).singleElement()
            .satisfies(row -> assertThat(row.getExposureState()).as("not revived by the later hang").isEqualTo("NOT_GIVEN"));
        assertThat(both).filteredOn(row -> !row.getId().equals(first)).singleElement().satisfies(row -> {
            assertThat(row.getDisposition()).isEqualTo("ADMINISTERED");
            assertThat(row.getExposureState()).isEqualTo("CONFIRMED");
        });
        assertThat(service.getAntibodyStatus(patient).getRhdPositiveExposureConfirmed()).isTrue();

        // A second bag, discarded by the laboratory before anybody hung it: its follow-up tasks leave the in-baskets.
        UUID otherBag = bag(AboGroup.O, RhFactor.POSITIVE).getId();
        service.emergencyRelease(requestId, documented(otherBag, EmergencyReleaseType.O_POSITIVE_DECISION));
        UUID third = service.listPositiveReleases(false).stream().filter(row -> row.getBloodUnitId().equals(otherBag))
            .findFirst().orElseThrow().getId();
        clearInvocations(inBasketService);
        service.discardUnit(otherBag, "Broken seal");
        verify(inBasketService).removeOpenItemsForReference(third, "TRANSFUSION_RHD_EXPOSURE_FOLLOW_UP");
        assertThat(service.listPositiveReleases(false)).filteredOn(row -> row.getId().equals(third)).singleElement()
            .satisfies(row -> assertThat(row.getDisposition()).isEqualTo("RETURNED_UNUSED"));
    }
}
