package com.example.hms.repository;

import com.example.hms.enums.DeathDisputeStatus;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeResponseDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.impl.DeathDisputeServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The urgent dispute on real PostgreSQL built by Liquibase, each call in its own transaction on its own thread, as two
 * instances of the API would be. Foreign keys are off for the seed: the patient and hospital rows say nothing here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeathDisputeConcurrencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_death_dispute")
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

    @Autowired private DeathDisputeRepository disputes;
    @Autowired private DeathRecordRepository records;
    @Autowired private PatientRepository patients;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory entityManagers;

    private final RoleValidator roles = mock(RoleValidator.class);
    private final PatientChartAccess chartAccess = mock(PatientChartAccess.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InBasketService inBasket = mock(InBasketService.class);
    private DeathDisputeServiceImpl service;
    private TransactionTemplate tx;
    private UUID patient;
    private UUID hospital;
    private UUID record;

    @BeforeEach
    void setUp() {
        service = new DeathDisputeServiceImpl(Clock.systemDefaultZone(), disputes, records, patients, chartAccess, roles,
            assignments, inBasket, mock(AuditEventLogService.class));
        tx = new TransactionTemplate(transactions);
        patient = UUID.randomUUID();
        hospital = UUID.randomUUID();
        record = UUID.randomUUID();
        jdbc.execute("ALTER TABLE clinical.death_records DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE clinical.death_disputes DISABLE TRIGGER ALL");
        jdbc.update("""
            INSERT INTO clinical.death_records (id, patient_id, hospital_id, died_at, immediate_cause)
            VALUES (?, ?, ?, ?, 'Cause')
            """, record, patient, hospital, Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        Patient chart = new Patient();
        chart.setId(patient);
        UUID actor = UUID.randomUUID();
        when(chartAccess.require(patient, hospital)).thenReturn(chart);
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.isDoctor(actor, hospital)).thenReturn(true);
        when(roles.isHospitalAdmin(actor, hospital)).thenReturn(true);
        User admin = new User();
        admin.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(admin));
    }

    private static DeathDisputeRequestDTO statement() {
        return DeathDisputeRequestDTO.builder().reason("Seen alive").assessedAlive(true).identityVerified(true).build();
    }

    private <T> List<Future<T>> together(List<Callable<T>> calls) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        for (Callable<T> call : calls) {
            results.add(pool.submit(() -> {
                start.await();
                return tx.execute(status -> {
                    try {
                        return call.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }));
        }
        start.countDown();
        pool.shutdown();
        return results;
    }

    private long open() {
        return jdbc.queryForObject("SELECT count(*) FROM clinical.death_disputes WHERE death_record_id = ? AND status = 'OPEN'",
            Long.class, record);
    }

    @Test
    @DisplayName("every query the guard, the review and the sweep use, for each state a dispute can be in")
    void theQueries() {
        DeathDisputeResponseDTO opened = tx.execute(status -> service.dispute(patient, statement()));

        assertThat(disputes.existsOpen(patient, hospital)).isTrue();
        assertThat(disputes.existsOpen(patient, UUID.randomUUID())).as("another hospital stays blocked").isFalse();
        assertThat(disputes.existsOpen(UUID.randomUUID(), hospital)).isFalse();
        assertThat(disputes.findOpen(record, hospital)).isPresent();
        assertThat(disputes.findOpenRecordIds(List.of(record, UUID.randomUUID()))).containsExactly(record);
        assertThat(disputes.findOpenForReview(hospital)).extracting(DeathDispute::getId).containsExactly(opened.getId());
        assertThat(disputes.findOpenForReview(UUID.randomUUID())).isEmpty();
        assertThat(dueIds(LocalDateTime.now().plusHours(1))).contains(opened.getId());
        assertThat(dueIds(LocalDateTime.now().minusHours(1))).as("filed just now").doesNotContain(opened.getId());

        tx.executeWithoutResult(status -> service.uphold(opened.getId(), DeathDisputeOutcomeDTO.builder().reason("x").build()));

        assertThat(disputes.existsOpen(patient, hospital)).as("the block is back").isFalse();
        assertThat(disputes.findOpenRecordIds(List.of(record))).isEmpty();
        assertThat(dueIds(LocalDateTime.now().plusHours(1))).doesNotContain(opened.getId());
    }

    private List<UUID> dueIds(LocalDateTime before) {
        return disputes.findDueForEscalation(before).stream().map(DeathDispute::getId).toList();
    }

    @Test
    @DisplayName("a dispute with nobody to tell is due at once, and a due dispute is not dropped")
    void dueWithNoReviewItem() {
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of());

        tx.executeWithoutResult(status -> service.dispute(patient, statement()));

        assertThat(disputes.findDueForEscalation(LocalDateTime.now().minusYears(1)))
            .extracting(DeathDispute::getDeathRecordId).contains(record);
    }

    @Test
    @DisplayName("six doctors at once: one dispute, every one of them answered with it, one review item")
    void concurrentDisputes() throws Exception {
        List<Callable<DeathDisputeResponseDTO>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> service.dispute(patient, statement()));
        }
        Set<UUID> ids = new java.util.HashSet<>();
        for (Future<DeathDisputeResponseDTO> result : together(calls)) {
            ids.add(result.get().getId());
        }
        assertThat(ids).hasSize(1);
        assertThat(open()).isEqualTo(1);
        verify(inBasket, times(1)).createItem(any());
    }

    @Test
    @DisplayName("two administrators upholding at once: decided once, the other told so")
    void concurrentUphold() throws Exception {
        DeathDisputeResponseDTO first = tx.execute(status -> service.dispute(patient, statement()));
        List<Callable<String>> calls = List.of(
            () -> service.uphold(first.getId(), DeathDisputeOutcomeDTO.builder().reason("a").build()).getStatus().name(),
            () -> service.uphold(first.getId(), DeathDisputeOutcomeDTO.builder().reason("b").build()).getStatus().name());
        int refused = 0;
        for (Future<String> result : together(calls)) {
            try {
                assertThat(result.get()).isEqualTo(DeathDisputeStatus.UPHELD.name());
            } catch (java.util.concurrent.ExecutionException e) {
                refused++;
            }
        }
        assertThat(refused).as("decided once").isEqualTo(1);
        assertThat(open()).isZero();
    }

    @Test
    @DisplayName("the partial index holds at most one open dispute per death and hospital, whatever the service does")
    void theIndex() {
        Mockito.reset(inBasket);
        tx.executeWithoutResult(status -> service.dispute(patient, statement()));
        DeathDispute open = disputes.findOpen(record, hospital).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO clinical.death_disputes (death_record_id, death_record_version, patient_id, hospital_id, disputed_by_id,
                disputed_at, reason, assessed_alive, identity_verified)
            VALUES (?, 0, ?, ?, ?, now(), 'x', true, true)
            """, record, patient, hospital, open.getDisputedById()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** The lock order of MortalityServiceImpl.markEnteredInError: the record, then its open disputes. */
    @Test
    @DisplayName("entered in error's list of open disputes waits for an upholding, and sees it gone")
    void enteredInErrorSeesWhatWasDecided() throws Exception {
        DeathDisputeResponseDTO first = tx.execute(status -> service.dispute(patient, statement()));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> upholding = pool.submit(() -> tx.executeWithoutResult(status -> {
            service.uphold(first.getId(), DeathDisputeOutcomeDTO.builder().reason("a").build());
            entityManagerFlush();
            held.countDown();
            await(release);
        }));
        held.await();
        Future<List<DeathDispute>> list = pool.submit(() -> tx.execute(status -> records.lockById(record).map(id ->
            disputes.lockOpenForRecord(id)).orElseThrow()));
        Thread.sleep(300);
        assertThat(list.isDone()).as("it waits for the lock").isFalse();
        release.countDown();
        upholding.get();
        assertThat(list.get()).as("what was decided meanwhile is not closed twice").isEmpty();
        pool.shutdown();
    }

    private void entityManagerFlush() {
        org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers).flush();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
