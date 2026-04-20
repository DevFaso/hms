package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PrescriptionRoutingMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.PartnerOptionDTO;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockOutRoutingServiceImpl implements StockOutRoutingService {

    private final PrescriptionRepository prescriptionRepository;
    private final PharmacyRepository pharmacyRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final MedicationCatalogItemRepository medicationCatalogItemRepository;
    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final UserRepository userRepository;
    private final PrescriptionRoutingMapper routingMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;

    private static final Set<PrescriptionStatus> ROUTABLE_STATUSES = Set.of(
            PrescriptionStatus.REQUIRES_EXTERNAL_FILL,
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED
    );

    @Override
    @Transactional(readOnly = true)
    public StockCheckResultDTO checkStock(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId);

        // Find hospital dispensary pharmacies
        List<Pharmacy> dispensaries = pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                hospitalId, PharmacyType.HOSPITAL_DISPENSARY);

        // Find the medication catalog item for this prescription
        MedicationCatalogItem catalogItem = resolveCatalogItem(prescription, hospitalId);

        BigDecimal totalOnHand = BigDecimal.ZERO;
        String dispensaryName = null;
        UUID dispensaryId = null;

        if (catalogItem != null) {
            for (Pharmacy dispensary : dispensaries) {
                Optional<InventoryItem> inv = inventoryItemRepository
                        .findByPharmacyIdAndMedicationCatalogItemId(dispensary.getId(), catalogItem.getId());
                if (inv.isPresent() && inv.get().isActive()) {
                    totalOnHand = totalOnHand.add(inv.get().getQuantityOnHand());
                    if (dispensaryName == null) {
                        dispensaryName = dispensary.getName();
                        dispensaryId = dispensary.getId();
                    }
                }
            }
        }

        BigDecimal needed = prescription.getQuantity() != null ? prescription.getQuantity() : BigDecimal.ONE;
        boolean sufficient = totalOnHand.compareTo(needed) >= 0;

        List<PartnerOptionDTO> partnerOptions = new ArrayList<>();
        if (!sufficient) {
            List<Pharmacy> partners = pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(
                    hospitalId, PharmacyType.PARTNER_PHARMACY);
            for (Pharmacy partner : partners) {
                boolean hasOnFormulary = false;
                if (catalogItem != null) {
                    hasOnFormulary = inventoryItemRepository
                            .findByPharmacyIdAndMedicationCatalogItemId(partner.getId(), catalogItem.getId())
                            .isPresent();
                }
                partnerOptions.add(PartnerOptionDTO.builder()
                        .pharmacyId(partner.getId())
                        .pharmacyName(partner.getName())
                        .pharmacyType(partner.getPharmacyType().name())
                        .city(partner.getCity())
                        .phoneNumber(partner.getPhoneNumber())
                        .hasOnFormulary(hasOnFormulary)
                        .build());
            }
        }

        return StockCheckResultDTO.builder()
                .medicationName(prescription.getMedicationName())
                .pharmacyName(dispensaryName)
                .pharmacyId(dispensaryId)
                .quantityOnHand(totalOnHand)
                .sufficient(sufficient)
                .partnerPharmacies(partnerOptions)
                .build();
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO routeToPartner(UUID prescriptionId, RoutingDecisionRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId);
        validateRoutableStatus(prescription);

        UUID targetPharmacyId = dto.getTargetPharmacyId();
        if (targetPharmacyId == null) {
            throw new BusinessException("Target pharmacy ID is required for partner routing");
        }

        Pharmacy targetPharmacy = pharmacyRepository.findById(targetPharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("Target pharmacy not found"));

        if (!targetPharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("Target pharmacy not found");
        }

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();

        // Update prescription
        prescription.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        prescription.setPharmacyId(targetPharmacy.getId());
        prescription.setPharmacyName(targetPharmacy.getName());
        prescription.setPharmacyContact(targetPharmacy.getPhoneNumber());
        prescription.setPharmacyAddress(targetPharmacy.getAddressLine1());
        prescriptionRepository.save(prescription);

        // Create routing decision
        dto.setRoutingType(RoutingType.PARTNER);
        PrescriptionRoutingMapper.RoutingContext ctx = new PrescriptionRoutingMapper.RoutingContext(
                prescription, targetPharmacy, currentUser, patient);
        PrescriptionRoutingDecision decision = routingMapper.toEntity(dto, ctx);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                "Prescription " + prescriptionId + " routed to partner pharmacy " + targetPharmacy.getName(),
                prescriptionId.toString());

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO printForPatient(UUID prescriptionId) {
        roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId);
        validateRoutableStatus(prescription);

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();

        prescription.setStatus(PrescriptionStatus.PRINTED_FOR_PATIENT);
        prescriptionRepository.save(prescription);

        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .routingType(RoutingType.PRINT)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .reason("Prescription printed for patient to fill at external pharmacy")
                .status(RoutingDecisionStatus.COMPLETED)
                .decidedAt(LocalDateTime.now())
                .build();
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_PRINTED,
                "Prescription " + prescriptionId + " printed for patient",
                prescriptionId.toString());

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO backOrder(UUID prescriptionId, LocalDate estimatedRestockDate) {
        roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId);
        validateRoutableStatus(prescription);

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();

        prescription.setStatus(PrescriptionStatus.PENDING_STOCK);
        prescriptionRepository.save(prescription);

        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .routingType(RoutingType.BACKORDER)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .reason("Medication out of stock; placed on back order")
                .estimatedRestockDate(estimatedRestockDate)
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now())
                .build();
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_BACKORDER,
                "Prescription " + prescriptionId + " placed on back order"
                        + (estimatedRestockDate != null ? ", estimated restock: " + estimatedRestockDate : ""),
                prescriptionId.toString());

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO partnerRespond(UUID routingDecisionId, boolean accepted) {
        roleValidator.requireActiveHospitalId();
        PrescriptionRoutingDecision decision = routingDecisionRepository.findById(routingDecisionId)
                .orElseThrow(() -> new ResourceNotFoundException("Routing decision not found"));

        if (decision.getRoutingType() != RoutingType.PARTNER) {
            throw new BusinessException("Only PARTNER routing decisions can receive partner responses");
        }
        if (decision.getStatus() != RoutingDecisionStatus.PENDING) {
            throw new BusinessException("Routing decision is not in PENDING status");
        }

        Prescription prescription = decision.getPrescription();

        if (accepted) {
            decision.setStatus(RoutingDecisionStatus.ACCEPTED);
            prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
        } else {
            decision.setStatus(RoutingDecisionStatus.REJECTED);
            prescription.setStatus(PrescriptionStatus.PARTNER_REJECTED);
        }

        prescriptionRepository.save(prescription);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(accepted ? AuditEventType.PRESCRIPTION_SENT_TO_PARTNER : AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                "Partner pharmacy " + (accepted ? "accepted" : "rejected")
                        + " prescription " + prescription.getId(),
                routingDecisionId.toString());

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO confirmPartnerDispense(UUID routingDecisionId) {
        roleValidator.requireActiveHospitalId();
        PrescriptionRoutingDecision decision = routingDecisionRepository.findById(routingDecisionId)
                .orElseThrow(() -> new ResourceNotFoundException("Routing decision not found"));

        if (decision.getRoutingType() != RoutingType.PARTNER) {
            throw new BusinessException("Only PARTNER routing decisions can confirm dispense");
        }
        if (decision.getStatus() != RoutingDecisionStatus.ACCEPTED) {
            throw new BusinessException("Routing decision must be in ACCEPTED status to confirm dispense");
        }

        Prescription prescription = decision.getPrescription();
        prescription.setStatus(PrescriptionStatus.PARTNER_DISPENSED);
        prescriptionRepository.save(prescription);

        decision.setStatus(RoutingDecisionStatus.COMPLETED);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                "Partner pharmacy confirmed dispense for prescription " + prescription.getId(),
                routingDecisionId.toString());

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoutingDecisionResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable) {
        return routingDecisionRepository.findByPrescriptionId(prescriptionId, pageable)
                .map(routingMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoutingDecisionResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        return routingDecisionRepository.findByDecidedForPatientId(patientId, pageable)
                .map(routingMapper::toResponseDTO);
    }

    // ── Private helpers ──

    private Prescription findPrescription(UUID prescriptionId) {
        return prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
    }

    private void validateRoutableStatus(Prescription prescription) {
        if (!ROUTABLE_STATUSES.contains(prescription.getStatus())) {
            throw new BusinessException("Prescription is not in a routable state: " + prescription.getStatus());
        }
    }

    private MedicationCatalogItem resolveCatalogItem(Prescription prescription, UUID hospitalId) {
        if (prescription.getMedicationCode() != null && !prescription.getMedicationCode().isBlank()) {
            return medicationCatalogItemRepository
                    .findByHospitalIdAndCode(hospitalId, prescription.getMedicationCode())
                    .orElse(null);
        }
        return null;
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType("PRESCRIPTION_ROUTING")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
