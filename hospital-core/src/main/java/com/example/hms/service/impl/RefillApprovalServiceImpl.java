package com.example.hms.service.impl;

import com.example.hms.service.i18n.NotificationText;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.RefillApprovalService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefillApprovalServiceImpl implements RefillApprovalService {

    private static final String NOTIFICATION_TYPE = "MEDICATION_REFILL";
    private static final String MSG_MEDICATION_FALLBACK = "refill.decision.medicationFallback";
    /** Shared opening clause of every refill-decision notification. */

    private final RefillRequestRepository refillRequestRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;
    private final StaffRepository staffRepository;
    private final NotificationService notificationService;
    private final ControllerAuthUtils authUtils;
    private final RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationRefillResponseDTO> listForProvider(Authentication auth,
                                                             RefillStatus status,
                                                             Pageable pageable) {
        Page<RefillRequest> page = isDispensingRole()
                ? listForPharmacy(status, pageable)
                : listForPrescriber(auth, status, pageable);
        return page.map(this::toResponseDTO);
    }

    /**
     * A pharmacist is never the prescriber, so the staff-scoped queries return
     * an empty page for them even though {@code PROVIDER_ROLES} lets them call
     * this endpoint. They need the hospital's whole refill traffic — an approval
     * tells them a fill is authorized, a denial or hold tells them to refuse one.
     */
    private boolean isDispensingRole() {
        return roleValidator.hasAnyAuthority("PHARMACIST")
                && !roleValidator.hasAnyAuthority("DOCTOR", "NURSE", "MIDWIFE");
    }

    private Page<RefillRequest> listForPharmacy(RefillStatus status, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // Null active hospital means super-admin, which the house idiom
            // reads as unscoped.
            return (status == null)
                    ? refillRequestRepository.findAll(pageable)
                    : refillRequestRepository.findByStatus(status, pageable);
        }
        return (status == null)
                ? refillRequestRepository.findByPrescription_Hospital_Id(hospitalId, pageable)
                : refillRequestRepository.findByPrescription_Hospital_IdAndStatus(hospitalId, status, pageable);
    }

    private Page<RefillRequest> listForPrescriber(Authentication auth, RefillStatus status, Pageable pageable) {
        UUID staffId = resolveStaffId(auth);
        return (status == null)
                ? refillRequestRepository.findByPrescription_Staff_Id(staffId, pageable)
                : refillRequestRepository.findByPrescription_Staff_IdAndStatus(staffId, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingForProvider(Authentication auth) {
        if (isDispensingRole()) {
            UUID hospitalId = roleValidator.requireActiveHospitalId();
            return hospitalId == null
                    ? refillRequestRepository.countByStatus(RefillStatus.REQUESTED)
                    : refillRequestRepository.countByPrescription_Hospital_IdAndStatus(
                            hospitalId, RefillStatus.REQUESTED);
        }
        UUID staffId = resolveStaffId(auth);
        return refillRequestRepository.countByPrescription_Staff_IdAndStatus(staffId, RefillStatus.REQUESTED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO approve(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        return decide(auth, refillId, decision, RefillStatus.APPROVED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO reject(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        return decide(auth, refillId, decision, RefillStatus.DENIED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO pause(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        // A hold the patient can't interpret is worse than a denial, so the reason
        // is mandatory here even though approve/reject leave it optional.
        if (decision == null || decision.getProviderNotes() == null || decision.getProviderNotes().isBlank()) {
            throw new BusinessException("patient.refill.decision.reasonRequired");
        }
        return decide(auth, refillId, decision, RefillStatus.PAUSED);
    }

    private MedicationRefillResponseDTO decide(Authentication auth,
                                               UUID refillId,
                                               RefillDecisionRequestDTO decision,
                                               RefillStatus newStatus) {
        UUID staffId = resolveStaffId(auth);

        RefillRequest refill = refillRequestRepository.findById(refillId)
                .orElseThrow(() -> new ResourceNotFoundException("refillRequest.notFound", refillId));

        Prescription prescription = refill.getPrescription();
        Staff prescriber = prescription != null ? prescription.getStaff() : null;
        if (prescriber == null || !staffId.equals(prescriber.getId())) {
            throw new AccessDeniedException("patient.refill.decision.notOwnPrescription");
        }

        if (!isActionable(refill.getStatus(), newStatus)) {
            throw new BusinessException("patient.refill.decision.notPending",
                String.valueOf(refill.getStatus()));
        }

        // Release the fill BEFORE recording the decision. If the prescription
        // can no longer be refilled this throws, and the refill request stays
        // REQUESTED rather than sitting at APPROVED with nothing dispensable
        // behind it — which is exactly the dead-end this endpoint used to be.
        if (newStatus == RefillStatus.APPROVED) {
            releaseRefillToPharmacy(prescription);
        }

        refill.setStatus(newStatus);
        if (decision != null && decision.getProviderNotes() != null && !decision.getProviderNotes().isBlank()) {
            refill.setProviderNotes(decision.getProviderNotes().trim());
        }
        refill = refillRequestRepository.save(refill);

        notifyPatient(refill, newStatus);
        log.info("Staff {} marked refill {} as {}", staffId, refillId, newStatus);
        return toResponseDTO(refill);
    }

    /**
     * Turns an approval into something the pharmacy can actually dispense.
     *
     * <p>Before this existed, approving wrote APPROVED and stopped: no new
     * prescription, no pharmacy order, and the original prescription stayed
     * DISPENSED — a terminal state excluded from the pharmacist work queue. The
     * patient was told their refill was approved and could not collect it.
     *
     * <p>A refill is not a new prescription: {@code refillsAllowed} models one
     * authorization with N fills, which is how the schema has always described
     * it. So the fill is released by returning the existing prescription to
     * SIGNED — the state the work queue reads — and advancing the fill counters.
     *
     * <p>{@code refillsRemaining} is decremented but never blocks: the
     * prescriber deciding this request IS the authorization, and refusing their
     * approval because a counter set at prescribing time hit zero would be
     * surprising to the one person entitled to override it. When the counter is
     * already zero the approval simply grants a fill beyond the original
     * allowance, and {@code refillsUsed} records that it happened.
     */
    private void releaseRefillToPharmacy(Prescription prescription) {
        PrescriptionStatus status = prescription.getStatus();
        // QA D719: a controlled substance is never refilled, whatever its status.
        if (!prescription.allowsRefill() || controlledResolver.isControlled(prescription)) {
            throw new BusinessException("patient.refill.approveNotRefillable", String.valueOf(status));
        }

        Integer remaining = prescription.getRefillsRemaining();
        if (remaining != null && remaining > 0) {
            prescription.setRefillsRemaining(remaining - 1);
        }
        Integer used = prescription.getRefillsUsed();
        prescription.setRefillsUsed((used == null ? 0 : used) + 1);
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescriptionRepository.save(prescription);

        log.info("Released refill fill {} of prescription {} back to the pharmacy work queue",
                prescription.getRefillsUsed(), prescription.getId());
    }

    /**
     * A request awaiting review can go anywhere. A paused one can still be
     * approved or denied — but it cannot be paused a second time, which would
     * fire a redundant notification at the patient without changing anything.
     */
    private boolean isActionable(RefillStatus current, RefillStatus target) {
        if (current == RefillStatus.REQUESTED) {
            return true;
        }
        return current == RefillStatus.PAUSED && target != RefillStatus.PAUSED;
    }

    private UUID resolveStaffId(Authentication auth) {
        authUtils.requireAuth(auth);
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("patient.refill.decision.userUnresolved"));
        List<Staff> staffList = staffRepository.findByUserId(userId);
        if (staffList.isEmpty()) {
            throw new BusinessException("patient.refill.decision.noStaffRecord");
        }
        return staffList.get(0).getId();
    }

    private void notifyPatient(RefillRequest refill, RefillStatus status) {
        Patient patient = refill.getPatient();
        if (patient == null || patient.getUser() == null) {
            return;
        }
        String username = patient.getUser().getUsername();
        if (username == null || username.isBlank()) {
            return;
        }
        // Read by the patient, in the language they read it in — never the
        // prescriber's request locale (QA D665).
        Object medicationName = refill.getPrescription() != null && refill.getPrescription().getMedicationName() != null
                ? refill.getPrescription().getMedicationName()
                : NotificationLocalizer.key(MSG_MEDICATION_FALLBACK);
        NotificationText message = buildDecisionMessage(refill, medicationName, status);
        try {
            notificationService.createNotification(message.key(), message.args(), username, NOTIFICATION_TYPE);
        } catch (Exception ex) {
            log.warn("Failed to deliver refill decision notification to {} for refill {}",
                    username, refill.getId(), ex);
        }
    }

    private NotificationText buildDecisionMessage(RefillRequest refill, Object medicationName, RefillStatus status) {
        if (status == RefillStatus.PAUSED) {
            // The reason is mandatory for a hold, so it is always worth surfacing.
            return NotificationText.of("refill.decision.paused", medicationName, refill.getProviderNotes());
        }
        if (status == RefillStatus.APPROVED) {
            // The fill is now in the pharmacy work queue, so tell the patient
            // what to actually do rather than just that a decision was made.
            return NotificationText.of("refill.decision.approved", medicationName);
        }
        return NotificationText.of("refill.decision.denied", medicationName);
    }

    private MedicationRefillResponseDTO toResponseDTO(RefillRequest r) {
        String medName = null;
        if (r.getPrescription() != null && r.getPrescription().getMedicationName() != null) {
            medName = r.getPrescription().getMedicationName();
        }
        return MedicationRefillResponseDTO.builder()
                .id(r.getId())
                .prescriptionId(r.getPrescription() != null ? r.getPrescription().getId() : null)
                .medicationName(medName)
                .patientId(r.getPatient() != null ? r.getPatient().getId() : null)
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .preferredPharmacy(r.getPreferredPharmacy())
                .notes(r.getPatientNotes())
                .providerNotes(r.getProviderNotes())
                .requestedAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
