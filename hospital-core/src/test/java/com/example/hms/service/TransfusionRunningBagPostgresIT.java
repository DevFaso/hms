package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import com.example.hms.service.impl.TransfusionServiceImpl;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The emergency walk, on a real PostgreSQL with real transactions and lazy associations: an O negative
 * bag goes out with no crossmatch, is hung, and the crossmatch done afterwards comes back incompatible.
 * What a mock cannot show: the recipients are read inside the transaction, the callbacks run after the
 * commit with no session, and a failed notification leaves the result recorded.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransfusionServiceImpl.class, TransfusionMapper.class, TransfusionRunningBagPostgresIT.Config.class})
@DisplayName("a crossmatch result on a bag already running, on PostgreSQL")
class TransfusionRunningBagPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_running_bag")
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
        Clock transfusionTestClock() {
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

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID doctorUser = UUID.randomUUID();
    private final UUID nurseUser = UUID.randomUUID();
    private final UUID verifierUser = UUID.randomUUID();
    private final UUID labUser = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "R34-" + tag)
                .setParameter("name", "Hopital R34 " + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 70 " + tag).executeUpdate();
            staff(doctorUser);
            staff(nurseUser);
            staff(verifierUser);
            staff(labUser);
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(patientChartAccess.require(any(), any()))
            .thenAnswer(call -> entityManager.find(Patient.class, call.getArgument(0)));
    }

    private void staff(UUID user) {
        String tag = user.toString().substring(0, 8);
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, password_hash, email, phone_number, is_active, is_deleted,
                force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'x', :email, :phone, true, false, false, now(), now())
            """).setParameter("id", user).setParameter("username", "r34-" + tag)
            .setParameter("email", "r34-" + tag + "@example.test").setParameter("phone", "+226" + tag)
            .executeUpdate();
        entityManager.createNativeQuery("""
            INSERT INTO hospital.staff (id, user_id, hospital_id, assignment_id, active, employment_type, job_title,
                created_at, updated_at)
            VALUES (:id, :u, :h, :a, true, 'FULL_TIME', 'NURSE', now(), now())
            """).setParameter("id", UUID.randomUUID()).setParameter("u", user).setParameter("h", hospital)
            .setParameter("a", UUID.randomUUID()).executeUpdate();
    }

    private void actingAs(UUID user) {
        when(roleValidator.getCurrentUserId()).thenReturn(user);
    }

    /** The emergency walk up to the running bag: raised by the doctor, released by the lab, hung by the nurse. */
    private Walk runningEmergencyBag() {
        actingAs(doctorUser);
        TransfusionRequestResponseDTO request = service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("Haemorrhage")
            .urgency(TransfusionUrgency.EMERGENCY)
            .build());
        actingAs(labUser);
        BloodUnitResponseDTO bag = service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("R34-" + UUID.randomUUID().toString().substring(0, 12))
            .productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(AboGroup.O)
            .rhFactor(RhFactor.NEGATIVE)
            .expiresOn(LocalDate.now().plusDays(20))
            .build());
        service.issueUnit(request.getId(), bag.getId());
        actingAs(nurseUser);
        TransfusionAdministrationResponseDTO running = service.startAdministration(TransfusionAdministrationRequestDTO.builder()
            .requestId(request.getId())
            .bloodUnitId(bag.getId())
            .verifiedByUserId(verifierUser)
            .build());
        actingAs(labUser);
        return new Walk(request.getId(), bag.getId(), bag.getUnitNumber(), running.getId());
    }

    private record Walk(UUID requestId, UUID bagId, String unitNumber, UUID administrationId) {
    }

    private void incompatibleResult(Walk walk) {
        service.recordCrossmatch(walk.requestId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(walk.bagId()).compatible(Boolean.FALSE).incompatibilityReason("Anti-K").build());
    }

    private String unitStatus(UUID bagId) {
        return (String) entityManager.createNativeQuery("SELECT status FROM clinical.blood_units WHERE id = :id")
            .setParameter("id", bagId).getSingleResult();
    }

    @Test
    @DisplayName("the bag stays issued and running, the result shows on the request and the panel, the requester and the nurse are told")
    void anIncompatibleResultOnARunningBag() {
        Walk walk = runningEmergencyBag();

        incompatibleResult(walk);

        assertThat(unitStatus(walk.bagId())).as("not back at the lab").isEqualTo("ISSUED");
        TransfusionRequestResponseDTO detail = service.getRequest(walk.requestId());
        assertThat(detail.getCrossmatches()).singleElement()
            .satisfies(result -> assertThat(result.getCompatible()).isFalse());
        assertThat(detail.getUnits()).singleElement()
            .satisfies(unit -> assertThat(unit.getStatus().name()).isEqualTo("ISSUED"));
        List<TransfusionAdministrationResponseDTO> panel = service.listAdministrationsForPatient(patient);
        assertThat(panel).singleElement().satisfies(administration -> {
            assertThat(administration.getStatus().name()).as("not stopped: the clinician decides").isEqualTo("IN_PROGRESS");
            assertThat(administration.getCrossmatch().getCompatible()).isFalse();
            assertThat(administration.getCrossmatch().getIncompatibilityReason()).isEqualTo("Anti-K");
        });

        ArgumentCaptor<CreateInBasketItemRequest> items = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasketService, times(2)).createItemInNewTransaction(items.capture());
        Set<UUID> recipients = items.getAllValues().stream()
            .map(CreateInBasketItemRequest::getRecipientUserId).collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrder(doctorUser, nurseUser);
        assertThat(items.getAllValues()).allSatisfy(item -> {
            assertThat(item.getPriority()).isEqualTo(InBasketPriority.URGENT);
            assertThat(item.getPatientName()).contains("Awa");
            assertThat(item.getBodyKey()).isEqualTo("transfusion.crossmatch.incompatible.running.inBasketBody");
            assertThat(item.getBodyArgs()).contains(walk.unitNumber());
            assertThat(item.getBodyArgs()[1].toString()).contains("Awa");
            assertThat(item.getHospitalId()).isEqualTo(hospital);
        });
    }

    @Test
    @DisplayName("the clinician can then stop the bag, or let it finish: both still work")
    void theBagStillClosesEitherWay() {
        Walk stopped = runningEmergencyBag();
        incompatibleResult(stopped);
        service.stopAdministration(stopped.administrationId(), "Incompatible crossmatch");
        assertThat(unitStatus(stopped.bagId())).isEqualTo("DISCARDED");

        Walk finished = runningEmergencyBag();
        incompatibleResult(finished);
        service.completeAdministration(finished.administrationId(), 300);
        assertThat(unitStatus(finished.bagId())).isEqualTo("TRANSFUSED");
    }

    @Test
    @DisplayName("a failed notification never undoes the recorded result")
    void aFailedNotificationLeavesTheResult() {
        Walk walk = runningEmergencyBag();
        doThrow(new IllegalStateException("in-basket down")).when(inBasketService).createItemInNewTransaction(any());

        incompatibleResult(walk);

        assertThat(service.getRequest(walk.requestId()).getCrossmatches()).singleElement()
            .satisfies(result -> assertThat(result.getCompatible()).isFalse());
        verify(inBasketService, times(2)).createItemInNewTransaction(any());
    }

    @Test
    @DisplayName("the emergency release writes its own audit row: who, which unit, which request, nothing else")
    void theEmergencyReleaseIsAudited() {
        Walk walk = runningEmergencyBag();

        ArgumentCaptor<AuditEventRequestDTO> rows = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, atLeastOnce()).logEvent(rows.capture());
        AuditEventRequestDTO row = rows.getAllValues().stream()
            .filter(candidate -> candidate.getEventType() == AuditEventType.EMERGENCY_BLOOD_RELEASED)
            .findFirst().orElseThrow();
        assertThat(row.getUserId()).isEqualTo(labUser);
        assertThat(row.getHospitalId()).isEqualTo(hospital);
        assertThat(row.getResourceId()).isEqualTo(walk.requestId().toString());
        assertThat(row.getEventDescription()).contains(walk.bagId().toString()).contains(walk.requestId().toString())
            .doesNotContain("Awa").doesNotContain("Kabore");
    }

    @Test
    @DisplayName("a compatible result on a running bag is recorded too, and tells nobody")
    void aCompatibleResultTellsNobody() {
        Walk walk = runningEmergencyBag();
        // The patient is typed meanwhile: a compatible verdict stands on a current screen.
        service.recordBloodGroup(com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO.builder()
            .patientId(patient).aboGroup(AboGroup.A).rhFactor(RhFactor.POSITIVE)
            .antibodyScreen(com.example.hms.enums.AntibodyScreenResult.NEGATIVE)
            .specimenCollectedAt(java.time.LocalDateTime.now().minusMinutes(5))
            .expiresAt(java.time.LocalDateTime.now().plusHours(72))
            .build());

        service.recordCrossmatch(walk.requestId(), CrossmatchRequestDTO.builder()
            .bloodUnitId(walk.bagId()).compatible(Boolean.TRUE).build());

        assertThat(unitStatus(walk.bagId())).isEqualTo("ISSUED");
        verify(inBasketService, never()).createItemInNewTransaction(any());
    }
}
