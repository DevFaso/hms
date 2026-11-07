package com.example.hms.repository;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.OrderReviewHold;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.mortality.DeathDisputeOutcomeDTO;
import com.example.hms.payload.dto.mortality.DeathDisputeRequestDTO;
import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TransfusionService;
import com.example.hms.service.PrescriberReauthenticationService;
import com.example.hms.service.impl.DeathDisputeServiceImpl;
import com.example.hms.service.impl.OrderReviewHoldServiceImpl;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import com.example.hms.service.support.OrderReviewHoldGuard;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The hold's rule on real PostgreSQL built by Liquibase, through the real dispute and review services, the real guard
 * and real transactions on real order rows. What a step does past the guard is not here: the prescription and blood
 * services are stubbed at their stop paths, and the hospital's staff rows are not seeded, with foreign keys off.
 * {@code DeathDisputeEmergencyPathwayTest} walks the real sign, dispense, MAR and transfusion code.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(120)
class OrderReviewHoldPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_order_review")
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

    private static final List<String> SEEDED = List.of("clinical.patients", "hospital.hospitals", "\"security\".users", "hospital.staff", "clinical.encounters", "clinical.prescriptions", "clinical.transfusion_requests",
        "clinical.death_records", "clinical.death_disputes", "clinical.order_review_holds");

    @Autowired private OrderReviewHoldRepository holds;
    @Autowired private DeathDisputeRepository disputes;
    @Autowired private DeathRecordRepository records;
    @Autowired private PatientRepository patients;
    @Autowired private PrescriptionRepository prescriptions;
    @Autowired private TransfusionRequestRepository requests;
    @Autowired private TransfusionAdministrationRepository administrations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final RoleValidator roles = mock(RoleValidator.class);
    private final PatientChartAccess chartAccess = mock(PatientChartAccess.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private DeathDisputeServiceImpl disputeService;
    private OrderReviewHoldServiceImpl review;
    private TransactionTemplate tx;
    private UUID patient;
    private UUID hospital;
    private UUID actor;
    private UUID record;
    private UUID staff;
    private UUID encounter;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactions);
        InBasketService inBasket = mock(InBasketService.class);
        AuditEventLogService audit = mock(AuditEventLogService.class);
        review = new OrderReviewHoldServiceImpl(Clock.systemDefaultZone(), holds, prescriptions, requests, administrations,
            chartAccess, roles, mock(PrescriptionControlledResolver.class), mock(PrescriberReauthenticationService.class),
            mock(PrescriptionService.class), mock(TransfusionService.class), inBasket, audit,
            mock(com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler.class));
        disputeService = new DeathDisputeServiceImpl(Clock.systemDefaultZone(), disputes, records, patients, chartAccess, roles,
            assignments, inBasket, audit, review);
        new OrderReviewHoldGuard(holds).afterPropertiesSet();

        SEEDED.forEach(table -> jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER ALL"));
        patient = insert("clinical.patients", Map.of());
        hospital = insert("hospital.hospitals", Map.of());
        actor = UUID.randomUUID();
        record = UUID.randomUUID();
        UUID user = insert("\"security\".users", Map.of());
        staff = insert("hospital.staff", Map.of("user_id", user, "hospital_id", hospital, "employment_type", "FULL_TIME", "job_title", "DOCTOR"));
        encounter = insert("clinical.encounters", Map.of("patient_id", patient, "hospital_id", hospital, "staff_id", staff,
            "encounter_type", "CONSULTATION", "status", "IN_PROGRESS"));
        jdbc.update("INSERT INTO clinical.death_records (id, patient_id, hospital_id, died_at, immediate_cause) VALUES (?, ?, ?, ?, 'Cause')",
            record, patient, hospital, Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        Patient chart = new Patient();
        chart.setId(patient);
        when(chartAccess.require(patient, hospital)).thenReturn(chart);
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.isDoctor(actor, hospital)).thenReturn(true);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(true);
        when(roles.isHospitalAdmin(actor, hospital)).thenReturn(true);
        User admin = new User();
        admin.setId(UUID.randomUUID());
        when(assignments.findLiveUsersAtHospitalWithRoleCodes(any(), any())).thenReturn(List.of(admin));
    }

    @AfterEach
    void tearDown() {
        // A test that left a row locked fails here, in 15 seconds, instead of hanging the worker.
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '15s'");
            SEEDED.forEach(table -> jdbc.execute("ALTER TABLE " + table + " ENABLE TRIGGER ALL"));
        });
        OrderReviewHoldGuard.useHolds(null);
    }

    private static void awaitBounded(CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).as("the other transaction moved on").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void markDeathEnteredInError() {
        jdbc.update("UPDATE clinical.death_records SET entered_in_error_at = now() WHERE id = ?", record);
    }

    /** A row with only what its table refuses to go without; the schema names those, not this test. */
    private UUID insert(String table, Map<String, Object> values) {
        UUID id = UUID.randomUUID();
        StringBuilder columns = new StringBuilder("id");
        StringBuilder marks = new StringBuilder("?");
        java.util.List<Object> args = new java.util.ArrayList<>(List.of(id));
        for (Map.Entry<String, Object> value : values.entrySet()) {
            columns.append(", ").append(value.getKey());
            marks.append(", ?");
            args.add(value.getValue());
        }
        String[] name = table.replace("\"", "").split("\\.");
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND is_nullable = 'NO' AND column_default IS NULL
                """, name[0], name[1])) {
            String col = (String) column.get("column_name");
            if (!"id".equals(col) && !values.containsKey(col)) {
                columns.append(", ").append(col);
                marks.append(", ?");
                args.add(filler((String) column.get("data_type")));
            }
        }
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")", args.toArray());
        return id;
    }

    private static Object filler(String type) {
        return switch (type) {
            case "uuid" -> UUID.randomUUID();
            case "boolean" -> Boolean.FALSE;
            case "integer", "bigint", "smallint" -> 1;
            case "numeric" -> java.math.BigDecimal.ONE;
            case "date" -> java.sql.Date.valueOf("2026-01-01");
            case "timestamp without time zone", "timestamp with time zone" -> Timestamp.valueOf(LocalDateTime.now());
            default -> UUID.randomUUID().toString().substring(0, 6);
        };
    }

    private UUID order(UUID at, String status, String name, boolean takeHome) {
        return insert("clinical.prescriptions", Map.of("patient_id", patient, "hospital_id", at, "status", status,
            "medication_name", name, "take_home", takeHome, "staff_id", staff, "encounter_id", encounter, "version", 0L));
    }

    private UUID signedMorphine() {
        return order(hospital, "SIGNED", "Morphine", false);
    }

    private UUID bloodRequest() {
        return insert("clinical.transfusion_requests", Map.of("patient_id", patient, "hospital_id", hospital, "status", "ISSUED",
            "product_type", "PACKED_RED_CELLS", "urgency", "ROUTINE"));
    }

    private void dispute() {
        tx.executeWithoutResult(status -> disputeService.dispute(patient, DeathDisputeRequestDTO.builder()
            .reason("Seen alive").assessedAlive(true).identityVerified(true).build()));
    }

    private OrderReviewHold holdOf(UUID order) {
        return holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital).stream()
            .filter(row -> row.getOrderId().equals(order)).findFirst().orElseThrow();
    }

    private OrderReviewDecisionDTO reconfirm(UUID order, long version) {
        return OrderReviewDecisionDTO.builder().reason("Still indicated").orderVersion(version)
            .disputeId(holdOf(order).getDisputeId()).build();
    }

    @Test
    @DisplayName("morphine and blood existing at the dispute are refused until reconfirmed; a new order has no hold")
    void refusedUntilReconfirmed() {
        UUID morphine = signedMorphine();
        UUID blood = bloodRequest();

        dispute();

        assertThatThrownBy(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId"))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> OrderReviewHoldGuard.refuseBloodRequestIfHeld(blood, "requestId"))
            .isInstanceOf(FieldBusinessException.class);

        UUID newOrder = signedMorphine();
        assertThatCode(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(newOrder, "prescriptionId")).doesNotThrowAnyException();

        tx.executeWithoutResult(status -> review.continueOrder(holdOf(morphine).getId(), reconfirm(morphine, 0)));
        tx.executeWithoutResult(status -> review.continueOrder(holdOf(blood).getId(), reconfirm(blood, 0)));
        assertThatCode(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId")).doesNotThrowAnyException();
        assertThatCode(() -> OrderReviewHoldGuard.refuseBloodRequestIfHeld(blood, "requestId")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancelled and dispensed take-home orders are not held, nor another hospital's")
    void whatIsNotHeld() {
        UUID cancelled = order(hospital, "CANCELLED", "A", false);
        UUID takeHome = order(hospital, "DISPENSED", "B", true);
        UUID ward = order(hospital, "DISPENSED", "C", false);
        UUID elsewhere = order(insert("hospital.hospitals", Map.of()), "SIGNED", "D", false);
        UUID refused = order(hospital, "PARTNER_REJECTED", "E", false);
        UUID refillLeft = order(hospital, "DISPENSED", "F", true);
        jdbc.update("UPDATE clinical.prescriptions SET refills_remaining = 2 WHERE id = ?", refillLeft);
        UUID partnerSpent = order(hospital, "PARTNER_DISPENSED", "G", false);

        dispute();

        assertThat(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital))
            .extracting(OrderReviewHold::getOrderId).containsExactlyInAnyOrder(ward, refused, refillLeft)
            .doesNotContain(cancelled, takeHome, elsewhere, partnerSpent);
    }

    @Test
    @DisplayName("a repeat dispute adds nothing")
    void repeat() {
        signedMorphine();
        dispute();
        dispute();

        assertThat(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital)).hasSize(1);
    }

    @Test
    @DisplayName("death upheld, and entered in error's closing of the dispute, leave the holds standing and visible")
    void outcomesLeaveTheHolds() {
        UUID morphine = signedMorphine();
        dispute();
        UUID dispute = disputes.findOpen(record, hospital).orElseThrow().getId();

        tx.executeWithoutResult(status -> disputeService.uphold(dispute, DeathDisputeOutcomeDTO.builder().reason("x").build()));

        assertThat(holdOf(morphine).getStatus()).isEqualTo(OrderReviewStatus.HELD);
        assertThatThrownBy(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId"))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(holds.existsByOrderTypeAndOrderIdAndStatus(OrderReviewType.PRESCRIPTION, morphine, OrderReviewStatus.HELD)).isTrue();
    }

    @Test
    @DisplayName("an order edited since it was read: the reconfirmation of the old version is refused")
    void staleVersion() {
        UUID morphine = signedMorphine();
        dispute();
        jdbc.update("UPDATE clinical.prescriptions SET version = version + 1 WHERE id = ?", morphine);
        UUID id = holdOf(morphine).getId();
        OrderReviewDecisionDTO old = reconfirm(morphine, 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> review.continueOrder(id, old)))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("orderVersion"));
        assertThat(holdOf(morphine).getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("an order being dispensed as the dispute opens: the snapshot waits for the lock, and reads what it left")
    void theRaceWithADispense() throws Exception {
        UUID morphine = signedMorphine();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> dispensing = pool.submit(() -> tx.executeWithoutResult(status -> {
                assertThat(prescriptions.findByIdAndHospitalIdForUpdate(morphine, hospital)).isPresent();
                jdbc.update("UPDATE clinical.prescriptions SET status = 'PARTIALLY_FILLED', version = version + 1 WHERE id = ?", morphine);
                held.countDown();
                awaitBounded(release);
            }));
            assertThat(held.await(30, TimeUnit.SECONDS)).as("the dispense holds the order").isTrue();
            Future<?> disputing = pool.submit(this::dispute);
            Thread.sleep(400);
            if (disputing.isDone()) {
                disputing.get(1, TimeUnit.SECONDS);
            }
            assertThat(disputing.isDone()).as("the snapshot waits for the order's lock").isFalse();
            release.countDown();
            dispensing.get(30, TimeUnit.SECONDS);
            disputing.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(holdOf(morphine).getOrderVersion()).as("the version the dispense left").isEqualTo(1L);
        assertThat(holdOf(morphine).getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("two prescribers deciding at once: decided once")
    void decidedOnce() throws Exception {
        UUID morphine = signedMorphine();
        dispute();
        UUID id = holdOf(morphine).getId();
        OrderReviewDecisionDTO decision = reconfirm(morphine, 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    awaitBounded(start);
                    try {
                        tx.executeWithoutResult(status -> review.continueOrder(id, decision));
                        return "done";
                    } catch (com.example.hms.exception.BusinessException e) {
                        return "refused";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new java.util.ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("done", "refused");
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("the death event holds orders at every hospital; a later date correction and entered in error clear nothing")
    void theDeathEventHolds() {
        UUID morphine = signedMorphine();
        UUID blood = bloodRequest();
        UUID elsewhere = insert("hospital.hospitals", Map.of());
        UUID otherOrder = order(elsewhere, "SIGNED", "E", false);

        atTheDeathEvent();

        assertThat(holdOf(morphine).getDisputeId()).isNull();
        assertThat(holdOf(blood).getStatus()).isEqualTo(OrderReviewStatus.HELD);
        assertThat(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, elsewhere))
            .extracting(OrderReviewHold::getOrderId).containsExactly(otherOrder);

        jdbc.update("UPDATE clinical.death_records SET died_at = died_at - interval '3 days' WHERE id = ?", record);
        markDeathEnteredInError();

        assertThatThrownBy(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId"))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a dispute after the death event binds its hospital's rows and adds no second one")
    void aDisputeBindsTheDeathEventsRows() {
        UUID morphine = signedMorphine();
        atTheDeathEvent();

        dispute();

        assertThat(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital)).hasSize(1);
        assertThat(holdOf(morphine).getDisputeId()).isNotNull();
    }

    @Test
    @DisplayName("an edit after the reconfirmation holds the order again")
    void aChangeInvalidates() {
        UUID morphine = signedMorphine();
        dispute();
        tx.executeWithoutResult(status -> review.continueOrder(holdOf(morphine).getId(), reconfirm(morphine, 0)));
        assertThatCode(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId")).doesNotThrowAnyException();

        tx.executeWithoutResult(status -> review.reviewAgainAfterChange(com.example.hms.enums.OrderReviewType.PRESCRIPTION, morphine));

        assertThatThrownBy(() -> OrderReviewHoldGuard.refusePrescriptionIfHeld(morphine, "prescriptionId"))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital))
            .extracting(OrderReviewHold::getStatus)
            .containsExactlyInAnyOrder(OrderReviewStatus.HELD, OrderReviewStatus.CONTINUED);
    }

    @Test
    @DisplayName("the decision's reason is encrypted at rest, and reads back whole")
    void theReasonIsEncryptedAtRest() {
        UUID morphine = signedMorphine();
        dispute();
        UUID row = holdOf(morphine).getId();
        tx.executeWithoutResult(status -> review.continueOrder(row, reconfirm(morphine, 0)));

        assertThat(jdbc.queryForObject("SELECT reason FROM clinical.order_review_holds WHERE id = ?", String.class, row)).startsWith("gcm1:");
        assertThat(holds.findById(row).orElseThrow().getReason()).doesNotStartWith("gcm1:").isNotBlank();
    }

    private void atTheDeathEvent() {
        tx.executeWithoutResult(status -> review.snapshotAtDeath(records.findById(record).orElseThrow()));
    }
}
