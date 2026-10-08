package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
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
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.security.EncryptionKeyHolder;
import com.example.hms.service.AuditEventLogService;
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
import org.mockito.Mockito;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G13 plan rule 3 on PostgreSQL, against the schema Liquibase builds (V179):
 * every path that creates, renews, moves or deletes a work-queue claim holds
 * the prescription row lock first, so the racing pairs below serialise. Plus
 * the in-place take-over, V179's unique constraint, the after-commit audit
 * under a rollback, and the unlocked partner no-show that must not touch a
 * claim. Modelled on {@link PreparedFillConcurrencyPostgresIT}, whose
 * {@code race} harness proves the second writer WAITS on the lock before
 * the first commits.
 *
 * <p>The real services run: {@link PrescriptionQueueClaimService},
 * {@link DispenseServiceImpl}, {@link PreparedFillVoider},
 * {@link StockOutRoutingServiceImpl}. The caller identity is a mocked
 * {@link RoleValidator} answering per thread ({@link #as}); the audit service
 * is a mock, on which the audit assertions are {@code verify} calls.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PrescriptionQueueClaimService.class, DispenseServiceImpl.class, PreparedFillVoider.class,
        DispenseVerificationService.class, ControlledSubstanceGuard.class, StockOutRoutingServiceImpl.class,
        DispenseMapper.class, PrescriptionRoutingMapper.class, EncryptionKeyHolder.class,
        QueueClaimConcurrencyPostgresIT.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueClaimConcurrencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_queue_claim")
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

    /** The caller of the current thread; null means the first pharmacist. */
    private static final ThreadLocal<UUID> CALLER = new ThreadLocal<>();

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private PharmacyServiceSupport support;
    @MockitoBean private CdsCheckService cdsCheckService;
    @MockitoBean private PrescriberPharmacyNotifier prescriberNotifier;
    @MockitoBean private PartnerNotificationChannel partnerChannel;
    @MockitoBean private WithdrawnOrderPartnerHandler withdrawnOrders;
    @MockitoBean private AuditEventLogService auditEventLogService;

    @Autowired private PrescriptionQueueClaimService claims;
    @Autowired private DispenseService dispenseService;
    @Autowired private StockOutRoutingService routingService;
    @Autowired private PreparedFillVoider voider;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Hospital hospital;
    private Patient patient;
    private User pharmacist;
    private User secondPharmacist;
    private User doctor;
    private Prescription prescription;
    private Pharmacy dispensary;
    private Pharmacy partner;
    private StockLot lot;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seed());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital.getId());
        when(roleValidator.getCurrentUserId()).thenAnswer(inv ->
            CALLER.get() != null ? CALLER.get() : pharmacist.getId());
        when(support.resolveCurrentUser()).thenReturn(pharmacist);
    }

    /** Runs {@code body} as {@code user} on whichever thread executes it. */
    private static Supplier<Object> as(User user, Supplier<Object> body) {
        return () -> {
            CALLER.set(user.getId());
            try {
                return body.get();
            } finally {
                CALLER.remove();
            }
        };
    }

    // ── the races (rule 3) ───────────────────────────────────────────────

    @Test
    @DisplayName("AC-3: two claims of one row: the second waits on the lock, then 409 heldByOther; one holder")
    void claimVersusClaim() throws Exception {
        CompletableFuture<Object> second = race(
            as(pharmacist, () -> claims.claim(prescription.getId())),
            as(secondPharmacist, () -> claims.claim(prescription.getId())));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage(MessageUtil.resolve("workqueue.claim.heldByOther"));
        assertThat(claimHolders()).containsExactly(pharmacist.getId());
    }

    @Test
    @DisplayName("AC-10: a claim, then a ready that waits on it: the ready succeeds and ends the claim")
    void claimThenReady() throws Exception {
        CompletableFuture<Object> second = race(
            as(pharmacist, () -> claims.claim(prescription.getId())),
            as(pharmacist, () -> dispenseService.markReadyForCollection(request())));

        assertThat(second.get(30, TimeUnit.SECONDS)).isNotNull();
        assertThat(claimHolders()).isEmpty();
        assertThat(statusesOfDispenses()).containsExactly("PENDING");
    }

    @Test
    @DisplayName("AC-10: a ready, then a claim that waits on it: 409 openPreparation, never a claim beside a prepared fill")
    void readyThenClaim() throws Exception {
        CompletableFuture<Object> second = race(
            as(pharmacist, () -> dispenseService.markReadyForCollection(request())),
            as(secondPharmacist, () -> claims.claim(prescription.getId())));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage(MessageUtil.resolve("dispense.ready.openPreparation"));
        assertThat(claimHolders()).isEmpty();
        assertThat(statusesOfDispenses()).containsExactly("PENDING");
    }

    @Test
    @DisplayName("AC-11: a claim, then a withdrawal that waits on it: the withdrawal ends the claim")
    void claimThenWithdrawal() throws Exception {
        CompletableFuture<Object> second = race(
            as(pharmacist, () -> claims.claim(prescription.getId())),
            this::withdraw);

        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(Boolean.TRUE);
        assertThat(prescriptionStatus()).isEqualTo("CANCELLED");
        assertThat(claimHolders()).isEmpty();
    }

    @Test
    @DisplayName("AC-11: a withdrawal, then a claim that waits on it: 409 notInQueue, no claim on a withdrawn order")
    void withdrawalThenClaim() throws Exception {
        CompletableFuture<Object> second = race(
            this::withdraw,
            as(pharmacist, () -> claims.claim(prescription.getId())));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage(MessageUtil.resolve("workqueue.claim.notInQueue"));
        assertThat(prescriptionStatus()).isEqualTo("CANCELLED");
        assertThat(claimHolders()).isEmpty();
    }

    @Test
    @DisplayName("rule 3: a take-over, then the old holder's release that waits on it: 409 notHolder; B holds, never A")
    void takeOverVersusRelease() throws Exception {
        as(pharmacist, () -> claims.claim(prescription.getId())).get();

        CompletableFuture<Object> second = race(
            as(secondPharmacist, () -> claims.takeOver(prescription.getId())),
            as(pharmacist, () -> {
                claims.release(prescription.getId());
                return Boolean.TRUE;
            }));

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class)
            .hasMessage(MessageUtil.resolve("workqueue.claim.notHolder"));
        assertThat(claimHolders()).containsExactly(secondPharmacist.getId());
    }

    // ── advisory: acting over a claim (AC-8) ─────────────────────────────

    @Test
    @DisplayName("AC-8: A claims, B fills it in one step: the fill commits, the claim is gone, TAKEN_OVER once, by B")
    void claimThenOneStepByAnother() {
        as(pharmacist, () -> claims.claim(prescription.getId())).get();

        Object filled = as(secondPharmacist, () -> dispenseService.createDispense(request())).get();

        assertThat(filled).isNotNull();
        assertThat(prescriptionStatus()).isEqualTo("DISPENSED");
        assertThat(claimHolders()).isEmpty();
        verify(auditEventLogService, times(1)).logEvent(argThat((AuditEventRequestDTO a) ->
            a.getEventType() == AuditEventType.PRESCRIPTION_QUEUE_CLAIM_TAKEN_OVER
                && secondPharmacist.getId().equals(a.getUserId())
                && prescription.getId().toString().equals(a.getResourceId())));
    }

    // ── the state a take-over leaves (AC-6, rule 5) ──────────────────────

    @Test
    @DisplayName("AC-6: a take-over updates the same row in place: re-read in a new transaction, one row, holder B")
    void takeOverPersistsInPlace() {
        as(pharmacist, () -> claims.claim(prescription.getId())).get();
        UUID rowId = claimRowId();

        as(secondPharmacist, () -> claims.takeOver(prescription.getId())).get();

        assertThat(claimRowId()).isEqualTo(rowId);
        assertThat(claimHolders()).containsExactly(secondPharmacist.getId());
    }

    @Test
    @DisplayName("AC-19: V179's unique constraint refuses a second claim row written behind the service's back")
    void uniqueConstraintRefusesASecondRow() {
        as(pharmacist, () -> claims.claim(prescription.getId())).get();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO clinical.prescription_queue_claims
                            (id, prescription_id, claimed_by, claimed_at, created_at, updated_at)
                        VALUES (gen_random_uuid(), :rx, :user, now(), now(), now())
                        """)
                    .setParameter("rx", prescription.getId())
                    .setParameter("user", secondPharmacist.getId())
                    .executeUpdate()))
            .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
            .hasMessageContaining("uq_rx_queue_claim_prescription");
    }

    // ── a rollback after the service ran (AC-9) ──────────────────────────

    @Test
    @DisplayName("AC-9: the holder's one-step fill fails at the database after the service ran: claim kept, nothing audited")
    void exitWriteRollsBackAtFlush() {
        as(pharmacist, () -> claims.claim(prescription.getId())).get();
        Mockito.clearInvocations(auditEventLogService);

        assertThatThrownBy(() -> as(pharmacist, () -> new TransactionTemplate(transactionManager).execute(status -> {
            Object filled = dispenseService.createDispenseTransactionally(request());
            entityManager.flush();
            // Two PENDING copies of the new fill: the second trips
            // uq_disp_one_pending_per_rx, as a lost preparation race would.
            copyNewestDispenseAsPending();
            copyNewestDispenseAsPending();
            return filled;
        })).get())
            .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
            .hasMessageContaining("uq_disp_one_pending_per_rx");

        assertThat(claimHolders()).containsExactly(pharmacist.getId());
        assertThat(prescriptionStatus()).isEqualTo("SIGNED");
        assertThat(statusesOfDispenses()).isEmpty();
        verify(auditEventLogService, never()).logEvent(any());
    }

    // ── the unlocked partner writers (rule 4, D13) ───────────────────────

    @Test
    @DisplayName("rule 4: a partner no-show over a claimed PARTNER_ACCEPTED row succeeds and keeps the claim")
    void partnerNoShowOverClaim() {
        UUID decisionId = new TransactionTemplate(transactionManager).execute(status -> {
            Prescription locked = entityManager.find(Prescription.class, prescription.getId());
            locked.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
            PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(locked).routingType(RoutingType.PARTNER).targetPharmacy(entityManager.merge(partner))
                .decidedByUser(entityManager.merge(pharmacist)).decidedForPatient(entityManager.merge(patient))
                .status(RoutingDecisionStatus.ACCEPTED).decidedAt(LocalDateTime.now()).reason("Nearest partner")
                .build();
            entityManager.persist(decision);
            return decision.getId();
        });
        as(pharmacist, () -> claims.claim(prescription.getId())).get();

        as(secondPharmacist, () -> routingService.partnerNoShow(decisionId, "Nothing delivered")).get();

        assertThat(prescriptionStatus()).isEqualTo("SIGNED");
        assertThat(claimHolders()).containsExactly(pharmacist.getId());
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

    /**
     * What {@code updatePrescription} does to the lock, the prepared fill and
     * the claim on its way into CANCELLED: G15's {@code withdraw()} helper,
     * plus the claim release next to the void (AC-11).
     */
    private Object withdraw() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Prescription locked = prescriptionRepository.findByIdForUpdate(prescription.getId()).orElseThrow();
            if (!locked.getStatus().isWithdrawn()) {
                locked.setStatus(PrescriptionStatus.CANCELLED);
                voider.voidPreparedFill(locked, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
                claims.releaseOnExit(locked, QueueClaimReleaseReason.WITHDRAWN, doctor.getId(),
                    QueueClaimExitActor.OTHER);
                prescriptionRepository.save(locked);
            }
            return Boolean.TRUE;
        });
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

    private void copyNewestDispenseAsPending() {
        entityManager.createNativeQuery("""
                INSERT INTO clinical.dispenses (id, prescription_id, patient_id, pharmacy_id, stock_lot_id,
                    dispensed_by, medication_name, quantity_requested, quantity_dispensed, unit, substitution,
                    status, dispensed_at, verification_status, created_at, updated_at)
                SELECT gen_random_uuid(), prescription_id, patient_id, pharmacy_id, stock_lot_id,
                    dispensed_by, medication_name, quantity_requested, quantity_dispensed, unit, substitution,
                    'PENDING', NULL, verification_status, now(), now()
                  FROM clinical.dispenses WHERE prescription_id = :rx AND status <> 'PENDING'
                """)
            .setParameter("rx", prescription.getId())
            .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> claimHolders() {
        return entityManager.createNativeQuery(
                "SELECT claimed_by FROM clinical.prescription_queue_claims WHERE prescription_id = :id")
            .setParameter("id", prescription.getId())
            .getResultList();
    }

    private UUID claimRowId() {
        return (UUID) entityManager.createNativeQuery(
                "SELECT id FROM clinical.prescription_queue_claims WHERE prescription_id = :id")
            .setParameter("id", prescription.getId())
            .getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private List<String> statusesOfDispenses() {
        return entityManager.createNativeQuery(
                "SELECT status FROM clinical.dispenses WHERE prescription_id = :id ORDER BY created_at")
            .setParameter("id", prescription.getId())
            .getResultList();
    }

    private String prescriptionStatus() {
        return (String) entityManager.createNativeQuery("SELECT status FROM clinical.prescriptions WHERE id = :id")
            .setParameter("id", prescription.getId())
            .getSingleResult();
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
            .name("Queue Claim Network " + n).code("ORG-QC-" + n)
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build();
        entityManager.persist(organization);
        hospital = Hospital.builder()
            .name("Queue Claim Hospital " + n).code("HQC" + n)
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226556" + n).email("qc" + n + "@hospital.test")
            .organization(organization).build();
        entityManager.persist(hospital);

        Role doctorRole = role("ROLE_DOCTOR", "Doctor");
        doctor = user("doctor", n);
        entityManager.persist(doctor);
        UserRoleHospitalAssignment doctorAssignment = UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-QC-" + n).description("Doctor assignment")
            .user(doctor).hospital(hospital).role(doctorRole)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
        entityManager.persist(doctorAssignment);
        Staff doctorStaff = Staff.builder()
            .user(doctor).hospital(hospital).assignment(doctorAssignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-QC-" + n).name("Dr. Doctor").active(true).build();
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
            .phoneNumberPrimary("+22679" + n).email("aminata" + n + "@patient-qc.test")
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build();
        entityManager.persist(patient);

        Encounter encounter = Encounter.builder()
            .patient(patient).staff(doctorStaff).hospital(hospital).assignment(doctorAssignment)
            .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
            .code("ENC-QC-" + n).build();
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
            .phoneNumber("+22671" + n).build();
        entityManager.persist(partner);
        InventoryItem item = InventoryItem.builder()
            .pharmacy(dispensary).medicationCatalogItem(amoxicillin)
            .quantityOnHand(BigDecimal.valueOf(100)).active(true).build();
        entityManager.persist(item);
        lot = StockLot.builder()
            .inventoryItem(item).lotNumber("AMXQ-" + n).expiryDate(LocalDate.now().plusYears(1))
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
            .username(prefix + "-qc-" + n).passwordHash("hashed-password")
            .email(prefix + n + "@queue-claim.test").firstName(prefix + "FN").lastName("User" + n)
            .phoneNumber("+22676" + n + prefix.length()).isActive(true).build();
    }
}
