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
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventDescription()).contains("stated not obtainable in time").contains(shown.getId().toString());
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
}
