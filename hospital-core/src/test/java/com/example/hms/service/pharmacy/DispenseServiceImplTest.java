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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.hms.enums.StockTransactionType;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
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

    /** QA D156: real, so the tests exercise the rule and not a stub of it. */
    @org.mockito.Spy
    private PharmacistVerificationService pharmacistVerificationService =
        new PharmacistVerificationService(null, null, null, FIXED_CLOCK);

    /** G13: the work-queue claim; exit-path releases are verified where they matter. */
    @Mock private com.example.hms.service.pharmacy.PrescriptionQueueClaimService queueClaimService;

    /** QA D241: real, so the lot picker's response is what the portal gets. */
    @org.mockito.Spy
    private com.example.hms.mapper.pharmacy.StockLotMapper stockLotMapper =
        new com.example.hms.mapper.pharmacy.StockLotMapper();

    /** QA D404: the money follows a cancelled dispense. */
    @Mock private PharmacyPaymentVoider paymentVoider;
    /** QA D654: the controlled-stock witness rule. */
    @Mock private ControlledStockWitness controlledStockWitness;

    @Mock private com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;

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
        // QA D512: every fill needs a pharmacist verification; the fixture is
        // verified unless a test is about the verification itself.
        prescription.setPharmacistVerifiedAt(java.time.LocalDateTime.of(2026, 8, 24, 8, 0));
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
        // #926 review: by default the lot has issued plenty, so the lot cap does not bind.
        org.mockito.Mockito.lenient().when(stockTransactionRepository.sumQuantityForLot(any(),
                eq(java.util.EnumSet.of(StockTransactionType.DISPENSE, StockTransactionType.TRANSFER))))
                .thenReturn(new BigDecimal("1000"));
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
    @DisplayName("G13: the work-queue claim")
    class QueueClaim {

        private void stubFullFill(DispenseRequestDTO dto) {
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any(Prescription.class))).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
        }

        @Test
        @DisplayName("AC-8/AC-9: a one-step fill ends the claim in its transaction: DISPENSED, the dispenser, QUEUE_ROLE")
        void oneStepFillEndsTheClaim() {
            DispenseRequestDTO dto = buildRequest();
            stubFullFill(dto);

            service.createDispense(dto);

            verify(queueClaimService).releaseOnExit(prescription,
                    com.example.hms.enums.QueueClaimReleaseReason.DISPENSED, userId,
                    com.example.hms.enums.QueueClaimExitActor.QUEUE_ROLE);
        }

        @Test
        @DisplayName("AC-9: a refused fill (400, not dispensable) keeps the claim: no release")
        void refusedFillKeepsTheClaim() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            DispenseRequestDTO request = buildRequest();
            assertThatThrownBy(() -> service.createDispense(request)).isInstanceOf(BusinessException.class);
            verify(queueClaimService, never()).releaseOnExit(any(), any(), any(), any());
        }

        private com.example.hms.payload.dto.pharmacy.WorkQueueClaimDTO claimDto(boolean mine) {
            return com.example.hms.payload.dto.pharmacy.WorkQueueClaimDTO.builder()
                    .prescriptionId(prescriptionId).claimedByUserId(UUID.randomUUID())
                    .claimedByName("Awa Sanou").mine(mine).build();
        }

        @Test
        @DisplayName("AC-2: each row carries its active claim from ONE batched lookup per page; rows without one carry none")
        void queueRowsCarryTheirClaim() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription other = new Prescription();
            other.setId(UUID.randomUUID());
            other.setStatus(PrescriptionStatus.SIGNED);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(prescription, other)));
            when(queueClaimService.activeClaimsFor(List.of(prescription, other)))
                    .thenReturn(java.util.Map.of(prescriptionId, claimDto(false)));

            List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> rows =
                    service.getWorkQueue(pageable, com.example.hms.enums.QueueClaimFilter.ALL).getContent();

            verify(queueClaimService, times(1)).activeClaimsFor(any());
            assertThat(rows.get(0).getClaim().getClaimedByName()).isEqualTo("Awa Sanou");
            assertThat(rows.get(0).getClaim().isMine()).isFalse();
            assertThat(rows.get(1).getClaim()).isNull();
        }

        @Test
        @DisplayName("AC-13: MINE pages the caller's active claims, with the clock's activeAfter")
        void mineUsesItsOwnQuery() {
            Pageable pageable = PageRequest.of(0, 20);
            java.time.LocalDateTime activeAfter = java.time.LocalDateTime.now(FIXED_CLOCK).minusMinutes(15);
            when(queueClaimService.isEnabled()).thenReturn(true);
            when(queueClaimService.activeAfter()).thenReturn(activeAfter);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(prescriptionRepository.findWorkQueueClaimedBy(eq(hospitalId), any(), eq(userId), eq(activeAfter),
                    eq(pageable))).thenReturn(new PageImpl<>(List.of(prescription)));

            assertThat(service.getWorkQueue(pageable, com.example.hms.enums.QueueClaimFilter.MINE).getContent())
                    .hasSize(1);
            verify(prescriptionRepository, never()).findByHospital_IdAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("AC-13: UNCLAIMED has its own query")
        void unclaimedUsesItsOwnQuery() {
            Pageable pageable = PageRequest.of(0, 20);
            java.time.LocalDateTime activeAfter = java.time.LocalDateTime.now(FIXED_CLOCK).minusMinutes(15);
            when(queueClaimService.isEnabled()).thenReturn(true);
            when(queueClaimService.activeAfter()).thenReturn(activeAfter);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findWorkQueueUnclaimed(eq(hospitalId), any(), eq(activeAfter), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(prescription)));

            assertThat(service.getWorkQueue(pageable, com.example.hms.enums.QueueClaimFilter.UNCLAIMED).getContent())
                    .hasSize(1);
            verify(prescriptionRepository, never()).findByHospital_IdAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("AC-16: with claims off, claim=MINE is ignored and the list is ALL")
        void flagOffIgnoresTheFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            when(queueClaimService.isEnabled()).thenReturn(false);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(eq(hospitalId), any(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(prescription)));

            assertThat(service.getWorkQueue(pageable, com.example.hms.enums.QueueClaimFilter.MINE).getContent())
                    .hasSize(1);
            verify(prescriptionRepository, never()).findWorkQueueClaimedBy(any(), any(), any(), any(), any());
        }
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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
        @DisplayName("QA D138: a pharmacy that keeps lots refuses a fill that names none, and nothing moves")
        void aFillWithNoLotIsRefusedWhereThePharmacyKeepsLots() {
            DispenseRequestDTO dto = buildRequest();
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(inventoryItemRepository.existsByPharmacyIdAndActiveTrue(pharmacyId)).thenReturn(true);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("dispense.lot.required") || e.getMessage().contains("keeps stock in lots"));
            verify(dispenseRepository, never()).save(any(Dispense.class));
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
        }

        @Test
        @DisplayName("QA D512: an ordinary prescription nobody verified is not filled (POST /pharmacy/dispense, no verifiedBy)")
        void d512_anUnverifiedOrdinaryPrescriptionIsRefused() {
            prescription.setPharmacistVerifiedAt(null);
            DispenseRequestDTO dto = buildRequest();
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("dispense.verification.required") || e.getMessage().contains("verified by a pharmacist"));
            verify(dispenseRepository, never()).save(any(Dispense.class));
            assertThat(prescription.getPharmacistVerifiedAt()).isNull();
        }

        @Test
        @DisplayName("QA D512: the dispensing pharmacist's attestation is recorded as the verification")
        void d512_theAttestedVerificationIsStampedWithTheFill() {
            prescription.setPharmacistVerifiedAt(null);
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(userId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.hasAnyAuthority("PHARMACIST", "PHARMACY_VERIFIER", "SUPER_ADMIN")).thenReturn(true);

            try {
                service.createDispense(dto);
            } catch (RuntimeException downstream) {
                // Later stages (stock, mapping) are not stubbed here; the
                // verification is stamped before any of them runs.
            }

            assertThat(prescription.getPharmacistVerifiedAt()).isNotNull();
            assertThat(prescription.getPharmacistVerifiedBy()).isSameAs(user);
        }

        @Test
        @DisplayName("#922 review: a HOSPITAL_ADMIN filling cannot attest the pharmacist verification")
        void d512_aHospitalAdminCannotAttestTheVerification() {
            prescription.setPharmacistVerifiedAt(null);
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(userId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.hasAnyAuthority("PHARMACIST", "PHARMACY_VERIFIER", "SUPER_ADMIN")).thenReturn(false);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(prescription.getPharmacistVerifiedAt()).isNull();
            assertThat(prescription.getPharmacistVerifiedBy()).isNull();
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D512: the prescriber cannot attest their own prescription at the counter")
        void d512_thePrescriberCannotVerifyTheirOwn() {
            prescription.setPharmacistVerifiedAt(null);
            com.example.hms.model.Staff prescriber = new com.example.hms.model.Staff();
            prescriber.setUser(user);
            prescription.setStaff(prescriber);
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(userId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.hasAnyAuthority("PHARMACIST", "PHARMACY_VERIFIER", "SUPER_ADMIN")).thenReturn(true);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class);
            assertThat(prescription.getPharmacistVerifiedAt()).isNull();
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D706: morphine prescribed x3 - a fill of 4, then of 40, is refused")
        void aFillAbovePrescribedQuantityIsRefused() {
            prescription.setQuantity(new BigDecimal("3"));
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(BigDecimal.ZERO);

            for (String qty : new String[] {"4", "40"}) {
                DispenseRequestDTO dto = buildRequest();
                dto.setQuantityRequested(new BigDecimal(qty));
                dto.setQuantityDispensed(new BigDecimal(qty));
                assertThatThrownBy(() -> service.createDispense(dto))
                        .isInstanceOf(BusinessException.class)
                        .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()));
            }
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D706: the fills to date count - 2 of 3 given, a further 2 is refused")
        void cumulativeFillsAreCappedAtThePrescribedQuantity() {
            prescription.setQuantity(new BigDecimal("3"));
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(new BigDecimal("2"));
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(new BigDecimal("2"));
            dto.setQuantityDispensed(new BigDecimal("2"));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D706 re-test: legacy catalog morphine (tick false, no quantity), 40 with a witness, is refused")
        void aControlledPrescriptionWithNoQuantityIsRefused() {
            prescription.setQuantity(null);
            // The legacy row: the prescriber never ticked it, the catalog item is controlled.
            prescription.setControlledSubstance(false);
            when(controlledResolver.isControlled(prescription)).thenReturn(true);
            prescription.setTwoFactorVerifiedAt(java.time.LocalDateTime.of(2026, 8, 24, 9, 0));
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.quantityUnknownControlled".equals(((BusinessException) e).getMessageKey()));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @ParameterizedTest
        @CsvSource({"three times a day,3 days,90", "three times a day,3 days,60", "3 fois par jour,3 jours,90",
                "TID,3 days,10"})
        @DisplayName("QA D706 retest 2: legacy paracetamol 500 mg x3/day x3 days (no quantity) caps at 9")
        void aLegacyNonControlledPrescriptionIsCappedAtItsRegimen(String frequency, String duration, String qty) {
            prescription.setQuantity(null);
            prescription.setMedicationName("Paracétamol 500 mg");
            prescription.setDosage("500 mg");
            prescription.setFrequency(frequency);
            prescription.setDuration(duration);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(BigDecimal.ZERO);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(new BigDecimal(qty));
            dto.setQuantityDispensed(new BigDecimal(qty));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D706 retest 2: a legacy fill of 5 after 5 already given (of 9) is refused")
        void aLegacyRegimenCountsTheFillsToDate() {
            prescription.setQuantity(null);
            prescription.setMedicationName("Paracétamol 500 mg");
            prescription.setDosage("500 mg");
            prescription.setFrequency("three times a day");
            prescription.setDuration("3 days");
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(new BigDecimal("5"));
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityRequested(new BigDecimal("5"));
            dto.setQuantityDispensed(new BigDecimal("5"));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey())
                            && e.getMessage().contains("4"));
        }

        @Test
        @DisplayName("QA D706 retest 2: a legacy prescription whose regimen cannot be read is refused until a quantity is recorded")
        void aLegacyPrescriptionWithAnUnreadableRegimenIsRefused() {
            prescription.setQuantity(null);
            prescription.setDosage("500 mg");
            prescription.setFrequency("as needed");
            prescription.setDuration("3 days");
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.quantityUnknown".equals(((BusinessException) e).getMessageKey()));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("QA D156: a controlled prescription nobody verified is not filled")
        void anUnverifiedControlledPrescriptionIsRefusedAtTheCounter() {
            prescription.setPharmacistVerifiedAt(null);
            prescription.setControlledSubstance(true);
            prescription.setTwoFactorVerifiedAt(java.time.LocalDateTime.of(2026, 8, 24, 9, 0));
            DispenseRequestDTO dto = buildRequest();
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> e.getMessage().contains("dispense.verification.required") || e.getMessage().contains("verified by a pharmacist"));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("should dispense with stock lot and decrement inventory")
        void shouldDispenseWithStockLot() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            DispenseResponseDTO responseDTO = DispenseResponseDTO.builder()
                    .id(dispenseId).status("COMPLETED").build();

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(40));
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.valueOf(90));
            verify(stockLotRepository).decrementRemaining(eq(stockLotId), eq(BigDecimal.TEN), any());
            verify(inventoryItemRepository).decrementOnHand(eq(inventoryItem.getId()), eq(BigDecimal.TEN), any());
            verify(stockTransactionRepository).save(any());
        }

        @Test
        @DisplayName("QA D416: a fill from a lot with no catalog id is saved with the lot's medication, so it can be priced")
        void aFillFromALotTakesTheLotsMedication() {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setMedicationCatalogItemId(null); // what the dispensing screen sends
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            org.mockito.ArgumentCaptor<com.example.hms.mapper.pharmacy.DispenseMapper.DispenseContext> ctx =
                    org.mockito.ArgumentCaptor.forClass(com.example.hms.mapper.pharmacy.DispenseMapper.DispenseContext.class);
            verify(dispenseMapper).toEntity(eq(dto), ctx.capture());
            assertThat(ctx.getValue().medicationCatalogItem()).isSameAs(catalogItem);
        }

        @Test
        @DisplayName("QA D416: a body naming another medication than the lot's is refused, nothing moves")
        void aCatalogIdThatIsNotTheLotsIsRefused() {
            MedicationCatalogItem other = new MedicationCatalogItem();
            other.setId(UUID.randomUUID());
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setMedicationCatalogItemId(other.getId());
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(medicationCatalogItemRepository.findById(other.getId())).thenReturn(Optional.of(other));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            assertThatThrownBy(() -> service.createDispense(dto)).isInstanceOf(BusinessException.class);
            verify(dispenseRepository, never()).save(any(Dispense.class));
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
        }

        @Test
        @DisplayName("G5: a prescription awaiting clarification is not dispensable")
        void shouldRejectPendingClarification() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);
            DispenseRequestDTO dto = buildRequest();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a dispensable state");
        }

        private void stubUpToTheShelf(DispenseRequestDTO dto) {
            dto.setStockLotId(stockLotId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
        }

        /**
         * QA D654, the exact replay: morphine (catalog item controlled) on a
         * prescription whose own flag reads controlledSubstance:false,
         * dispensed by PHARMACIST_A with no witness: 201 and witnessed_by
         * NULL. The catalog decides, and nothing leaves the shelf unwitnessed.
         */
        @Test
        @DisplayName("D654: a controlled substance is not dispensed without a witness, whatever the prescription flag")
        void d654_controlledDispenseNeedsAWitness() {
            catalogItem.setControlled(true);
            prescription.setControlledSubstance(false);
            DispenseRequestDTO dto = buildRequest();
            stubUpToTheShelf(dto);
            when(controlledStockWitness.resolve(null, inventoryItem, user))
                    .thenThrow(new BusinessException("stockTransaction.witness.required"));

            assertThatThrownBy(() -> service.createDispense(dto)).isInstanceOf(BusinessException.class);

            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("D654: a witnessed controlled dispense records its witness on the stock movement")
        void d654_witnessedControlledDispenseIsRecorded() {
            catalogItem.setControlled(true);
            UUID witnessId = UUID.randomUUID();
            User witness = new User();
            witness.setId(witnessId);
            DispenseRequestDTO dto = buildRequest();
            dto.setWitnessedBy(witnessId);
            stubUpToTheShelf(dto);
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(controlledStockWitness.resolve(witnessId, inventoryItem, user)).thenReturn(witness);
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.TEN);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());

            service.createDispense(dto);

            org.mockito.ArgumentCaptor<com.example.hms.model.pharmacy.StockTransaction> tx =
                    org.mockito.ArgumentCaptor.forClass(com.example.hms.model.pharmacy.StockTransaction.class);
            verify(stockTransactionRepository).save(tx.capture());
            assertThat(tx.getValue().getWitnessedByUser()).isSameAs(witness);
        }

        @Test
        @DisplayName("should reject when stock lot has insufficient quantity")
        void shouldRejectInsufficientLotStock() {
            stockLot.setRemainingQuantity(BigDecimal.valueOf(5));
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.empty());

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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);
        }

        @Test
        @DisplayName("D95: a complete fill of a prescription with no quantity is DISPENSED")
        void completeFillWithoutPrescribedQuantityIsDispensed() {
            prescription.setQuantity(null);
            // A legacy regimen the dispense can read: 1 tablet x 2/day x 5 days = 10.
            prescription.setDosage("1 comprimé");
            prescription.setFrequency("2 fois par jour");
            prescription.setDuration("5 jours");
            DispenseRequestDTO dto = buildRequest();
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.valueOf(10));
            // QA D752: the derived cap decides, not the count of complete fills.
            org.mockito.Mockito.lenient().when(dispenseRepository.countCompleteFillsForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(1L);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
        }

        @Test
        @DisplayName("D95 + QA D752: a short fill below the derived cap stays PARTIALLY_FILLED even when the fill itself is complete")
        void shortFillWithoutPrescribedQuantityStaysPartial() {
            prescription.setQuantity(null);
            // A legacy regimen the dispense can read: 1 tablet x 2/day x 5 days = 10.
            prescription.setDosage("1 comprimé");
            prescription.setFrequency("2 fois par jour");
            prescription.setDuration("5 jours");
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.valueOf(5));
            Dispense entity = buildDispense(DispenseStatus.PARTIAL);

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.valueOf(5));
            // QA D752: the derived cap decides, not the count of complete fills.
            org.mockito.Mockito.lenient().when(dispenseRepository.countCompleteFillsForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(1L);
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(DispenseResponseDTO.builder().id(dispenseId).build());
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            // 10 from the original fill + 3 of the refill's 10.
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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

            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(BigDecimal.valueOf(5));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
            when(dispenseMapper.toResponseDTO(entity)).thenReturn(responseDTO);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);

            service.createDispense(dto);

            verify(support, never()).notifyDispensed(any(), any(), any());
        }
    }

    /**
     * QA D779 re-test: receipt, adjustment and lot PUT refused 2.5 tablets,
     * but POST /pharmacy/dispense took 2.5 then 9.5 Paracetamol 500 mg
     * tablets (prescription of 12) and the order became DISPENSED through
     * half-tablet movements. The fill now asks the same rule.
     */
    @Nested
    @DisplayName("QA D779: whole units on every fill")
    class WholeUnitFill {

        @BeforeEach
        void paracetamolTablets() {
            catalogItem.setGenericName("Paracetamol");
            catalogItem.setNameFr("Paracétamol 500 mg comprimé");
            inventoryItem.setUnit("comprimé");
            prescription.setMedicationName("Paracétamol 500 mg comprimé");
            prescription.setQuantity(new BigDecimal("12"));
            prescription.setQuantityUnit("comprimé");
        }

        private DispenseRequestDTO fill(String requested, String dispensed) {
            DispenseRequestDTO dto = buildRequest();
            dto.setStockLotId(stockLotId);
            dto.setMedicationName("paracetamol");
            dto.setUnit("comprimé");
            dto.setQuantityRequested(new BigDecimal(requested));
            dto.setQuantityDispensed(new BigDecimal(dispensed));
            return dto;
        }

        private void stubUpToTheLot(boolean withLot) {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            if (withLot) {
                when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            }
        }

        private void assertRefusedAndNothingMoved(DispenseRequestDTO dto) {
            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> WholeUnitQuantity.DEFAULT_KEY.equals(((BusinessException) e).getMessageKey()));
            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("50");
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo("100");
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
            verify(inventoryItemRepository, never()).decrementOnHand(any(), any(), any());
            verify(stockTransactionRepository, never()).save(any());
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @ParameterizedTest
        @CsvSource({"2.5,2.5", "9.5,9.5", "12,2.5", "2.5,2"})
        @DisplayName("a fraction of a tablet from a lot is refused before anything moves (the re-test 2.5 and 9.5)")
        void aFractionOfATabletIsRefused(String requested, String dispensed) {
            stubUpToTheLot(true);
            assertRefusedAndNothingMoved(fill(requested, dispensed));
        }

        @Test
        @DisplayName("a lot with no unit on record is counted: 2.5 is refused")
        void aLotWithNoUnitIsCounted() {
            inventoryItem.setUnit(null);
            stubUpToTheLot(true);
            assertRefusedAndNothingMoved(fill("2.5", "2.5"));
        }

        @Test
        @DisplayName("a fill with no lot is refused on the prescription's counted unit")
        void aFillWithNoLotUsesThePrescriptionUnit() {
            stubUpToTheLot(false);
            DispenseRequestDTO dto = fill("2.5", "2.5");
            dto.setStockLotId(null);
            dto.setUnit(null);
            assertRefusedAndNothingMoved(dto);
        }

        @Test
        @DisplayName("a fill with no lot counts in the prescription's unit, not the one the request claims: 'ml' on tablets still refuses 2.5")
        void aFillWithNoLotIgnoresTheRequestUnitWhenThePrescriptionNamesOne() {
            stubUpToTheLot(false);
            DispenseRequestDTO dto = fill("2.5", "2.5");
            dto.setStockLotId(null);
            dto.setUnit("ml");
            assertRefusedAndNothingMoved(dto);
        }

        @Test
        @DisplayName("a fill with no lot claiming 'comprimé' on a 7.5 ml order does not earn the next whole unit: 8 is refused")
        void aFillWithNoLotCannotClaimACountedUnitOnAMeasuredOrder() {
            prescription.setQuantity(new BigDecimal("7.5"));
            prescription.setQuantityUnit("ml");
            // refused at the ceiling, before the patient, pharmacy and actors are read
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            DispenseRequestDTO eight = fill("8", "8");
            eight.setStockLotId(null);
            eight.setUnit("comprimé");
            assertThatThrownBy(() -> service.createDispense(eight))
                    .isInstanceOf(BusinessException.class)
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()))
                    .matches(e -> "7.5".equals(((BusinessException) e).getArgs()[0]));
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("a whole partial fill of the same tablets still goes through")
        void aWholePartialFillIsAccepted() {
            stubUpToTheLot(true);
            DispenseRequestDTO dto = fill("12", "3");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(new BigDecimal("3"));
            when(prescriptionRepository.save(any())).thenReturn(prescription);

            service.createDispense(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("47");
            assertThat(inventoryItem.getQuantityOnHand()).isEqualByComparingTo("97");
        }

        private void stubAcceptedFill(DispenseRequestDTO dto, String dispensedAfter) {
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(new BigDecimal(dispensedAfter));
            when(prescriptionRepository.save(any())).thenReturn(prescription);
        }

        @Test
        @DisplayName("the re-test order (12, a legacy 2.5 given): 9.5 is refused, 10 completes it, 11 is above the prescription")
        void aLegacyHalfTabletRemainderIsMetByTheNextWholeTablet() {
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(new BigDecimal("2.5"));
            stubUpToTheLot(true);
            assertThatThrownBy(() -> service.createDispense(fill("9.5", "9.5")))
                    .matches(e -> WholeUnitQuantity.DEFAULT_KEY.equals(((BusinessException) e).getMessageKey()));
            DispenseRequestDTO eleven = fill("11", "11");
            // the refusal names the 10 that is accepted, not the 9.5 the whole-unit rule refuses
            assertThatThrownBy(() -> service.createDispense(eleven))
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()))
                    .matches(e -> "10".equals(((BusinessException) e).getArgs()[0]));

            DispenseRequestDTO ten = fill("10", "10");
            stubAcceptedFill(ten, "12.5");
            service.createDispense(ten);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("40");
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
        }

        @Test
        @DisplayName("a half-tablet regimen of 7.5 tablets is filled with 8")
        void aFractionalPrescriptionOfTabletsIsFilledWithTheNextWholeTablet() {
            prescription.setQuantity(new BigDecimal("7.5"));
            stubUpToTheLot(true);
            DispenseRequestDTO eight = fill("8", "8");
            stubAcceptedFill(eight, "8");

            service.createDispense(eight);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("42");
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
        }

        @Test
        @DisplayName("the next-whole-unit ceiling is for counted items only: 8 ml against 7.5 ml is refused")
        void aMeasuredItemKeepsTheExactCeiling() {
            inventoryItem.setUnit("ml");
            prescription.setQuantity(new BigDecimal("7.5"));
            // refused at the ceiling, before the patient, pharmacy and actors are read
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            DispenseRequestDTO eight = fill("8", "8");
            assertThatThrownBy(() -> service.createDispense(eight))
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()));
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
        }

        @Test
        @DisplayName("a controlled substance is never met by the next whole unit: 8 tablets against 7.5 is refused, and the message names 7")
        void aControlledSubstanceNamesTheWholeUnitsBelowTheCeiling() {
            prescription.setQuantity(new BigDecimal("7.5"));
            when(controlledResolver.isControlled(prescription)).thenReturn(true);
            prescription.setTwoFactorVerifiedAt(java.time.LocalDateTime.of(2026, 8, 24, 9, 0));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            DispenseRequestDTO eight = fill("8", "8");
            // 7.5 would be refused as fractional; 7 is the most that is accepted
            assertThatThrownBy(() -> service.createDispense(eight))
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()))
                    .matches(e -> "7".equals(((BusinessException) e).getArgs()[0]));
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("a controlled substance with 9.5 left after a legacy 2.5 fill: 10 is refused, and the message names 9")
        void aControlledSubstanceAfterALegacyHalfFillNamesNine() {
            when(controlledResolver.isControlled(prescription)).thenReturn(true);
            prescription.setTwoFactorVerifiedAt(java.time.LocalDateTime.of(2026, 8, 24, 9, 0));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(stockLotRepository.findById(stockLotId)).thenReturn(Optional.of(stockLot));
            when(dispenseRepository.sumQuantityCommittedForPrescription(prescriptionId)).thenReturn(new BigDecimal("2.5"));
            DispenseRequestDTO ten = fill("10", "10");
            assertThatThrownBy(() -> service.createDispense(ten))
                    .matches(e -> "pharmacy.dispense.exceedsPrescribed".equals(((BusinessException) e).getMessageKey()))
                    .matches(e -> "9".equals(((BusinessException) e).getArgs()[0]));
            verify(stockLotRepository, never()).decrementRemaining(any(), any(), any());
            verify(dispenseRepository, never()).save(any(Dispense.class));
        }

        @Test
        @DisplayName("a measured item (ml) still dispenses a fraction")
        void aMeasuredItemDispensesAFraction() {
            inventoryItem.setUnit("ml");
            prescription.setQuantityUnit("ml");
            stubUpToTheLot(true);
            DispenseRequestDTO dto = fill("12", "2.5");
            dto.setUnit("ml");
            Dispense entity = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
                    .thenReturn(new BigDecimal("2.5"));
            when(prescriptionRepository.save(any())).thenReturn(prescription);

            service.createDispense(dto);

            assertThat(stockLot.getRemainingQuantity()).isEqualByComparingTo("47.5");
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

        /** Rule 1 (#825 security finding 2): the legacy cancel locks the prescription first. */
        @BeforeEach
        void prescriptionLock() {
            org.mockito.Mockito.lenient().when(prescriptionRepository.findByIdForUpdate(prescriptionId))
                    .thenReturn(Optional.of(prescription));
            // QA D404: the dispense row lock answers with the row findById gives.
            org.mockito.Mockito.lenient().when(dispenseRepository.findLockedById(dispenseId))
                    .thenAnswer(inv -> dispenseRepository.findById(dispenseId));
        }

        @Test
        @DisplayName("#825 finding 2: the prescription is locked before the dispense is touched")
        void locksThePrescriptionFirst() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);

            service.cancelDispense(dispenseId);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(prescriptionRepository, dispenseRepository);
            order.verify(prescriptionRepository).findByIdForUpdate(prescriptionId);
            order.verify(dispenseRepository).save(dispense);
        }

        @Test
        @DisplayName("QA D404: a paid fill's payment is voided after the dispense row is locked")
        void voidsThePaymentOfACancelledFill() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);

            service.cancelDispense(dispenseId);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(prescriptionRepository, dispenseRepository,
                    paymentVoider);
            order.verify(prescriptionRepository).findByIdForUpdate(prescriptionId);
            order.verify(dispenseRepository).findLockedById(dispenseId);
            order.verify(dispenseRepository).save(dispense);
            order.verify(paymentVoider).voidForCancelledDispense(eq(dispense), any());
        }

        @Test
        @DisplayName("QA D404: a refused cancel voids nothing")
        void aRefusedCancelVoidsNothing() {
            Dispense dispense = buildDispense(DispenseStatus.CANCELLED);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId)).isInstanceOf(BusinessException.class);

            verify(paymentVoider, never()).voidForCancelledDispense(any(), any());
        }

        @Test
        @DisplayName("a global-view caller cannot undo another tenant's fill")
        void refusesWithoutAHospital() {
            // Cancelling reverses a stock lot and rewrites the prescription's
            // status. enforceHospitalScope(Pharmacy) tolerates a null on its
            // own, so this write sat outside the read/write split until now.
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);

            assertThatThrownBy(() -> service.cancelDispense(dispenseId))
                    .isInstanceOf(ResourceNotFoundException.class);

            assertThat(dispense.getStatus()).isEqualTo(DispenseStatus.COMPLETED);
            verify(dispenseRepository, never()).save(any());
            verify(stockLotRepository, never()).save(any());
        }

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
            verify(stockLotRepository).incrementRemaining(eq(stockLotId), eq(BigDecimal.TEN), any());
            verify(inventoryItemRepository).incrementOnHand(eq(inventoryItem.getId()), eq(BigDecimal.TEN), any());
            verify(stockTransactionRepository).save(any());
            // Undoing a fill is bookkeeping, not a pharmacy outcome: nothing is announced.
            verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        }

        private Dispense controlledFillToCancel() {
            catalogItem.setControlled(true);
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setQuantityDispensed(new BigDecimal("3"));
            dispense.setStockLot(stockLot);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            return dispense;
        }

        /**
         * QA D654, the exact replay: POST /pharmacy/dispense/b0d16827/cancel
         * on a morphine fill, no body: 200, 2 units back, witnessed_by NULL.
         */
        @Test
        @DisplayName("D654: a controlled fill is not cancelled back onto the shelf without a witness")
        void d654_controlledCancelNeedsAWitness() {
            controlledFillToCancel();
            when(controlledStockWitness.resolve(null, inventoryItem, user))
                    .thenThrow(new BusinessException("stockTransaction.witness.required"));

            assertThatThrownBy(() -> service.cancelDispense(dispenseId)).isInstanceOf(BusinessException.class);

            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
            verify(stockTransactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("D654: a witnessed controlled cancel records its witness on the return")
        void d654_witnessedControlledCancelIsRecorded() {
            Dispense dispense = controlledFillToCancel();
            UUID witnessId = UUID.randomUUID();
            User witness = new User();
            witness.setId(witnessId);
            when(controlledStockWitness.resolve(witnessId, inventoryItem, user)).thenReturn(witness);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);

            service.cancelDispense(dispenseId, witnessId);

            org.mockito.ArgumentCaptor<com.example.hms.model.pharmacy.StockTransaction> tx =
                    org.mockito.ArgumentCaptor.forClass(com.example.hms.model.pharmacy.StockTransaction.class);
            verify(stockTransactionRepository).save(tx.capture());
            assertThat(tx.getValue().getWitnessedByUser()).isSameAs(witness);
            assertThat(tx.getValue().getQuantity()).isEqualByComparingTo("3");
        }

        /**
         * QA D604: dispense 63a1f3fb delivered 4, 2 came back by a RETURN
         * referencing it; the cancel then put the whole 4 back (9 for 4).
         * It now puts back only the 2 still out, and its reversal names the
         * dispense so it counts as one of its returns.
         */
        @Test
        @DisplayName("D604: a cancel puts back only what was not already returned against the fill")
        void cancelReturnsOnlyTheRemainder() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setQuantityDispensed(new BigDecimal("4"));
            dispense.setStockLot(stockLot);
            stockLot.setRemainingQuantity(BigDecimal.valueOf(40));
            inventoryItem.setQuantityOnHand(BigDecimal.valueOf(90));
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(stockTransactionRepository.sumQuantityForReference(dispenseId,
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(new BigDecimal("2"));

            service.cancelDispense(dispenseId);

            verify(stockLotRepository).incrementRemaining(eq(stockLotId), eq(new BigDecimal("2")), any());
            verify(inventoryItemRepository).incrementOnHand(eq(inventoryItem.getId()), eq(new BigDecimal("2")), any());
            org.mockito.ArgumentCaptor<com.example.hms.model.pharmacy.StockTransaction> tx =
                    org.mockito.ArgumentCaptor.forClass(com.example.hms.model.pharmacy.StockTransaction.class);
            verify(stockTransactionRepository).save(tx.capture());
            assertThat(tx.getValue().getReferenceId()).isEqualTo(dispenseId);
            assertThat(tx.getValue().getQuantity()).isEqualByComparingTo("2");
        }

        /**
         * #926 review: the portal RETURN form names no dispense, so a RETURN
         * of 4 on a lot whose only issue is this 4-unit fill was not counted
         * against the fill, and the cancel put 4 more back (8 for 4). The
         * reversal is now capped at what is still out of the lot.
         */
        @Test
        @DisplayName("D604: an unreferenced return of the whole fill leaves the cancel nothing to move")
        void unreferencedReturnLeavesNothingToCancel() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setQuantityDispensed(new BigDecimal("4"));
            dispense.setStockLot(stockLot);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(stockTransactionRepository.sumQuantityForReference(dispenseId,
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(BigDecimal.ZERO);
            when(stockTransactionRepository.sumQuantityForLot(stockLotId,
                    java.util.EnumSet.of(StockTransactionType.DISPENSE, StockTransactionType.TRANSFER)))
                    .thenReturn(new BigDecimal("4"));
            when(stockTransactionRepository.sumQuantityForLot(stockLotId,
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(new BigDecimal("4"));

            service.cancelDispense(dispenseId);

            verify(inventoryItemRepository).lockById(inventoryItem.getId());
            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
            verify(stockTransactionRepository, never()).save(any());
            assertThat(dispense.getStatus()).isEqualTo(DispenseStatus.CANCELLED);
        }

        @Test
        @DisplayName("D604: a fill returned in full is cancelled with no stock movement")
        void cancelOfAFullyReturnedFillMovesNothing() {
            Dispense dispense = buildDispense(DispenseStatus.COMPLETED);
            dispense.setQuantityDispensed(new BigDecimal("4"));
            dispense.setStockLot(stockLot);
            when(dispenseRepository.findById(dispenseId)).thenReturn(Optional.of(dispense));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(dispense);
            when(stockTransactionRepository.sumQuantityForReference(dispenseId,
                    java.util.EnumSet.of(StockTransactionType.RETURN))).thenReturn(new BigDecimal("4"));

            service.cancelDispense(dispenseId);

            verify(stockLotRepository, never()).incrementRemaining(any(), any(), any());
            verify(inventoryItemRepository, never()).incrementOnHand(any(), any(), any());
            verify(stockTransactionRepository, never()).save(any());
            assertThat(dispense.getStatus()).isEqualTo(DispenseStatus.CANCELLED);
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
                    .hasMessageContaining("quantity requested");
        }

        @Test
        @DisplayName("should reject zero quantity dispensed")
        void shouldRejectZeroQuantityDispensed() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            DispenseRequestDTO dto = buildRequest();
            dto.setQuantityDispensed(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("quantity dispensed must");
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("patient does not match");
        }

        @Test
        @DisplayName("should reject when verifiedBy != authenticated user")
        void shouldRejectVerifierMismatch() {
            DispenseRequestDTO dto = buildRequest();
            dto.setVerifiedBy(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("verifier must be the signed-in user");
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
        @DisplayName("an order whose SMS dispatch failed is on the queue, flagged, and fillable at the counter")
        void transmissionFailedIsQueuedFlaggedAndDispensable() {
            Pageable pageable = PageRequest.of(0, 20);
            Prescription failed = new Prescription();
            failed.setId(UUID.randomUUID());
            failed.setStatus(PrescriptionStatus.TRANSMISSION_FAILED);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<PrescriptionStatus>> asked =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByHospital_IdAndStatusIn(
                    eq(hospitalId), asked.capture(), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(failed)));

            List<com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO> rows =
                    service.getWorkQueue(pageable).getContent();

            assertThat(asked.getValue()).contains(PrescriptionStatus.TRANSMISSION_FAILED);
            assertThat(DispenseServiceImpl.DISPENSABLE_STATUSES).contains(PrescriptionStatus.TRANSMISSION_FAILED);
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.isNeedsAttention()).isTrue();
                assertThat(row.getAttentionReason()).isEqualTo("TRANSMISSION_FAILED");
            });
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
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findHospitalIdById(dispenseId)).thenReturn(Optional.of(hospitalId));
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
        @DisplayName("#825 finding 4: a key that names another hospital's dispense answers as a missing one")
        void replayOfAnotherHospitalsKeyIsNotFound() {
            DispenseRequestDTO replay = buildRequest();
            replay.setIdempotencyKey(KEY);
            Dispense foreign = buildDispense(DispenseStatus.COMPLETED);
            when(dispenseRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(foreign));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(dispenseRepository.findHospitalIdById(dispenseId)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThatThrownBy(() -> service.createDispense(replay))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.markReadyForCollection(replay))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(dispenseMapper, never()).toResponseDTO(any());
        }

        @Test
        @DisplayName("#825 finding 4: a global-view caller gets no replay either")
        void replayWithoutAHospitalIsNotFound() {
            DispenseRequestDTO replay = buildRequest();
            replay.setIdempotencyKey(KEY);
            when(dispenseRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(buildDispense(DispenseStatus.COMPLETED)));
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(dispenseRepository.findHospitalIdById(dispenseId)).thenReturn(Optional.of(hospitalId));

            assertThatThrownBy(() -> service.createDispense(replay))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(dispenseMapper, never()).toResponseDTO(any());
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(dispenseMapper.toEntity(eq(dto), any())).thenReturn(entity);
            when(dispenseRepository.save(any(Dispense.class))).thenReturn(entity);
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
            when(dispenseRepository.findHospitalIdById(dispenseId)).thenReturn(Optional.of(hospitalId));

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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
            when(dispenseRepository.sumQuantityDispensedForPrescription(prescriptionId, DispenseRepository.NOT_A_FILL))
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hospital dispensary")
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
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

            assertThatThrownBy(() -> service.createDispense(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("PARTNER_PHARMACY");

            verify(dispenseRepository, never()).save(any());
        }
    }

    /**
     * QA D241: the counter's lot picker listed every lot of the pharmacy,
     * morphine included, for a paracetamol order. The server lists only
     * lots of the prescribed drug with stock left, for a prescription of the
     * caller's own hospital.
     */
    @Nested
    @DisplayName("listDispensableLots (QA D241)")
    class ListDispensableLots {

        private StockLot lot(String genericName, String lotNumber, BigDecimal remaining) {
            MedicationCatalogItem drug = new MedicationCatalogItem();
            drug.setId(UUID.randomUUID());
            drug.setGenericName(genericName);
            InventoryItem item = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(drug)
                    .quantityOnHand(BigDecimal.valueOf(100)).build();
            item.setId(UUID.randomUUID());
            StockLot lot = StockLot.builder().inventoryItem(item).lotNumber(lotNumber)
                    .expiryDate(TODAY.plusMonths(6)).remainingQuantity(remaining)
                    .barcodeValue(LotBarcode.mint()).build();
            lot.setId(UUID.randomUUID());
            return lot;
        }

        /** QA D335: an expired lot is not offered, and the rest come shortest-dated first. */
        @Test
        void expiredLotsAreDroppedAndTheRestAreFefoOrdered() {
            prescription.setMedicationName("Paracétamol 500 mg comprimé");
            StockLot late = lot("paracetamol", "PCM-LATE", BigDecimal.valueOf(10));
            late.setExpiryDate(java.time.LocalDate.now().plusYears(2));
            StockLot expired = lot("paracetamol", "PCM-OLD", BigDecimal.valueOf(10));
            expired.setExpiryDate(java.time.LocalDate.now().minusDays(1));
            StockLot soon = lot("paracetamol", "PCM-SOON", BigDecimal.valueOf(10));
            soon.setExpiryDate(java.time.LocalDate.now().plusDays(10));
            // QA D710: expiring today is expired, and was listed FIRST.
            StockLot today = lot("paracetamol", "PCM-TODAY", BigDecimal.valueOf(10));
            today.setExpiryDate(java.time.LocalDate.now());
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(stockLotRepository.findByInventoryItemPharmacyId(eq(pharmacyId), any()))
                    .thenReturn(new PageImpl<>(List.of(late, expired, today, soon)));

            assertThat(service.listDispensableLots(prescriptionId, pharmacyId))
                    .extracting(com.example.hms.payload.dto.pharmacy.StockLotResponseDTO::getLotNumber)
                    .containsExactly("PCM-SOON", "PCM-LATE");
        }

        @Test
        void onlyLotsOfThePrescribedDrugWithStockLeftAreListed() {
            prescription.setMedicationName("Paracétamol 500 mg comprimé");
            StockLot paracetamol = lot("paracetamol", "PCM-1", BigDecimal.valueOf(40));
            StockLot emptyParacetamol = lot("paracetamol", "PCM-0", BigDecimal.ZERO);
            StockLot morphine = lot("morphine", "MOR-1", BigDecimal.valueOf(20));
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            when(stockLotRepository.findByInventoryItemPharmacyId(eq(pharmacyId), any()))
                    .thenReturn(new PageImpl<>(List.of(paracetamol, emptyParacetamol, morphine)));

            assertThat(service.listDispensableLots(prescriptionId, pharmacyId))
                    .extracting(com.example.hms.payload.dto.pharmacy.StockLotResponseDTO::getLotNumber)
                    .containsExactly("PCM-1");
        }

        @Test
        void withoutAnActiveHospitalNothingIsListed() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);

            assertThatThrownBy(() -> service.listDispensableLots(prescriptionId, pharmacyId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(prescriptionRepository, never()).findById(any());
        }

        @Test
        void aPrescriptionOfAnotherHospitalIsRefusedLikeAMissingOne() {
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            prescription.setHospital(other);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.listDispensableLots(prescriptionId, pharmacyId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(stockLotRepository, never()).findByInventoryItemPharmacyId(any(), any());
        }
    }

    @Nested
    @DisplayName("QA D780: the not-dispensable refusal names the status in words")
    class NotDispensableLabel {

        @org.junit.jupiter.api.AfterEach
        void resetLocale() {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }

        @Test
        @DisplayName("a second fill of a DISPENSED order reads 'délivrée' in French, never the raw code")
        void dispensedIsWordedInFrench() {
            com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            prescription.setStatus(PrescriptionStatus.DISPENSED);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(eq(prescriptionId), any())).thenReturn(Optional.of(prescription));

            DispenseRequestDTO request = buildRequest();
            assertThatThrownBy(() -> service.createDispense(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("délivrée")
                    .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage()).doesNotContain("DISPENSED"));
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(PrescriptionStatus.class)
        @DisplayName("every status has a label in EN, FR and ES (no raw code can leak)")
        void everyStatusIsLabelled(PrescriptionStatus status) {
            com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
            for (java.util.Locale locale : java.util.List.of(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.of("es"))) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                org.assertj.core.api.Assertions.assertThat(status.localizedLabel()).isNotEqualTo(status.name());
            }
        }
    }
}
