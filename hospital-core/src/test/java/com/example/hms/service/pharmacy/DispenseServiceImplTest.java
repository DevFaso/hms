package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.CdsAlertSeverity;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.DispenseVerificationStatus;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.LotBarcode;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispenseServiceImplTest {

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
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PharmacyServiceSupport support;
    @Mock private CdsCheckService cdsCheckService;
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;
    @Mock private com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository routingDecisionRepository;

    // Real, not mocked: the guard is a pure rule with its own test.
    @org.mockito.Spy
    private ControlledSubstanceGuard controlledSubstanceGuard = new ControlledSubstanceGuard();

    /**
     * Also real, for the same reason — a pure rule with its own test
     * (DispenseVerificationServiceTest). Mocking it here would make these
     * tests assert that the service CALLS a gate rather than that the gate
     * stops anything, which is exactly how the reservation transitions in
     * #515 ended up with no test at all.
     */
    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 8, 25);

    private static final java.time.Clock FIXED_CLOCK = java.time.Clock.fixed(
        TODAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC);

    @org.mockito.Spy
    private DispenseVerificationService dispenseVerificationService =
        new DispenseVerificationService(FIXED_CLOCK);

    /** The service's own clock, so dispensedAt and scanVerifiedAt are pinned too. */
    @org.mockito.Spy
    private java.time.Clock clock = FIXED_CLOCK;

    @InjectMocks
    private DispenseServiceImpl service;

    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID dispenseId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID stockLotId = UUID.randomUUID();

    private Hospital hospital;
    private Pharmacy pharmacy;
    private Prescription prescription;
    private Patient patient;
    private User user;
    private StockLot stockLot;
    private InventoryItem inventoryItem;
    private MedicationCatalogItem catalogItem;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);

        pharmacy = Pharmacy.builder().hospital(hospital).name("Main Pharmacy").build();
        pharmacy.setId(pharmacyId);

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setMedicationName("Amoxicillin");
        prescription.setQuantity(BigDecimal.TEN);

        patient = new Patient();
        patient.setId(patientId);
        prescription.setHospital(hospital);
        prescription.setPatient(patient);

        user = new User();
        user.setId(userId);

        // The catalogue item and the expiry date are not decoration: expiry
        // is NOT NULL in the schema, so the lot this fixture used to build —
        // no expiry, no linked medication — could never have existed in a
        // real database. V138 made that unreality visible by giving the
        // columns their first reader.
        catalogItem = new MedicationCatalogItem();
        catalogItem.setId(UUID.randomUUID());
        catalogItem.setGenericName("Amoxicillin");
        catalogItem.setNameFr("Amoxicilline");

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

    private DispenseRequestDTO buildRequest() {
        return DispenseRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .patientId(patientId)
                .pharmacyId(pharmacyId)
                .dispensedBy(userId)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .build();
    }

    private Dispense buildDispense(DispenseStatus status) {
        Dispense d = Dispense.builder()
                .prescription(prescription)
                .patient(patient)
                .pharmacy(pharmacy)
                .dispensedByUser(user)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(status)
                .build();
        d.setId(dispenseId);
        return d;
    }

    @Nested
    @DisplayName("createDispense")
    class CreateDispense {

        @Test
        @DisplayName("should dispense fully without stock lot")
        void shouldDispenseFullyWithoutStockLot() {
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any(Prescription.class))).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            DispenseResponseDTO result = service.createDispense(dto);

            assertThat(result.getMedicationName()).isEqualTo("Amoxicillin");
            verify(dispenseRepository).save(any(Dispense.class));
            verify(prescriptionRepository).save(any(Prescription.class));
            verify(stockLotRepository, never()).save(any());
        }

        @Test
        @DisplayName("should dispense with stock lot and decrement inventory")
        void shouldDispenseWithStockLot() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(40));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(90));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
            verify(stockTransactionRepository).save(any());
        }

        @Test
        @DisplayName("G5: a prescription awaiting clarification is not dispensable")
        void shouldRejectPendingClarification() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("G3: a back-ordered prescription is dispensable once stock lands, and the back order closes")
        void shouldDispensePendingStockAndCloseTheBackOrder() {
            prescription.setStatus(PrescriptionStatus.PENDING_STOCK);
            com.example.hms.model.pharmacy.PrescriptionRoutingDecision backOrder =
                    com.example.hms.model.pharmacy.PrescriptionRoutingDecision.builder()
                            .prescription(prescription)
                            .routingType(com.example.hms.enums.RoutingType.BACKORDER)
                            .status(com.example.hms.enums.RoutingDecisionStatus.PENDING)
                            .build();
            backOrder.setId(UUID.randomUUID());
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescriptionId))
                    .thenReturn(List.of(backOrder));

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            assertThat(backOrder.getStatus()).isEqualTo(com.example.hms.enums.RoutingDecisionStatus.COMPLETED);
            verify(routingDecisionRepository).save(backOrder);
        }

        @Test
        @DisplayName("a back order filled over two dispenses closes when the second fill reaches DISPENSED")
        void secondFillClosesTheBackOrder() {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            com.example.hms.model.pharmacy.PrescriptionRoutingDecision backOrder =
                    com.example.hms.model.pharmacy.PrescriptionRoutingDecision.builder()
                            .prescription(prescription)
                            .routingType(com.example.hms.enums.RoutingType.BACKORDER)
                            .status(com.example.hms.enums.RoutingDecisionStatus.PENDING)
                            .build();
            backOrder.setId(UUID.randomUUID());
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(6));
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescriptionId))
                    .thenReturn(List.of(backOrder));

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            assertThat(backOrder.getStatus()).isEqualTo(com.example.hms.enums.RoutingDecisionStatus.COMPLETED);
        }

        @Test
        @DisplayName("a PARTIAL fill against a back order leaves the back order pending — the remainder is still unavailable")
        void partialFillKeepsTheBackOrderPending() {
            prescription.setStatus(PrescriptionStatus.PENDING_STOCK);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(4));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(4));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
            verify(routingDecisionRepository, never()).findByPrescriptionIdOrderByDecidedAtDesc(any());
            verify(routingDecisionRepository, never()).save(any());
        }

        @Test
        @DisplayName("G3: a partner-rejected prescription can be filled in-house")
        void shouldDispensePartnerRejected() {
            prescription.setStatus(PrescriptionStatus.PARTNER_REJECTED);
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);
            dto.setQuantityDispensed(BigDecimal.valueOf(4));

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(4));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
            // G6: a partial fill is reported to the prescriber too
            verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PARTIALLY_FILLED);
            verify(routingDecisionRepository, never()).findByPrescriptionIdOrderByDecidedAtDesc(any());
        }

        @Test
        @DisplayName("G3 round 4: an order a partner accepted is NOT fillable in-house — the no-show has to be recorded first")
        void partnerAcceptedIsNotDispensable() {
            prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject non-dispensable prescription status")
        void shouldRejectNonDispensableStatus() {
            prescription.setStatus(PrescriptionStatus.DRAFT);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
        }

        @Test
        @DisplayName("should reject when stock lot has insufficient quantity")
        void shouldRejectInsufficientLotStock() {
            stockLot.setRemainingQuantity(BigDecimal.valueOf(5));
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Insufficient lot stock");
        }

        @Test
        @DisplayName("should throw when prescription not found")
        void shouldThrowWhenPrescriptionNotFound() {
            DispenseRequestDTO dto = buildRequest();
            // Scoped caller: the lookup is the thing under test here, so the
            // hospital must be present or the null-scope guard answers first.
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a super-admin in GLOBAL view is refused: recording a fill is a write")
        void globalViewSuperAdminCannotRecordAFill() {
            DispenseRequestDTO dto = buildRequest();

            // Reading across tenants is what global view is for; booking a
            // stock movement against one hospital's order is not. Refused
            // before this change too — as a 500 from the dereference.
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("should set PARTIALLY_FILLED when quantity dispensed < requested")
        void shouldSetPartiallyFilled() {
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(5));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("PARTIAL").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
        }

        @Test
        @DisplayName("refill fill: a partial second fill is PARTIALLY_FILLED, not DISPENSED")
        void refillPartialFillIsNotMistakenForComplete() {
            // Approving a refill returns the prescription to the queue, so the
            // lifetime dispensed sum already covers the first fill. Comparing
            // against `quantity` alone would call this second, partial fill
            // complete. Entitlement is quantity * (1 + refillsUsed).
            prescription.setRefillsUsed(1);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(3));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            // 10 from the original fill + 3 of the refill's 10.
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(13));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(
                    DispenseResponseDTO.builder().id(dispenseId).status("PARTIAL").build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
            verify(refillRequestRepository, never()).save(any(RefillRequest.class));
        }

        @Test
        @DisplayName("refill fill: completing it closes the approved refill out as DISPENSED")
        void completedRefillFillClosesOutTheRequest() {
            // RefillStatus.DISPENSED was declared when the feature shipped and
            // nothing ever wrote it, so a collected refill still read "Approved".
            prescription.setRefillsUsed(1);
            RefillRequest approved = new RefillRequest();
            approved.setId(UUID.randomUUID());
            approved.setStatus(RefillStatus.APPROVED);

            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(20));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(
                    DispenseResponseDTO.builder().id(dispenseId).status("COMPLETED").build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(refillRequestRepository.findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc(
                    prescriptionId, RefillStatus.APPROVED)).thenReturn(Optional.of(approved));

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            assertThat(approved.getStatus()).isEqualTo(RefillStatus.DISPENSED);
            verify(refillRequestRepository).save(approved);
        }

        @Test
        @DisplayName("refill bookkeeping never fails a dispense that physically happened")
        void refillCloseOutFailureDoesNotFailTheDispense() {
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(10));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(
                    DispenseResponseDTO.builder().id(dispenseId).status("COMPLETED").build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(refillRequestRepository.findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc(
                    prescriptionId, RefillStatus.APPROVED)).thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> service.createDispense(dto)).doesNotThrowAnyException();
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
        }

        @Test
        @DisplayName("T-38: should send ready-for-pickup SMS when Rx is fully DISPENSED")
        void shouldSendReadyForPickupSmsOnFullDispense() {
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
            verify(support).notifyDispensed(patient, pharmacy, "Amoxicillin");
            // G6: the prescriber is told the order was filled
            verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.DISPENSED);
        }

        @Test
        @DisplayName("P-04: should emit DISPENSE_SUBSTITUTED audit event when substitution flag is true")
        void shouldEmitSubstitutedAuditOnSubstitution() {
            DispenseRequestDTO dto = buildRequest();
            dto.setSubstitution(Boolean.TRUE);
            dto.setSubstitutionReason("Brand X out of stock; substituted with Brand Y");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            // The DISPENSE_CREATED event always fires; the new DISPENSE_SUBSTITUTED event
            // fires in addition when substitution=true so substitutions are queryable.
            verify(support).logAudit(eq(AuditEventType.DISPENSE_CREATED), any(), any(), any());
            verify(support).logAudit(eq(AuditEventType.DISPENSE_SUBSTITUTED),
                    contains("Brand X out of stock"), eq(dispenseId.toString()), any());
        }

        @Test
        @DisplayName("P-04: should NOT emit DISPENSE_SUBSTITUTED when substitution flag is absent")
        void shouldNotEmitSubstitutedAuditWhenNoSubstitution() {
            DispenseRequestDTO dto = buildRequest();
            // substitution flag deliberately left null
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(support, never()).logAudit(eq(AuditEventType.DISPENSE_SUBSTITUTED), any(), any(), any());
        }

        @Test
        @DisplayName("P-08: should block dispense on CRITICAL CDS alert without override reason")
        void shouldBlockOnCriticalCdsWithoutOverride() {
            DispenseRequestDTO dto = buildRequest();
            // No cdsOverrideReason set — must block

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(cdsCheckService.checkAtDispense(prescription, patientId)).thenReturn(
                    new CdsAlertResult(CdsAlertSeverity.CRITICAL,
                            java.util.List.of("[CONTRAINDICATED] Drug A ↔ Drug B"), true));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CDS_CRITICAL");

            // No state should have mutated.
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("P-08: should proceed when CRITICAL CDS alert is overridden with a reason")
        void shouldProceedOnCriticalCdsWithOverride() {
            DispenseRequestDTO dto = buildRequest();
            dto.setCdsOverrideReason("Specialist consulted; benefit outweighs risk");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(cdsCheckService.checkAtDispense(prescription, patientId)).thenReturn(
                    new CdsAlertResult(CdsAlertSeverity.CRITICAL,
                            java.util.List.of("[MAJOR] Drug A ↔ Drug B"), true));

            DispenseResponseDTO result = service.createDispense(dto);

            assertThat(result.getId()).isEqualTo(dispenseId);
            verify(dispenseRepository).save(any(Dispense.class));
        }

        @Test
        @DisplayName("T-38: should NOT send ready-for-pickup SMS on partial fill")
        void shouldNotSendSmsOnPartialFill() {
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(5));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("PARTIAL").build();

            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(support, never()).notifyDispensed(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getDispense")
    class GetDispense {

        @Test
        @DisplayName("should return dispense when found")
        void shouldReturnDispense() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder().id(dispenseId).build();

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseMapper.toResponseDTO(dispense)).thenReturn(responseDTO);

            DispenseResponseDTO result = service.getDispense(dispenseId);

            assertThat(result.getId()).isEqualTo(dispenseId);
        }

        @Test
        @DisplayName("should throw when dispense not found")
        void shouldThrowWhenNotFound() {
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDispense(dispenseId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("listByPrescription")
    class ListByPrescription {

        @Test
        @DisplayName("should return page of dispenses")
        void shouldReturnPage() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(dispenseRepository.findByPrescriptionId(prescriptionId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPrescription(prescriptionId, pageable);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("a super-admin in GLOBAL view has no hospital: the read is unscoped, not a 500")
        void globalViewSuperAdminReadsUnscoped() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);

            // requireActiveHospitalId returns null for that caller, and the
            // discrete JWT claim is what says the null is a real super-admin
            // rather than an inflated authorities collection.
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(dispenseRepository.findByPrescriptionId(prescriptionId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            assertThat(service.listByPrescription(prescriptionId, pageable).getContent())
                    .containsExactly(dto);
        }

        @Test
        @DisplayName("a null hospital WITHOUT the JWT claim is refused, not served cross-tenant")
        void inflatedAuthoritiesDoNotEarnAnUnscopedRead() {
            Pageable pageable = PageRequest.of(0, 20);
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);

            // The step-4 fallback in requireActiveHospitalId reads the
            // AUTHORITIES, which RoleValidator warns can be inflated.
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.listByPrescription(prescriptionId, pageable))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a scoped caller still gets 404 for a prescription at another hospital")
        void scopedCallerStillNarrowed() {
            Pageable pageable = PageRequest.of(0, 20);
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.listByPrescription(prescriptionId, pageable))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("cancelDispense")
    class CancelDispense {

        @Test
        @DisplayName("should cancel and reverse stock")
        void shouldCancelAndReverseStock() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setStockLot(stockLot);
            stockLot.setRemainingQuantity(BigDecimal.valueOf(40));
            inventoryItem.setQuantityOnHand(BigDecimal.valueOf(90));
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("CANCELLED").build();

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(dispenseMapper.toResponseDTO(dispense)).thenReturn(responseDTO);

            DispenseResponseDTO result = service.cancelDispense(dispenseId);

            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(50));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(100));
            verify(stockLotRepository).save(stockLot);
            verify(inventoryItemRepository).save(inventoryItem);
            verify(stockTransactionRepository).save(any());
            // Undoing a fill is bookkeeping, not a pharmacy outcome: nothing is announced.
            verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        }

        @Test
        @DisplayName("cancelling an earlier partial on an order that is with a partner keeps SENT_TO_PARTNER")
        void cancelKeepsPharmacyOwnedStates() {
            for (PrescriptionStatus owned : List.of(PrescriptionStatus.SENT_TO_PARTNER,
                    PrescriptionStatus.PENDING_STOCK, PrescriptionStatus.PARTNER_ACCEPTED)) {
                prescription.setStatus(owned);
                Dispense dispense = buildDispense(DispenseStatus.PARTIAL);
                when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
                when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
                when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
                when(dispenseMapper.toResponseDTO(dispense))
                        .thenReturn(DispenseResponseDTO.builder().id(dispenseId).status("CANCELLED").build());

                service.cancelDispense(dispenseId);

                assertThat(prescription.getStatus()).as(owned.name()).isEqualTo(owned);
            }
            verify(prescriptionRepository, never()).save(any());
            verify(dispenseRepository, never()).sumQuantityDispensedForPrescription(any(), any());
        }

        @Test
        @DisplayName("cancelling a dispense on an order awaiting clarification keeps PENDING_CLARIFICATION")
        void cancelKeepsPendingClarification() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
            Dispense dispense = buildDispense(DispenseStatus.PARTIAL);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(dispenseMapper.toResponseDTO(dispense))
                    .thenReturn(DispenseResponseDTO.builder().id(dispenseId).status("CANCELLED").build());

            service.cancelDispense(dispenseId);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_CLARIFICATION);
            verify(prescriptionRepository, never()).save(any());
            verify(dispenseRepository, never()).sumQuantityDispensedForPrescription(any(), any());
            verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        }

        @Test
        @DisplayName("should reject cancellation of already cancelled dispense")
        void shouldRejectAlreadyCancelled() {
            Dispense dispense = buildDispense(DispenseStatus.CANCELLED);

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already cancelled");
        }

        @Test
        @DisplayName("should reject cancellation of PENDING dispense")
        void shouldRejectPendingCancellation() {
            Dispense dispense = buildDispense(DispenseStatus.PENDING);

            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only completed or partial");
        }
    }

    @Nested
    @DisplayName("createDispense validation")
    class CreateDispenseValidation {

        @Test
        @DisplayName("should reject zero quantity requested")
        void shouldRejectZeroQuantityRequested() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Quantity requested");
        }

        @Test
        @DisplayName("should reject zero quantity dispensed")
        void shouldRejectZeroQuantityDispensed() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Quantity dispensed must");
        }

        @Test
        @DisplayName("should reject dispensed greater than requested")
        void shouldRejectDispensedGreaterThanRequested() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(BigDecimal.valueOf(5));
            dto.setQuantityDispensed(BigDecimal.TEN);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot exceed");
        }

        @Test
        @DisplayName("should reject when prescription hospital != active hospital")
        void shouldRejectCrossHospital() {
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            DispenseRequestDTO dto = buildRequest();
            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("should reject when dto.patientId != prescription.patient")
        void shouldRejectPatientMismatch() {
            DispenseRequestDTO dto = buildRequest();
            dto.setPatientId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Patient does not match");
        }

        @Test
        @DisplayName("should reject when verifiedBy != authenticated user")
        void shouldRejectVerifierMismatch() {
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("verifiedBy");
        }

        @Test
        @DisplayName("should reject when stockLot belongs to another pharmacy")
        void shouldRejectStockLotFromOtherPharmacy() {
            Pharmacy otherPharmacy = Pharmacy.builder().hospital(hospital).build();
            otherPharmacy.setId(UUID.randomUUID());
            inventoryItem.setPharmacy(otherPharmacy);
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("does not belong");
        }
    }

    @Nested
    @DisplayName("listByPatient / listByPharmacy / getWorkQueue")
    class ListAndQueue {

        @Test
        @DisplayName("listByPatient should map dispenses in scope")
        void listByPatientShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findByPatientId(patientId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPatient(patientId, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("listByPharmacy should enforce scope and map")
        void listByPharmacyShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            Dispense d = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO dto = DispenseResponseDTO.builder().id(dispenseId).build();

            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findByPharmacyId(pharmacyId, pageable))
                    .thenReturn(new PageImpl<>(List.of(d)));
            when(dispenseMapper.toResponseDTO(d)).thenReturn(dto);

            Page<DispenseResponseDTO> result = service.listByPharmacy(pharmacyId, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("getWorkQueue should return mapped DTOs")
        void getWorkQueueShouldMap() {
            Pageable pageable = PageRequest.of(0, 20);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(
                    eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(prescription)));

            Page<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> result =
                    service.getWorkQueue(pageable);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(prescriptionId);
            assertThat(result.getContent().get(0).getPatient().getId()).isEqualTo(patientId);
            assertThat(result.getContent().get(0).isNeedsAttention()).isFalse();
            assertThat(result.getContent().get(0).getAttentionReason()).isNull();
        }

        @Test
        @DisplayName("G3/G13: the queue asks for the dead-end statuses and flags them as needing attention")
        void getWorkQueueListsDeadEndStatusesWithNeedsAttention() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription backOrdered = new Prescription();
            backOrdered.setId(UUID.randomUUID());
            backOrdered.setStatus(PrescriptionStatus.PENDING_STOCK);
            backOrdered.setMedicationName("Amoxicillin");
            Prescription refused = new Prescription();
            refused.setId(UUID.randomUUID());
            refused.setStatus(PrescriptionStatus.PARTNER_REJECTED);
            refused.setPharmacyName("Pharmacie du Marché");
            Prescription answered = new Prescription();
            answered.setId(UUID.randomUUID());
            answered.setStatus(PrescriptionStatus.SIGNED);
            answered.setClarificationResolvedAt(java.time.LocalDateTime.now(FIXED_CLOCK));

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<PrescriptionStatus>> asked =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(
                    eq(hospitalId), asked.capture(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(backOrdered, refused, answered)));

            List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> rows =
                    service.getWorkQueue(pageable).getContent();

            // The queue lists more than it may dispense: an order sitting with
            // a partner that accepted it is on the screen (flagged) but is
            // not fillable until a no-show is recorded.
            assertThat(asked.getValue()).contains(PrescriptionStatus.PENDING_STOCK,
                    PrescriptionStatus.PARTNER_REJECTED, PrescriptionStatus.PARTNER_ACCEPTED)
                    .doesNotContain(PrescriptionStatus.PENDING_CLARIFICATION);
            assertThat(DispenseServiceImpl.DISPENSABLE_STATUSES)
                    .doesNotContain(PrescriptionStatus.PARTNER_ACCEPTED);
            assertThat(rows).extracting(
                    com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO::isNeedsAttention)
                    .containsExactly(true, true, true);
            assertThat(rows).extracting(
                    com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO::getAttentionReason)
                    .containsExactly("PENDING_STOCK", "PARTNER_REJECTED", "CLARIFICATION_RESOLVED");
            assertThat(rows.get(1).getPharmacyName()).isEqualTo("Pharmacie du Marché");
        }

        @Test
        @DisplayName("a partial fill keeps the queue cue while the supplier order is still outstanding")
        void outstandingBackOrderKeepsNeedsAttention() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription partiallyFilled = new Prescription();
            partiallyFilled.setId(UUID.randomUUID());
            partiallyFilled.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            com.example.hms.model.pharmacy.PrescriptionRoutingDecision backOrder =
                    com.example.hms.model.pharmacy.PrescriptionRoutingDecision.builder()
                            .prescription(partiallyFilled)
                            .routingType(com.example.hms.enums.RoutingType.BACKORDER)
                            .status(com.example.hms.enums.RoutingDecisionStatus.PENDING)
                            .decidedAt(java.time.LocalDateTime.now(FIXED_CLOCK))
                            .build();
            backOrder.setId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(partiallyFilled)));
            when(routingDecisionRepository.findByPrescription_IdInOrderByDecidedAtDesc(any()))
                    .thenReturn(List.of(backOrder));

            com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO row =
                    service.getWorkQueue(pageable).getContent().get(0);

            assertThat(row.isNeedsAttention()).isTrue();
            assertThat(row.getAttentionReason()).isEqualTo("BACK_ORDER_OUTSTANDING");
        }

        @Test
        @DisplayName("a completed back order no longer flags the row")
        void completedBackOrderClearsTheCue() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription filled = new Prescription();
            filled.setId(UUID.randomUUID());
            filled.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            com.example.hms.model.pharmacy.PrescriptionRoutingDecision backOrder =
                    com.example.hms.model.pharmacy.PrescriptionRoutingDecision.builder()
                            .prescription(filled)
                            .routingType(com.example.hms.enums.RoutingType.BACKORDER)
                            .status(com.example.hms.enums.RoutingDecisionStatus.COMPLETED)
                            .decidedAt(java.time.LocalDateTime.now(FIXED_CLOCK))
                            .build();
            backOrder.setId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(filled)));
            when(routingDecisionRepository.findByPrescription_IdInOrderByDecidedAtDesc(any()))
                    .thenReturn(List.of(backOrder));

            com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO row =
                    service.getWorkQueue(pageable).getContent().get(0);

            assertThat(row.isNeedsAttention()).isFalse();
        }

        @Test
        @DisplayName("CLARIFICATION_RESOLVED is flagged only until the pharmacy acts on the answer")
        void clarificationResolvedFlagClearsAfterTheNextPharmacyAction() {
            Pageable pageable = PageRequest.of(0, 20);
            java.time.LocalDateTime resolvedAt = java.time.LocalDateTime.now(FIXED_CLOCK);
            Prescription justAnswered = new Prescription();
            justAnswered.setId(UUID.randomUUID());
            justAnswered.setStatus(PrescriptionStatus.SIGNED);
            justAnswered.setClarificationResolvedAt(resolvedAt);
            Prescription actedOn = new Prescription();
            actedOn.setId(UUID.randomUUID());
            actedOn.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            actedOn.setClarificationResolvedAt(resolvedAt);
            Dispense later = buildDispense(DispenseStatus.PARTIAL);
            later.setPrescription(actedOn);
            later.setDispensedAt(resolvedAt.plusHours(1));

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(justAnswered, actedOn)));
            when(dispenseRepository.findByPrescription_IdInAndStatusNotOrderByDispensedAtDesc(
                    any(), eq(DispenseStatus.CANCELLED))).thenReturn(List.of(later));

            List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> rows =
                    service.getWorkQueue(pageable).getContent();

            assertThat(rows.get(0).getAttentionReason()).isEqualTo("CLARIFICATION_RESOLVED");
            assertThat(rows.get(1).isNeedsAttention()).isFalse();
            assertThat(rows.get(1).getAttentionReason()).isNull();
        }

        @Test
        @DisplayName("an answer on a PENDING_STOCK row is reported although the status wins the reason")
        void clarificationAnswerSurvivesTheAttentionPrecedence() {
            Pageable pageable = PageRequest.of(0, 20);
            java.time.LocalDateTime resolvedAt = java.time.LocalDateTime.now(FIXED_CLOCK);
            Prescription answeredOnBackOrder = new Prescription();
            answeredOnBackOrder.setId(UUID.randomUUID());
            // resolveClarification restores the status the question was asked
            // from, so this is exactly what the pharmacist sees come back.
            answeredOnBackOrder.setStatus(PrescriptionStatus.PENDING_STOCK);
            answeredOnBackOrder.setClarificationResolvedAt(resolvedAt);
            Prescription plain = new Prescription();
            plain.setId(UUID.randomUUID());
            plain.setStatus(PrescriptionStatus.SIGNED);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(answeredOnBackOrder, plain)));

            List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> rows =
                    service.getWorkQueue(pageable).getContent();

            // The single attentionReason still reports the status, by
            // precedence — and the answer is no longer invisible next to it.
            assertThat(rows.get(0).getAttentionReason()).isEqualTo("PENDING_STOCK");
            assertThat(rows.get(0).getClarificationResolvedAt()).isEqualTo(resolvedAt);
            assertThat(rows.get(1).getClarificationResolvedAt()).isNull();
        }

        @Test
        @DisplayName("the answer cue clears once the pharmacy has acted on it")
        void clarificationAnswerCueClearsAfterAPharmacyAction() {
            Pageable pageable = PageRequest.of(0, 20);
            java.time.LocalDateTime resolvedAt = java.time.LocalDateTime.now(FIXED_CLOCK);
            Prescription actedOn = new Prescription();
            actedOn.setId(UUID.randomUUID());
            actedOn.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            actedOn.setClarificationResolvedAt(resolvedAt);
            Dispense later = buildDispense(DispenseStatus.PARTIAL);
            later.setPrescription(actedOn);
            later.setDispensedAt(resolvedAt.plusHours(1));

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(actedOn)));
            when(dispenseRepository.findByPrescription_IdInAndStatusNotOrderByDispensedAtDesc(
                    any(), eq(DispenseStatus.CANCELLED))).thenReturn(List.of(later));

            assertThat(service.getWorkQueue(pageable).getContent().get(0).getClarificationResolvedAt())
                    .isNull();
        }

        @Test
        @DisplayName("a PARTNER_REJECTED row groups in-house and names the partner that refused it")
        void partnerRejectedRowNamesTheRefusingPartner() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription refused = new Prescription();
            refused.setId(UUID.randomUUID());
            refused.setStatus(PrescriptionStatus.PARTNER_REJECTED);
            Pharmacy partner = Pharmacy.builder().hospital(hospital).name("Pharmacie du Marché").build();
            partner.setId(UUID.randomUUID());
            com.example.hms.model.pharmacy.PrescriptionRoutingDecision refusal =
                    com.example.hms.model.pharmacy.PrescriptionRoutingDecision.builder()
                            .prescription(refused)
                            .targetPharmacy(partner)
                            .routingType(com.example.hms.enums.RoutingType.PARTNER)
                            .status(com.example.hms.enums.RoutingDecisionStatus.REJECTED)
                            .decidedAt(java.time.LocalDateTime.now(FIXED_CLOCK))
                            .build();
            refusal.setId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(refused)));
            when(routingDecisionRepository.findByPrescription_IdInOrderByDecidedAtDesc(any()))
                    .thenReturn(List.of(refusal));

            com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO row =
                    service.getWorkQueue(pageable).getContent().get(0);

            assertThat(row.getPharmacyName()).isNull();
            assertThat(row.getLastRefusedBy()).isEqualTo("Pharmacie du Marché");
            assertThat(row.getAttentionReason()).isEqualTo("PARTNER_REJECTED");
        }
    }

    /**
     * Roadmap row 4 / T-68 — offline dispense queue replay path.
     *
     * <p>The contract under test: when the same {@code idempotencyKey} is
     * POSTed twice (the second POST being the offline-queue replay after
     * connectivity returns), the second call must return the existing
     * DispenseResponseDTO and skip every side-effect — no second stock
     * decrement, no second audit, no second SMS, no validation re-run.
     *
     * <p>We deliberately do NOT stub any of the validation collaborators
     * (prescription/patient/pharmacy lookups, RoleValidator, CDS check,
     * stock-lot consume) on the replay path. If the implementation were
     * to fall through to the create branch, Mockito would throw on the
     * unstubbed strict-stubs and the test would surface the regression.
     */
    @Nested
    @DisplayName("createDispense — idempotency (T-68)")
    class CreateDispenseIdempotency {

        private static final String KEY = "user-001-rx-002-2026-05-10T13:45:30.123456789Z";

        @Test
        @DisplayName("replay with known key returns existing dispense and skips all side-effects")
        void replayReturnsExistingAndSkipsSideEffects() {
            DispenseRequestDTO replay = buildRequest();
            replay.setIdempotencyKey(KEY);

            Dispense existing = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO existingDto = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            when(dispenseRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));
            when(dispenseMapper.toResponseDTO(existing)).thenReturn(existingDto);

            DispenseResponseDTO result = service.createDispense(replay);

            assertThat(result).isSameAs(existingDto);
            // No write, no stock decrement, no audit, no SMS — the create
            // branch is fully short-circuited.
            verify(dispenseRepository, never()).save(any());
            verify(prescriptionRepository, never()).save(any());
            verify(stockLotRepository, never()).save(any());
            verify(support, never()).notifyDispensed(any(), any(), any());
            verify(auditEventLogService, never()).logEvent(any());
        }

        @Test
        @DisplayName("blank idempotency key falls through to the normal create path")
        void blankKeyFallsThroughToCreate() {
            DispenseRequestDTO dto = buildRequest();
            dto.setIdempotencyKey("   ");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            // The replay lookup must NOT be consulted for a blank key — that's
            // wasted IO. Verified via never() below.
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any(Prescription.class))).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(dispenseRepository, never()).findByIdempotencyKey(any());
            verify(dispenseRepository).save(any(Dispense.class));
        }

        @Test
        @DisplayName("unknown key falls through to create — second POST persists with the key")
        void unknownKeyCreatesNewRow() {
            DispenseRequestDTO dto = buildRequest();
            dto.setIdempotencyKey(KEY);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("COMPLETED").build();

            when(dispenseRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any(Prescription.class))).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(dispenseRepository).findByIdempotencyKey(KEY);
            verify(dispenseRepository).save(any(Dispense.class));
        }

        @Test
        @DisplayName("race recovery: V94 unique violation on save → re-lookup returns winning DTO")
        void racingWriteRecoversByLookingUpWinner() {
            // Concurrency model: this thread's pre-check returns empty (the
            // racing tx has not committed yet). The save then throws because
            // the racing tx committed in between. The implementation must
            // catch DataIntegrityViolationException and re-look up — the
            // winner is now visible — and return that DTO instead of
            // bubbling a 500.
            DispenseRequestDTO racing = buildRequest();
            racing.setIdempotencyKey(KEY);

            Dispense winner = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO winnerDto = DispenseResponseDTO.builder()
                    .id(dispenseId).medicationName("Amoxicillin").status("COMPLETED").build();

            // First lookup (pre-check) sees empty; second lookup (post-rollback
            // race recovery) sees the winning row.
            when(dispenseRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));

            // Stub everything the create branch touches, up to and including
            // the save() that explodes. Spring would normally roll back the
            // tx here; in this unit test there is no real tx to roll back —
            // we only need to verify the catch-and-recover path.
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(racing), any())).thenReturn(buildDispense(DispenseStatus.COMPLETED));
            when(dispenseRepository.save(any(Dispense.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"uq_disp_idempotency_key\""));
            when(dispenseMapper.toResponseDTO(winner)).thenReturn(winnerDto);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            DispenseResponseDTO result = service.createDispense(racing);

            assertThat(result).isSameAs(winnerDto);
            // Both lookups happened (pre-check + post-violation recovery).
            verify(dispenseRepository, org.mockito.Mockito.times(2)).findByIdempotencyKey(KEY);
            // We ATTEMPTED to save — that's how we discovered the race.
            verify(dispenseRepository).save(any(Dispense.class));
            // No prescription save / SMS / audit got committed because the
            // tx (in production) would have rolled back. Mockito verifies
            // we never reached those code paths after the violation.
            verify(prescriptionRepository, never()).save(any(Prescription.class));
            verify(support, never()).notifyDispensed(any(), any(), any());
            verify(auditEventLogService, never()).logEvent(any());
        }

        @Test
        @DisplayName("race recovery: violation with no recoverable winner → original exception bubbles")
        void racingWriteWithNoWinnerRethrowsViolation() {
            // Defensive case: V94 unique-index violation but the second
            // lookup still finds nothing (should not happen — the constraint
            // exists precisely because somebody won — but be honest about
            // the failure rather than silently returning null).
            DispenseRequestDTO racing = buildRequest();
            racing.setIdempotencyKey(KEY);

            when(dispenseRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(racing), any())).thenReturn(buildDispense(DispenseStatus.COMPLETED));
            when(dispenseRepository.save(any(Dispense.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("constraint violation"));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            assertThatThrownBy(() -> service.createDispense(racing))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    /**
     * Tier 2 item 34 — the counter-side gate, tested through the service
     * rather than only against the rule class, because what matters here is
     * that a refusal happens BEFORE stock moves. The rule returning
     * "expired" is worth nothing if the shelf has already been decremented
     * by the time anybody reads it.
     */
    @Nested
    @DisplayName("dispense-time verification")
    class DispenseVerification {

        /** Everything a create needs up to the point verification runs. */
        private void stubUpToVerification() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
        }

        /** The rest, for the paths that are expected to succeed. */
        private void stubThroughSave(DispenseRequestDTO dto, Dispense entity) {
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("COMPLETED").build();
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseStatus.CANCELLED))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
        }

        @Test
        @DisplayName("expired stock is refused and the shelf is left alone")
        void expiredStockIsRefusedWithoutDecrementingAnything() {
            // The defect this closes: expiry_date was written at goods-in and
            // read by NOTHING. findAvailableLotsByFEFO filters it; the
            // dispense path called findById and went straight past.
            stockLot.setExpiryDate(TODAY.minusDays(1));
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            stubUpToVerification();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired on");

            // The whole point of splitting load from consume.
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(50));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(100));
            verify(stockLotRepository, never()).save(any());
            verify(inventoryItemRepository, never()).save(any());
            verify(stockTransactionRepository, never()).save(any());
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("a lot of the wrong drug is refused")
        void aLotOfTheWrongDrugIsRefused() {
            catalogItem.setGenericName("Methotrexate");
            catalogItem.setNameFr("Methotrexate");
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            stubUpToVerification();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Methotrexate")
                    .hasMessageContaining("Amoxicillin")
                    .hasMessageNotContaining("%s");

            verify(stockLotRepository, never()).save(any());
        }

        @Test
        @DisplayName("a wrong-drug lot IS allowed as a recorded substitution")
        void aRecordedSubstitutionCarriesTheDrugCheck() {
            // Generic for brand, two 250s for a 500 — a real workflow the
            // request already models. Reusing it beats inventing a second
            // override that would need its own audit event.
            catalogItem.setGenericName("Amoxicilline trihydrate");
            catalogItem.setNameFr("Amoxicilline trihydrate");
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setSubstitution(true);
            dto.setSubstitutionReason("Prescribed brand out of stock");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            stubUpToVerification();
            stubThroughSave(dto, entity);

            service.createDispense(dto);

            assertThat(entity.getVerificationStatus())
                    .isEqualTo(DispenseVerificationStatus.OVERRIDDEN);
            assertThat(entity.getVerificationOverrides()).contains("DRUG");
            assertThat(entity.getVerificationOverrideReason()).isEqualTo("Prescribed brand out of stock");
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(40));
        }

        @Test
        @DisplayName("substitution without a reason does not carry the drug check")
        void aSubstitutionFlagWithoutAReasonIsNotAnOverride() {
            // Otherwise a single boolean is a one-click bypass of the only
            // check standing between a prescription and the wrong drug.
            catalogItem.setGenericName("Methotrexate");
            catalogItem.setNameFr("Methotrexate");
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setSubstitution(true);
            dto.setSubstitutionReason("   ");
            stubUpToVerification();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class);
            verify(stockLotRepository, never()).save(any());
        }

        @Test
        @DisplayName("a substitution never carries an expired lot")
        void aSubstitutionDoesNotCarryAnExpiredLot() {
            // EXPIRY is the one check with no override at all: unlike the
            // isolation override in V137, there is no circumstance where
            // out-of-date medication is the better of two options.
            stockLot.setExpiryDate(TODAY.minusMonths(2));
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setSubstitution(true);
            dto.setSubstitutionReason("Prescribed brand out of stock");
            stubUpToVerification();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired on");
            verify(stockLotRepository, never()).save(any());
        }

        @Test
        @DisplayName("the wrong wristband is refused, substitution or not")
        void theWrongWristbandIsRefusedAndIsNotOverridable() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setPatientScanValue(UUID.randomUUID().toString());
            dto.setSubstitution(true);
            dto.setSubstitutionReason("Prescribed brand out of stock");
            stubUpToVerification();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("different patient");
            verify(dispenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("a clean scan is stamped VERIFIED")
        void aCleanScanIsStampedVerified() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setPatientScanValue(patientId.toString());
            dto.setProductScanValue(stockLot.getBarcodeValue());
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            stubUpToVerification();
            stubThroughSave(dto, entity);

            service.createDispense(dto);

            assertThat(entity.getVerificationStatus())
                    .isEqualTo(DispenseVerificationStatus.VERIFIED);
            assertThat(entity.getPatientScanValue()).isEqualTo(patientId.toString());
            assertThat(entity.getProductScanValue()).isEqualTo(stockLot.getBarcodeValue());
            assertThat(entity.getScanVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("dispensing without a scan is NOT_VERIFIED, not a failure")
        void thePaperFallbackPathIsNotVerifiedRatherThanRefused() {
            // Most sites here have no scanner. Requiring one would take the
            // pharmacy offline rather than make it safer.
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            stubUpToVerification();
            stubThroughSave(dto, entity);

            service.createDispense(dto);

            assertThat(entity.getVerificationStatus())
                    .isEqualTo(DispenseVerificationStatus.NOT_VERIFIED);
            assertThat(entity.getScanVerifiedAt()).isNull();
            // ...but the checks that need no scan still ran and passed.
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(40));
        }

        @Test
        @DisplayName("an unscanned dispense is never recorded as VERIFIED")
        void anUnscannedDispenseIsNeverRecordedAsVerified() {
            // The record must not be able to claim the right patient was
            // confirmed when nobody confirmed anything. With no lot named
            // there is nothing at all to evaluate.
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            stubThroughSave(dto, entity);

            service.createDispense(dto);

            assertThat(entity.getVerificationStatus())
                    .isEqualTo(DispenseVerificationStatus.NOT_VERIFIED);
            assertThat(entity.getVerificationOverrides()).isNull();
        }
    }

    @Nested
    @DisplayName("G12: in-house dispenses are booked against dispensaries only")
    class DispensaryOnly {

        @Test
        @DisplayName("a COMMUNITY_PHARMACY row at the same hospital is refused before any state moves")
        void rejectsCommunityPharmacy() {
            pharmacy.setPharmacyType(PharmacyType.COMMUNITY_PHARMACY);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("HOSPITAL_DISPENSARY")
                    .hasMessageContaining("COMMUNITY_PHARMACY");

            verify(dispenseRepository, never()).save(any());
            verify(cdsCheckService, never()).checkAtDispense(any(), any());
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        }

        @Test
        @DisplayName("a PARTNER_PHARMACY row is refused the same way")
        void rejectsPartnerPharmacy() {
            pharmacy.setPharmacyType(PharmacyType.PARTNER_PHARMACY);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("PARTNER_PHARMACY");

            verify(dispenseRepository, never()).save(any());
        }
    }
}
