package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.DispenseStatus;
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
import com.example.hms.payload.dto.pharmacy.PartnerOptionDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockCheckResultDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
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
    private final DispenseRepository dispenseRepository;
    private final UserRepository userRepository;
    private final PrescriptionRoutingMapper routingMapper;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;
    private final com.example.hms.service.pharmacy.partner.PartnerNotificationChannel partnerChannel;
    private final PrescriberPharmacyNotifier prescriberNotifier;

    private static final String AUDIT_ENTITY = "PRESCRIPTION_ROUTING";

    /**
     * What a pharmacist may route (to a partner, to paper, or to a back
     * order).
     *
     * <p>PENDING_STOCK and PARTNER_REJECTED are routable (gap G3): a back
     * order the supplier cannot fill goes to a partner instead, and a partner
     * that refused sends the order back to be routed elsewhere. Both used to
     * be dead ends. SENT_TO_PARTNER is deliberately NOT routable: its PARTNER
     * decision is still PENDING and a late "1" reply from that partner would
     * flip the prescription to PARTNER_ACCEPTED under the new route. The
     * pharmacist records the refusal first ({@link #partnerRespond}), or the
     * timeout sweep does it for them, and PARTNER_REJECTED is routable.
     * PARTIALLY_FILLED is routable so the remainder of a fill the shelf could
     * not complete can be back-ordered or sent to a partner.
     *
     * <p>PARTNER_ACCEPTED is routable too (gap G3, round 3): a partner that
     * accepted and then never delivered left the order with no exit at all —
     * not dispensable, not routable, reachable only by that partner
     * confirming a dispense that is not going to happen. Re-routing it
     * supersedes the accepted decision ({@link #supersedeOpenDecisions}), so
     * a late confirmation from the original partner is refused rather than
     * flipping an order somebody else has since filled.
     */
    static final Set<PrescriptionStatus> ROUTABLE_STATUSES = Set.of(
            PrescriptionStatus.REQUIRES_EXTERNAL_FILL,
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTIALLY_FILLED,
            PrescriptionStatus.PENDING_STOCK,
            PrescriptionStatus.PARTNER_REJECTED,
            PrescriptionStatus.PARTNER_ACCEPTED
    );

    /** Decisions nothing has closed yet: a re-route supersedes these. */
    private static final Set<RoutingDecisionStatus> OPEN_DECISION_STATUSES =
            Set.of(RoutingDecisionStatus.PENDING, RoutingDecisionStatus.ACCEPTED);

    private static final String PRESCRIPTION_PREFIX = "Prescription ";

    @Override
    @Transactional(readOnly = true)
    public StockCheckResultDTO checkStock(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId, hospitalId);

        // Find the medication catalog item for this prescription
        MedicationCatalogItem catalogItem = resolveCatalogItem(prescription, hospitalId);

        BigDecimal totalOnHand = BigDecimal.ZERO;

        // Single query for hospital-wide on-hand stock avoids N+1 across dispensaries.
        if (catalogItem != null) {
            List<InventoryItem> items = inventoryItemRepository
                    .findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(
                            hospitalId, catalogItem.getId());
            for (InventoryItem item : items) {
                if (item.getPharmacy() != null
                        && item.getPharmacy().getPharmacyType() == PharmacyType.HOSPITAL_DISPENSARY
                        && item.getQuantityOnHand() != null) {
                    totalOnHand = totalOnHand.add(item.getQuantityOnHand());
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

        // pharmacyName/pharmacyId are intentionally null: quantityOnHand is the aggregate
        // across all hospital dispensaries. A per-dispensary breakdown is out of scope.
        return StockCheckResultDTO.builder()
                .medicationName(prescription.getMedicationName())
                .pharmacyName(null)
                .pharmacyId(null)
                .quantityOnHand(totalOnHand)
                .sufficient(sufficient)
                .partnerPharmacies(partnerOptions)
                .build();
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO routeToPartner(UUID prescriptionId, RoutingDecisionRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId, hospitalId);
        validateRoutableStatus(prescription);

        UUID targetPharmacyId = dto.getTargetPharmacyId();
        if (targetPharmacyId == null) {
            throw new BusinessException("Target pharmacy ID is required for partner routing");
        }

        Pharmacy targetPharmacy = pharmacyRepository.findById(targetPharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));

        if (!targetPharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("pharmacy.notfound");
        }
        if (targetPharmacy.getPharmacyType() != PharmacyType.PARTNER_PHARMACY) {
            throw new BusinessException("Target pharmacy must be a PARTNER_PHARMACY for partner routing");
        }

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();
        BigDecimal remaining = remainingQuantity(prescription);
        supersedeOpenDecisions(prescription);

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
                prescription, targetPharmacy, currentUser, patient, remaining);
        PrescriptionRoutingDecision decision = routingMapper.toEntity(dto, ctx);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                PRESCRIPTION_PREFIX + prescriptionId + " routed to partner pharmacy " + targetPharmacy.getName(),
                prescriptionId.toString());

        // T-40: notify patient the medication is unavailable at hospital; routed to partner
        support.notifyOutOfStock(patient, prescription.getMedicationName(),
                PharmacyServiceSupport.OUT_OF_STOCK_PARTNER, targetPharmacy.getName());

        // T-54: notify the partner pharmacy via SMS (best-effort, never fails the routing)
        try {
            partnerChannel.sendPrescriptionOffer(saved, prescription, targetPharmacy);
        } catch (Exception ex) {
            log.warn("Partner outbound SMS failed for decision {}: {}", saved.getId(), ex.getMessage());
        }

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO printForPatient(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId, hospitalId);
        validateRoutableStatus(prescription);

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();
        BigDecimal remaining = remainingQuantity(prescription);
        supersedeOpenDecisions(prescription);

        prescription.setStatus(PrescriptionStatus.PRINTED_FOR_PATIENT);
        prescriptionRepository.save(prescription);

        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .routingType(RoutingType.PRINT)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .reason("Prescription printed for patient to fill at external pharmacy")
                .remainingQuantity(remaining)
                .status(RoutingDecisionStatus.COMPLETED)
                .decidedAt(LocalDateTime.now())
                .build();
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_PRINTED,
                PRESCRIPTION_PREFIX + prescriptionId + " printed for patient",
                prescriptionId.toString());

        // T-40: notify patient the medication is unavailable; Rx printed for any pharmacy
        support.notifyOutOfStock(patient, prescription.getMedicationName(),
                PharmacyServiceSupport.OUT_OF_STOCK_PRINT);

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO backOrder(UUID prescriptionId, LocalDate estimatedRestockDate) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        Prescription prescription = findPrescription(prescriptionId, hospitalId);
        validateRoutableStatus(prescription);

        User currentUser = resolveCurrentUser();
        Patient patient = prescription.getPatient();
        BigDecimal remaining = remainingQuantity(prescription);
        supersedeOpenDecisions(prescription);

        prescription.setStatus(PrescriptionStatus.PENDING_STOCK);
        prescriptionRepository.save(prescription);
        prescriberNotifier.notifyPrescriber(prescription, PrescriptionStatus.PENDING_STOCK);

        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .routingType(RoutingType.BACKORDER)
                .decidedByUser(currentUser)
                .decidedForPatient(patient)
                .reason("Medication out of stock; placed on back order")
                .remainingQuantity(remaining)
                .estimatedRestockDate(estimatedRestockDate)
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now())
                .build();
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_BACKORDER,
                PRESCRIPTION_PREFIX + prescriptionId + " placed on back order"
                        + (estimatedRestockDate != null ? ", estimated restock: " + estimatedRestockDate : ""),
                prescriptionId.toString());

        // T-40: notify patient the medication is unavailable; back-ordered with optional restock date
        if (estimatedRestockDate != null) {
            support.notifyOutOfStock(patient, prescription.getMedicationName(),
                    PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER_DATED, estimatedRestockDate.toString());
        } else {
            support.notifyOutOfStock(patient, prescription.getMedicationName(),
                    PharmacyServiceSupport.OUT_OF_STOCK_BACKORDER);
        }

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO partnerRespond(UUID routingDecisionId, boolean accepted) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PrescriptionRoutingDecision decision = routingDecisionRepository.findById(routingDecisionId)
                .orElseThrow(() -> new ResourceNotFoundException("routing.decision.notfound"));
        enforceDecisionHospitalScope(decision, hospitalId);

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
            // The refusing partner is no longer this order's pharmacy: the
            // work queue groups the row under the in-house dispensary and
            // shows the refusal from the decision (lastRefusedBy).
            clearPharmacy(prescription);
        }

        prescriptionRepository.save(prescription);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
        prescriberNotifier.notifyPrescriber(prescription, prescription.getStatus());

        logAudit(accepted ? AuditEventType.PRESCRIPTION_SENT_TO_PARTNER : AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                "Partner pharmacy " + (accepted ? "accepted" : "rejected")
                        + " prescription " + prescription.getId(),
                routingDecisionId.toString());

        // T-61: notify the patient when the partner accepts
        if (accepted) {
            try {
                partnerChannel.notifyPatientAccepted(prescription.getPatient(), decision.getTargetPharmacy());
            } catch (Exception ex) {
                log.warn("Patient accept SMS failed: {}", ex.getMessage());
            }
        }

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public RoutingDecisionResponseDTO confirmPartnerDispense(UUID routingDecisionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        PrescriptionRoutingDecision decision = routingDecisionRepository.findById(routingDecisionId)
                .orElseThrow(() -> new ResourceNotFoundException("routing.decision.notfound"));
        enforceDecisionHospitalScope(decision, hospitalId);

        if (decision.getRoutingType() != RoutingType.PARTNER) {
            throw new BusinessException("Only PARTNER routing decisions can confirm dispense");
        }
        if (decision.getStatus() != RoutingDecisionStatus.ACCEPTED) {
            throw new BusinessException("Routing decision must be in ACCEPTED status to confirm dispense");
        }

        Prescription prescription = decision.getPrescription();
        prescription.setStatus(PrescriptionStatus.PARTNER_DISPENSED);
        prescriptionRepository.save(prescription);
        prescriberNotifier.notifyPrescriber(prescription, PrescriptionStatus.PARTNER_DISPENSED);

        decision.setStatus(RoutingDecisionStatus.COMPLETED);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);

        logAudit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                "Partner pharmacy confirmed dispense for prescription " + prescription.getId(),
                routingDecisionId.toString());

        // T-61: notify the patient their medication has been dispensed by the partner
        try {
            partnerChannel.notifyPatientDispensed(prescription.getPatient(), decision.getTargetPharmacy());
        } catch (Exception ex) {
            log.warn("Patient dispense SMS failed: {}", ex.getMessage());
        }

        return routingMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoutingDecisionResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        // Enforce scope on the prescription before returning history
        findPrescription(prescriptionId, hospitalId);
        return routingDecisionRepository.findByPrescriptionId(prescriptionId, pageable)
                .map(routingMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoutingDecisionResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return routingDecisionRepository.findByDecidedForPatientId(patientId, pageable)
                .map(d -> {
                    enforceDecisionHospitalScope(d, hospitalId);
                    return routingMapper.toResponseDTO(d);
                });
    }

    // ── Private helpers ──

    private Prescription findPrescription(UUID prescriptionId, UUID hospitalId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));
        if (prescription.getHospital() == null
                || !hospitalId.equals(prescription.getHospital().getId())) {
            throw new ResourceNotFoundException("prescription.notfound");
        }
        return prescription;
    }

    private void enforceDecisionHospitalScope(PrescriptionRoutingDecision decision, UUID hospitalId) {
        if (decision.getPrescription() == null
                || decision.getPrescription().getHospital() == null
                || !hospitalId.equals(decision.getPrescription().getHospital().getId())) {
            throw new ResourceNotFoundException("routing.decision.notfound");
        }
    }

    /**
     * Whatever this order was waiting for, the pharmacist has just decided
     * something else: every decision still open (a back order waiting for
     * stock, a partner offer, a partner that accepted and never delivered)
     * is CANCELLED so the routing history reads as it happened — and so a
     * late partner reply cannot act on a decision this route replaced.
     * Best-effort: a bookkeeping miss must not stop the re-route.
     */
    private void supersedeOpenDecisions(Prescription prescription) {
        try {
            for (PrescriptionRoutingDecision decision
                    : routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescription.getId())) {
                if (OPEN_DECISION_STATUSES.contains(decision.getStatus())) {
                    decision.setStatus(RoutingDecisionStatus.CANCELLED);
                    routingDecisionRepository.save(decision);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not supersede the open routing decisions of prescription {}: {}",
                    prescription.getId(), ex.getMessage());
        }
    }

    /**
     * What this routing is actually for. A partially filled order routes its
     * REMAINDER: the partner SMS, the printed copy and the supplier's back
     * order all used to carry the full prescribed amount, which invites the
     * external filler to hand over a second complete course.
     */
    private BigDecimal remainingQuantity(Prescription prescription) {
        BigDecimal dispensedToDate = dispenseRepository
                .sumQuantityDispensedForPrescription(prescription.getId(), DispenseStatus.CANCELLED);
        return FillAccounting.remaining(prescription, dispensedToDate);
    }

    private static void clearPharmacy(Prescription prescription) {
        prescription.setPharmacyId(null);
        prescription.setPharmacyName(null);
        prescription.setPharmacyContact(null);
        prescription.setPharmacyAddress(null);
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
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        support.logAudit(eventType, description, resourceId, AUDIT_ENTITY);
    }
}
