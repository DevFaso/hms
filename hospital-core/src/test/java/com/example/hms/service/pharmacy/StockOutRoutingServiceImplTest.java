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

    @Test
    @DisplayName("checkStock should report sufficient when dispensary stock meets need")
    void checkStockShouldReportSufficient() {
        InventoryItem dispensaryInventory = InventoryItem.builder()
                .pharmacy(dispensary)
                .medicationCatalogItem(catalogItem)
                .quantityOnHand(BigDecimal.valueOf(50))
                .active(true)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationCatalogItemRepository.findByHospitalIdAndCode(hospitalId, "AMOX500"))
                .thenReturn(Optional.of(catalogItem));
        when(inventoryItemRepository.findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                hospitalId, medicationId)).thenReturn(List.of(dispensaryInventory));

        StockCheckResultDTO result = service.checkStock(prescriptionId);

        assertThat(result.isSufficient()).isTrue();
        assertThat(result.getPartnerPharmacies()).isEmpty();
    }

    @Test
    @DisplayName("checkStock should throw when prescription belongs to other hospital")
    void checkStockShouldRejectCrossHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        prescription.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.checkStock(prescriptionId))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("routeToPartner should reject non-routable prescription status")
    void routeToPartnerShouldRejectNonRoutableStatus() {
        prescription.setStatus(PrescriptionStatus.DISPENSED);
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not in a routable state");
    }

    @Test
    @DisplayName("routeToPartner should reject null target pharmacy")
    void routeToPartnerShouldRejectNullTarget() {
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Target pharmacy ID");
    }

    @Test
    @DisplayName("routeToPartner should reject non-partner pharmacy")
    void routeToPartnerShouldRejectNonPartnerType() {
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(dispensaryId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(dispensaryId)).thenReturn(Optional.of(dispensary));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PARTNER_PHARMACY");
    }

    @Test
    @DisplayName("routeToPartner should reject target pharmacy from other hospital")
    void routeToPartnerShouldRejectCrossHospitalTarget() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Pharmacy crossHospitalPartner = Pharmacy.builder()
                .hospital(other)
                .pharmacyType(PharmacyType.PARTNER_PHARMACY)
                .build();
        crossHospitalPartner.setId(partnerId);
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(partnerId)).thenReturn(Optional.of(crossHospitalPartner));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("printForPatient should update status and persist decision")
    void printForPatientShouldSucceed() {
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .routingType("PRINT").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routingMapper.toResponseDTO(any())).thenReturn(response);

        RoutingDecisionResponseDTO result = service.printForPatient(prescriptionId);

        assertThat(result.getRoutingType()).isEqualTo("PRINT");
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PRINTED_FOR_PATIENT);
        verify(prescriptionRepository).save(prescription);
    }

    @Test
    @DisplayName("backOrder should update status and persist decision")
    void backOrderShouldSucceed() {
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .routingType("BACKORDER").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routingMapper.toResponseDTO(any())).thenReturn(response);

        RoutingDecisionResponseDTO result = service.backOrder(prescriptionId, java.time.LocalDate.now().plusDays(7));

        assertThat(result.getRoutingType()).isEqualTo("BACKORDER");
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_STOCK);
        verify(prescriptionRepository).save(prescription);
    }

    @Test
    @DisplayName("partnerRespond should accept and update statuses")
    void partnerRespondShouldAccept() {
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
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .status("ACCEPTED").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));
        when(routingDecisionRepository.save(any())).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);

        RoutingDecisionResponseDTO result = service.partnerRespond(decisionId, true);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
    }

    @Test
    @DisplayName("partnerRespond should reject and update statuses")
    void partnerRespondShouldReject() {
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
        RoutingDecisionResponseDTO response = RoutingDecisionResponseDTO.builder()
                .status("REJECTED").build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));
        when(routingDecisionRepository.save(any())).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(response);

        service.partnerRespond(decisionId, false);

        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
    }

    @Test
    @DisplayName("partnerRespond should reject non-PARTNER routing type")
    void partnerRespondShouldRejectNonPartnerRoutingType() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .routingType(RoutingType.PRINT)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> service.partnerRespond(decisionId, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PARTNER routing");
    }

    @Test
    @DisplayName("confirmPartnerDispense should update status when accepted")
    void confirmPartnerDispenseShouldSucceed() {
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

        service.confirmPartnerDispense(decisionId);

        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);
    }

    @Test
    @DisplayName("listByPrescription should enforce scope and map")
    void listByPrescriptionShouldMap() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .build();
        RoutingDecisionResponseDTO dto = RoutingDecisionResponseDTO.builder().build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(decision)));
        when(routingMapper.toResponseDTO(decision)).thenReturn(dto);

        org.springframework.data.domain.Page<RoutingDecisionResponseDTO> page =
                service.listByPrescription(prescriptionId, pageable);
        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("listByPatient should enforce scope and map")
    void listByPatientShouldMap() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        UUID patientQueryId = UUID.randomUUID();
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .build();
        RoutingDecisionResponseDTO dto = RoutingDecisionResponseDTO.builder().build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findByDecidedForPatientId(patientQueryId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(decision)));
        when(routingMapper.toResponseDTO(decision)).thenReturn(dto);

        org.springframework.data.domain.Page<RoutingDecisionResponseDTO> page =
                service.listByPatient(patientQueryId, pageable);
        assertThat(page.getContent()).hasSize(1);
    }
}