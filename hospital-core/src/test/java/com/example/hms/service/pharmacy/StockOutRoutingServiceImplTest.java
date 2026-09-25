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
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockOutRoutingServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private MedicationCatalogItemRepository medicationCatalogItemRepository;
    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private com.example.hms.repository.pharmacy.DispenseRepository dispenseRepository;
    @Mock private UserRepository userRepository;
    @Mock private PrescriptionRoutingMapper routingMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PharmacyServiceSupport support;
    @Mock private com.example.hms.service.pharmacy.partner.PartnerNotificationChannel partnerChannel;
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;

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
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeInAndActiveTrue(
                hospitalId, StockOutRoutingServiceImpl.EXTERNAL_PHARMACY_TYPES))
                .thenReturn(List.of(partnerPharmacy));
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
        // T-40 / G14: patient notified out-of-stock with the partner routing sentence
        verify(support).notifyOutOfStock(patient, prescription.getMedicationName(),
                PharmacyServiceSupport.OUT_OF_STOCK_PARTNER, "Partner Pharmacy");
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
    @DisplayName("G4: REQUIRES_EXTERNAL_FILL has no writer and is not a routable state")
    void routeToPartnerShouldRejectDeadRequiresExternalFillStatus() {
        prescription.setStatus(PrescriptionStatus.REQUIRES_EXTERNAL_FILL);
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("REQUIRES_EXTERNAL_FILL");
        verify(routingDecisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("round 3: a target with no phone number is refused — the offer is an SMS")
    void routeToPartnerShouldRejectTargetWithoutPhone() {
        Pharmacy noPhone = Pharmacy.builder()
                .hospital(hospital)
                .name("Pharmacie sans t\u00e9l\u00e9phone")
                .pharmacyType(PharmacyType.COMMUNITY_PHARMACY)
                .build();
        UUID noPhoneId = UUID.randomUUID();
        noPhone.setId(noPhoneId);
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(noPhoneId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(noPhoneId)).thenReturn(Optional.of(noPhone));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no phone number");

        // Nothing moved: the prescription is still dispensable in-house.
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
        verify(routingDecisionRepository, never()).save(any());
        verify(prescriptionRepository, never()).save(any());
        verifyNoInteractions(partnerChannel);
    }

    @Test
    @DisplayName("round 3: a blank phone number counts as none")
    void routeToPartnerShouldRejectTargetWithBlankPhone() {
        partnerPharmacy.setPhoneNumber("   ");
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(partnerId)
                .build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(partnerId)).thenReturn(Optional.of(partnerPharmacy));

        assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no phone number");
    }

    @Test
    @DisplayName("G2: a COMMUNITY_PHARMACY is an accepted routing target")
    void routeToPartnerShouldAcceptCommunityPharmacy() {
        Pharmacy community = Pharmacy.builder()
                .hospital(hospital)
                .name("Pharmacie du Quartier")
                .phoneNumber("+22670000002")
                .pharmacyType(PharmacyType.COMMUNITY_PHARMACY)
                .build();
        UUID communityId = UUID.randomUUID();
        community.setId(communityId);
        RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                .prescriptionId(prescriptionId)
                .targetPharmacyId(communityId)
                .build();
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(community)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        decision.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(pharmacyRepository.findById(communityId)).thenReturn(Optional.of(community));
        when(routingMapper.toEntity(eq(request), any())).thenReturn(decision);
        when(routingDecisionRepository.save(decision)).thenReturn(decision);
        when(routingMapper.toResponseDTO(decision)).thenReturn(
                RoutingDecisionResponseDTO.builder().id(decision.getId()).status("PENDING").build());

        RoutingDecisionResponseDTO result = service.routeToPartner(prescriptionId, request);

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(prescription.getPharmacyId()).isEqualTo(communityId);
        verify(partnerChannel).sendPrescriptionOffer(decision, prescription, community);
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
        // T-40 / G14: patient notified out-of-stock with the print-for-patient sentence
        verify(support).notifyOutOfStock(patient, prescription.getMedicationName(),
                PharmacyServiceSupport.OUT_OF_STOCK_PRINT);
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
        // T-40 / G14: patient notified out-of-stock with the dated back-order sentence
        verify(support).notifyOutOfStock(eq(patient), eq(prescription.getMedicationName()),
                eq(PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER_DATED), contains("-"));
        // G6: the prescriber hears about the back order
        verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PENDING_STOCK);
    }

    @Nested
    @DisplayName("G3: dead-end statuses are routable again")
    class DeadEndStatuses {

        private PrescriptionRoutingDecision pendingBackOrder() {
            PrescriptionRoutingDecision backOrder = PrescriptionRoutingDecision.builder()
                    .prescription(prescription)
                    .routingType(RoutingType.BACKORDER)
                    .status(RoutingDecisionStatus.PENDING)
                    .build();
            backOrder.setId(UUID.randomUUID());
            return backOrder;
        }

        @Test
        @DisplayName("a back-ordered prescription can be re-routed to a partner, and the back order is superseded")
        void pendingStockIsRoutable() {
            prescription.setStatus(PrescriptionStatus.PENDING_STOCK);
            PrescriptionRoutingDecision backOrder = pendingBackOrder();
            RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                    .prescriptionId(prescriptionId).targetPharmacyId(partnerId).build();
            PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                    .prescription(prescription).targetPharmacy(partnerPharmacy)
                    .routingType(RoutingType.PARTNER).status(RoutingDecisionStatus.PENDING).build();
            decision.setId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(pharmacyRepository.findById(partnerId)).thenReturn(Optional.of(partnerPharmacy));
            when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescriptionId))
                    .thenReturn(List.of(backOrder));
            when(routingMapper.toEntity(eq(request), any())).thenReturn(decision);
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(decision))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("PARTNER").build());

            service.routeToPartner(prescriptionId, request);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
            assertThat(backOrder.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
            verify(routingDecisionRepository).save(backOrder);
        }

        @Test
        @DisplayName("a partner-rejected prescription can be printed for the patient")
        void partnerRejectedIsRoutable() {
            prescription.setStatus(PrescriptionStatus.PARTNER_REJECTED);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(any()))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("PRINT").build());

            service.printForPatient(prescriptionId);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PRINTED_FOR_PATIENT);
        }

        @Test
        @DisplayName("re-routing a partially filled order supersedes the back order waiting for its remainder")
        void partiallyFilledRerouteSupersedesTheBackOrder() {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            PrescriptionRoutingDecision backOrder = pendingBackOrder();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescriptionId))
                    .thenReturn(List.of(backOrder));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(any()))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("PRINT").build());

            service.printForPatient(prescriptionId);

            assertThat(backOrder.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        }

        @Test
        @DisplayName("a partially filled prescription can be back-ordered for the remainder")
        void partiallyFilledIsRoutable() {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(any()))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("BACKORDER").build());

            service.backOrder(prescriptionId, null);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_STOCK);
            verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PENDING_STOCK);
        }

        @Test
        @DisplayName("G3 round 3: an order a partner accepted and never delivered can be re-routed, and that acceptance is superseded")
        void partnerAcceptedIsRoutableAndSupersedesTheAcceptance() {
            prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
            PrescriptionRoutingDecision accepted = PrescriptionRoutingDecision.builder()
                    .prescription(prescription)
                    .targetPharmacy(partnerPharmacy)
                    .routingType(RoutingType.PARTNER)
                    .status(RoutingDecisionStatus.ACCEPTED)
                    .build();
            accepted.setId(UUID.randomUUID());

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescriptionId))
                    .thenReturn(List.of(accepted));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(any()))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("PRINT").build());

            service.printForPatient(prescriptionId);

            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PRINTED_FOR_PATIENT);
            // The original partner can no longer confirm a dispense of an
            // order somebody else is now filling.
            assertThat(accepted.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        }

        @Test
        @DisplayName("G3 round 3: a routed partially filled order carries the REMAINDER, not the full prescribed amount")
        void routingCarriesTheRemainingQuantity() {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
            prescription.setQuantity(java.math.BigDecimal.TEN);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(roleValidator.getCurrentUserId()).thenReturn(userId);
            when(userRepository.findById(userId)).thenReturn(Optional.of(currentUser));
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(dispenseRepository.sumQuantityDispensedForPrescription(
                    prescriptionId, com.example.hms.enums.DispenseStatus.CANCELLED))
                    .thenReturn(java.math.BigDecimal.valueOf(4));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(any()))
                    .thenReturn(RoutingDecisionResponseDTO.builder().routingType("BACKORDER").build());

            service.backOrder(prescriptionId, null);

            org.mockito.ArgumentCaptor<PrescriptionRoutingDecision> saved =
                    org.mockito.ArgumentCaptor.forClass(PrescriptionRoutingDecision.class);
            verify(routingDecisionRepository).save(saved.capture());
            assertThat(saved.getValue().getRemainingQuantity()).isEqualByComparingTo(java.math.BigDecimal.valueOf(6));
        }

        @Test
        @DisplayName("a prescription still with a partner is NOT re-routable — the partner's reply must land first")
        void sentToPartnerIsNotRoutable() {
            prescription.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
            RoutingDecisionRequestDTO request = RoutingDecisionRequestDTO.builder()
                    .prescriptionId(prescriptionId).targetPharmacyId(partnerId).build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.routeToPartner(prescriptionId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a routable state");
        }

        @Test
        @DisplayName("a prescription awaiting clarification is NOT routable")
        void pendingClarificationIsNotRoutable() {
            prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.backOrder(prescriptionId, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not in a routable state");
        }
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
        // G6: the prescriber hears about the partner's answer
        verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PARTNER_ACCEPTED);
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
        prescription.setPharmacyId(partnerId);
        prescription.setPharmacyName("Partner Pharmacy");
        prescription.setPharmacyContact("+22670000000");
        prescription.setPharmacyAddress("Rue 12");

        service.partnerRespond(decisionId, false);

        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
        verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PARTNER_REJECTED);
        // The refusing partner is no longer the order's pharmacy: the queue groups it in-house.
        assertThat(prescription.getPharmacyId()).isNull();
        assertThat(prescription.getPharmacyName()).isNull();
        assertThat(prescription.getPharmacyContact()).isNull();
        assertThat(prescription.getPharmacyAddress()).isNull();
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
        verify(prescriberNotifier).notifyPrescriber(prescription, PrescriptionStatus.PARTNER_DISPENSED);
    }

    @Test
    @DisplayName("confirmPartnerDispense refuses to overwrite an open clarification")
    void confirmPartnerDispenseRefusesWhileAwaitingClarification() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partnerPharmacy)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.ACCEPTED)
                .build();
        UUID decisionId = UUID.randomUUID();
        decision.setId(decisionId);
        prescription.setStatus(PrescriptionStatus.PENDING_CLARIFICATION);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(routingDecisionRepository.findById(decisionId)).thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> service.confirmPartnerDispense(decisionId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("awaiting the prescriber's clarification");
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PENDING_CLARIFICATION);
    }

    @Nested
    @DisplayName("partnerNoShow (G3 round 4: the explicit in-house exit)")
    class PartnerNoShow {

        private PrescriptionRoutingDecision accepted() {
            PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                    .prescription(prescription)
                    .targetPharmacy(partnerPharmacy)
                    .routingType(RoutingType.PARTNER)
                    .status(RoutingDecisionStatus.ACCEPTED)
                    .reason("Nearest partner has stock")
                    .build();
            decision.setId(UUID.randomUUID());
            return decision;
        }

        @Test
        @DisplayName("cancels the acceptance with the reason and returns the order to SIGNED")
        void happyPath() {
            PrescriptionRoutingDecision decision = accepted();
            prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
            prescription.setPharmacyId(partnerId);
            prescription.setPharmacyName("Partner Pharmacy");
            prescription.setPharmacyContact("+22670000000");

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(routingDecisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));
            when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(routingMapper.toResponseDTO(decision))
                    .thenReturn(RoutingDecisionResponseDTO.builder().status("CANCELLED").build());

            service.partnerNoShow(decision.getId(), "  Patient waited two days, nothing delivered ");

            assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
            // The fact is a marker the client translates, never an English
            // sentence: a composed "Partner no-show: " reached French and
            // Spanish prescribers in English, and stored text cannot be
            // translated at render time.
            assertThat(decision.getReason())
                    .startsWith("Nearest partner has stock")
                    .contains("[PARTNER_NO_SHOW] Patient waited two days, nothing delivered")
                    .doesNotContain("Partner no-show");
            assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
            assertThat(prescription.getPharmacyId()).isNull();
            assertThat(prescription.getPharmacyName()).isNull();
            verify(prescriptionRepository).save(prescription);
        }

        @Test
        @DisplayName("refuses a decision that is not an acceptance")
        void refusesNonAccepted() {
            PrescriptionRoutingDecision decision = accepted();
            decision.setStatus(RoutingDecisionStatus.PENDING);

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(routingDecisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));

            assertThatThrownBy(() -> service.partnerNoShow(decision.getId(), "never came"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ACCEPTED");
            verify(prescriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a blank reason — this cancels a partner's claim")
        void refusesBlankReason() {
            PrescriptionRoutingDecision decision = accepted();

            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(routingDecisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));

            assertThatThrownBy(() -> service.partnerNoShow(decision.getId(), "   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("reason");
        }

        @Test
        @DisplayName("a decision at another hospital is 404, not 403")
        void crossTenantIs404() {
            PrescriptionRoutingDecision decision = accepted();

            when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
            when(routingDecisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));

            assertThatThrownBy(() -> service.partnerNoShow(decision.getId(), "never came"))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a global-view super-admin is refused: cancelling a partner's claim is a write")
        void globalViewSuperAdminIsRefused() {
            PrescriptionRoutingDecision decision = accepted();
            prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);

            // Reading across tenants is what global view is for; acting on one
            // hospital's order is not. The refusal is what happened before too
            // — as a 500 from the unguarded dereference. Only its shape changes.
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(routingDecisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));

            assertThatThrownBy(() -> service.partnerNoShow(decision.getId(), "never came"))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
            assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
            verify(prescriptionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("a super-admin in GLOBAL view has no hospital — the read is unscoped, not a 500")
    class GlobalViewReads {

        @Test
        @DisplayName("listByPrescription answers instead of dereferencing a null hospital")
        void listByPrescriptionUnscoped() {
            org.springframework.data.domain.Pageable pageable =
                    org.springframework.data.domain.PageRequest.of(0, 10);
            PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                    .prescription(prescription)
                    .build();
            RoutingDecisionResponseDTO dto = RoutingDecisionResponseDTO.builder().build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(routingDecisionRepository.findByPrescriptionId(prescriptionId, pageable))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(decision)));
            when(routingMapper.toResponseDTO(decision)).thenReturn(dto);

            assertThat(service.listByPrescription(prescriptionId, pageable).getContent())
                    .containsExactly(dto);
        }

        @Test
        @DisplayName("checkStock asks the ORDER's hospital, not the caller's empty scope")
        void checkStockUsesTheOrdersHospital() {
            // Passing a null scope down would have answered "0 on hand, no
            // partner pharmacies" with confidence — a wrong clinical answer
            // the page then offers a back order on.
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
            when(medicationCatalogItemRepository.findByHospitalIdAndCode(hospitalId, "AMOX500"))
                    .thenReturn(Optional.of(catalogItem));
            when(inventoryItemRepository
                    .findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                            hospitalId, catalogItem.getId()))
                    .thenReturn(java.util.List.of());
            when(pharmacyRepository.findByHospitalIdAndPharmacyTypeInAndActiveTrue(
                    eq(hospitalId), any())).thenReturn(java.util.List.of(partnerPharmacy));

            StockCheckResultDTO result = service.checkStock(prescriptionId);

            assertThat(result.getPartnerPharmacies()).hasSize(1);
            verify(medicationCatalogItemRepository).findByHospitalIdAndCode(hospitalId, "AMOX500");
        }

        @Test
        @DisplayName("checkStock refuses a hospital-less order rather than reporting zeros")
        void checkStockRefusesAHospitalLessOrder() {
            prescription.setHospital(null);

            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

            assertThatThrownBy(() -> service.checkStock(prescriptionId))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a write is still refused without a hospital, as a 404 rather than a 500")
        void writesStillNeedAHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);

            assertThatThrownBy(() -> service.printForPatient(prescriptionId))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.backOrder(prescriptionId, null))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
            verify(prescriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("listByPatient answers instead of dereferencing a null hospital")
        void listByPatientUnscoped() {
            org.springframework.data.domain.Pageable pageable =
                    org.springframework.data.domain.PageRequest.of(0, 10);
            PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                    .prescription(prescription)
                    .build();
            RoutingDecisionResponseDTO dto = RoutingDecisionResponseDTO.builder().build();

            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(routingDecisionRepository.findByDecidedForPatientId(patient.getId(), pageable))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(decision)));
            when(routingMapper.toResponseDTO(decision)).thenReturn(dto);

            assertThat(service.listByPatient(patient.getId(), pageable).getContent()).containsExactly(dto);
        }
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