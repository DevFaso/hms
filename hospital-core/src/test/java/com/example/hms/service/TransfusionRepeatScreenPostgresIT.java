package com.example.hms.service;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.transfusion.BloodUnitRequestDTO;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupRequestDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * QA CX-011: a repeat type and screen was refused as "this record already
 * exists". Hibernate flushes INSERTs before UPDATEs, so the new current row
 * reached the partial unique index {@code uq_blood_group_current} while the row
 * it supersedes still read superseded=false. H2 tables are built from the
 * entities and carry no such index, so only a real PostgreSQL built by Liquibase
 * can show it. Each call below runs in its own transaction, as the API does.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TransfusionServiceImpl.class, TransfusionMapper.class, TransfusionRepeatScreenPostgresIT.Config.class})
@DisplayName("CX-011: a repeat type and screen supersedes the current one, on PostgreSQL")
class TransfusionRepeatScreenPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_repeat_screen")
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

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "CX11-" + tag)
                .setParameter("name", "Hopital CX11 " + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 70 " + tag).executeUpdate();
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(patientChartAccess.require(any(), any()))
            .thenAnswer(call -> entityManager.find(Patient.class, call.getArgument(0)));
    }

    private PatientBloodGroupRequestDTO screen(AboGroup abo, RhFactor rh, String correctionReason) {
        return PatientBloodGroupRequestDTO.builder()
            .patientId(patient)
            .aboGroup(abo)
            .rhFactor(rh)
            .antibodyScreen(AntibodyScreenResult.NEGATIVE)
            .specimenCollectedAt(LocalDateTime.now().minusMinutes(5))
            .expiresAt(LocalDateTime.now().plusHours(72))
            .correctionReason(correctionReason)
            .build();
    }

    private long currentRows() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM clinical.patient_blood_groups WHERE patient_id = :p AND superseded = false")
            .setParameter("p", patient).getSingleResult()).longValue();
    }

    private TransfusionRequestResponseDTO request() {
        return service.createRequest(TransfusionRequestRequestDTO.builder()
            .patientId(patient)
            .productType(BloodProductType.PACKED_RED_CELLS)
            .unitsRequested(1)
            .indication("CX-011")
            .urgency(TransfusionUrgency.ROUTINE)
            .build());
    }

    private BloodUnitResponseDTO unit(AboGroup abo, RhFactor rh) {
        return service.receiveUnit(BloodUnitRequestDTO.builder()
            .unitNumber("CX11-" + UUID.randomUUID().toString().substring(0, 12))
            .productType(BloodProductType.PACKED_RED_CELLS)
            .aboGroup(abo)
            .rhFactor(rh)
            .expiresOn(LocalDate.now().plusDays(20))
            .build());
    }

    private void crossmatch(UUID requestId, UUID unitId) {
        service.recordCrossmatch(requestId, CrossmatchRequestDTO.builder()
            .bloodUnitId(unitId).compatible(Boolean.TRUE).build());
    }

    private void lapseCurrentScreen() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("""
                UPDATE clinical.patient_blood_groups SET expires_at = now() - interval '1 hour'
                WHERE patient_id = :p AND superseded = false
                """).setParameter("p", patient).executeUpdate());
    }

    @Test
    @DisplayName("the repeat lands, the old one stays in history, and the new one is current")
    void repeatScreenSupersedes() {
        PatientBloodGroupResponseDTO first = service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));
        PatientBloodGroupResponseDTO second = service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getSuperseded()).isFalse();
        assertThat(service.getCurrentBloodGroup(patient).getId()).isEqualTo(second.getId());
        List<PatientBloodGroupResponseDTO> history = service.getBloodGroupHistory(patient);
        assertThat(history).extracting(PatientBloodGroupResponseDTO::getId)
            .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(history).filteredOn(g -> g.getId().equals(first.getId()))
            .singleElement().extracting(PatientBloodGroupResponseDTO::getSuperseded).isEqualTo(Boolean.TRUE);
        assertThat(currentRows()).isOne();
    }

    @Test
    @DisplayName("two repeats at the same moment both land, one after the other, and one is current")
    void concurrentRepeatsSerialise() throws Exception {
        service.recordBloodGroup(screen(AboGroup.A, RhFactor.NEGATIVE, null));
        assertThat(concurrently(2)).isEqualTo(2);
        assertThat(service.getBloodGroupHistory(patient)).hasSize(3);
        assertThat(currentRows()).isOne();
    }

    @Test
    @DisplayName("two FIRST screens at the same moment both land too: there is no row to lock, the patient is")
    void concurrentFirstScreensSerialise() throws Exception {
        assertThat(concurrently(2)).isEqualTo(2);
        assertThat(service.getBloodGroupHistory(patient)).hasSize(2);
        assertThat(currentRows()).isOne();
    }

    private int concurrently(int callers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            outcomes.add(pool.submit(() -> {
                start.await();
                service.recordBloodGroup(screen(AboGroup.A, RhFactor.NEGATIVE, null));
                return Boolean.TRUE;
            }));
        }
        start.countDown();
        int landed = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get(60, TimeUnit.SECONDS)) {
                landed++;
            }
        }
        pool.shutdown();
        return landed;
    }

    @Test
    @DisplayName("an open request follows the repeat: a lapsed screen no longer blocks the crossmatch once repeated")
    void openRequestCrossmatchesAgainstTheRepeat() {
        service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));
        TransfusionRequestResponseDTO request = request();
        BloodUnitResponseDTO bag = unit(AboGroup.O, RhFactor.POSITIVE);
        lapseCurrentScreen();

        assertThatThrownBy(() -> crossmatch(request.getId(), bag.getId()))
            .isInstanceOf(BusinessException.class);

        PatientBloodGroupResponseDTO repeat = service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));

        // At once, before anything is crossmatched: the worklist stops saying "lapsed".
        TransfusionRequestResponseDTO followed = service.getRequest(request.getId());
        assertThat(followed.getBloodGroupId()).isEqualTo(repeat.getId());
        assertThat(followed.getScreenCurrent()).isTrue();

        crossmatch(request.getId(), bag.getId());

        TransfusionRequestResponseDTO after = service.getRequest(request.getId());
        assertThat(after.getBloodGroupId()).isEqualTo(repeat.getId());
        assertThat(after.getScreenCurrent()).isTrue();
    }

    @Test
    @DisplayName("a closed request keeps the screen it was raised against")
    void closedRequestKeepsItsScreen() {
        PatientBloodGroupResponseDTO first = service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));
        TransfusionRequestResponseDTO request = request();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE clinical.transfusion_requests SET status = 'CANCELLED' WHERE id = :r")
                .setParameter("r", request.getId()).executeUpdate());

        service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));

        assertThat(service.getRequest(request.getId()).getBloodGroupId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("a corrected group governs the crossmatch and the issue: the old group's blood is refused")
    void correctedGroupGovernsCrossmatchAndIssue() {
        service.recordBloodGroup(screen(AboGroup.A, RhFactor.POSITIVE, null));
        TransfusionRequestResponseDTO request = request();
        BloodUnitResponseDTO bagForA = unit(AboGroup.A, RhFactor.POSITIVE);
        BloodUnitResponseDTO otherBagForA = unit(AboGroup.A, RhFactor.POSITIVE);
        crossmatch(request.getId(), bagForA.getId());

        service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, "CX-011 sample mislabelled"));

        assertThatThrownBy(() -> service.issueUnit(request.getId(), bagForA.getId()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> crossmatch(request.getId(), otherBagForA.getId()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a screen lapsed since the crossmatch stops the issue; the repeat lets it go")
    void lapsedScreenStopsTheIssueUntilRepeated() {
        service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));
        TransfusionRequestResponseDTO request = request();
        BloodUnitResponseDTO bag = unit(AboGroup.O, RhFactor.POSITIVE);
        crossmatch(request.getId(), bag.getId());
        lapseCurrentScreen();

        assertThatThrownBy(() -> service.issueUnit(request.getId(), bag.getId()))
            .isInstanceOf(BusinessException.class);

        service.recordBloodGroup(screen(AboGroup.O, RhFactor.POSITIVE, null));
        assertThat(service.issueUnit(request.getId(), bag.getId()).getStatus().name()).isEqualTo("ISSUED");
    }
}
