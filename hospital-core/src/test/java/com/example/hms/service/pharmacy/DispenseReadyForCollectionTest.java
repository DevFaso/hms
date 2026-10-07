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
import static org.mockito.Mockito.never;
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

    @org.mockito.Spy
    private ControlledSubstanceGuard controlledSubstanceGuard = new ControlledSubstanceGuard();

    static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    static final java.time.Clock FIXED_CLOCK = java.time.Clock.fixed(
            TODAY.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC);

    @org.mockito.Spy
    private DispenseVerificationService dispenseVerificationService = new DispenseVerificationService(FIXED_CLOCK);

    @org.mockito.Spy
    private java.time.Clock clock = FIXED_CLOCK;

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
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));
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
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        if (withLot) {
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
        }
        when(dispenseMapper.toEntity(any(), any())).thenAnswer(inv -> {
            DispenseMapper.DispenseContext ctx = inv.getArgument(1);
            Dispense d = Dispense.builder()
                    .prescription(ctx.prescription())
                    .patient(ctx.patient())
                    .pharmacy(ctx.pharmacy())
                    .stockLot(ctx.stockLot())
                    .dispensedByUser(ctx.dispensedByUser())
                    .medicationName("Amoxicillin")
                    .quantityRequested(BigDecimal.TEN)
                    .quantityDispensed(BigDecimal.TEN)
                    .build();
            return d;
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
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-2: a controlled substance without 2FA is refused like a dispense")
        void controlledSubstanceIsRefused() {
            prescription.setControlledSubstance(true);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
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
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));
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
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(cdsCheckService.checkAtDispense(prescription, patientId)).thenReturn(
                    new CdsAlertResult(CdsAlertSeverity.CRITICAL, java.util.List.of("interaction"), true));

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CDS_CRITICAL");
            assertNothingPrepared();
        }

        @Test
        @DisplayName("AC-3: a second preparation of the same order is a 409, checked under the lock")
        void secondPreparationIsAConflict() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(prescription));
            when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
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

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
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

            assertThatThrownBy(() -> service.markReadyForCollection(request()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Ready for collection is not enabled.");
            assertThatThrownBy(() -> service.markReadyForCollectionTransactionally(request()))
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

        private void assertNothingPrepared() {
            verify(dispenseRepository, never()).save(any());
            verify(stockLotRepository, never()).save(any());
            verify(stockTransactionRepository, never()).save(any());
            verify(support, never()).notifyReadyForCollection(any(), any(), any());
        }
    }
}
