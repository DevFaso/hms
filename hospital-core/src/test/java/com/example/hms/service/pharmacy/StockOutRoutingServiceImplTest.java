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
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockOutRoutingServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PrescriptionRoutingMapper routingMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks
    private StockOutRoutingServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID medicationId = UUID.randomUUID();
    private final UUID dispensaryId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Hospital hospital;
    private Prescription prescription;
    private Patient patient;
    private User currentUser;
    private MedicationCatalogItem catalogItem;
    private Pharmacy dispensary;
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

        catalogItem = MedicationCatalogItem.builder()
                .hospital(hospital)
                .code("AMOX500")
                .nameFr("Amoxicilline 500 mg")
                .genericName("Amoxicillin")
                .build();
        catalogItem.setId(medicationId);

        dispensary = Pharmacy.builder()
                .hospital(hospital)
                .name("Main Dispensary")
                .pharmacyType(PharmacyType.HOSPITAL_DISPENSARY)
                .build();
        dispensary.setId(dispensaryId);

        partnerPharmacy = Pharmacy.builder()
                .hospital(hospital)
                .name("Partner Pharmacy")
                .city("Ouagadougou")
                .phoneNumber("+22670000000")
                .pharmacyType(PharmacyType.PARTNER_PHARMACY)
                .build();
        partnerPharmacy.setId(partnerId);
    }

    @Test
    @DisplayName("checkStock should include partner routing options when stock is insufficient")
    void checkStockShouldIncludePartnerOptionsWhenInsufficient() {
        InventoryItem dispensaryInventory = InventoryItem.builder()
                .pharmacy(dispensary)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.ONE)
                .active(true)
                .build();
        InventoryItem partnerInventory = InventoryItem.builder()
                .pharmacy(partnerPharmacy)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.valueOf(25))
                .active(true)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationCatalogItemRepository.findByHospitalIdAndCode(hospitalId, "AMOX500"))
                .thenReturn(Optional.of(catalogItem));
        when(inventoryItemRepository.findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                hospitalId, medicationId)).thenReturn(List.of(dispensaryInventory, partnerInventory));
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                hospitalId, PharmacyType.PARTNER_PHARMACY)).thenReturn(List.of(partnerPharmacy));
        when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(partnerId, medicationId))
                .thenReturn(Optional.of(partnerInventory));

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.getMedicationName()).isEqualTo("Amoxicillin 500mg");
        assertThat(result.getQuantityOnHand()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.isSufficient()).isFalse();
        assertThat(result.getPartnerPharmacies()).hasSize(1);
        assertThat(result.getPartnerPharmacies().get(0).getPharmacyId()).isEqualTo(partnerId);
        assertThat(result.getPartnerPharmacies().get(0).isHasOnFormulary()).isTrue();
    }

    @Test
    @DisplayName("routeToPartner should update prescription and persist a routing decision")
    void routeToPartnerShouldUpdatePrescriptionAndPersistDecision() {
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .reason("Nearest partner has stock")
                .build();
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        decision.setId(UUID.randomUUID());
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .id(decision.getId())
                .prescriptionId(prescriptionId)
                .routingType("PARTNER")
                .targetPharmacyId(partnerId)
                .status("PENDING")
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(partnerId)).thenReturn(Optional.of(partnerPharmacy));
        when(routingMapper.toEntity(eq(request), any())).thenReturn(decision);
        when(routingDecisionRepository.save(decision)).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);

        RoutingDecisionResponseDTO result = service.routeToPartner(prescriptionId, request);

        assertThat(result.getRoutingType()).isEqualTo("PARTNER");
        assertThat(request.getRoutingType()).isEqualTo(RoutingType.PARTNER);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(prescription.getPharmacyId()).isEqualTo(partnerId);
        assertThat(prescription.getPharmacyName()).isEqualTo("Partner Pharmacy");
        verify(prescriptionRepository).save(prescription);
        verify(routingDecisionRepository).save(decision);
    }

    @Test
    @DisplayName("confirmPartnerDispense should reject decisions that are not accepted")
    void confirmPartnerDispenseShouldRejectNonAcceptedDecision() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> service.confirmPartnerDispense(decisionId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACCEPTED status");
    }
}