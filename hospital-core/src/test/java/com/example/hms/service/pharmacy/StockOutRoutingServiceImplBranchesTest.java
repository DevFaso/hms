package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.pharmacy.PrescriptionRoutingMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockCheckResultDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.pharmacy.partner.PartnerNotificationChannel;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link StockOutRoutingServiceImpl}: null user,
 * null restock date, null/blank medication code, null quantityOnHand entries,
 * and the best-effort partner SMS error-handling paths.
 */
@ExtendWith(MockitoExtension.class)
class StockOutRoutingServiceImplBranchesTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PrescriptionRoutingMapper routingMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private PartnerNotificationChannel partnerChannel;

    @InjectMocks
    private StockOutRoutingServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Hospital hospital;
    private Prescription prescription;
    private Patient patient;
    private User currentUser;
    private Pharmacy partnerPharmacy;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        currentUser = new User();
        currentUser.setId(userId);

        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setMedicationCode("AMOX500");
        prescription.setMedicationName("Amoxicillin 500mg");
        prescription.setQuantity(BigDecimal.TEN);
        prescription.setPatient(patient);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription.setHospital(hospital);

        partnerPharmacy = Pharmacy.builder()
                .hospital(hospital)
                .name("Partner Pharmacy")
                .phoneNumber("+22670000000")
                .pharmacyType(PharmacyType.PARTNER_PHARMACY)
                .build();
        partnerPharmacy.setId(partnerId);
    }

    @Test
    @DisplayName("checkStock handles prescription with null medication code (no catalog lookup)")
    void checkStockNullMedicationCode() {
        prescription.setMedicationCode(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                hospitalId, PharmacyType.PARTNER_PHARMACY)).thenReturn(List.of(partnerPharmacy));

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.isSufficient()).isFalse();
        assertThat(result.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getPartnerPharmacies()).hasSize(1);
        assertThat(result.getPartnerPharmacies().get(0).isHasOnFormulary()).isFalse();
    }

    @Test
    @DisplayName("checkStock handles prescription with blank medication code")
    void checkStockBlankMedicationCode() {
        prescription.setMedicationCode("   ");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                hospitalId, PharmacyType.PARTNER_PHARMACY)).thenReturn(List.of());

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.isSufficient()).isFalse();
        assertThat(result.getPartnerPharmacies()).isEmpty();
    }

    @Test
    @DisplayName("checkStock ignores inventory items with null pharmacy or null quantity")
    void checkStockIgnoresNullQuantityItems() {
        UUID medicationId = UUID.randomUUID();
        MedicationCatalogItem catalogItem = MedicationCatalogItem.builder()
                .hospital(hospital)
                .code("AMOX500")
                .build();
        catalogItem.setId(medicationId);

        Pharmacy dispensary = Pharmacy.builder()
                .hospital(hospital)
                .pharmacyType(PharmacyType.HOSPITAL_DISPENSARY)
                .build();
        dispensary.setId(UUID.randomUUID());

        InventoryItem nullQty = InventoryItem.builder()
                .pharmacy(dispensary)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(null)
                .active(true)
                .build();
        InventoryItem nullPharmacy = InventoryItem.builder()
                .pharmacy(null)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.valueOf(100))
                .active(true)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationCatalogItemRepository.findByHospitalIdAndCode(hospitalId, "AMOX500"))
                .thenReturn(Optional.of(catalogItem));
        when(inventoryItemRepository.findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                hospitalId, medicationId)).thenReturn(List.of(nullQty, nullPharmacy));
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                hospitalId, PharmacyType.PARTNER_PHARMACY)).thenReturn(List.of());

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.isSufficient()).isFalse();
    }

    @Test
    @DisplayName("checkStock treats null prescription.quantity as ONE")
    void checkStockNullQuantityDefaultsToOne() {
        prescription.setQuantity(null);
        UUID medicationId = UUID.randomUUID();
        MedicationCatalogItem catalogItem = MedicationCatalogItem.builder()
                .hospital(hospital)
                .code("AMOX500")
                .build();
        catalogItem.setId(medicationId);

        Pharmacy dispensary = Pharmacy.builder()
                .hospital(hospital)
                .pharmacyType(PharmacyType.HOSPITAL_DISPENSARY)
                .build();
        dispensary.setId(UUID.randomUUID());

        InventoryItem inv = InventoryItem.builder()
                .pharmacy(dispensary)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.ONE)
                .active(true)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationCatalogItemRepository.findByHospitalIdAndCode(hospitalId, "AMOX500"))
                .thenReturn(Optional.of(catalogItem));
        when(inventoryItemRepository.findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                hospitalId, medicationId)).thenReturn(List.of(inv));

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.isSufficient()).isTrue();
    }

    @Test
    @DisplayName("backOrder without restock date uses shorter patient-notification suffix")
    void backOrderNullRestockDate() {
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .routingType("BACKORDER").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routingMapper.toResponseDTO(any())).thenReturn(response);

        service.backOrder(prescriptionId, null);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_STOCK);
        verify(support).notifyOutOfStock(eq(patient), eq(prescription.getMedicationName()),
                contains("disponibilit"));
    }

    @Test
    @DisplayName("resolveCurrentUser throws BusinessException when no user in context")
    void resolveCurrentUserNullThrows() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.printForPatient(prescriptionId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");
    }

    @Test
    @DisplayName("resolveCurrentUser throws ResourceNotFoundException when user missing from repo")
    void resolveCurrentUserMissingThrows() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.printForPatient(prescriptionId))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("routeToPartner still succeeds when partner SMS channel throws")
    void routeToPartnerSwallowsChannelException() {
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .build();
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        decision.setId(UUID.randomUUID());
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .routingType("PARTNER").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(partnerId)).thenReturn(Optional.of(partnerPharmacy));
        when(routingMapper.toEntity(eq(request), any())).thenReturn(decision);
        when(routingDecisionRepository.save(decision)).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);
        doThrow(new RuntimeException("sms gw down"))
                .when(partnerChannel).sendPrescriptionOffer(any(), any(), any());

        RoutingDecisionResponseDTO result = service.routeToPartner(prescriptionId, request);

        assertThat(result.getRoutingType()).isEqualTo("PARTNER");
        verify(partnerChannel).sendPrescriptionOffer(any(), any(), any());
    }

    @Test
    @DisplayName("partnerRespond accept swallows partner channel SMS failures")
    void partnerRespondAcceptSwallowsException() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .status("ACCEPTED").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));
        when(routingDecisionRepository.save(any())).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);
        doThrow(new RuntimeException("down"))
                .when(partnerChannel).notifyPatientAccepted(any(), any());

        RoutingDecisionResponseDTO result = service.partnerRespond(decisionId, true);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        verify(partnerChannel).notifyPatientAccepted(any(), any());
    }

    @Test
    @DisplayName("confirmPartnerDispense swallows partner channel SMS failures")
    void confirmPartnerDispenseSwallowsException() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.ACCEPTED)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .status("COMPLETED").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));
        when(routingDecisionRepository.save(any())).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);
        doThrow(new RuntimeException("down"))
                .when(partnerChannel).notifyPatientDispensed(any(), any());

        service.confirmPartnerDispense(decisionId);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);
        verify(partnerChannel).notifyPatientDispensed(any(), any());
    }

    @Test
    @DisplayName("backOrder rejects cross-hospital prescriptions")
    void backOrderRejectsCrossHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        prescription.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.backOrder(prescriptionId, LocalDate.now().plusDays(3)))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("partnerRespond enforces PENDING status")
    void partnerRespondRejectsNonPendingStatus() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.ACCEPTED)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> service.partnerRespond(decisionId, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING");
    }
}
