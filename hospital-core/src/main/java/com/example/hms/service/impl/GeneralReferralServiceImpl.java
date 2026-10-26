package com.example.hms.service.impl;

import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.GeneralReferralAttachment;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.ReferralAttachmentResponseDTO;
import com.example.hms.payload.dto.referral.ReferralEventResponseDTO;
import com.example.hms.payload.dto.referral.RejectReferralRequestDTO;
import com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO;
import com.example.hms.persistence.JpaProxyUtils;
import com.example.hms.enums.ReferralEventType;
import com.example.hms.model.ReferralEvent;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ReferralEventRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ReferralEventRecorder;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.NeverSentRule;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;

/**
 * Service implementation for multi-specialty general referrals
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@Transactional(readOnly = true)
public class GeneralReferralServiceImpl implements GeneralReferralService {

    private static final int REFERRAL_APPOINTMENT_MINUTES = 30;

    private final GeneralReferralRepository referralRepository;
    private final com.example.hms.repository.AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;
    private final ReferralEventRecorder eventRecorder;
    private final ReferralEventRepository eventRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    /** QA D309: each referral step is audited here, with its patient (the generic write audit is skipped). */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.service.AuditEventLogService> auditServiceProvider;

    @Override
    @Transactional
    public GeneralReferralResponseDTO createReferral(GeneralReferralRequestDTO request) {
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        // Both hospitals of a referral are clinical: a provider facility answers
        // as an unknown one (provider plan AC-11).
        // QA D205: the sending hospital is the caller's acting scope; the body's id is a claim.
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(request.getHospitalId())) {
            throw new ResourceNotFoundException("hospital.notFound", request.getHospitalId());
        }
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
        // QA D263: the referred patient is one the sending hospital registered. Any
        // other patient answers as unknown, so the response cannot carry their name.
        if (!registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospital.getId())) {
            throw new ResourceNotFoundException("patient.notFound", request.getPatientId());
        }

        Staff referringProvider = staffRepository.findById(request.getReferringProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("generalReferral.referringProvider.notFound", request.getReferringProviderId()));

        Staff receivingProvider = null;
        if (request.getReceivingProviderId() != null) {
            receivingProvider = staffRepository.findById(request.getReceivingProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("generalReferral.receivingProvider.notFound", request.getReceivingProviderId()));
        }

        Department targetDepartment = null;
        if (request.getTargetDepartmentId() != null) {
            targetDepartment = departmentRepository.findById(request.getTargetDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("generalReferral.targetDepartment.notFound", request.getTargetDepartmentId()));
        }

        Hospital receivingHospital = null;
        if (request.getReceivingHospitalId() != null) {
            receivingHospital = hospitalRepository.findClinicalById(request.getReceivingHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("generalReferral.receivingHospital.notFound", request.getReceivingHospitalId()));
        }

        // QA D205: the referrer is the signed-in clinician of the sending hospital,
        // and a named receiver works at the hospital the referral is addressed to.
        requireReferrerIsCaller(referringProvider, hospital.getId());
        // QA D319 (D205): the referrer is a physician or a midwife. A nurse
        // naming herself as the referring clinician passed the caller check.
        requireReferrerMayRefer(referringProvider, hospital.getId());
        if (receivingProvider != null) {
            UUID addressedTo = receivingHospital != null ? receivingHospital.getId() : hospital.getId();
            if (!worksAt(receivingProvider, addressedTo)) {
                throw BusinessRuleException.ofKey("generalReferral.receivingProvider.wrongHospital");
            }
            // QA D273: only a physician acts on a received referral (acknowledge, schedule,
            // start, complete are doctor-only), so a nurse cannot be the one it is addressed to.
            requirePhysicianAt(receivingProvider, addressedTo);
        }

        // A source department is one of the sending hospital's.
        Department sourceDepartment = loadSourceDepartment(request.getSourceDepartmentId(), hospital.getId());
        // A target department is one of the hospital the referral is addressed to.
        UUID targetHospitalId = receivingHospital != null ? receivingHospital.getId() : hospital.getId();
        if (targetDepartment != null && !belongsTo(targetDepartment, targetHospitalId)) {
            throw new ResourceNotFoundException("generalReferral.targetDepartment.notFound", request.getTargetDepartmentId());
        }

        GeneralReferral referral = new GeneralReferral();
        referral.setPatient(patient);
        referral.setHospital(hospital);
        referral.setReceivingHospital(receivingHospital);
        referral.setSourceDepartment(sourceDepartment);
        referral.setReferringProvider(referringProvider);
        referral.setReceivingProvider(receivingProvider);
        referral.setTargetSpecialty(request.getTargetSpecialty());
        referral.setTargetDepartment(targetDepartment);
        referral.setTargetFacilityName(request.getTargetFacilityName());
        referral.setReferralType(request.getReferralType());
        referral.setStatus(ReferralStatus.DRAFT);
        referral.setUrgency(request.getUrgency());
        referral.setReferralReason(request.getReferralReason());
        referral.setClinicalIndication(request.getClinicalIndication());
        referral.setClinicalSummary(request.getClinicalSummary());
        referral.setCurrentMedications(copyList(request.getCurrentMedications()));
        referral.setDiagnoses(copyList(request.getDiagnoses()));
        referral.setClinicalQuestion(request.getClinicalQuestion());
        referral.setAnticipatedTreatment(request.getAnticipatedTreatment());
        referral.setInsuranceAuthNumber(request.getInsuranceAuthNumber());
        referral.setMetadata(request.getMetadata());
        referral.setPriorityScore(calculatePriorityScore(request.getUrgency()));

        referral = referralRepository.save(referral);
        auditStep(referral, "CREATE", null);
        return toResponse(referral);
    }

    @Override
    public GeneralReferralResponseDTO getReferral(UUID referralId) {
        return toResponse(findReferral(referralId, true));
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO submitReferral(UUID referralId) {
        GeneralReferral referral = findReferral(referralId);
        requireCallerIsReferrer(referral);
        ReferralStatus before = referral.getStatus();
        referral.submit();
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.SUBMIT, before, null);
        auditStep(referral, "SUBMIT", before);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO acknowledgeReferral(UUID referralId, String notes, UUID receivingProviderId) {
        GeneralReferral referral = findReferral(referralId);
        UUID receivingHospitalId = requireReceivingSide(referral);
        Staff receivingProvider = staffRepository.findById(receivingProviderId)
            .orElseThrow(() -> new ResourceNotFoundException("generalReferral.receivingProvider.notFound", receivingProviderId));
        // QA D90: the named receiver must work at the hospital the referral is addressed to.
        if (receivingProvider.getHospital() == null
                || !receivingProvider.getHospital().getId().equals(receivingHospitalId)) {
            throw new ResourceNotFoundException("generalReferral.receivingProvider.notFound", receivingProviderId);
        }
        requirePhysicianAt(receivingProvider, receivingHospitalId);
        ReferralStatus before = referral.getStatus();
        referral.acknowledge(notes, receivingProvider);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.ACKNOWLEDGE, before, notes);
        auditStep(referral, "ACKNOWLEDGE", before);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO scheduleReferral(UUID referralId, ScheduleReferralRequestDTO request) {
        GeneralReferral referral = findReferral(referralId);
        requireReceivingSide(referral);
        ReferralStatus before = referral.getStatus();
        referral.schedule(request.getAppointmentTime(), request.getLocation());

        com.example.hms.model.Appointment appointment =
            createAppointmentForReferral(referral, request.getAppointmentTime());
        if (appointment != null) {
            referral.setAppointment(appointment);
        }

        referral = referralRepository.save(referral);
        String note = "appointmentTime=" + request.getAppointmentTime()
            + (request.getLocation() == null ? "" : ", location=" + request.getLocation())
            + (appointment == null ? ", appointment=none" : ", appointmentId=" + appointment.getId());
        eventRecorder.recordUserEvent(referral, ReferralEventType.SCHEDULE, before, note);
        auditStep(referral, "SCHEDULE", before);
        return toResponse(referral);
    }

    /**
     * Create the appointment a scheduled referral implies.
     *
     * <p>Scheduling used to store {@code scheduledAppointmentAt} plus a free-text
     * location and stop. No Appointment row existed, so a "booked" referral was
     * invisible to everything that works off appointments — the receiving
     * provider's calendar, reception check-in, the V112 reminder sweep,
     * utilisation reporting. The referral said booked and the schedule
     * disagreed.
     *
     * <p>Returns null when the referral cannot produce one, which is a NORMAL
     * outcome rather than an error: Appointment requires staff, department and
     * assignment (all NOT NULL), and a referral to an external facility has no
     * receiving provider. Those referrals keep the old behaviour. Failing the
     * schedule instead would break the commonest referral there is.
     *
     * <p>Never propagates. A referral must still schedule if appointment
     * creation fails — the referral is the clinical record, the appointment is
     * the convenience built on top of it.
     */
    private com.example.hms.model.Appointment createAppointmentForReferral(
            GeneralReferral referral, java.time.LocalDateTime appointmentTime) {
        if (appointmentTime == null) {
            return null;
        }
        com.example.hms.model.Staff provider = referral.getReceivingProvider();
        com.example.hms.model.Department department = referral.getTargetDepartment();
        com.example.hms.model.Hospital hospital = referral.getReceivingHospital() != null
            ? referral.getReceivingHospital()
            : referral.getHospital();

        if (provider == null || department == null || hospital == null
            || provider.getAssignment() == null) {
            log.debug("Referral {} scheduled without an appointment: "
                    + "provider={}, department={}, hospital={}, assignment={}",
                referral.getId(), provider != null, department != null, hospital != null,
                provider != null && provider.getAssignment() != null);
            return null;
        }

        try {
            com.example.hms.model.Appointment appointment = com.example.hms.model.Appointment.builder()
                .patient(referral.getPatient())
                .staff(provider)
                .assignment(provider.getAssignment())
                .hospital(hospital)
                .department(department)
                .appointmentDate(appointmentTime.toLocalDate())
                .startTime(appointmentTime.toLocalTime())
                .endTime(appointmentTime.toLocalTime().plusMinutes(REFERRAL_APPOINTMENT_MINUTES))
                .status(com.example.hms.enums.AppointmentStatus.SCHEDULED)
                .reason(buildAppointmentReason(referral))
                .build();
            return appointmentRepository.save(appointment);
        } catch (RuntimeException ex) {
            log.warn("Could not create an appointment for referral {}: {}",
                referral.getId(), ex.getMessage());
            return null;
        }
    }

    private String buildAppointmentReason(GeneralReferral referral) {
        String reason = referral.getReferralReason();
        String prefix = "Referral";
        return (reason == null || reason.isBlank()) ? prefix : prefix + ": " + reason;
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO startReferral(UUID referralId) {
        GeneralReferral referral = findReferral(referralId);
        requireReceivingSide(referral);
        ReferralStatus before = referral.getStatus();
        referral.start();
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.START, before, null);
        auditStep(referral, "START", before);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO completeReferral(UUID referralId, String summary, String followUp) {
        GeneralReferral referral = findReferral(referralId);
        requireReceivingSide(referral);
        ReferralStatus before = referral.getStatus();
        referral.complete(summary, followUp);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.COMPLETE, before, summary);
        auditStep(referral, "COMPLETE", before);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO rejectReferral(UUID referralId, RejectReferralRequestDTO request) {
        GeneralReferral referral = findReferral(referralId);
        requireReceivingSide(referral);
        ReferralStatus before = referral.getStatus();
        referral.reject(request.getReason());
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.REJECT, before, request.getReason());
        auditStep(referral, "REJECT", before);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public void cancelReferral(UUID referralId, String reason) {
        GeneralReferral referral = findReferral(referralId);
        // QA D510: cancelling is the SENDING side's act; the receiver declines
        // (reject) instead. QA D509: and only the clinician who wrote it, as for
        // submit — a colleague at the same hospital cancelled DOCTOR_A's referral.
        requireSendingSide(referral);
        requireCallerIsReferrer(referral, "generalReferral.cancel.referrerOnly");
        ReferralStatus before = referral.getStatus();
        referral.cancel(reason);
        referral = referralRepository.save(referral);
        eventRecorder.recordUserEvent(referral, ReferralEventType.CANCEL, before, reason);
        auditStep(referral, "CANCEL", before);
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByPatient(UUID patientId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            // E9 #59b — referrals follow the patient across the readable
            // hospitals (untagged, V158); every foreign row is accounted.
            UUID requesterUserId = roleValidator.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
            // QA D623/D652: the same rule as the by-id read (findReferral) — a
            // referral never sent exists only for the hospital that wrote it.
            referrals = referralRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable)
                .stream()
                .filter(r -> NeverSentRule.readableHere(
                    CrossHospitalReachRecorder.hospitalIdOf(r.getHospital()), activeHospitalId, wasSent(r)))
                .toList();
            reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(referrals.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), activeHospitalId),
                "Cross-hospital referral read on the treatment relationship");
        } else {
            referrals = referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsForPortalPatient(UUID patientId) {
        // QA D430: a draft is the clinician's unfinished work, not yet a
        // referral; the patient sees it once it is sent.
        return referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
            .filter(referral -> referral.getStatus() != ReferralStatus.DRAFT)
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<GeneralReferral> outgoing;
        List<GeneralReferral> incoming;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            outgoing = referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(effectiveHospitalId, referralStatus);
            incoming = referralRepository.findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(effectiveHospitalId, referralStatus);
        } else {
            outgoing = referralRepository.findByHospitalIdOrderByCreatedAtDesc(effectiveHospitalId);
            incoming = referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(effectiveHospitalId);
        }
        // Exclude never-sent incoming referrals — drafts are unsent and only visible to the
        // sender. QA D509: a draft CANCELLED before it was sent is still unsent.
        incoming = incoming.stream()
            .filter(GeneralReferralServiceImpl::wasSent)
            .toList();
        // Merge outgoing and incoming, dedup by ID, sort newest-first
        Map<UUID, GeneralReferral> merged = new LinkedHashMap<>();
        outgoing.forEach(r -> merged.put(r.getId(), r));
        incoming.forEach(r -> merged.putIfAbsent(r.getId(), r));
        return merged.values().stream()
            .sorted(Comparator.comparing(GeneralReferral::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getAllReferrals(String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            return getReferralsByHospital(activeHospitalId, status);
        }
        List<GeneralReferral> referrals;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            referrals = referralRepository.findByStatusOrderByCreatedAtDesc(referralStatus);
        } else {
            referrals = referralRepository.findAllByOrderByCreatedAtDesc();
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getRecentForSuperAdmin(Pageable pageable) {
        return referralRepository.findAll(pageable)
            .map(this::toResponse)
            .getContent();
    }

    @Override
    public List<GeneralReferralResponseDTO> getOverdueReferrals() {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findOverdueReferralsByHospital(activeHospitalId, LocalDateTime.now());
        } else {
            referrals = referralRepository.findOverdueReferrals(LocalDateTime.now());
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<ReferralEventResponseDTO> getReferralEvents(UUID referralId) {
        // Validate the referral exists in the caller's scope before exposing its history.
        findReferral(referralId);
        return eventRepository.findByReferralIdOrderByRecordedAtAsc(referralId).stream()
            .map(GeneralReferralServiceImpl::toEventResponse)
            .toList();
    }

    private static ReferralEventResponseDTO toEventResponse(ReferralEvent event) {
        return ReferralEventResponseDTO.builder()
            .id(event.getId())
            .referralId(event.getReferralId())
            .eventType(event.getEventType())
            .fromStatus(event.getFromStatus())
            .toStatus(event.getToStatus())
            .actorUsername(event.getActorUsername())
            .actorLabel(event.getActorLabel())
            .note(event.getNote())
            .recordedAt(event.getRecordedAt())
            .build();
    }

    private static final String REFERRAL_NOT_FOUND = "generalReferral.notFound";

    /**
     * QA D90: receiver-side transitions (acknowledge, schedule, start, complete, reject)
     * belong to the hospital the referral is addressed to — the receiving hospital, or the
     * sending hospital itself for an internal referral. {@link #findReferral} lets either
     * side READ the referral; only the receiving side may act on it. Returns that hospital id.
     */
    private UUID requireReceivingSide(GeneralReferral referral) {
        Hospital target = referral.getReceivingHospital() != null
            ? referral.getReceivingHospital() : referral.getHospital();
        UUID targetId = target != null ? target.getId() : null;
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && !activeHospitalId.equals(targetId)) {
            // QA D332: a refusal the caller can read, in its own language.
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("generalReferral.receivingSide.required"));
        }
        return targetId;
    }

    private GeneralReferral findReferral(UUID referralId) {
        return findReferral(referralId, false);
    }

    /**
     * @param read a read by id ({@code getReferral}): it also opens a referral
     *             the by-patient list shows on the treatment relationship (QA
     *             D652). Every write passes {@code false}: reading across is not
     *             acting across.
     */
    private GeneralReferral findReferral(UUID referralId, boolean read) {
        GeneralReferral referral = referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException(REFERRAL_NOT_FOUND));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            boolean isSendingHospital = isSendingHospital(referral, activeHospitalId);
            boolean isReceivingHospital = referral.getReceivingHospital() != null
                && activeHospitalId.equals(referral.getReceivingHospital().getId());
            if (!isSendingHospital && !isReceivingHospital
                    && !(read && readableOnTheTreatmentRelationship(referral, activeHospitalId))) {
                throw new ResourceNotFoundException(REFERRAL_NOT_FOUND);
            }
            // QA D509: a referral never sent does not exist for the receiving hospital.
            if (!NeverSentRule.readableHere(
                    CrossHospitalReachRecorder.hospitalIdOf(referral.getHospital()), activeHospitalId, wasSent(referral))) {
                throw new ResourceNotFoundException(REFERRAL_NOT_FOUND);
            }
        }
        return referral;
    }

    /**
     * QA D652 - the by-patient list reads across {@code readableHospitalIds}, so a
     * sent referral of a third hospital it lists must open by id too (the rule
     * D508 gave imaging), once it was sent.
     */
    private boolean readableOnTheTreatmentRelationship(GeneralReferral referral, UUID activeHospitalId) {
        UUID rowHospitalId = CrossHospitalReachRecorder.hospitalIdOf(referral.getHospital());
        UUID patientId = referral.getPatient() != null ? referral.getPatient().getId() : null;
        if (rowHospitalId == null || patientId == null
                || !NeverSentRule.readableHere(rowHospitalId, activeHospitalId, wasSent(referral))) {
            return false;
        }
        UUID requesterUserId = roleValidator.getCurrentUserId();
        if (!recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId).contains(rowHospitalId)) {
            return false;
        }
        reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(List.of(rowHospitalId), activeHospitalId),
            "Cross-hospital referral read on the treatment relationship");
        return true;
    }

    private static boolean isSendingHospital(GeneralReferral referral, UUID activeHospitalId) {
        return referral.getHospital() != null && activeHospitalId.equals(referral.getHospital().getId());
    }

    private static final String OWNER = "GeneralReferral";

    /**
     * QA D889: the referral list and detail print the patient's MRN under the
     * name, and the response never carried one, so the line was blank. The
     * MRN at the referring hospital, else at the receiving one. A registration
     * row pointing at a hard-deleted hospital leaves it unknown, never a 500.
     */
    private String resolvePatientMrn(Patient patient, Hospital hospital, Hospital receivingHospital,
                                     java.util.UUID referralId) {
        if (patient == null) {
            return null;
        }
        try {
            String mrn = hospital != null && hospital.getId() != null
                ? patient.getMrnForHospital(hospital.getId()) : null;
            if ((mrn == null || mrn.isBlank()) && receivingHospital != null && receivingHospital.getId() != null) {
                mrn = patient.getMrnForHospital(receivingHospital.getId());
            }
            return mrn == null || mrn.isBlank() ? null : mrn;
        } catch (jakarta.persistence.EntityNotFoundException
                 | org.springframework.orm.jpa.JpaObjectRetrievalFailureException ex) {
            org.slf4j.LoggerFactory.getLogger(GeneralReferralServiceImpl.class)
                .warn("Referral {}: patient registration points at a missing hospital; MRN unavailable.", referralId);
            return null;
        }
    }

    private GeneralReferralResponseDTO toResponse(GeneralReferral referral) {
        GeneralReferralResponseDTO dto = new GeneralReferralResponseDTO();
        java.util.UUID referralId = referral.getId();
        dto.setId(referralId);

        // Force-initialise each lazy association up front and substitute null
        // when the referenced row was hard-deleted (dangling FK). Without this
        // defence a bare `referral.getPatient().getFirstName()` blows up the
        // whole list response with a 500 the moment a single referenced row
        // is missing — visible on dev's /super-admin/recent-activity endpoint.
        Patient patient = JpaProxyUtils.safeInit(referral.getPatient(), OWNER, referralId, "patient");
        Hospital hospital = JpaProxyUtils.safeInit(referral.getHospital(), OWNER, referralId, "hospital");
        Hospital receivingHospital = JpaProxyUtils.safeInit(
            referral.getReceivingHospital(), OWNER, referralId, "receivingHospital");
        Department sourceDepartment = JpaProxyUtils.safeInit(
            referral.getSourceDepartment(), OWNER, referralId, "sourceDepartment");
        Staff referringProvider = JpaProxyUtils.safeInit(
            referral.getReferringProvider(), OWNER, referralId, "referringProvider");
        Staff receivingProvider = JpaProxyUtils.safeInit(
            referral.getReceivingProvider(), OWNER, referralId, "receivingProvider");
        Department targetDepartment = JpaProxyUtils.safeInit(
            referral.getTargetDepartment(), OWNER, referralId, "targetDepartment");

        dto.setPatientId(patient != null ? patient.getId() : null);
        dto.setPatientName(extractPatientName(patient));
        dto.setPatientMrn(resolvePatientMrn(patient, hospital, receivingHospital, referralId));
        dto.setHospitalId(hospital != null ? hospital.getId() : null);
        dto.setHospitalName(hospital != null ? hospital.getName() : null);
        dto.setReceivingHospitalId(receivingHospital != null ? receivingHospital.getId() : null);
        dto.setReceivingHospitalName(receivingHospital != null ? receivingHospital.getName() : null);
        dto.setSourceDepartmentId(sourceDepartment != null ? sourceDepartment.getId() : null);
        dto.setSourceDepartmentName(sourceDepartment != null ? sourceDepartment.getName() : null);
        dto.setReferringProviderId(referringProvider != null ? referringProvider.getId() : null);
        dto.setReferringProviderName(extractStaffName(referringProvider));
        dto.setReceivingProviderId(receivingProvider != null ? receivingProvider.getId() : null);
        dto.setReceivingProviderName(extractStaffName(receivingProvider));
        dto.setTargetSpecialty(referral.getTargetSpecialty());
        dto.setTargetDepartmentId(targetDepartment != null ? targetDepartment.getId() : null);
        dto.setTargetDepartmentName(targetDepartment != null ? targetDepartment.getName() : null);
        dto.setTargetFacilityName(referral.getTargetFacilityName());
        dto.setReferralType(referral.getReferralType());
        dto.setStatus(referral.getStatus());
        dto.setUrgency(referral.getUrgency());
        dto.setReferralReason(referral.getReferralReason());
        dto.setClinicalIndication(referral.getClinicalIndication());
        dto.setClinicalSummary(referral.getClinicalSummary());
        dto.setCurrentMedications(referral.getCurrentMedications());
        dto.setDiagnoses(referral.getDiagnoses());
        dto.setClinicalQuestion(referral.getClinicalQuestion());
        dto.setAnticipatedTreatment(referral.getAnticipatedTreatment());
        dto.setSubmittedAt(referral.getSubmittedAt());
        dto.setSlaDueAt(referral.getSlaDueAt());
        dto.setAcknowledgedAt(referral.getAcknowledgedAt());
        dto.setAcknowledgementNotes(referral.getAcknowledgementNotes());
        dto.setScheduledAppointmentAt(referral.getScheduledAppointmentAt());
        dto.setAppointmentLocation(referral.getAppointmentLocation());
        dto.setAppointmentId(referral.getAppointment() != null ? referral.getAppointment().getId() : null);
        dto.setStartedAt(referral.getStartedAt());
        dto.setCompletedAt(referral.getCompletedAt());
        dto.setCompletionSummary(referral.getCompletionSummary());
        dto.setFollowUpRecommendations(referral.getFollowUpRecommendations());
        dto.setCancellationReason(referral.getCancellationReason());
        dto.setInsuranceAuthNumber(referral.getInsuranceAuthNumber());
        dto.setPriorityScore(referral.getPriorityScore());
        dto.setMetadata(referral.getMetadata());
        dto.setAttachments(referral.getAttachments() == null ? List.of() :
            referral.getAttachments().stream()
                .map(this::toAttachmentResponse)
                .toList());
        dto.setIsOverdue(referral.isOverdue());
        dto.setCreatedAt(referral.getCreatedAt());
        dto.setUpdatedAt(referral.getUpdatedAt());
        return dto;
    }

    private ReferralAttachmentResponseDTO toAttachmentResponse(GeneralReferralAttachment attachment) {
        ReferralAttachmentResponseDTO dto = new ReferralAttachmentResponseDTO();
        dto.setId(attachment.getId());
        dto.setReferralId(attachment.getReferral() != null ? attachment.getReferral().getId() : null);
        dto.setStorageKey(attachment.getStorageKey());
        dto.setDisplayName(attachment.getDisplayName());
        dto.setCategory(attachment.getCategory());
        dto.setContentType(attachment.getContentType());
        dto.setSizeBytes(attachment.getSizeBytes());
        dto.setUploadedById(attachment.getUploadedBy() != null ? attachment.getUploadedBy().getId() : null);
        dto.setUploadedByName(extractStaffName(attachment.getUploadedBy()));
        dto.setUploadedAt(attachment.getUploadedAt());
        dto.setDescription(attachment.getDescription());
        return dto;
    }

    private List<Map<String, String>> copyList(List<Map<String, String>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(source);
    }

    private int calculatePriorityScore(ReferralUrgency urgency) {
        if (urgency == null) {
            return 0;
        }
        return switch (urgency) {
            case EMERGENCY -> 100;
            case URGENT -> 75;
            case PRIORITY -> 50;
            case ROUTINE -> 25;
        };
    }

    private String extractStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        return staff.getFullName();
    }

    private String extractPatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (patient.getFirstName() != null) {
            builder.append(patient.getFirstName().trim());
        }
        if (patient.getLastName() != null) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(patient.getLastName().trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    /**
     * QA D509 — has this referral ever been sent to the receiving hospital? A
     * draft has not, and neither has a draft cancelled before submission
     * (status CANCELLED, never stamped {@code submittedAt}).
     */
    static boolean wasSent(GeneralReferral referral) {
        ReferralStatus status = referral.getStatus();
        if (status == ReferralStatus.DRAFT) {
            return false;
        }
        return !(status == ReferralStatus.CANCELLED && referral.getSubmittedAt() == null);
    }

    /** QA D510 — only the sending hospital (which, for an internal referral, is also the receiver) cancels. */
    private void requireSendingSide(GeneralReferral referral) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID sendingId = referral.getHospital() != null ? referral.getHospital().getId() : null;
        if (activeHospitalId != null && !activeHospitalId.equals(sendingId)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("generalReferral.cancel.sendingSideOnly"));
        }
    }

    /**
     * QA D205: only the signed-in clinician, at the sending hospital, may be named as the referrer.
     * QA D412: a draft is sent by the clinician who wrote it. A nurse could
     * submit a doctor's draft, sending it out under the doctor's name. The
     * referral was resolved in the caller's scope (findReferral), so the
     * refusal names only what the caller would need to be.
     */
    private void requireCallerIsReferrer(GeneralReferral referral) {
        requireCallerIsReferrer(referral, "generalReferral.submit.referrerOnly");
    }

    private void requireCallerIsReferrer(GeneralReferral referral, String messageKey) {
        UUID callerId = roleValidator.getCurrentUserId();
        Staff referrer = referral.getReferringProvider();
        boolean isReferrer = callerId != null && referrer != null && referrer.getUser() != null
            && callerId.equals(referrer.getUser().getId());
        if (!isReferrer) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw(messageKey));
        }
    }

    private void requireReferrerIsCaller(Staff referringProvider, UUID sendingHospitalId) {
        UUID callerId = roleValidator.getCurrentUserId();
        boolean isCaller = callerId != null && referringProvider.getUser() != null
            && callerId.equals(referringProvider.getUser().getId());
        if (!isCaller || !worksAt(referringProvider, sendingHospitalId)) {
            throw BusinessRuleException.ofKey("generalReferral.referringProvider.notCaller");
        }
    }

    /** QA D319: the referring clinician holds a physician or midwife role at the sending hospital. */
    private void requireReferrerMayRefer(Staff provider, UUID hospitalId) {
        UUID userId = provider.getUser() != null ? provider.getUser().getId() : null;
        boolean mayRefer = userId != null && (roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isPhysician(userId, hospitalId)
            || roleValidator.isSurgeon(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId));
        if (!mayRefer) {
            throw BusinessRuleException.ofKey("generalReferral.referringProvider.prescriber.required");
        }
    }

    /**
     * QA D309 (D260): one audit row per referral step, typed by what happened
     * (a creation, or a change of status), naming the referral and keyed on
     * its patient. Ids and statuses only: no name, no clinical text.
     * The generic write audit was "DATA_CREATE" for every POST with no
     * patient and an "Unknown Resource" name.
     */
    private void auditStep(GeneralReferral referral, String step, ReferralStatus before) {
        com.example.hms.service.AuditEventLogService audit =
            auditServiceProvider != null ? auditServiceProvider.getIfAvailable() : null;
        if (audit == null || referral == null || referral.getId() == null) {
            return;
        }
        try {
            UUID activeHospitalId = roleValidator.requireActiveHospitalId();
            Hospital acting = referral.getReceivingHospital() != null && activeHospitalId != null
                && activeHospitalId.equals(referral.getReceivingHospital().getId())
                ? referral.getReceivingHospital() : referral.getHospital();
            boolean created = "CREATE".equals(step);
            audit.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                .eventType(created ? com.example.hms.enums.AuditEventType.DATA_CREATE
                    : com.example.hms.enums.AuditEventType.DATA_UPDATE)
                .status(com.example.hms.enums.AuditStatus.SUCCESS)
                .userId(roleValidator.getCurrentUserId())
                .hospitalName(acting != null ? acting.getName() : null)
                .entityType("REFERRAL")
                .resourceId(referral.getId().toString())
                .resourceName("Referral " + referral.getId().toString().substring(0, 8))
                .patientId(referral.getPatient() != null ? referral.getPatient().getId() : null)
                .eventDescription("Referral " + step
                    + (created ? " -> " + referral.getStatus() : ": " + before + " -> " + referral.getStatus()))
                .build());
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(GeneralReferralServiceImpl.class)
                .warn("Referral step audit not written for {} ({}): {}", referral.getId(), step,
                    ex.getClass().getSimpleName());
        }
    }

    /** QA D273: the receiving provider holds a physician role at the hospital the referral is addressed to. */
    private void requirePhysicianAt(Staff provider, UUID hospitalId) {
        UUID userId = provider.getUser() != null ? provider.getUser().getId() : null;
        boolean physician = userId != null && (roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isPhysician(userId, hospitalId)
            || roleValidator.isSurgeon(userId, hospitalId));
        if (!physician) {
            throw BusinessRuleException.ofKey("generalReferral.receivingProvider.physician.required");
        }
    }

    /** The referral's source department, which must be one of the sending hospital's; null when none is named. */
    private Department loadSourceDepartment(UUID sourceDepartmentId, UUID sendingHospitalId) {
        if (sourceDepartmentId == null) {
            return null;
        }
        Department department = departmentRepository.findById(sourceDepartmentId)
            .orElseThrow(() -> new ResourceNotFoundException("generalReferral.sourceDepartment.notFound", sourceDepartmentId));
        if (department.getHospital() == null || !sendingHospitalId.equals(department.getHospital().getId())) {
            throw new ResourceNotFoundException("generalReferral.sourceDepartment.notFound", sourceDepartmentId);
        }
        return department;
    }

    private static boolean belongsTo(Department department, UUID hospitalId) {
        return department.getHospital() != null && department.getHospital().getId() != null
            && department.getHospital().getId().equals(hospitalId);
    }

    private static boolean worksAt(Staff staff, UUID hospitalId) {
        return staff.getHospital() != null && staff.getHospital().getId() != null
            && staff.getHospital().getId().equals(hospitalId);
    }
}
