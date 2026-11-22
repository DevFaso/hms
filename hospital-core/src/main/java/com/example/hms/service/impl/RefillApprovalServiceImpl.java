package com.example.hms.service.impl;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
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
@ProvisionalRule(ClinicalRuleSetKey.PRESCRIPTION_RENEWAL)
public class RefillApprovalServiceImpl implements RefillApprovalService {

    private static final String NOTIFICATION_TYPE = "MEDICATION_REFILL";
    private static final String MSG_MEDICATION_FALLBACK = "refill.decision.medicationFallback";
    private static final String MSG_NOT_FOUND = "refillRequest.notFound";
    private static final String MSG_NOT_PENDING = "patient.refill.decision.notPending";
    private static final String MSG_USER_UNRESOLVED = "patient.refill.decision.userUnresolved";
    /** Policy rule 1 (QA D2308): only an authorised prescriber approves, holds or refuses a renewal. */
    static final String MSG_PRESCRIBER_ONLY = "patient.refill.decision.prescriberOnly";

    private final RefillRequestRepository refillRequestRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;
    private final StaffRepository staffRepository;
    private final NotificationService notificationService;
    private final ControllerAuthUtils authUtils;
    private final RoleValidator roleValidator;
    /** QA D792 (#953 review, round 3): a ward order of an ended stay is not refillable. */
    private final com.example.hms.service.EndedStayOrderGuard endedStayOrders;
    /** Policy rule 1: when a request was decided or reviewed. */
    private final java.time.Clock clock;

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
        if (mayCoverHere(auth)) {
            // QA D3301: the designated cover of a prescriber who left answers their requests too.
            return refillRequestRepository.findAnsweredBy(staffId, roleValidator.getCurrentHospitalId(),
                status == null ? java.util.EnumSet.allOf(RefillStatus.class) : java.util.EnumSet.of(status), pageable);
        }
        return (status == null)
                ? refillRequestRepository.findByPrescription_Staff_Id(staffId, pageable)
                : refillRequestRepository.findByPrescription_Staff_IdAndStatus(staffId, status, pageable);
    }

    /** QA D3301: who is told of a renewal request and answers it, over this service's role reader. */
    private com.example.hms.service.pharmacy.RenewalAlertRouting routing() {
        return new com.example.hms.service.pharmacy.RenewalAlertRouting(roleValidator);
    }

    /**
     * Whether the caller could be a prescriber's cover at the hospital they act at: they may approve a renewal
     * there. Anybody else lists their own prescriptions' requests only.
     */
    private boolean mayCoverHere(Authentication auth) {
        UUID hospitalId = roleValidator.getCurrentHospitalId();
        UUID userId = authUtils.resolveUserId(auth).orElse(null);
        return hospitalId != null && roleValidator.canApproveRenewal(userId, hospitalId, false);
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
        if (mayCoverHere(auth)) {
            return refillRequestRepository.countAnsweredBy(staffId, roleValidator.getCurrentHospitalId(),
                java.util.EnumSet.of(RefillStatus.REQUESTED));
        }
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
        // QA D1315 (round 29): a denial the patient cannot read is refused, as a hold is.
        requireReason(decision);
        return decide(auth, refillId, decision, RefillStatus.DENIED);
    }

    @Override
    @Transactional
    public MedicationRefillResponseDTO pause(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision) {
        // A hold the patient can't interpret is worse than a denial, so the reason
        // is mandatory here, as for a denial (approval leaves it optional).
        requireReason(decision);
        return decide(auth, refillId, decision, RefillStatus.PAUSED);
    }

    private static void requireReason(RefillDecisionRequestDTO decision) {
        if (decision == null || decision.getProviderNotes() == null || decision.getProviderNotes().isBlank()) {
            throw new com.example.hms.exception.FieldBusinessException("providerNotes", "patient.refill.decision.reasonRequired");
        }
    }

    private MedicationRefillResponseDTO decide(Authentication auth,
                                               UUID refillId,
                                               RefillDecisionRequestDTO decision,
                                               RefillStatus newStatus) {
        // Policy rule 1 (QA D2308): a renewal is a prescriber's decision. Whoever comes here only as a pharmacist
        // is refused before any lookup, so an unknown id answers them as a known one does.
        if (isDispensingRole()) {
            throw new AccessDeniedException(MSG_PRESCRIBER_ONLY);
        }
        UUID staffId = resolveStaffId(auth);

        RefillRequest refill = refillRequestRepository.findById(refillId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND, refillId));

        Prescription prescription = refill.getPrescription();
        Staff prescriber = prescription != null ? prescription.getStaff() : null;
        // QA D3301: the prescriber, or the designated cover of a prescriber who is no longer active.
        boolean answers = prescriber != null
            && (staffId.equals(prescriber.getId()) || routing().isCoverOf(staffId, prescription));
        if (!answers) {
            throw new AccessDeniedException("patient.refill.decision.notOwnPrescription");
        }
        UUID deciderId = requireAuthorisedPrescriber(auth, prescription, newStatus);

        if (!isActionable(refill.getStatus(), newStatus)) {
            throw new BusinessException(MSG_NOT_PENDING,
                String.valueOf(refill.getStatus()));
        }

        // Release the fill BEFORE recording the decision. If the prescription
        // can no longer be refilled this throws, and the refill request stays
        // REQUESTED rather than sitting at APPROVED with nothing dispensable
        // behind it — which is exactly the dead-end this endpoint used to be.
        if (newStatus == RefillStatus.APPROVED) {
            com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(prescription.getPatient());
            com.example.hms.service.support.OrderReviewHoldGuard.refusePrescriptionIfHeld(prescription.getId(), "prescriptionId");
            releaseRefillToPharmacy(prescription);
        }

        refill.setStatus(newStatus);
        if (decision != null && decision.getProviderNotes() != null && !decision.getProviderNotes().isBlank()) {
            refill.setProviderNotes(decision.getProviderNotes().trim());
        }
        if (newStatus != RefillStatus.PAUSED) {
            stampDecision(refill, deciderId, com.example.hms.enums.RefillDecisionBasis.PRESCRIBER_DECISION);
        }
        refill = refillRequestRepository.save(refill);

        notifyPatient(refill, newStatus);
        log.info("Staff {} marked refill {} as {}", staffId, refillId, newStatus);
        return toResponseDTO(refill);
    }

    /**
     * Policy rule 1 (provisional; CSP art. 276, QA D2308): the caller is an authorised prescriber at the
     * prescription's own hospital, on an assignment there: a physician for every list, a nurse or a midwife for
     * Lists I and II only (never a controlled drug). Never a pharmacist. Returns the caller's user id.
     */
    private UUID requireAuthorisedPrescriber(Authentication auth, Prescription prescription, RefillStatus decision) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_USER_UNRESOLVED));
        UUID hospitalId = prescription.getHospital() != null ? prescription.getHospital().getId() : null;
        boolean controlled = decision == RefillStatus.APPROVED && controlledResolver.isControlled(prescription);
        if (!roleValidator.canApproveRenewal(userId, hospitalId, controlled)) {
            throw new AccessDeniedException(MSG_PRESCRIBER_ONLY);
        }
        return userId;
    }

    private void stampDecision(RefillRequest refill, UUID userId, com.example.hms.enums.RefillDecisionBasis basis) {
        refill.setDecidedBy(userId);
        refill.setDecidedAt(java.time.LocalDateTime.now(clock));
        refill.setDecisionBasis(basis);
    }

    // ── Policy rule 1: what the pharmacist may do with a renewal request ─────────

    /**
     * The pharmacist reviews a request and forwards it to the prescriber with flags (an interaction, an early
     * request, a stock question). It decides nothing: the request stays open and the prescriber is told.
     */
    @Override
    @Transactional
    public MedicationRefillResponseDTO pharmacistReview(Authentication auth, UUID refillId,
                                                        RefillDecisionRequestDTO review) {
        String note = requirePharmacistNote(review);
        PharmacyReview at = requirePharmacist(auth, refillId);
        RefillRequest refill = at.refill();
        if (!isOpen(refill.getStatus())) {
            throw new BusinessException(MSG_NOT_PENDING, String.valueOf(refill.getStatus()));
        }
        refill.setPharmacistNote(note);
        refill.setPharmacistReviewedBy(at.userId());
        refill.setPharmacistReviewedAt(java.time.LocalDateTime.now(clock));
        refill = refillRequestRepository.save(refill);
        notifyPrescriber(refill, "refill.pharmacist.flagged", note);
        log.info("Pharmacist {} forwarded refill {} to its prescriber with flags", at.userId(), refillId);
        return toResponseDTO(refill);
    }

    /**
     * The pharmacist refuses to dispense, with a reason: the request is denied, the refusal is recorded as the
     * pharmacist's (never as a prescriber's decision), the patient is told and so is the prescriber.
     */
    @Override
    @Transactional
    public MedicationRefillResponseDTO pharmacistRefuse(Authentication auth, UUID refillId,
                                                        RefillDecisionRequestDTO refusal) {
        String reason = requirePharmacistNote(refusal);
        PharmacyReview at = requirePharmacist(auth, refillId);
        RefillRequest refill = at.refill();
        if (!isOpen(refill.getStatus())) {
            throw new BusinessException(MSG_NOT_PENDING, String.valueOf(refill.getStatus()));
        }
        refill.setStatus(RefillStatus.DENIED);
        refill.setPharmacistNote(reason);
        refill.setPharmacistReviewedBy(at.userId());
        refill.setPharmacistReviewedAt(java.time.LocalDateTime.now(clock));
        stampDecision(refill, at.userId(), com.example.hms.enums.RefillDecisionBasis.PHARMACIST_REFUSAL);
        refill = refillRequestRepository.save(refill);
        notifyPatient(refill, RefillStatus.DENIED);
        notifyPrescriber(refill, "refill.pharmacist.refused", reason);
        log.info("Pharmacist {} refused to dispense refill {}", at.userId(), refillId);
        return toResponseDTO(refill);
    }

    /**
     * A refill the prescriber already wrote on the prescription is ordinary dispensing: the pharmacist releases it
     * without a new decision. Only while the prescription still counts a refill; one refill beyond what was written
     * is a renewal, and only a prescriber approves that ({@link #approve}). Never a controlled drug, and never a
     * request its prescriber put on hold.
     */
    @Override
    @Transactional
    public MedicationRefillResponseDTO releaseAsPrescribed(Authentication auth, UUID refillId) {
        PharmacyReview at = requirePharmacist(auth, refillId);
        RefillRequest refill = at.refill();
        // Only a request nobody decided: a prescriber's hold (PAUSED) is a decision, and the pharmacy never
        // releases past it. The prescriber lifts their own hold by approving or refusing.
        if (refill.getStatus() == RefillStatus.PAUSED) {
            throw new BusinessException("patient.refill.release.onHold");
        }
        if (refill.getStatus() != RefillStatus.REQUESTED) {
            throw new BusinessException(MSG_NOT_PENDING, String.valueOf(refill.getStatus()));
        }
        Prescription prescription = refill.getPrescription();
        if (!hasWrittenRefillLeft(prescription)) {
            throw new BusinessException("patient.refill.release.noneLeft");
        }
        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(prescription.getPatient());
        com.example.hms.service.support.OrderReviewHoldGuard.refusePrescriptionIfHeld(prescription.getId(), "prescriptionId");
        releaseRefillToPharmacy(prescription);
        refill.setStatus(RefillStatus.APPROVED);
        stampDecision(refill, at.userId(), com.example.hms.enums.RefillDecisionBasis.ORIGINAL_PRESCRIPTION);
        refill = refillRequestRepository.save(refill);
        notifyPatient(refill, RefillStatus.APPROVED);
        log.info("Pharmacist {} released refill {} as written on the prescription", at.userId(), refillId);
        return toResponseDTO(refill);
    }

    /** Whether the prescriber wrote a refill that has not been used yet. */
    static boolean hasWrittenRefillLeft(Prescription prescription) {
        Integer remaining = prescription != null ? prescription.getRefillsRemaining() : null;
        return remaining != null && remaining > 0;
    }

    private record PharmacyReview(RefillRequest refill, UUID userId) {
    }

    /**
     * The request, for a pharmacist of its prescription's own hospital, acting there. A request of another hospital
     * answers as a missing one.
     */
    private PharmacyReview requirePharmacist(Authentication auth, UUID refillId) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_USER_UNRESOLVED));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (!roleValidator.canReviewRenewalAtPharmacy(userId, hospitalId)) {
            throw new AccessDeniedException("patient.refill.pharmacist.only");
        }
        RefillRequest refill = refillRequestRepository.findById(refillId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND, refillId));
        Prescription prescription = refill.getPrescription();
        if (prescription == null || prescription.getHospital() == null
                || !hospitalId.equals(prescription.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_NOT_FOUND, refillId);
        }
        return new PharmacyReview(refill, userId);
    }

    private static String requirePharmacistNote(RefillDecisionRequestDTO body) {
        String note = body != null && body.getPharmacistNote() != null ? body.getPharmacistNote().trim() : "";
        if (note.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("pharmacistNote", "patient.refill.pharmacist.noteRequired");
        }
        return note;
    }

    private static boolean isOpen(RefillStatus status) {
        return status == RefillStatus.REQUESTED || status == RefillStatus.PAUSED;
    }

    /** Tells the prescriber what the pharmacy flagged or refused, in the language they read it in. */
    private void notifyPrescriber(RefillRequest refill, String messageKey, String note) {
        Prescription prescription = refill.getPrescription();
        // QA D3301: whoever answers the request (the prescriber, else the designated cover), nobody else.
        Staff reader = prescription == null ? null : routing().answererOf(prescription).orElse(null);
        String username = reader != null && reader.getUser() != null ? reader.getUser().getUsername() : null;
        if (username == null || username.isBlank()) {
            return;
        }
        // The medicine is named to its prescriber only; a cover reads « the prescription » and opens the request.
        boolean toPrescriber = com.example.hms.service.pharmacy.RenewalAlertRouting.isPrescriber(reader, prescription);
        Object medicationName = toPrescriber && prescription.getMedicationName() != null
                ? prescription.getMedicationName() : NotificationLocalizer.key(MSG_MEDICATION_FALLBACK);
        String patientName = refill.getPatient() != null ? refill.getPatient().getFullName() : "";
        try {
            notificationService.createNotification(messageKey, new Object[]{medicationName, patientName, note},
                    username, NOTIFICATION_TYPE);
        } catch (Exception ex) {
            log.warn("Failed to tell the prescriber about the pharmacy review of refill {}", refill.getId(), ex);
        }
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
        // QA D792 (#953 review, round 3): a ward order that ended with its
        // stay, released back to SIGNED, sat in the pharmacy work queue with
        // every action refused (stayEnded) and nothing to end it. Medication
        // for home is a new (take-home) prescription, not a refill of it.
        if (endedStayOrders.endedWithAStay(prescription)) {
            throw new BusinessException("patient.refill.approveEndedStayOrder");
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
                .orElseThrow(() -> new BusinessException(MSG_USER_UNRESOLVED));
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

    /**
     * QA D1276: the MRN of the hospital the prescription was written at, the
     * hospital whose queue lists the refill (tenant-safe: never the MRN of
     * another hospital the patient is registered at).
     */
    static String mrnAtPrescribingHospital(RefillRequest r) {
        Prescription prescription = r.getPrescription();
        if (r.getPatient() == null || prescription == null || prescription.getHospital() == null) {
            return null;
        }
        return r.getPatient().getMrnForHospital(prescription.getHospital().getId());
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
                .patientName(r.getPatient() != null ? r.getPatient().getFullName() : null)
                .patientMrn(mrnAtPrescribingHospital(r))
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .preferredPharmacy(r.getPreferredPharmacy())
                .notes(r.getPatientNotes())
                .providerNotes(r.getProviderNotes())
                .requestedAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .pharmacistNote(r.getPharmacistNote())
                .pharmacistReviewedAt(r.getPharmacistReviewedAt())
                .decisionBasis(r.getDecisionBasis() != null ? r.getDecisionBasis().name() : null)
                .writtenRefillLeft(r.getStatus() == RefillStatus.REQUESTED
                        && hasWrittenRefillLeft(r.getPrescription())
                        // QA D2926 (r66): never offered for a controlled drug, which the release refuses.
                        && !controlledResolver.isControlled(r.getPrescription()))
                .pharmacyView(isDispensingRole())
                .build();
    }
}
