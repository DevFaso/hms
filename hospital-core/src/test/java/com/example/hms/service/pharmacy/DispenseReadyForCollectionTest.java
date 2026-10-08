package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.CdsAlertSeverity;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import org.mockito.ArgumentCaptor;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.DispenseVerificationStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.RefillStatus;
import com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO;
import com.example.hms.payload.dto.pharmacy.HandOverRequestDTO;
import java.time.LocalDateTime;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.LotBarcode;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ready for collection (G15): the prepared-fill paths of
 * {@link DispenseServiceImpl}. The one-step dispense keeps its own test,
 * {@code DispenseServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
class DispenseReadyForCollectionTest {

    @Mock private DispenseRepository dispenseRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private DispenseMapper dispenseMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private CdsCheckService cdsCheckService;
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;
    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private PreparedFillVoider preparedFillVoider;
    @Mock private jakarta.persistence.EntityManager entityManager;

    @org.mockito.Spy
    private ControlledSubstanceGuard controlledSubstanceGuard = new ControlledSubstanceGuard();

    static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    static final java.time.Clock FIXED_CLOCK = java.time.Clock.fixed(
            TODAY.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);

    @org.mockito.Spy
    private DispenseVerificationService dispenseVerificationService = new DispenseVerificationService(FIXED_CLOCK);

    @org.mockito.Spy
    private java.time.Clock clock = FIXED_CLOCK;

    /** G13: the work-queue claim; exit-path releases are verified where they matter. */
    @Mock private com.example.hms.service.pharmacy.PrescriptionQueueClaimService queueClaimService;

    @InjectMocks
    private DispenseServiceImpl service;

    final UUID prescriptionId = UUID.randomUUID();
    final UUID patientId = UUID.randomUUID();
    final UUID pharmacyId = UUID.randomUUID();
    final UUID hospitalId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID stockLotId = UUID.randomUUID();

    Hospital hospital;
    Pharmacy pharmacy;
    Prescription prescription;
    Patient patient;
    User user;
    StockLot stockLot;
    InventoryItem inventoryItem;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        hospital = new Hospital();
        hospital.setId(hospitalId);
        pharmacy = Pharmacy.builder().hospital(hospital).name("Main Pharmacy").build();
        pharmacy.setId(pharmacyId);

        patient = new Patient();
        patient.setId(patientId);

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setMedicationName("Amoxicillin");
        prescription.setQuantity(BigDecimal.TEN);
        prescription.setHospital(hospital);
        prescription.setPatient(patient);

        user = new User();
        user.setId(userId);

        MedicationCatalogItem catalogItem = new MedicationCatalogItem();
        catalogItem.setId(UUID.randomUUID());
        catalogItem.setGenericName("Amoxicillin");
        inventoryItem = InventoryItem.builder()
                .pharmacy(pharmacy)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.valueOf(100))
                .build();
        inventoryItem.setId(UUID.randomUUID());
        stockLot = StockLot.builder()
                .inventoryItem(inventoryItem)
                .lotNumber("AMX-2291")
                .expiryDate(TODAY.plusMonths(9))
                .remainingQuantity(BigDecimal.valueOf(50))
                .barcodeValue(LotBarcode.mint())
                .build();
        stockLot.setId(stockLotId);
        stockMovesLikeTheDatabase();
    }

    /**
     * The atomic stock UPDATEs (#825 security finding 1) act on the fixture
     * as the database would: a decrement applies only when enough is left.
     */
    private void stockMovesLikeTheDatabase() {
        org.mockito.Mockito.lenient().when(stockLotRepository.decrementRemaining(any(), any(), any()))
                .thenAnswer(inv -> {
                    java.math.BigDecimal q = inv.getArgument(1);
                    if (stockLot.getRemainingQuantity().compareTo(q) < 0) return 0;
                    stockLot.setRemainingQuantity(stockLot.getRemainingQuantity().subtract(q));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(stockLotRepository.incrementRemaining(any(), any(), any()))
                .thenAnswer(inv -> {
                    stockLot.setRemainingQuantity(stockLot.getRemainingQuantity().add(inv.getArgument(1)));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(inventoryItemRepository.decrementOnHand(any(), any(), any()))
                .thenAnswer(inv -> {
                    java.math.BigDecimal q = inv.getArgument(1);
                    if (inventoryItem.getQuantityOnHand().compareTo(q) < 0) return 0;
                    inventoryItem.setQuantityOnHand(inventoryItem.getQuantityOnHand().subtract(q));
                    return 1;
                });
        org.mockito.Mockito.lenient().when(inventoryItemRepository.incrementOnHand(any(), any(), any()))
                .thenAnswer(inv -> {
                    inventoryItem.setQuantityOnHand(inventoryItem.getQuantityOnHand().add(inv.getArgument(1)));
                    return 1;
                });
    }

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    DispenseRequestDTO request() {
        return DispenseRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .patientId(patientId)
                .pharmacyId(pharmacyId)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .build();
    }

    @Nested
    @DisplayName("one-step dispense (AC-15, AC-3)")
    class OneStep {

        @ParameterizedTest
        @EnumSource(value = DispenseStatus.class, names = {"PENDING", "CANCELLED"})
        @DisplayName("a client may not assert PENDING or CANCELLED: 400, nothing read or written")
        void clientCannotAssertStatus(DispenseStatus status) {
            DispenseRequestDTO dto = request();
            dto.setStatus(status);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("The dispense status cannot be set directly; use the ready-for-collection actions.");
            verify(prescriptionRepository, never()).findByIdForUpdate(any());
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("an open preparation refuses the one-step fill with 409, before any stock moves")
        void openPreparationRefusesOneStep() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(true);
            DispenseRequestDTO dto = request();
            dto.setStockLotId(stockLotId);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("A fill is prepared for this prescription. Hand it over or cancel the preparation first.");
            verify(stockLotRepository, never()).save(any());
            verify(dispenseRepository, never()).save(any());
        }
    }

    /** Stubs the reads a successful ready makes, up to the save. */
    void stubReadyPath(boolean withLot) {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        if (withLot) {
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
        }
        when(dispenseMapper.toEntity(any(), any())).thenAnswer(inv -> {
            DispenseMapper.DispenseContext ctx = inv.getArgument(1);
            return Dispense.builder()
                    .prescription(ctx.prescription())
                    .patient(ctx.patient())
                    .pharmacy(ctx.pharmacy())
                    .stockLot(ctx.stockLot())
                    .dispensedByUser(ctx.dispensedByUser())
                    .medicationName("Amoxicillin")
                    .quantityRequested(BigDecimal.TEN)
                    .quantityDispensed(BigDecimal.TEN)
                    .build();
        });
        // lenient: a test that makes the save fail never reaches the mapping
        org.mockito.Mockito.lenient().when(dispenseRepository.save(any(Dispense.class))).thenAnswer(inv -> {
            Dispense d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        org.mockito.Mockito.lenient().when(dispenseMapper.toResponseDTO(any(Dispense.class)))
                .thenAnswer(inv -> DispenseResponseDTO.builder()
                        .status(((Dispense) inv.getArgument(0)).getStatus().name()).build());
    }

    @Nested
    @DisplayName("mark ready (AC-1, AC-2, AC-3, AC-17)")
    class MarkReady {

        @Test
        @DisplayName("AC-1: a PENDING row, not handed over, prepared and dispensed by the caller; stock moves, the order does not")
        void preparesAPendingFill() {
            stubReadyPath(true);
            DispenseRequestDTO dto = request();
            dto.setStockLotId(stockLotId);

            DispenseResponseDTO result = service.markReadyForCollection(dto);

            assertThat(result.getStatus()).isEqualTo("PENDING");
            ArgumentCaptor<Dispense> saved = ArgumentCaptor.forClass(Dispense.class);
            verify(dispenseRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(DispenseStatus.PENDING);
            assertThat(saved.getValue().getDispensedAt()).isNull();
            assertThat(saved.getValue().getPreparedByUser()).isSameAs(user);
            assertThat(saved.getValue().getDispensedByUser()).isSameAs(user);
            // the lot and the inventory come off the shelf, with a DISPENSE movement
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("40");
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo("90");
            ArgumentCaptor<StockTransaction> tx = ArgumentCaptor.forClass(StockTransaction.class);
            verify(stockTransactionRepository).save(tx.capture());
            assertThat(tx.getValue().getTransactionType()).isEqualTo(StockTransactionType.DISPENSE);
            // the prescription is untouched and the prescriber hears nothing
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
            verify(prescriptionRepository, never()).save(any());
            verify(dispenseRepository, never()).sumQuantityDispensedForPrescription(any(), any());
            verifyNoInteractions(prescriberNotifier);
            verify(support).logAudit(eq(AuditEventType.DISPENSE_READY), anyString(), anyString(), eq("DISPENSE"));
            verify(support).notifyReadyForCollection(patient, pharmacy, "Amoxicillin");
            verify(support, never()).notifyDispensed(any(), any(), any());
            verify(prescriptionRepository, never()).findById(any());
        }

        @Test
        @DisplayName("AC-1: the wristband is not checked at ready, even when one is sent")
        void patientScanIsIgnoredAtReady() {
            stubReadyPath(false);
            DispenseRequestDTO dto = request();
            dto.setPatientScanValue(UUID.randomUUID().toString()); // somebody else

            assertThatCode(() -> service.markReadyForCollection(dto)).doesNotThrowAnyException();
            ArgumentCaptor<Dispense> saved = ArgumentCaptor.forClass(Dispense.class);
            verify(dispenseRepository).save(saved.capture());
            assertThat(saved.getValue().getPatientScanValue()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED", "PENDING_CLARIFICATION",
                "PARTNER_ACCEPTED", "SENT_TO_PARTNER", "DISPENSED", "DRAFT", "PENDING_SIGNATURE"})
        @DisplayName("AC-2: an order that cannot be dispensed cannot be prepared: 400, nothing written, no SMS")
        void notDispensableIsRefused(PrescriptionStatus status) {
            prescription.setStatus(status);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-2: a controlled substance without 2FA is refused like a dispense")
        void controlledSubstanceIsRefused() {
            prescription.setControlledSubstance(true);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CONTROLLED_SUBSTANCE");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-2: an expired lot is refused and the shelf is left alone")
        void expiredLotIsRefused() {
            stockLot.setExpiryDate(TODAY.minusDays(1));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            DispenseRequestDTO dto = request();
            dto.setStockLotId(stockLotId);

            assertThatThrownBy(() -> service.markReadyForCollection(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired on");
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("50");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-2: a CRITICAL CDS alert without an override reason is refused")
        void criticalCdsIsRefused() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(cdsCheckService.checkAtDispense(prescription, patientId)).thenReturn(
                    new CdsAlertResult(CdsAlertSeverity.CRITICAL, java.util.List.of("interaction"), true));

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CDS_CRITICAL");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-3: a second preparation of the same order is a 409, checked under the lock")
        void secondPreparationIsAConflict() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(true);

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This prescription already has a fill prepared for collection.");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-3: a lost race on the one-open-preparation index is a 409 too, not a 400")
        void lostIndexRaceIsAConflict() {
            stubReadyPath(false);
            when(dispenseRepository.save(any(Dispense.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_disp_one_pending_per_rx"));
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(false, true);

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This prescription already has a fill prepared for collection.");
        }

        @Test
        @DisplayName("AC-15: a status in the ready body is refused")
        void statusInBodyIsRefused() {
            DispenseRequestDTO dto = request();
            dto.setStatus(DispenseStatus.COMPLETED);

            assertThatThrownBy(() -> service.markReadyForCollection(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("The dispense status cannot be set directly; use the ready-for-collection actions.");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-17: with the flag off, ready is a 404 and the settings say so")
        void flagOffIsNotFound() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "readyForCollectionEnabled", false);

            DispenseRequestDTO req = request();
            assertThatThrownBy(() -> service.markReadyForCollection(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Ready for collection is not enabled.");
            assertThatThrownBy(() -> service.markReadyForCollectionTransactionally(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Ready for collection is not enabled.");
            verifyNoInteractions(prescriptionRepository);
            assertThat(service.isReadyForCollectionEnabled()).isFalse();
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-17: the flag is on by default")
        void flagOnByDefault() {
            assertThat(service.isReadyForCollectionEnabled()).isTrue();
        }

        @Test
        @DisplayName("G13 AC-9/AC-10: preparing ends the work-queue claim, PREPARED, by the preparer as a queue role")
        void preparingEndsTheClaim() {
            stubReadyPath(false);

            service.markReadyForCollection(request());

            verify(queueClaimService).releaseOnExit(prescription,
                    com.example.hms.enums.QueueClaimReleaseReason.PREPARED, userId,
                    com.example.hms.enums.QueueClaimExitActor.QUEUE_ROLE);
        }

        private void assertNothingPrepared() {
            // G13 AC-9: a refused preparation keeps the work-queue claim.
            verify(queueClaimService, never()).releaseOnExit(any(), any(), any(), any());
            verify(dispenseRepository, never()).save(any());
            verify(stockLotRepository, never()).save(any());
            verify(stockTransactionRepository, never()).save(any());
            verify(support, never()).notifyReadyForCollection(any(), any(), any());
        }
    }

    /** A fill prepared earlier by {@link #user}, waiting at {@link #pharmacy}. */
    Dispense preparedFill() {
        Dispense d = Dispense.builder()
                .prescription(prescription)
                .patient(patient)
                .pharmacy(pharmacy)
                .stockLot(stockLot)
                .dispensedByUser(user)
                .preparedByUser(user)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(DispenseStatus.PENDING)
                .dispensedAt(null)
                .build();
        d.setId(dispenseId);
        return d;
    }

    /** Scope and lock succeed for {@code d}; the resync re-reads it (not managed in a unit test). */
    void stubLocked(Dispense d) {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findPrescriptionIdById(dispenseId)).thenReturn(Optional.of(prescriptionId));
        when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(d));
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
        org.mockito.Mockito.lenient().when(dispenseMapper.toResponseDTO(any(Dispense.class)))
                .thenAnswer(inv -> DispenseResponseDTO.builder()
                        .id(((Dispense) inv.getArgument(0)).getId())
                        .status(((Dispense) inv.getArgument(0)).getStatus().name()).build());
    }

    /** The conditional UPDATE succeeds, and the resync sees what it wrote. */
    void stubHandOverWrites(Dispense d) {
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(dispenseRepository.completePreparedFill(eq(dispenseId), any(), eq(user), any(), any(), any()))
                .thenAnswer(inv -> {
                    d.setStatus(DispenseStatus.COMPLETED);
                    d.setDispensedAt(inv.getArgument(1));
                    d.setVerificationStatus(inv.getArgument(3));
                    d.setPatientScanValue(inv.getArgument(4));
                    d.setScanVerifiedAt(inv.getArgument(5));
                    return 1;
                });
        org.mockito.Mockito.lenient()
                .when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                .thenReturn(BigDecimal.TEN);
    }

    final UUID dispenseId = UUID.randomUUID();

    @Nested
    @DisplayName("hand-over (AC-4, AC-5, AC-6)")
    class HandOver {

        @Test
        @DisplayName("AC-4: COMPLETED, handed over now by the caller, the order DISPENSED and announced, receipt and audit")
        void handsOver() {
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);

            DispenseResponseDTO result = service.handOver(dispenseId, null);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            verify(dispenseRepository).completePreparedFill(dispenseId, LocalDateTime.now(FIXED_CLOCK), user,
                    DispenseVerificationStatus.NOT_VERIFIED, null, null);
            assertThat(d.getPreparedByUser()).isSameAs(user);
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.DISPENSED);
            verify(refillRequestRepository).findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc(
                    prescriptionId, RefillStatus.APPROVED);
            verify(support).notifyDispensed(patient, pharmacy, "Amoxicillin");
            verify(support).logAudit(eq(AuditEventType.DISPENSE_HANDED_OVER), anyString(),
                    eq(dispenseId.toString()), eq("DISPENSE"));
            // no stock moves at hand-over: it moved at ready
            verifyNoInteractions(stockLotRepository, stockTransactionRepository);
        }

        @Test
        @DisplayName("AC-4: a partial fill hands over as PARTIALLY_FILLED and sends no receipt yet")
        void partialHandOver() {
            prescription.setQuantity(BigDecimal.valueOf(30));
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);

            service.handOver(dispenseId, null);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
            verify(support, never()).notifyDispensed(any(), any(), any());
        }

        @Test
        @DisplayName("AC-5: a repeated hand-over of a prepared fill answers 200 and does nothing")
        void replayDoesNothing() {
            Dispense d = preparedFill();
            d.setStatus(DispenseStatus.COMPLETED);
            stubLocked(d);

            DispenseResponseDTO result = service.handOver(dispenseId, null);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
            verify(support, never()).logAudit(any(), any(), any(), any());
            verify(support, never()).notifyDispensed(any(), any(), any());
            verifyNoInteractions(prescriberNotifier);
        }

        @Test
        @DisplayName("AC-5: a racing hand-over that lost the conditional UPDATE answers the winner's body, once")
        void lostRaceIsAReplay() {
            Dispense d = preparedFill();
            stubLocked(d);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseRepository.completePreparedFill(any(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        d.setStatus(DispenseStatus.COMPLETED); // the other one won
                        return 0;
                    });

            DispenseResponseDTO result = service.handOver(dispenseId, null);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            verify(support, never()).logAudit(any(), any(), any(), any());
            verifyNoInteractions(prescriberNotifier);
        }

        @Test
        @DisplayName("AC-5: a one-step COMPLETED fill was never prepared: 409")
        void oneStepFillIsNotPending() {
            Dispense d = preparedFill();
            d.setStatus(DispenseStatus.COMPLETED);
            d.setPreparedByUser(null);
            stubLocked(d);

            assertThatThrownBy(() -> service.handOver(dispenseId, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This fill is no longer waiting for collection.");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = PrescriptionStatus.class, names = {"CANCELLED", "DISCONTINUED",
                "PENDING_CLARIFICATION", "PARTNER_ACCEPTED", "DISPENSED"})
        @DisplayName("AC-6: the order re-read under the lock is no longer dispensable: 409, nothing changes")
        void orderNoLongerDispensable(PrescriptionStatus status) {
            prescription.setStatus(status);
            stubLocked(preparedFill());

            assertThatThrownBy(() -> service.handOver(dispenseId, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("This prescription can no longer be handed over.");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-6: the lot expired while the bag waited: 400, never overridable")
        void expiredAtHandOver() {
            stockLot.setExpiryDate(TODAY.minusDays(1));
            stubLocked(preparedFill());

            assertThatThrownBy(() -> service.handOver(dispenseId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired on");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-6: a wristband that is somebody else's: 400")
        void wrongPatient() {
            stubLocked(preparedFill());
            HandOverRequestDTO body = HandOverRequestDTO.builder()
                    .patientScanValue(UUID.randomUUID().toString()).build();

            assertThatThrownBy(() -> service.handOver(dispenseId, body))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("different patient");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-6: the controlled-substance guard is re-run: 400")
        void controlledAtHandOver() {
            prescription.setControlledSubstance(true);
            stubLocked(preparedFill());

            assertThatThrownBy(() -> service.handOver(dispenseId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CONTROLLED_SUBSTANCE");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("rule 5: the right wristband makes the fill VERIFIED and is stored with its time")
        void rightWristbandVerifies() {
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);
            HandOverRequestDTO body = HandOverRequestDTO.builder()
                    .patientScanValue(" " + patientId + " ").notes("Collected by the patient").build();

            service.handOver(dispenseId, body);

            verify(dispenseRepository).completePreparedFill(dispenseId, LocalDateTime.now(FIXED_CLOCK), user,
                    DispenseVerificationStatus.VERIFIED, patientId.toString(), LocalDateTime.now(FIXED_CLOCK));
            assertThat(d.getNotes()).isEqualTo("Collected by the patient");
        }

        @Test
        @DisplayName("rule 5: OVERRIDDEN at ready stays OVERRIDDEN; a product scan at ready alone is VERIFIED")
        void mergeRule() {
            Dispense overridden = preparedFill();
            overridden.setVerificationStatus(DispenseVerificationStatus.OVERRIDDEN);
            stubLocked(overridden);
            stubHandOverWrites(overridden);

            service.handOver(dispenseId, null);

            verify(dispenseRepository).completePreparedFill(eq(dispenseId), any(), eq(user),
                    eq(DispenseVerificationStatus.OVERRIDDEN), any(), any());

            Dispense scanned = preparedFill();
            scanned.setProductScanValue("LOT-LABEL");
            scanned.setScanVerifiedAt(LocalDateTime.of(2026, 10, 6, 8, 0));
            prescription.setStatus(PrescriptionStatus.SIGNED);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(scanned));
            stubHandOverWrites(scanned);

            service.handOver(dispenseId, null);

            verify(dispenseRepository).completePreparedFill(dispenseId, LocalDateTime.now(FIXED_CLOCK), user,
                    DispenseVerificationStatus.VERIFIED, null, LocalDateTime.of(2026, 10, 6, 8, 0));
        }

        @Test
        @DisplayName("#825 round 2: a hand-over note that fits exactly is appended")
        void noteThatFitsIsAppended() {
            Dispense d = preparedFill();
            d.setNotes("p".repeat(600));
            stubLocked(d);
            stubHandOverWrites(d);

            service.handOver(dispenseId, HandOverRequestDTO.builder().notes("h".repeat(399)).build());

            assertThat(d.getNotes()).hasSize(1000);
        }

        @Test
        @DisplayName("#825 round 2: a note one character too long is refused before any write, naming what fits")
        void noteTooLongIsRefusedBeforeAnyWrite() {
            Dispense d = preparedFill();
            d.setNotes("p".repeat(600));
            stubLocked(d);

            HandOverRequestDTO tooLong = HandOverRequestDTO.builder().notes("h".repeat(400)).build();
            assertThatThrownBy(() -> service.handOver(dispenseId, tooLong))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("The hand-over note is too long for this fill's notes; at most 399 characters fit.");
            verify(dispenseRepository, never()).completePreparedFill(any(), any(), any(), any(), any(), any());
            verifyNoInteractions(prescriberNotifier);
        }

        @Test
        @DisplayName("#825 round 3: a retried hand-over carrying its note answers the same 200; the stored note does not refuse it")
        void retryWithItsNoteIsAReplay() {
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);
            HandOverRequestDTO body = HandOverRequestDTO.builder().notes("h".repeat(600)).build();

            DispenseResponseDTO first = service.handOver(dispenseId, body);
            DispenseResponseDTO retry = service.handOver(dispenseId, body);

            assertThat(first.getStatus()).isEqualTo("COMPLETED");
            assertThat(retry.getStatus()).isEqualTo("COMPLETED");
            assertThat(retry.getId()).isEqualTo(first.getId());
            assertThat(d.getNotes()).hasSize(600);
            // the side effects ran once, for the first call only
            verify(dispenseRepository, times(1))
                    .completePreparedFill(any(), any(), any(), any(), any(), any());
            verify(support, times(1)).logAudit(eq(AuditEventType.DISPENSE_HANDED_OVER),
                    anyString(), anyString(), eq("DISPENSE"));
            verify(support, times(1)).notifyDispensed(any(), any(), any());
            verify(prescriberNotifier, times(1)).notifyPrescriber(any(), any());
        }

        @Test
        @DisplayName("#825 round 2: with no preparation note the whole 1000 is available")
        void noPreparationNoteLeavesTheWholeField() {
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);

            service.handOver(dispenseId, HandOverRequestDTO.builder().notes("h".repeat(1000)).build());

            assertThat(d.getNotes()).hasSize(1000);
        }

        @Test
        @DisplayName("AC-17: hand-over still works with the flag off")
        void flagOffStillHandsOver() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "readyForCollectionEnabled", false);
            Dispense d = preparedFill();
            stubLocked(d);
            stubHandOverWrites(d);

            assertThat(service.handOver(dispenseId, null).getStatus()).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("cancel preparation (AC-7)")
    class CancelReady {

        @ParameterizedTest
        @EnumSource(value = ReadyCancelReason.class, names = {"PRESCRIPTION_WITHDRAWN", "PRESCRIPTION_CHANGED"})
        @DisplayName("a system reason, or none, is a 400 before anything is read")
        void systemReasonIsRefused(ReadyCancelReason reason) {
            CancelReadyRequestDTO systemReason = new CancelReadyRequestDTO(reason);
            CancelReadyRequestDTO noReason = new CancelReadyRequestDTO(null);
            assertThatThrownBy(() -> service.cancelReady(dispenseId, systemReason))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.cancelReady(dispenseId, noReason))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(dispenseRepository, preparedFillVoider);
        }

        @Test
        @DisplayName("scope, the lock, then the voider with the pharmacist's reason")
        void delegatesToTheVoider() {
            Dispense d = preparedFill();
            stubLocked(d);
            when(preparedFillVoider.cancel(d, ReadyCancelReason.STOCK_UNAVAILABLE)).thenAnswer(inv -> {
                d.setStatus(DispenseStatus.CANCELLED);
                return d;
            });

            DispenseResponseDTO result = service.cancelReady(dispenseId,
                    new CancelReadyRequestDTO(ReadyCancelReason.STOCK_UNAVAILABLE));

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(prescriptionRepository, preparedFillVoider);
            order.verify(prescriptionRepository).findByIdAndHospitalIdForUpdate(eq(prescriptionId), any());
            order.verify(preparedFillVoider).cancel(d, ReadyCancelReason.STOCK_UNAVAILABLE);
        }

        @Test
        @DisplayName("a row that is not PENDING: 409, the voider is not called")
        void notPending() {
            Dispense d = preparedFill();
            d.setStatus(DispenseStatus.COMPLETED);
            stubLocked(d);

            CancelReadyRequestDTO other = new CancelReadyRequestDTO(ReadyCancelReason.OTHER);
            assertThatThrownBy(() -> service.cancelReady(dispenseId, other))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(preparedFillVoider);
        }

        @Test
        @DisplayName("the old /cancel still refuses a PENDING row")
        void oldCancelRefusesPending() {
            Dispense d = preparedFill();
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(d));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only completed or partial");
        }
    }

    @Nested
    @DisplayName("tenancy (AC-14)")
    class Tenancy {

        private final com.example.hms.exception.GlobalExceptionHandler handler =
                new com.example.hms.exception.GlobalExceptionHandler();

        @SuppressWarnings("unchecked")
        private java.util.Map<String, Object> bodyOf(Runnable call) {
            org.springframework.web.context.request.WebRequest request =
                    mock(org.springframework.web.context.request.WebRequest.class);
            when(request.getDescription(false)).thenReturn("uri=/api/pharmacy/dispense/x");
            try {
                call.run();
            } catch (com.example.hms.exception.ResourceNotFoundException ex) {
                java.util.Map<String, Object> body = new java.util.HashMap<>(
                        (java.util.Map<String, Object>) handler.handleResourceNotFoundException(ex, request).getBody());
                body.remove("timestamp");
                body.remove("path");
                return body;
            }
            throw new AssertionError("expected a 404");
        }

        private java.util.List<java.util.Map<String, Object>> threeRefusals(Runnable call) {
            // a random id
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findPrescriptionIdById(dispenseId)).thenReturn(Optional.empty());
            java.util.Map<String, Object> randomId = bodyOf(call);

            // a dispense at another hospital's pharmacy
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            Pharmacy foreign = Pharmacy.builder().hospital(other).name("Elsewhere").build();
            Dispense d = preparedFill();
            d.setPharmacy(foreign);
            when(dispenseRepository.findPrescriptionIdById(dispenseId)).thenReturn(Optional.of(prescriptionId));
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(d));
            java.util.Map<String, Object> foreignHospital = bodyOf(call);

            // a caller with no hospital scope
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            java.util.Map<String, Object> nullScope = bodyOf(call);

            return java.util.List.of(randomId, foreignHospital, nullScope);
        }

        @Test
        @DisplayName("hand-over: a random id, another hospital's fill and a null scope answer the same 404 body")
        void handOverAnswersTheSame() {
            var bodies = threeRefusals(() -> service.handOver(dispenseId, null));

            assertThat(bodies.get(0)).containsEntry("status", 404)
                    .containsEntry("message", "Dispense record not found");
            assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
            assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
            verify(prescriptionRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("cancel-ready: the same three answer the same 404 body")
        void cancelReadyAnswersTheSame() {
            var bodies = threeRefusals(() -> service.cancelReady(dispenseId,
                    new CancelReadyRequestDTO(ReadyCancelReason.OTHER)));

            assertThat(bodies.get(0)).containsEntry("status", 404)
                    .containsEntry("message", "Dispense record not found");
            assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
            assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
            verifyNoInteractions(preparedFillVoider);
        }
    }

    @Nested
    @DisplayName("work queue (AC-11)")
    class WorkQueue {

        private final LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);

        private Dispense preparedAt(Prescription rx, LocalDateTime readyAt) {
            User preparer = new User();
            preparer.setId(UUID.randomUUID());
            preparer.setFirstName("Awa");
            preparer.setLastName("Ouedraogo");
            Dispense d = Dispense.builder()
                    .prescription(rx)
                    .preparedByUser(preparer)
                    .dispensedByUser(preparer)
                    .medicationName("Amoxicillin")
                    .quantityRequested(BigDecimal.TEN)
                    .quantityDispensed(BigDecimal.TEN)
                    .unit("tablets")
                    .status(DispenseStatus.PENDING)
                    .dispensedAt(null)
                    .build();
            d.setId(UUID.randomUUID());
            d.setCreatedAt(readyAt);
            return d;
        }

        private java.util.List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> queue(
                java.util.List<Prescription> rows, java.util.List<Dispense> open) {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(rows));
            when(dispenseRepository.findByPrescription_IdInAndStatus(any(), eq(DispenseStatus.PENDING)))
                    .thenReturn(open);
            return service.getWorkQueue(org.springframework.data.domain.PageRequest.of(0, 20)).getContent();
        }

        private Prescription another() {
            Prescription rx = new Prescription();
            rx.setId(UUID.randomUUID());
            rx.setStatus(PrescriptionStatus.SIGNED);
            rx.setMedicationName("Paracetamol");
            return rx;
        }

        @Test
        @DisplayName("a prepared order carries its preparation; the others carry none; one query for the page")
        void decoratesThePreparedRows() {
            Prescription plain = another();
            Dispense prepared = preparedAt(prescription, now.minusHours(2));

            var rows = queue(java.util.List.of(prescription, plain), java.util.List.of(prepared));

            var ready = rows.get(0).getReadyForCollection();
            assertThat(ready).isNotNull();
            assertThat(ready.getDispenseId()).isEqualTo(prepared.getId());
            assertThat(ready.getReadyAt()).isEqualTo(now.minusHours(2));
            assertThat(ready.getPreparedByName()).isEqualTo("Awa Ouedraogo");
            assertThat(ready.getQuantity()).isEqualByComparingTo("10");
            assertThat(ready.getUnit()).isEqualTo("tablets");
            assertThat(ready.getReminderSentAt()).isNull();
            assertThat(rows.get(0).getAttentionReason()).isNull();
            assertThat(rows.get(1).getReadyForCollection()).isNull();
            verify(dispenseRepository, times(1))
                    .findByPrescription_IdInAndStatus(any(), eq(DispenseStatus.PENDING));
        }

        @Test
        @DisplayName("READY_UNCOLLECTED once it has waited longer than 7 days, not before")
        void uncollectedAfterSevenDays() {
            Prescription fresh = another();
            var rows = queue(java.util.List.of(prescription, fresh), java.util.List.of(
                    preparedAt(prescription, now.minusDays(7).minusMinutes(1)),
                    preparedAt(fresh, now.minusDays(6))));

            assertThat(rows.get(0).getAttentionReason()).isEqualTo("READY_UNCOLLECTED");
            assertThat(rows.get(0).isNeedsAttention()).isTrue();
            assertThat(rows.get(1).getAttentionReason()).isNull();
            assertThat(rows.get(1).isNeedsAttention()).isFalse();
        }

        @Test
        @DisplayName("READY_UNCOLLECTED is the lowest precedence: a status reason wins")
        void statusReasonWins() {
            prescription.setStatus(PrescriptionStatus.PENDING_STOCK);

            var rows = queue(java.util.List.of(prescription), java.util.List.of(
                    preparedAt(prescription, now.minusDays(30))));

            assertThat(rows.get(0).getAttentionReason()).isEqualTo("PENDING_STOCK");
        }

        @Test
        @DisplayName("preparing is acting on an answered question: the CLARIFICATION_RESOLVED cue clears (coalesce)")
        void preparingActsOnTheAnswer() {
            prescription.setClarificationResolvedAt(now.minusHours(5));
            Dispense prepared = preparedAt(prescription, now.minusHours(1));
            when(dispenseRepository.findByPrescription_IdInAndStatusNotOrderByDispensedAtDesc(any(), eq(DispenseStatus.CANCELLED)))
                    .thenReturn(java.util.List.of(prepared));

            var rows = queue(java.util.List.of(prescription), java.util.List.of(prepared));

            assertThat(rows.get(0).getAttentionReason()).isNull();
            assertThat(rows.get(0).getClarificationResolvedAt()).isNull();
        }
    }
}
