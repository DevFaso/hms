package com.example.hms.service.pharmacy;

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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
