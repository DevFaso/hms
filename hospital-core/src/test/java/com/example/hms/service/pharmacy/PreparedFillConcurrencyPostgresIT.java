package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.mapper.pharmacy.PrescriptionRoutingMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.service.pharmacy.partner.PartnerNotificationChannel;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import com.example.hms.utility.LotBarcode;
import com.example.hms.utility.MessageUtil;
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
import org.springframework.context.i18n.LocaleContextHolder;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G15 rule 1 on PostgreSQL, against the schema Liquibase builds (V178's
 * partial unique index included): every path that creates, moves or voids a
 * prepared fill takes the prescription row lock first, so the racing pairs
 * below serialise instead of both committing. H2 can show neither the
 * partial index nor {@code SELECT ... FOR UPDATE} contention.
 *
 * <p>Each race: the first writer runs inside a transaction that it holds open
 * after its work; the second starts, is shown to be WAITING (on the row
 * lock), and is released by the first one's commit, then meets the state the
 * first committed.
 *
 * <p>The real services run: {@link DispenseServiceImpl},
 * {@link PreparedFillVoider}, {@link StockOutRoutingServiceImpl}. What is
 * mocked is the caller identity and the side channels (SMS and audit through
 * {@link PharmacyServiceSupport}, CDS, the prescriber notice, partner SMS).
 * The withdrawal is driven through what {@code updatePrescription} does to
 * the lock (findByIdForUpdate, the status change, the voider): its own
 * hundred collaborators add nothing to a locking test, and its use of the
 * locked read is pinned by {@code PrescriptionServiceImplTest}'s strict
 * stubs.
 *
 * <p>The container belongs to this class alone and every test builds its own
 * hospital, so rows are left for the container to take with it.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DispenseServiceImpl.class, PreparedFillVoider.class, DispenseVerificationService.class,
        ControlledSubstanceGuard.class, StockOutRoutingServiceImpl.class, DispenseMapper.class,
        PrescriptionRoutingMapper.class, EncryptionKeyHolder.class, PreparedFillConcurrencyPostgresIT.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PreparedFillConcurrencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_prepared_fill")
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
    }

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private PharmacyServiceSupport support;
    @MockitoBean private CdsCheckService cdsCheckService;
    @MockitoBean private PrescriberPharmacyNotifier prescriberNotifier;
    @MockitoBean private PartnerNotificationChannel partnerChannel;
    @MockitoBean private WithdrawnOrderPartnerHandler withdrawnOrders;

    @Autowired private DispenseService dispenseService;
    @Autowired private StockOutRoutingService routingService;
    @Autowired private PreparedFillVoider voider;
    @Autowired private DispenseRepository dispenseRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Hospital hospital;
    private Patient patient;
    private User pharmacist;
    private User secondPharmacist;
    private Prescription prescription;
    private Pharmacy dispensary;
    private Pharmacy partner;
    private StockLot lot;
    private Staff doctorStaff;
    private Encounter encounter;
    private UserRoleHospitalAssignment doctorAssignment;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seed());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacist.getId());
        when(support.resolveCurrentUser()).thenReturn(pharmacist);
    }

    // ── the races ────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-3: two readies of one order: the second waits on the lock, then 409; one PENDING row, stock spent once")
    void readyVersusReady() throws Exception {
        CompletableFuture<Object> second = race(
            () -> dispenseService.markReadyForCollection(request()),
            () -> dispenseService.markReadyForCollection(request()));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage("This prescription already has a fill prepared for collection.");
        assertThat(statusesOfDispenses()).containsExactly("PENDING");
        assertThat(lotRemaining()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("AC-3: ready against a one-step dispense: the one-step waits, then 409; stock spent once")
    void readyVersusOneStep() throws Exception {
        CompletableFuture<Object> second = race(
            () -> dispenseService.markReadyForCollection(request()),
            () -> dispenseService.createDispense(request()));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage("A fill is prepared for this prescription. Hand it over or cancel the preparation first.");
        assertThat(statusesOfDispenses()).containsExactly("PENDING");
        assertThat(lotRemaining()).isEqualByComparingTo("40");
        assertThat(prescriptionStatus()).isEqualTo("SIGNED");
    }

    @Test
    @DisplayName("AC-8: ready, then a withdrawal that waits on it: the withdrawal voids the fill and returns the stock")
    void readyThenWithdrawal() throws Exception {
        CompletableFuture<Object> second = race(
            () -> dispenseService.markReadyForCollection(request()),
            this::withdraw);

        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(Boolean.TRUE);
        assertThat(prescriptionStatus()).isEqualTo("CANCELLED");
        assertThat(statusesOfDispenses()).containsExactly("CANCELLED");
        assertThat(cancelReasons()).containsExactly(ReadyCancelReason.PRESCRIPTION_WITHDRAWN.name());
        assertThat(lotRemaining()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("AC-8: a withdrawal, then a ready that waits on it: the ready is refused, nothing is prepared")
    void withdrawalThenReady() throws Exception {
        CompletableFuture<Object> second = race(
            this::withdraw,
            () -> dispenseService.markReadyForCollection(request()));

        assertThat(causeOf(second)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("not in a dispensable state");
        assertThat(prescriptionStatus()).isEqualTo("CANCELLED");
        assertThat(statusesOfDispenses()).isEmpty();
        assertThat(lotRemaining()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("AC-10 (N1): ready, then a route to a partner that waits on it: the route is refused")
    void readyThenRouteToPartner() throws Exception {
        CompletableFuture<Object> second = race(
            () -> dispenseService.markReadyForCollection(request()),
            () -> routingService.routeToPartner(prescription.getId(), partnerRoute()));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class);
        assertThat(prescriptionStatus()).isEqualTo("SIGNED");
        assertThat(statusesOfDispenses()).containsExactly("PENDING");
        assertThat(routingDecisionCount()).isZero();
    }

    @Test
    @DisplayName("AC-10 (N1): a route to a partner, then a ready that waits on it: the ready is refused")
    void routeToPartnerThenReady() throws Exception {
        CompletableFuture<Object> second = race(
            () -> routingService.routeToPartner(prescription.getId(), partnerRoute()),
            () -> dispenseService.markReadyForCollection(request()));

        assertThat(causeOf(second)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("not in a dispensable state");
        assertThat(prescriptionStatus()).isEqualTo("SENT_TO_PARTNER");
        assertThat(statusesOfDispenses()).isEmpty();
    }

    @Test
    @DisplayName("AC-5: two hand-overs: the second waits, then replays; the side effects run once")
    void handOverVersusHandOver() throws Exception {
        UUID dispenseId = prepare();

        CompletableFuture<Object> second = race(
            () -> dispenseService.handOver(dispenseId, null),
            () -> dispenseService.handOver(dispenseId, null));

        assertThat(second.get(30, TimeUnit.SECONDS)).isNotNull();
        assertThat(statusesOfDispenses()).containsExactly("COMPLETED");
        verify(support, times(1)).logAudit(eq(AuditEventType.DISPENSE_HANDED_OVER), anyString(), anyString(), anyString());
        verify(prescriberNotifier, times(1)).notifyPrescriber(any(), eq(PrescriptionStatus.DISPENSED));
        verify(support, times(1)).notifyDispensed(any(), any(), any());
    }

    @Test
    @DisplayName("#825 finding 1: two orders filled from one lot at once: the second waits on the lot row, neither decrement is lost")
    void twoOrdersFromOneLotLoseNoStock() throws Exception {
        UUID otherPrescriptionId = new TransactionTemplate(transactionManager).execute(status -> {
            Prescription other = Prescription.builder()
                .patient(entityManager.merge(patient)).staff(entityManager.merge(doctorStaff))
                .encounter(entityManager.merge(encounter)).hospital(entityManager.merge(hospital))
                .assignment(entityManager.merge(doctorAssignment)).medicationName("Amoxicillin")
                .quantity(BigDecimal.TEN).status(PrescriptionStatus.SIGNED).build();
            entityManager.persist(other);
            return other.getId();
        });
        DispenseRequestDTO second = request();
        second.setPrescriptionId(otherPrescriptionId);

        CompletableFuture<Object> waiter = race(
            () -> dispenseService.markReadyForCollection(request()),
            () -> dispenseService.createDispense(second));

        assertThat(waiter.get(30, TimeUnit.SECONDS)).isNotNull();
        assertThat(lotRemaining()).isEqualByComparingTo("30");
        assertThat(((Number) entityManager.createNativeQuery(
                "SELECT quantity_on_hand FROM clinical.inventory_items WHERE id = :id")
            .setParameter("id", lot.getInventoryItem().getId())
            .getSingleResult()).intValue()).isEqualTo(80);
    }

    @Test
    @DisplayName("AC-7: a hand-over, then a cancel-ready that waits on it: the cancel is refused, the fill stays handed over")
    void handOverThenCancelReady() throws Exception {
        UUID dispenseId = prepare();

        CompletableFuture<Object> second = race(
            () -> dispenseService.handOver(dispenseId, null),
            () -> dispenseService.cancelReady(dispenseId, new CancelReadyRequestDTO(ReadyCancelReason.OTHER)));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage("This fill is no longer waiting for collection.");
        assertThat(statusesOfDispenses()).containsExactly("COMPLETED");
        assertThat(lotRemaining()).isEqualByComparingTo("40");
        verify(support, times(0)).notifyReadyCancelled(any(), any(), any());
    }

    @Test
    @DisplayName("AC-7: a cancel-ready, then a hand-over that waits on it: the hand-over is refused, the stock is back")
    void cancelReadyThenHandOver() throws Exception {
        UUID dispenseId = prepare();

        CompletableFuture<Object> second = race(
            () -> dispenseService.cancelReady(dispenseId, new CancelReadyRequestDTO(ReadyCancelReason.NOT_COLLECTED)),
            () -> dispenseService.handOver(dispenseId, null));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage("This fill is no longer waiting for collection.");
        assertThat(statusesOfDispenses()).containsExactly("CANCELLED");
        assertThat(cancelReasons()).containsExactly(ReadyCancelReason.NOT_COLLECTED.name());
        assertThat(lotRemaining()).isEqualByComparingTo("50");
        assertThat(prescriptionStatus()).isEqualTo("SIGNED");
        verify(support, times(1)).notifyReadyCancelled(any(), any(), any());
    }

    // ── the state a hand-over leaves (B2) ────────────────────────────────

    @Test
    @DisplayName("AC-4/B2: after hand-over the row read back in a new transaction is COMPLETED, by the hand-over user, kept preparer, updated_at moved")
    void handOverPersistsWhatItSays() {
        UUID dispenseId = prepare();
        Map<String, Object> before = dispenseRow(dispenseId);
        assertThat(before.get("dispensed_at")).isNull();
        assertThat(dispenseRepository.sumQuantityDispensedForPrescription(prescription.getId(),
            DispenseRepository.NOT_A_FILL)).isEqualByComparingTo("0");

        when(roleValidator.getCurrentUserId()).thenReturn(secondPharmacist.getId());
        dispenseService.handOver(dispenseId, null);

        Map<String, Object> after = dispenseRow(dispenseId);
        assertThat(after)
            .containsEntry("status", "COMPLETED")
            .containsEntry("dispensed_by", secondPharmacist.getId())
            .containsEntry("prepared_by", pharmacist.getId());
        assertThat(after.get("dispensed_at")).isNotNull();
        assertThat((LocalDateTime) after.get("updated_at")).isAfter((LocalDateTime) before.get("updated_at"));
        assertThat(prescriptionStatus()).isEqualTo("DISPENSED");
        assertThat(dispenseRepository.sumQuantityDispensedForPrescription(prescription.getId(),
            DispenseRepository.NOT_A_FILL)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("A1: a void inside the prescriber's transaction leaves the prescription managed")
    void voidLeavesThePrescriptionManaged() {
        prepare();
        Boolean managed = new TransactionTemplate(transactionManager).execute(status -> {
            Prescription locked = prescriptionRepository.findByIdForUpdate(prescription.getId()).orElseThrow();
            voider.voidPreparedFill(locked, ReadyCancelReason.PRESCRIPTION_CHANGED);
            return entityManager.contains(locked);
        });
        assertThat(managed).isTrue();
        assertThat(cancelReasons()).containsExactly(ReadyCancelReason.PRESCRIPTION_CHANGED.name());
    }

    // ── the reminder claim (AC-13) ───────────────────────────────────────

    @Test
    @DisplayName("AC-13: the reminder claim succeeds once, and never for a fill that was handed over")
    void reminderClaimIsConditional() {
        UUID dispenseId = prepare();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LocalDateTime now = LocalDateTime.now();

        Integer first = tx.execute(status -> dispenseRepository.claimReadyReminder(dispenseId, now));
        Integer again = tx.execute(status -> dispenseRepository.claimReadyReminder(dispenseId, now));
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();

        UUID second = prepareAfterHandingOver(dispenseId);
        dispenseService.handOver(second, null);
        Integer afterHandOver = tx.execute(status -> dispenseRepository.claimReadyReminder(second, now));
        assertThat(afterHandOver).isZero();
    }

    /** Hands {@code dispenseId} over, re-opens the order for a second fill, and prepares it. */
    private UUID prepareAfterHandingOver(UUID dispenseId) {
        dispenseService.handOver(dispenseId, null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE clinical.prescriptions SET status = 'SIGNED', quantity = 30 WHERE id = :id")
                .setParameter("id", prescription.getId())
                .executeUpdate());
        return prepare();
    }

    // ── V178's index, directly ───────────────────────────────────────────

    @Test
    @DisplayName("AC-3: the partial unique index refuses a second PENDING row written behind the services' back")
    void indexRefusesASecondPendingRow() {
        UUID dispenseId = prepare();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                copyDispense(dispenseId, "PENDING")))
            .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
            .hasMessageContaining("uq_disp_one_pending_per_rx");

        // ...and it is PARTIAL: a second COMPLETED row of the same order is fine.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            copyDispense(dispenseId, "COMPLETED"));
        assertThat(statusesOfDispenses()).containsExactlyInAnyOrder("PENDING", "COMPLETED");
    }

    // ── harness ──────────────────────────────────────────────────────────

    /**
     * Runs {@code first} in a transaction that stays open after it, starts
     * {@code second} on another thread, checks that it is waiting, then
     * commits the first. Returns the second's outcome.
     */
    private CompletableFuture<Object> race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Object> holder = CompletableFuture.supplyAsync(() ->
            new TransactionTemplate(transactionManager).execute(status -> {
                Object result = first.get();
                firstDone.countDown();
                await(releaseFirst);
                return result;
            }));
        assertThat(firstDone.await(30, TimeUnit.SECONDS)).as("the first writer finished its work").isTrue();

        CompletableFuture<Object> waiter = CompletableFuture.supplyAsync(second);
        assertThatThrownBy(() -> waiter.get(700, TimeUnit.MILLISECONDS))
            .as("the second writer waits on the prescription row lock")
            .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();
        holder.get(30, TimeUnit.SECONDS);
        try {
            waiter.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // inspected by the caller through causeOf
        }
        return waiter;
    }

    private static Throwable causeOf(CompletableFuture<Object> future) {
        assertThat(future).isCompletedExceptionally();
        try {
            future.get();
            throw new AssertionError("expected a failure");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** What {@code updatePrescription} does to the lock and the fill on its way into CANCELLED. */
    private Object withdraw() {
        // Joins the racing transaction when it is the first writer; its own
        // transaction when it is the second, as updatePrescription's is.
        return new TransactionTemplate(transactionManager).execute(status -> {
            Prescription locked = prescriptionRepository.findByIdForUpdate(prescription.getId()).orElseThrow();
            if (!locked.getStatus().isWithdrawn()) {
                locked.setStatus(PrescriptionStatus.CANCELLED);
                voider.voidPreparedFill(locked, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
                prescriptionRepository.save(locked);
            }
            return Boolean.TRUE;
        });
    }

    private UUID prepare() {
        return dispenseService.markReadyForCollection(request()).getId();
    }

    private DispenseRequestDTO request() {
        return DispenseRequestDTO.builder()
            .prescriptionId(prescription.getId())
            .patientId(patient.getId())
            .pharmacyId(dispensary.getId())
            .stockLotId(lot.getId())
            .medicationName("Amoxicillin")
            .quantityRequested(BigDecimal.TEN)
            .quantityDispensed(BigDecimal.TEN)
            .build();
    }

    private RoutingDecisionRequestDTO partnerRoute() {
        return RoutingDecisionRequestDTO.builder()
            .prescriptionId(prescription.getId())
            .targetPharmacyId(partner.getId())
            .reason("Nearest partner")
            .build();
    }

    private void copyDispense(UUID sourceId, String status) {
        entityManager.createNativeQuery("""
                INSERT INTO clinical.dispenses (id, prescription_id, patient_id, pharmacy_id, stock_lot_id,
                    dispensed_by, medication_name, quantity_requested, quantity_dispensed, unit, substitution,
                    status, dispensed_at, verification_status, created_at, updated_at)
                SELECT gen_random_uuid(), prescription_id, patient_id, pharmacy_id, stock_lot_id,
                    dispensed_by, medication_name, quantity_requested, quantity_dispensed, unit, substitution,
                    :status, now(), verification_status, now(), now()
                  FROM clinical.dispenses WHERE id = :id
                """)
            .setParameter("status", status)
            .setParameter("id", sourceId)
            .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<String> statusesOfDispenses() {
        return entityManager.createNativeQuery(
                "SELECT status FROM clinical.dispenses WHERE prescription_id = :id ORDER BY created_at")
            .setParameter("id", prescription.getId())
            .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> cancelReasons() {
        return entityManager.createNativeQuery(
                "SELECT cancel_reason FROM clinical.dispenses WHERE prescription_id = :id")
            .setParameter("id", prescription.getId())
            .getResultList();
    }

    private Map<String, Object> dispenseRow(UUID id) {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT status, dispensed_at, dispensed_by, prepared_by, updated_at FROM clinical.dispenses WHERE id = :id")
            .setParameter("id", id)
            .getSingleResult();
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", row[0]);
        m.put("dispensed_at", row[1]);
        m.put("dispensed_by", row[2]);
        m.put("prepared_by", row[3]);
        m.put("updated_at", row[4]);
        return m;
    }

    private String prescriptionStatus() {
        return (String) entityManager.createNativeQuery("SELECT status FROM clinical.prescriptions WHERE id = :id")
            .setParameter("id", prescription.getId())
            .getSingleResult();
    }

    private BigDecimal lotRemaining() {
        return (BigDecimal) entityManager.createNativeQuery(
                "SELECT remaining_quantity FROM clinical.stock_lots WHERE id = :id")
            .setParameter("id", lot.getId())
            .getSingleResult();
    }

    private long routingDecisionCount() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM clinical.prescription_routing_decisions WHERE prescription_id = :id")
            .setParameter("id", prescription.getId())
            .getSingleResult()).longValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ── fixture: one hospital, a doctor's signed order, a dispensary with stock ──

    private void seed() {
        String n = String.format("%05d", SEQUENCE.incrementAndGet());
        Organization organization = Organization.builder()
            .name("Prepared Fill Network " + n).code("ORG-PF-" + n)
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build();
        entityManager.persist(organization);
        hospital = Hospital.builder()
            .name("Prepared Fill Hospital " + n).code("HPF" + n)
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226555" + n).email("pf" + n + "@hospital.test")
            .organization(organization).build();
        entityManager.persist(hospital);

        Role doctorRole = role("ROLE_DOCTOR", "Doctor");
        User doctor = user("doctor", n);
        entityManager.persist(doctor);
        doctorAssignment = UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-PF-" + n).description("Doctor assignment")
            .user(doctor).hospital(hospital).role(doctorRole)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
        entityManager.persist(doctorAssignment);
        doctorStaff = Staff.builder()
            .user(doctor).hospital(hospital).assignment(doctorAssignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-PF-" + n).name("Dr. Doctor").active(true).build();
        entityManager.persist(doctorStaff);

        pharmacist = user("pharmacist", n);
        entityManager.persist(pharmacist);
        secondPharmacist = user("pharmacist2", n);
        entityManager.persist(secondPharmacist);

        User patientUser = user("patient", n);
        entityManager.persist(patientUser);
        patient = Patient.builder()
            .firstName("Aminata").lastName("Diallo").dateOfBirth(LocalDate.of(1992, 3, 10)).gender("F")
            .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
            .phoneNumberPrimary("+22678" + n).email("aminata" + n + "@patient.test")
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build();
        entityManager.persist(patient);

        encounter = Encounter.builder()
            .patient(patient).staff(doctorStaff).hospital(hospital).assignment(doctorAssignment)
            .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
            .code("ENC-PF-" + n).build();
        entityManager.persist(encounter);

        prescription = Prescription.builder()
            .patient(patient).staff(doctorStaff).encounter(encounter).hospital(hospital)
            .assignment(doctorAssignment).medicationName("Amoxicillin")
            .quantity(BigDecimal.TEN).status(PrescriptionStatus.SIGNED).build();
        entityManager.persist(prescription);

        MedicationCatalogItem amoxicillin = MedicationCatalogItem.builder()
            .nameFr("Amoxicilline").genericName("Amoxicillin").hospital(hospital).build();
        entityManager.persist(amoxicillin);
        dispensary = Pharmacy.builder()
            .hospital(hospital).name("Dispensary " + n).pharmacyType(PharmacyType.HOSPITAL_DISPENSARY).build();
        entityManager.persist(dispensary);
        partner = Pharmacy.builder()
            .hospital(hospital).name("Partner " + n).pharmacyType(PharmacyType.PARTNER_PHARMACY)
            .phoneNumber("+22670" + n).build();
        entityManager.persist(partner);
        InventoryItem item = InventoryItem.builder()
            .pharmacy(dispensary).medicationCatalogItem(amoxicillin)
            .quantityOnHand(BigDecimal.valueOf(100)).active(true).build();
        entityManager.persist(item);
        lot = StockLot.builder()
            .inventoryItem(item).lotNumber("AMX-" + n).expiryDate(LocalDate.now().plusYears(1))
            .initialQuantity(BigDecimal.valueOf(50)).remainingQuantity(BigDecimal.valueOf(50))
            .receivedDate(LocalDate.now()).barcodeValue(LotBarcode.mint()).build();
        entityManager.persist(lot);
    }

    private Role role(String code, String name) {
        List<Role> found = entityManager.createQuery("select r from Role r where r.code = :code", Role.class)
            .setParameter("code", code).getResultList();
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        Role created = Role.builder().name(name).code(code).description(name + " role").build();
        entityManager.persist(created);
        return created;
    }

    private static User user(String prefix, String n) {
        return User.builder()
            .username(prefix + "-pf-" + n).passwordHash("hashed-password")
            .email(prefix + n + "@prepared-fill.test").firstName(prefix + "FN").lastName("User" + n)
            .phoneNumber("+22677" + n + prefix.length()).isActive(true).build();
    }
}
