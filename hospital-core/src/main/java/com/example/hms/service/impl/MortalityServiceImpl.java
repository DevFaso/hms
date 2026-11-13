package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalAgeBasis;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PerinatalReportingEligibility;
import com.example.hms.enums.PlaceOfDeath;
import com.example.hms.enums.RecallStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.mortality.DeathClosureSummaryDTO;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import com.example.hms.payload.dto.mortality.FetalDeathEventDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.MortalityService;
import com.example.hms.service.support.DeathClassificationRules;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.service.support.StillbirthClassification;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>See {@link MortalityService} for why this exists. The interesting part is
 * {@link #recordDeath}: it is a cascade, and each thing it closes is something
 * that would otherwise keep running against a person who has died.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MortalityServiceImpl implements MortalityService {

    private static final String MSG_DEATH_NOT_FOUND = "mortality.deathRecord.notFound";

    /** Admissions still running when the patient dies. */
    private static final Set<AdmissionStatus> OPEN_ADMISSIONS = Set.of(
        AdmissionStatus.PENDING,
        AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE,
        AdmissionStatus.AWAITING_DISCHARGE);

    /** Encounters still running. COMPLETED and CANCELLED are already settled. */
    private static final Set<EncounterStatus> OPEN_ENCOUNTERS = Set.of(
        EncounterStatus.SCHEDULED,
        EncounterStatus.ARRIVED,
        EncounterStatus.TRIAGE,
        EncounterStatus.WAITING_FOR_PHYSICIAN,
        EncounterStatus.IN_PROGRESS,
        EncounterStatus.AWAITING_RESULTS,
        EncounterStatus.READY_FOR_DISCHARGE);

    /** Appointments that would still be honoured, and reminded about. */
    private static final Set<AppointmentStatus> LIVE_APPOINTMENTS = Set.of(
        AppointmentStatus.SCHEDULED,
        AppointmentStatus.CONFIRMED,
        AppointmentStatus.RESCHEDULED,
        AppointmentStatus.PENDING);

    /** Recalls that would still chase the patient up. */
    private static final Set<RecallStatus> OPEN_RECALLS = Set.of(
        RecallStatus.PENDING,
        RecallStatus.NOTIFIED,
        RecallStatus.SCHEDULED);

    private static final String CLOSURE_REASON = "Patient deceased";

    /** QA D1488: the entered-in-error reason's column size. */
    private static final int MAX_ERROR_REASON_LENGTH = 500;

    private com.example.hms.service.AdmissionStayEncounterService stayEncounters;
    private com.example.hms.service.AuditEventLogService auditEventLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAuditEventLogService(com.example.hms.service.AuditEventLogService auditEventLogService) {
        this.auditEventLogService = auditEventLogService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setStayEncounters(com.example.hms.service.AdmissionStayEncounterService stayEncounters) {
        this.stayEncounters = stayEncounters;
    }

    /**
     * Provisional platform rule (unsigned-note workflow): a closed visit's unsigned notes go to their authors'
     * in-basket (UnsignedNoteHookGuardTest). Setter-injected; null in hand-built unit tests.
     */
    private com.example.hms.service.UnsignedNoteFollowUp unsignedNotes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUnsignedNotes(com.example.hms.service.UnsignedNoteFollowUp unsignedNotes) {
        this.unsignedNotes = unsignedNotes;
    }

    /** QA D1516: files the review of what a voided death closed, after commit; absent in hand-built tests. */
    private OrderReviewAfterCommit notices;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotices(OrderReviewAfterCommit notices) {
        this.notices = notices;
    }

    static final String VOIDED_CLOSURE_REFERENCE_TYPE = "DEATH_ENTERED_IN_ERROR_CLOSURES";
    static final String VOIDED_CLOSURE_TITLE_KEY = "mortality.enteredInError.closures.title";
    static final String VOIDED_CLOSURE_BODY_KEY = "mortality.enteredInError.closures.body";
    static final String VOIDED_APPOINTMENT_NOTE_KEY = "mortality.enteredInError.appointmentNote";

    private final Clock clock;
    private final DeathRecordRepository deathRecordRepository;
    private final PatientRepository patientRepository;
    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRecallRepository recallRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PatientChartAccess patientChartAccess;
    private final MortalityMapper mapper;
    private final RoleValidator roleValidator;
    private final com.example.hms.service.BedAssignmentService bedAssignmentService;
    private final com.example.hms.repository.DeathDisputeRepository disputeRepository;
    private final com.example.hms.service.OrderReviewHoldService orderReview;
    private final com.example.hms.service.InBasketService inBasketService;
    /** CR-08: the way from a newborn's chart to the delivery that holds its birth time. */
    private final com.example.hms.repository.NewbornAssessmentRepository newbornAssessmentRepository;
    /** QA D972: a visit a death closes is handed to billing like a checkout (null in hand-built tests). */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    /** QA D1714: stillbirths recorded at delivery, without a patient account. */
    private final com.example.hms.repository.FetalDeathEventRepository fetalDeathEventRepository;
    /** QA D1714: the way from a registered baby to its delivery, to link a stillbirth death record to its event. */
    private final com.example.hms.repository.DeliveryNewbornRepository deliveryNewbornRepository;

    @Override
    public RecordDeathResponseDTO recordDeath(DeathRecordRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = patientChartAccess.require(request.getPatientId(), hospitalId);

        if (deathRecordRepository.existsByPatient_Id(patient.getId())) {
            throw new com.example.hms.exception.FieldBusinessException("patientId", "mortality.alreadyRecorded");
        }
        if (request.getDiedAt().isAfter(LocalDateTime.now(clock))) {
            throw new com.example.hms.exception.FieldBusinessException("diedAt", "mortality.timeInFuture");
        }
        validateMortalityFlags(request);
        // QA D1490: a wrong date, and a classification this patient cannot have. With the classification
        // left off, the death still saves.
        com.example.hms.service.support.DeathClassificationRules.requireDiedAfterBirth(patient, request.getDiedAt());
        if (Boolean.TRUE.equals(request.getMaternalDeath())) {
            com.example.hms.service.support.DeathClassificationRules.requireMaternalPossible(patient);
        }
        BirthFacts birth = judgeBirthFacts(patient, hospitalId,
            Boolean.TRUE.equals(request.getPerinatalDeath()) ? request.getPerinatalType() : null,
            request.getDiedAt(),
            new BirthFacts(request.getBornAt(), request.getBirthStatus(), request.getGestationalAgeWeeks()));

        DeathRecord deathRecord = DeathRecord.builder()
            .patient(patient)
            .hospital(hospitalRef(hospitalId))
            .diedAt(request.getDiedAt())
            .placeOfDeath(request.getPlaceOfDeath() != null ? request.getPlaceOfDeath() : PlaceOfDeath.FACILITY)
            .mannerOfDeath(request.getMannerOfDeath() != null ? request.getMannerOfDeath() : MannerOfDeath.NATURAL)
            .immediateCause(request.getImmediateCause())
            .immediateCauseCode(request.getImmediateCauseCode())
            .underlyingCause(request.getUnderlyingCause())
            .underlyingCauseCode(request.getUnderlyingCauseCode())
            .contributingCauses(request.getContributingCauses())
            .maternalDeath(Boolean.TRUE.equals(request.getMaternalDeath()))
            .maternalDeathTiming(request.getMaternalDeathTiming())
            .perinatalDeath(Boolean.TRUE.equals(request.getPerinatalDeath()))
            .perinatalType(request.getPerinatalType())
            .bornAt(birth.bornAt())
            .birthStatus(birth.status())
            .gestationalAgeWeeks(birth.gestationWeeks())
            .perinatalAgeBasis(birth.ageBasis())
            .fetalDeathEventId(linkedFetalDeathEvent(patient, hospitalId, request))
            .autopsyRequested(Boolean.TRUE.equals(request.getAutopsyRequested()))
            .certifiedBy(resolveCertifier(request.getCertifiedByStaffId(), hospitalId))
            .certifiedAt(request.getCertifiedByStaffId() != null ? LocalDateTime.now(clock) : null)
            .notes(request.getNotes())
            .recordedBy(currentStaff(hospitalId))
            .build();

        // The flag every sweep and worklist reads. Set before the cascade so
        // anything re-reading the patient mid-transaction already sees it.
        patient.setDeceasedAt(request.getDiedAt());
        patientRepository.save(patient);

        DeathRecord saved = deathRecordRepository.save(deathRecord);
        DeathClosureSummaryDTO closure = closeOpenRecords(patient, hospitalId, saved);
        // After the cascade, so an order that ended with its stay is left as it is. Against this event, not the date
        // on the record: correcting that date later cannot evade the hold, and no correction clears it.
        orderReview.snapshotAtDeath(saved);

        if (saved.isWhoMaternalDeath()) {
            log.warn("MATERNAL DEATH recorded for patient {} at hospital {} — timing {}",
                patient.getId(), hospitalId, saved.getMaternalDeathTiming());
        }

        return RecordDeathResponseDTO.builder()
            .deathRecord(mapper.toDto(saved))
            .closure(closure)
            .build();
    }

    @Override
    public DeathRecordResponseDTO amendDeathRecord(UUID recordId, DeathRecordAmendmentDTO request) {
        if (!StringUtils.hasText(request.getAmendmentReason())) {
            throw new com.example.hms.exception.FieldBusinessException("amendmentReason", "mortality.amendmentReasonRequired");
        }
        DeathRecord deathRecord = loadScoped(recordId);
        if (deathRecord.isEnteredInError()) {
            throw new BusinessException("mortality.enteredInError.closed");
        }
        // QA D1490: an amendment reaches the same two classifications. Only what this amendment sets is judged:
        // an autopsy's new cause is never refused over an older row's flags.
        if (Boolean.TRUE.equals(request.getMaternalDeath())) {
            com.example.hms.service.support.DeathClassificationRules.requireMaternalPossible(deathRecord.getPatient());
        }
        amendBirthFacts(deathRecord, request);

        if (request.getImmediateCause() != null) {
            deathRecord.setImmediateCause(request.getImmediateCause());
        }
        if (request.getImmediateCauseCode() != null) {
            deathRecord.setImmediateCauseCode(request.getImmediateCauseCode());
        }
        if (request.getUnderlyingCause() != null) {
            deathRecord.setUnderlyingCause(request.getUnderlyingCause());
        }
        if (request.getUnderlyingCauseCode() != null) {
            deathRecord.setUnderlyingCauseCode(request.getUnderlyingCauseCode());
        }
        if (request.getContributingCauses() != null) {
            deathRecord.setContributingCauses(request.getContributingCauses());
        }
        if (request.getMannerOfDeath() != null) {
            deathRecord.setMannerOfDeath(request.getMannerOfDeath());
        }
        if (request.getMaternalDeath() != null) {
            deathRecord.setMaternalDeath(request.getMaternalDeath());
            deathRecord.setMaternalDeathTiming(request.getMaternalDeathTiming());
        }
        if (request.getPerinatalDeath() != null) {
            deathRecord.setPerinatalDeath(request.getPerinatalDeath());
            deathRecord.setPerinatalType(request.getPerinatalType());
        }
        if (request.getNotes() != null) {
            deathRecord.setNotes(request.getNotes());
        }

        // Re-check the pairing AFTER the edits: an amendment that turns a death
        // maternal without saying when would break the reporting query.
        if (Boolean.TRUE.equals(deathRecord.getMaternalDeath())
            && deathRecord.getMaternalDeathTiming() == null) {
            throw new com.example.hms.exception.FieldBusinessException("maternalDeathTiming", "mortality.maternalTimingRequired");
        }
        if (Boolean.TRUE.equals(deathRecord.getPerinatalDeath()) && deathRecord.getPerinatalType() == null) {
            throw new com.example.hms.exception.FieldBusinessException("perinatalType", "mortality.perinatalTypeRequired");
        }

        deathRecord.setAmendedAt(LocalDateTime.now(clock));
        deathRecord.setAmendmentReason(request.getAmendmentReason());

        return mapper.toDto(deathRecordRepository.save(deathRecord));
    }

    @Override
    public DeathRecordResponseDTO markEnteredInError(UUID recordId, DeathRecordEnteredInErrorDTO request) {
        String reason = request == null || request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "mortality.enteredInError.reasonRequired");
        }
        if (reason.length() > MAX_ERROR_REASON_LENGTH) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "mortality.enteredInError.reasonTooLong",
                MAX_ERROR_REASON_LENGTH);
        }
        // Disputes and outcomes take turns on the record: a dispute committed meanwhile is ended below.
        deathRecordRepository.lockById(recordId);
        DeathRecord deathRecord = loadScoped(recordId);
        if (deathRecord.isEnteredInError()) {
            throw new BusinessException("mortality.enteredInError.closed");
        }
        UUID actorId = roleValidator.getCurrentUserId();
        // The controller's role check reads the union of the caller's assignments: an administrator of
        // hospital A who is a nurse at B, acting at B, must not reach B's records through it. A verified
        // super-admin may, in global view or with the record's hospital selected.
        if (!com.example.hms.security.CallerIdentity.isSuperAdminFor(deathRecord.getHospital().getId())
                && !roleValidator.isHospitalAdmin(actorId, deathRecord.getHospital().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Only a hospital administrator of this hospital can mark a death record entered in error.");
        }
        String actorName = currentUsername();
        deathRecord.setEnteredInErrorAt(LocalDateTime.now(clock));
        deathRecord.setEnteredInErrorById(actorId);
        deathRecord.setEnteredInErrorByName(actorName);
        deathRecord.setEnteredInErrorReason(reason);
        DeathRecord saved = deathRecordRepository.save(deathRecord);

        Patient patient = deathRecord.getPatient();
        patient.setDeceasedAt(null);
        patientRepository.save(patient);

        List<com.example.hms.model.DeathDispute> open = disputeRepository.lockOpenForRecord(recordId);
        DeathDisputeServiceImpl.closeAll(open, inBasketService, actorId, actorName, reason, deathRecord.getEnteredInErrorAt());
        // The holds stand and stay visible; none stays bound to a dispute now closed.
        open.forEach(dispute -> orderReview.disputeClosed(dispute.getId()));

        // QA D1516: nothing the death closed is reopened (#1068), but none of it stays marked as a death, and a
        // clinician is told which stays, visits and appointments to readmit or rebook.
        VoidedClosures closures = settleWhatTheDeathClosed(saved);
        fileClosureReview(saved, patient, closures);

        auditEnteredInError(saved, patient.getId(), actorId, actorName, reason, closures);
        return mapper.toDto(saved);
    }

    /** What a voided death had closed at its hospital, as it reads now. */
    record VoidedClosures(List<Admission> stays, List<Encounter> visits, List<Appointment> appointments) {
        boolean isEmpty() {
            return stays.isEmpty() && visits.isEmpty() && appointments.isEmpty();
        }
    }

    /**
     * QA D1516: the stays the death left DECEASED become {@link AdmissionStatus#CLOSED_IN_ERROR}, ended when the
     * closure ran (the record's creation), and the appointments it cancelled say the death was an error. The visits
     * stay completed: a completed visit says nothing about a death. Nothing is reopened.
     */
    private VoidedClosures settleWhatTheDeathClosed(DeathRecord deathRecord) {
        UUID hospitalId = deathRecord.getHospital() != null ? deathRecord.getHospital().getId() : null;
        LocalDateTime closedAt = deathRecord.getCreatedAt() != null ? deathRecord.getCreatedAt() : deathRecord.getEnteredInErrorAt();
        List<Admission> stays = closedStays(deathRecord, hospitalId, Set.of(AdmissionStatus.DECEASED));
        for (Admission stay : stays) {
            stay.setStatus(AdmissionStatus.CLOSED_IN_ERROR);
            if (stay.getActualDischargeDateTime() == null) {
                stay.setActualDischargeDateTime(closedAt);
                stay.calculateLengthOfStay();
            }
            admissionRepository.save(stay);
        }
        List<Appointment> appointments = closedAppointments(deathRecord, hospitalId);
        if (!appointments.isEmpty()) {
            String note = com.example.hms.utility.MessageUtil.resolve(
                com.example.hms.service.i18n.NotificationLocales.STAFF, VOIDED_APPOINTMENT_NOTE_KEY);
            for (Appointment appointment : appointments) {
                appointment.setNotes(StringUtils.hasText(appointment.getNotes()) ? appointment.getNotes() + " — " + note : note);
                appointmentRepository.save(appointment);
            }
        }
        return new VoidedClosures(stays, closedVisits(deathRecord, hospitalId), appointments);
    }

    /** The ids the record kept of one kind; empty for a row written before V281, which kept none. */
    private static java.util.Optional<List<UUID>> recordedIds(DeathRecord deathRecord, String kind) {
        java.util.Map<String, List<String>> closed = deathRecord.getClosedRecords();
        if (closed == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(closed.getOrDefault(kind, List.of()).stream().map(UUID::fromString).toList());
    }

    /** A row from before V281 names none: the stays still DECEASED at its hospital are the ones it closed. */
    private List<Admission> closedStays(DeathRecord deathRecord, UUID hospitalId, Set<AdmissionStatus> statuses) {
        List<Admission> candidates = recordedIds(deathRecord, DeathRecord.CLOSED_ADMISSIONS)
            .map(ids -> ids.isEmpty() ? List.<Admission>of() : admissionRepository.findAllById(ids))
            .orElseGet(() -> legacyGuessIsSafe(deathRecord)
                ? admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(deathRecord.getPatient().getId())
                : List.of());
        return candidates.stream()
            .filter(stay -> inScope(stay.getHospital(), hospitalId) && statuses.contains(stay.getStatus()))
            .toList();
    }

    /**
     * A row from before V281 names nothing, so what it closed is read off the records' marks (DECEASED, the closure
     * note). Only while the patient has no live death: a real death recorded later leaves the same marks, and its
     * closures must never be read as the voided one's ("readmit or rebook" for a patient who has died).
     */
    private boolean legacyGuessIsSafe(DeathRecord voided) {
        return voided.getPatient() != null && deathRecordRepository.findByPatient_Id(voided.getPatient().getId())
            .filter(live -> !Objects.equals(live.getId(), voided.getId()))
            .isEmpty();
    }

    /** A row from before V281 kept only the first visit it closed. */
    private List<Encounter> closedVisits(DeathRecord deathRecord, UUID hospitalId) {
        List<UUID> ids = recordedIds(deathRecord, DeathRecord.CLOSED_ENCOUNTERS)
            .orElseGet(() -> deathRecord.getEncounterId() != null ? List.of(deathRecord.getEncounterId()) : List.of());
        return ids.isEmpty() ? List.of() : encounterRepository.findAllById(ids).stream()
            .filter(visit -> inScope(visit.getHospital(), hospitalId))
            .toList();
    }

    /** A row from before V281: the cancelled appointments after the death that carry its closure note. */
    private List<Appointment> closedAppointments(DeathRecord deathRecord, UUID hospitalId) {
        List<Appointment> candidates = recordedIds(deathRecord, DeathRecord.CLOSED_APPOINTMENTS)
            .map(ids -> ids.isEmpty() ? List.<Appointment>of() : appointmentRepository.findAllById(ids))
            .orElseGet(() -> !legacyGuessIsSafe(deathRecord) ? List.of()
                : appointmentRepository.findByPatient_IdAndAppointmentDateAfter(deathRecord.getPatient().getId(),
                    deathRecord.getDiedAt().toLocalDate()).stream()
                .filter(a -> a.getNotes() != null && a.getNotes().contains(CLOSURE_REASON))
                .toList());
        return candidates.stream()
            .filter(a -> inScope(a.getHospital(), hospitalId) && a.getStatus() == AppointmentStatus.CANCELLED)
            .toList();
    }

    /**
     * QA D1516: one urgent item at the death's hospital, to the attending of each stay it closed (its admitting
     * doctor when none is named), else to the hospital's doctors, naming what was closed. Filed after commit in a
     * transaction of its own: a failure to file never undoes the entered-in-error. Ids and text only cross the commit.
     */
    private void fileClosureReview(DeathRecord deathRecord, Patient patient, VoidedClosures closures) {
        if (closures.isEmpty() || notices == null || deathRecord.getHospital() == null) {
            return;
        }
        java.util.Set<UUID> attendings = new java.util.LinkedHashSet<>();
        for (Admission stay : closures.stays()) {
            Staff attending = stay.getAttendingPhysician() != null ? stay.getAttendingPhysician() : stay.getAdmittingProvider();
            if (attending != null && attending.isActive() && attending.getUser() != null) {
                attendings.add(attending.getUser().getId());
            }
        }
        String patientName = patient.getFullName();
        Object[] args = {patientName,
            listOf(closures.stays().stream().map(s -> com.example.hms.utility.DisplayDates.dateTime(s.getAdmissionDateTime())).toList()),
            listOf(closures.visits().stream().map(v -> com.example.hms.utility.DisplayDates.dateTime(v.getEncounterDate())).toList()),
            listOf(closures.appointments().stream().map(a -> com.example.hms.utility.DisplayDates.date(a.getAppointmentDate())).toList())};
        OrderReviewAfterCommit.Notice notice = new OrderReviewAfterCommit.Notice(deathRecord.getHospital().getId(),
            patient.getId(), patientName,
            UUID.nameUUIDFromBytes((deathRecord.getId() + "@closures").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            VOIDED_CLOSURE_REFERENCE_TYPE, VOIDED_CLOSURE_TITLE_KEY, VOIDED_CLOSURE_BODY_KEY, args, attendings);
        OrderReviewAfterCommit filer = notices;
        UUID recordId = deathRecord.getId();
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                filer.file(notice);
            } catch (RuntimeException ex) {
                log.error("Review of what death record {} closed was not filed; the entered-in-error stands: {}",
                    recordId, ex.getMessage());
            }
        });
    }

    /** "none" reads as a dash, so the sentence keeps its shape. */
    private static String listOf(List<String> items) {
        return items.isEmpty() ? "—" : String.join(", ", items);
    }

    /** Ids only: the audit service writes in its own transaction, with no persistence context of this one. */
    private void auditEnteredInError(DeathRecord record, UUID patientId, UUID actorId, String actorName, String reason,
                                     VoidedClosures closures) {
        if (auditEventLogService == null) {
            return;
        }
        try {
            auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                .eventType(com.example.hms.enums.AuditEventType.PATIENT_UPDATE)
                .status(com.example.hms.enums.AuditStatus.SUCCESS)
                .entityType("DEATH_RECORD")
                .resourceId(record.getId().toString())
                .patientId(patientId)
                .hospitalId(record.getHospital() != null ? record.getHospital().getId() : null)
                .userId(actorId)
                .userName(actorName)
                .eventDescription("Death record " + record.getId() + " marked entered in error; the patient is no "
                    + "longer recorded as deceased. Stays, visits and appointments the death closed were not "
                    + "reopened: " + closures.stays().size() + " stay(s) marked closed in error, "
                    + closures.visits().size() + " visit(s), " + closures.appointments().size()
                    + " appointment(s); " + (closures.isEmpty() ? "nothing to review" : "an urgent review was filed")
                    + ". Reason: " + reason)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to audit death record {} entered in error: {}", record.getId(), ex.getMessage());
        }
    }

    private static String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public DeathRecordResponseDTO getForPatient(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        // A patient registered at two hospitals: the record stays with the one that wrote it.
        return deathRecordRepository.findByPatient_Id(patientId)
            .filter(death -> death.getHospital() != null && hospitalId.equals(death.getHospital().getId()))
            .map(mapper::toDto)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, patientId));
    }

    /**
     * QA D1516: for the chart, what each death of this patient marked entered in error at the acting hospital had
     * closed. A stay is shown CLOSED_IN_ERROR, or still DECEASED when its death was voided before this was settled
     * (the record then flags it too). Empty when nothing was closed.
     */
    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO> getVoidedClosures(UUID patientId) {
        UUID hospitalId = requireHospital();
        patientChartAccess.require(patientId, hospitalId);
        java.util.List<com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO> out = new java.util.ArrayList<>();
        for (DeathRecord voided : deathRecordRepository.findEnteredInErrorForPatient(patientId, hospitalId)) {
            List<Admission> stays = closedStays(voided, hospitalId,
                Set.of(AdmissionStatus.CLOSED_IN_ERROR, AdmissionStatus.DECEASED));
            List<Encounter> visits = closedVisits(voided, hospitalId);
            List<Appointment> appointments = closedAppointments(voided, hospitalId);
            if (stays.isEmpty() && visits.isEmpty() && appointments.isEmpty()) {
                continue;
            }
            out.add(new com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO(voided.getId(), voided.getEnteredInErrorAt(),
                stays.stream().map(s -> new com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO.Stay(
                    s.getId(), s.getAdmissionDateTime(), s.getActualDischargeDateTime())).toList(),
                visits.stream().map(v -> new com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO.Visit(
                    v.getId(), v.getEncounterDate())).toList(),
                appointments.stream().map(a -> new com.example.hms.payload.dto.mortality.VoidedDeathClosuresDTO.Booking(
                    a.getId(), a.getAppointmentDate())).toList()));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public MortalityRegisterDTO getRegister(LocalDate from, LocalDate to) {
        UUID hospitalId = requireHospital();
        if (from == null || to == null) {
            throw new com.example.hms.exception.FieldBusinessException("from", "mortality.periodRequired");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("mortality.periodReversed");
        }
        // Half-open on the upper bound so a death at 23:59 on the last day is
        // inside the period rather than silently dropped.
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();

        List<DeathRecord> all = deathRecordRepository.findRegister(hospitalId, start, end);

        int maternal = (int) all.stream().filter(DeathRecord::isWhoMaternalDeath).count();
        int lateMaternal = (int) all.stream()
            .filter(d -> Boolean.TRUE.equals(d.getMaternalDeath())
                && d.getMaternalDeathTiming() == MaternalDeathTiming.LATE_MATERNAL)
            .count();
        // QA D1714: the stillbirths, from the fetal-death events and the death records no event holds, each counted
        // once. Perinatal = the WHO stillbirths plus the early neonatal deaths (days 0-6), read off each death
        // record's own definition (CR-07). A late neonatal death is reported, but not in this figure.
        Stillbirths stillbirths = stillbirthsOf(hospitalId, start, end, all);
        int perinatal = stillbirths.who() + (int) all.stream()
            .filter(d -> ofPerinatalType(d) == PerinatalDeathType.EARLY_NEONATAL)
            .filter(DeathRecord::isInWhoPerinatalNumerator)
            .count();
        int eligibilityUnknown = (int) all.stream()
            .filter(d -> ofPerinatalType(d) == PerinatalDeathType.EARLY_NEONATAL
                && d.getPerinatalEligibility() == PerinatalReportingEligibility.UNKNOWN).count();
        int belowGestation = (int) all.stream()
            .filter(d -> ofPerinatalType(d) == PerinatalDeathType.EARLY_NEONATAL && d.isBelowWhoPerinatalGestation())
            .count();

        // A disputed death stays listed and counted until its outcome, flagged, with the count shown.
        java.util.Set<UUID> disputed = all.isEmpty() ? Set.of() : new java.util.HashSet<>(
            disputeRepository.findOpenRecordIds(all.stream().map(DeathRecord::getId).toList()));

        return MortalityRegisterDTO.builder()
            .disputedDeaths(disputed.size())
            .from(from)
            .to(to)
            .totalDeaths(all.size())
            .deathsAfterLiveBirth((int) all.stream()
                .filter(d -> ofPerinatalType(d) != PerinatalDeathType.STILLBIRTH).count())
            .maternalDeaths(maternal)
            .lateMaternalDeaths(lateMaternal)
            .perinatalDeaths(perinatal)
            .perinatalEligibilityUnknown(eligibilityUnknown)
            .perinatalBelowGestation(belowGestation)
            .stillbirths(stillbirths.rows().size())
            .stillbirthsWho(stillbirths.who())
            .stillbirthsIcd(stillbirths.icd())
            .stillbirthsClassificationIncomplete(stillbirths.incomplete())
            .whoStillbirthMinWeeks(StillbirthClassification.WHO_STILLBIRTH_MIN_COMPLETED_WEEKS)
            .icdFetalDeathMinWeeks(StillbirthClassification.ICD_FETAL_DEATH_MIN_COMPLETED_WEEKS)
            .stillbirthRows(stillbirths.rows())
            .earlyNeonatalDeaths(countOfType(all, PerinatalDeathType.EARLY_NEONATAL))
            .lateNeonatalDeaths(countOfType(all, PerinatalDeathType.LATE_NEONATAL))
            .deaths(all.stream().map(death -> {
                DeathRecordResponseDTO dto = mapper.toDto(death);
                dto.setDisputed(disputed.contains(death.getId()));
                return dto;
            }).toList())
            .enteredInError(deathRecordRepository.findEnteredInError(hospitalId, start, end).stream()
                .map(mapper::toDto).toList())
            .build();
    }

    // ── Stillbirths (QA D1714) ──────────────────────────────────────────

    /** The stillbirths of a period and their named classifications. */
    private record Stillbirths(List<FetalDeathEventDTO> rows, int who, int icd, int incomplete) {
    }

    /**
     * QA D1714 (user decision 2026-11-13): the fetal-death events of the period, plus the stillbirth death records no
     * live event holds. A death record that declares an event is counted once, through the event. Each row is
     * classified by StillbirthClassification; a missing gestation is counted as incomplete, never dropped.
     */
    private Stillbirths stillbirthsOf(UUID hospitalId, LocalDateTime start, LocalDateTime end, List<DeathRecord> deaths) {
        List<DeathRecord> declared = deaths.stream()
            .filter(d -> ofPerinatalType(d) == PerinatalDeathType.STILLBIRTH)
            .toList();
        java.util.Map<UUID, UUID> declarationOfEvent = new java.util.HashMap<>();
        declared.stream().filter(d -> d.getFetalDeathEventId() != null)
            .forEach(d -> declarationOfEvent.putIfAbsent(d.getFetalDeathEventId(), d.getId()));
        List<FetalDeathEventDTO> rows = new java.util.ArrayList<>();
        for (FetalDeathEvent event : fetalDeathEventRepository.findRegister(hospitalId, start, end)) {
            StillbirthClassification.Result result =
                StillbirthClassification.classify(event.getGestationalAgeWeeks(), event.isNoSignsOfLife());
            Patient mother = event.getMotherPatient();
            rows.add(FetalDeathEventDTO.builder()
                .id(event.getId())
                .deliveryRecordId(event.getDeliveryRecord() == null ? null : event.getDeliveryRecord().getId())
                .motherPatientId(mother == null ? null : mother.getId())
                .motherName(mother == null ? null : mother.getFullName())
                .birthOrder(event.getBirthOrder())
                .occurredAt(event.getOccurredAt())
                .gestationalAgeWeeks(event.getGestationalAgeWeeks())
                .noSignsOfLife(event.isNoSignsOfLife())
                .source(event.getSource())
                .recordedByName(event.getRecordedBy() == null ? null : event.getRecordedBy().getUsername())
                .whoStillbirth(result.whoStillbirth())
                .icdFetalDeath(result.icdFetalDeath())
                .classificationIncomplete(result.incomplete())
                .declaredByDeathRecordId(declarationOfEvent.get(event.getId()))
                .build());
        }
        List<UUID> linked = declared.stream().map(DeathRecord::getFetalDeathEventId).filter(Objects::nonNull).toList();
        Set<UUID> heldByAnEvent = linked.isEmpty() ? Set.of()
            : new java.util.HashSet<>(fetalDeathEventRepository.findLiveIdsAmong(linked));
        for (DeathRecord death : declared) {
            if (death.getFetalDeathEventId() != null && heldByAnEvent.contains(death.getFetalDeathEventId())) {
                continue;
            }
            StillbirthClassification.Result result = StillbirthClassification.classify(death.getGestationalAgeWeeks(), true);
            Patient patient = death.getPatient();
            rows.add(FetalDeathEventDTO.builder()
                .deathRecordId(death.getId())
                .motherName(patient == null ? null : patient.getFullName())
                .occurredAt(death.getDiedAt())
                .gestationalAgeWeeks(death.getGestationalAgeWeeks())
                .noSignsOfLife(true)
                .whoStillbirth(result.whoStillbirth())
                .icdFetalDeath(result.icdFetalDeath())
                .classificationIncomplete(result.incomplete())
                .build());
        }
        return new Stillbirths(rows,
            (int) rows.stream().filter(FetalDeathEventDTO::isWhoStillbirth).count(),
            (int) rows.stream().filter(FetalDeathEventDTO::isIcdFetalDeath).count(),
            (int) rows.stream().filter(FetalDeathEventDTO::isClassificationIncomplete).count());
    }

    /**
     * QA D1714: the fetal-death event a stillbirth death record declares, so the register counts it once. Named on
     * the request, it must be a live event of the acting hospital. Otherwise a baby registered from a delivery links
     * to its own live event, if it has one. Only a stillbirth classification links.
     */
    private UUID linkedFetalDeathEvent(Patient patient, UUID hospitalId, DeathRecordRequestDTO request) {
        if (!Boolean.TRUE.equals(request.getPerinatalDeath()) || request.getPerinatalType() != PerinatalDeathType.STILLBIRTH) {
            if (request.getFetalDeathEventId() != null) {
                throw new com.example.hms.exception.FieldBusinessException("fetalDeathEventId",
                    "mortality.fetalDeathEvent.stillbirthOnly");
            }
            return null;
        }
        if (request.getFetalDeathEventId() != null) {
            FetalDeathEvent event = fetalDeathEventRepository.findByIdAndHospital_Id(request.getFetalDeathEventId(), hospitalId)
                .filter(found -> !found.isSuperseded())
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException("fetalDeathEventId",
                    "mortality.fetalDeathEvent.notFound"));
            return event.getId();
        }
        return deliveryNewbornRepository.findByNewbornPatient_Id(patient.getId())
            .filter(link -> link.getHospital() != null && hospitalId.equals(link.getHospital().getId()))
            .flatMap(link -> fetalDeathEventRepository.findFirstByDeliveryRecord_IdAndBirthOrderAndSupersededAtIsNull(
                link.getDeliveryRecord().getId(), link.getBirthOrder()))
            .map(FetalDeathEvent::getId)
            .orElse(null);
    }

    // ── The cascade ─────────────────────────────────────────────────────

    /**
     * Close everything that would otherwise keep running against a person who
     * has died.
     *
     * <p>Each of these is a real failure mode, not tidiness — see the individual
     * methods for what each one would otherwise cost. Every count is reported
     * back to the caller rather than closed silently.
     */
    private DeathClosureSummaryDTO closeOpenRecords(Patient patient, UUID hospitalId, DeathRecord deathRecord) {
        // QA D1516: what was closed is kept on the record, so entered in error can flag it and name it.
        java.util.Map<String, List<String>> closed = new java.util.LinkedHashMap<>();
        DeathClosureSummaryDTO summary = DeathClosureSummaryDTO.builder()
            .admissionsClosed(closeAdmissions(patient, hospitalId, deathRecord, ids(closed, DeathRecord.CLOSED_ADMISSIONS)))
            .encountersClosed(closeEncounters(patient, hospitalId, deathRecord, ids(closed, DeathRecord.CLOSED_ENCOUNTERS)))
            .appointmentsCancelled(cancelFutureAppointments(patient, hospitalId, deathRecord,
                ids(closed, DeathRecord.CLOSED_APPOINTMENTS)))
            .recallsClosed(closeRecalls(patient, hospitalId))
            .build();
        deathRecord.setClosedRecords(closed);
        return summary;
    }

    private static List<String> ids(java.util.Map<String, List<String>> closed, String kind) {
        return closed.computeIfAbsent(kind, k -> new java.util.ArrayList<>());
    }

    /** An open admission holds a bed and keeps the patient on the ward board. */
    private int closeAdmissions(Patient patient, UUID hospitalId, DeathRecord deathRecord, List<String> closedIds) {
        int closed = 0;
        for (Admission admission : admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patient.getId())) {
            if (!inScope(admission.getHospital(), hospitalId) || !OPEN_ADMISSIONS.contains(admission.getStatus())) {
                continue;
            }
            // AdmissionStatus.DECEASED has existed since V1 and nothing has ever
            // written it — the MLLP visit projection only reads it as an
            // exclusion. This is its first writer.
            admission.setStatus(AdmissionStatus.DECEASED);
            // QA D515 (#922 review): death closes the stay like any discharge —
            // the bed is freed and a pending transfer is cancelled with its
            // reserved destination bed handed back.
            bedAssignmentService.releaseBed(admission);
            admissionRepository.save(admission);
            // QA D1435: and no nurse keeps a task or a handoff for the patient.
            if (stayEncounters != null) {
                stayEncounters.closeNursingWorkOfEndedStay(admission);
            }
            if (deathRecord.getAdmissionId() == null) {
                deathRecord.setAdmissionId(admission.getId());
            }
            closedIds.add(String.valueOf(admission.getId()));
            closed++;
        }
        return closed;
    }

    /** An encounter left open keeps the patient on a worklist someone will work. */
    private int closeEncounters(Patient patient, UUID hospitalId, DeathRecord deathRecord, List<String> closedIds) {
        int closed = 0;
        for (Encounter encounter : encounterRepository.findByPatient_Id(patient.getId())) {
            if (!inScope(encounter.getHospital(), hospitalId) || !OPEN_ENCOUNTERS.contains(encounter.getStatus())) {
                continue;
            }
            encounter.setStatus(EncounterStatus.COMPLETED);
            if (encounter.getCheckoutTimestamp() == null) {
                encounter.setCheckoutTimestamp(LocalDateTime.now(clock));
            }
            encounterRepository.save(encounter);
            // QA D972 (round 18): the visit is billed like any completed one;
            // the family settles it as in any hospital.
            if (eventPublisher != null) {
                eventPublisher.publishEvent(
                    com.example.hms.event.EncounterCheckedOutEvent.of(encounter, encounter.getCheckoutTimestamp()));
            }
            if (deathRecord.getEncounterId() == null) {
                deathRecord.setEncounterId(encounter.getId());
            }
            if (unsignedNotes != null) {
                unsignedNotes.visitClosed(encounter);
            }
            closedIds.add(String.valueOf(encounter.getId()));
            closed++;
        }
        return closed;
    }

    /**
     * A live appointment is a slot nobody will use and a reminder to a bereaved
     * family. Only FUTURE ones are cancelled — a past appointment the patient
     * actually attended is history, and rewriting it would falsify the record.
     */
    private int cancelFutureAppointments(Patient patient, UUID hospitalId, DeathRecord deathRecord, List<String> closedIds) {
        int cancelled = 0;
        LocalDate diedOn = deathRecord.getDiedAt().toLocalDate();
        for (Appointment appointment
            : appointmentRepository.findByPatient_IdAndAppointmentDateAfter(patient.getId(), diedOn)) {
            if (!inScope(appointment.getHospital(), hospitalId)
                || !LIVE_APPOINTMENTS.contains(appointment.getStatus())) {
                continue;
            }
            appointment.setStatus(AppointmentStatus.CANCELLED);
            appointment.setNotes(appendReason(appointment.getNotes()));
            appointmentRepository.save(appointment);
            closedIds.add(String.valueOf(appointment.getId()));
            cancelled++;
        }
        return cancelled;
    }

    /** An open recall is a follow-up chase for someone who cannot be chased. */
    private int closeRecalls(Patient patient, UUID hospitalId) {
        int closed = 0;
        for (PatientRecall recall : recallRepository.findByPatient_IdAndStatusIn(patient.getId(), OPEN_RECALLS)) {
            if (!inScope(recall.getHospital(), hospitalId)) {
                continue;
            }
            recall.setStatus(RecallStatus.CANCELLED);
            recallRepository.save(recall);
            closed++;
        }
        return closed;
    }

    private String appendReason(String existing) {
        return StringUtils.hasText(existing) ? existing + " — " + CLOSURE_REASON : CLOSURE_REASON;
    }

    // ── Perinatal classification ────────────────────────────────────────

    /** The facts offered for, or found for, a perinatal classification. */
    private record BirthFacts(LocalDateTime bornAt, PerinatalBirthStatus status, Integer gestationWeeks,
                              PerinatalAgeBasis ageBasis) {

        BirthFacts(LocalDateTime bornAt, PerinatalBirthStatus status, Integer gestationWeeks) {
            this(bornAt, status == null ? PerinatalBirthStatus.UNKNOWN : status, gestationWeeks, PerinatalAgeBasis.UNKNOWN);
        }

        /** What was stated stands. A delivery on file only answers what was left unknown. */
        BirthFacts answeredBy(DeliveryRecord delivery) {
            if (delivery == null) {
                return this;
            }
            PerinatalBirthStatus onFile = delivery.isLiveBirth()
                ? PerinatalBirthStatus.LIVE_BIRTH : PerinatalBirthStatus.STILLBIRTH;
            return new BirthFacts(
                bornAt != null || !delivery.isBirthTimeStated() ? bornAt : delivery.getBirthDateTime(),
                status != PerinatalBirthStatus.UNKNOWN ? status : onFile,
                gestationWeeks != null ? gestationWeeks : deliveryGestation(delivery),
                ageBasis);
        }

        BirthFacts withAgeBasis(PerinatalAgeBasis basis) {
            return new BirthFacts(bornAt, status, gestationWeeks, basis);
        }

        private static Integer deliveryGestation(DeliveryRecord delivery) {
            Integer weeks = delivery.getGestationalAgeWeeksAtBirth();
            return weeks != null && DeathClassificationRules.isPlausibleGestation(weeks) ? weeks : null;
        }
    }

    /**
     * With no type selected nothing here refuses, by the user's ruling: the death saves, and a fact that cannot be
     * true is simply not kept. With a type, a fact is refused on its own field and the type is judged. The answer is
     * kept on the record, so the register reads it without one more query per row.
     */
    private BirthFacts judgeBirthFacts(Patient patient, UUID hospitalId, PerinatalDeathType type,
                                       LocalDateTime diedAt, BirthFacts stated) {
        if (type == null) {
            return new BirthFacts(
                stated.bornAt() != null && DeathClassificationRules.birthTimeFits(patient, stated.bornAt(), diedAt)
                    ? stated.bornAt() : null,
                stated.status(),
                stated.gestationWeeks() != null && DeathClassificationRules.isPlausibleGestation(stated.gestationWeeks())
                    ? stated.gestationWeeks() : null);
        }
        DeathClassificationRules.requireBirthFactsPossible(patient, stated.bornAt(), stated.gestationWeeks(), diedAt);
        BirthFacts known = stated.answeredBy(singleBirthOnFile(patient, hospitalId, diedAt));
        return known.withAgeBasis(DeathClassificationRules.requirePerinatalPossible(
            patient, type, diedAt, known.bornAt(), known.status()));
    }

    /**
     * The birth on record for this chart: the delivery its newborn assessments link to, as the growth chart reads it.
     * A delivery of several infants holds one time and one outcome, so it does not speak for this one. The date of
     * birth must fit, which also keeps a mother's own delivery from being read as her birth. A delivery documented
     * with its time left blank holds the moment of documenting, so its time is not used as the birth's.
     */
    private DeliveryRecord singleBirthOnFile(Patient patient, UUID hospitalId, LocalDateTime diedAt) {
        if (patient.getDateOfBirth() == null) {
            return null;
        }
        return newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patient.getId())
            .filter(assessment -> inScope(assessment.getHospital(), hospitalId))
            .map(NewbornAssessment::getDeliveryRecord)
            .filter(delivery -> delivery.getNumberOfInfants() == 1)
            .filter(delivery -> delivery.getBirthDateTime() != null
                && DeathClassificationRules.birthTimeFits(patient, delivery.getBirthDateTime(), diedAt))
            .orElse(null);
    }

    /**
     * An amendment reaches the same classification, and only what it sets is judged: an autopsy's new cause is never
     * refused over an older row's flags, and a gestation alone says nothing about the type.
     */
    private void amendBirthFacts(DeathRecord deathRecord, DeathRecordAmendmentDTO request) {
        boolean typeOrTimingSet = request.getPerinatalDeath() != null
            || request.getBornAt() != null || request.getBirthStatus() != null;
        if (!typeOrTimingSet && request.getGestationalAgeWeeks() == null) {
            return;
        }
        Patient patient = deathRecord.getPatient();
        LocalDateTime diedAt = deathRecord.getDiedAt();
        BirthFacts stated = new BirthFacts(
            request.getBornAt() != null ? request.getBornAt() : deathRecord.getBornAt(),
            request.getBirthStatus() != null ? request.getBirthStatus() : deathRecord.getBirthStatus(),
            request.getGestationalAgeWeeks() != null ? request.getGestationalAgeWeeks() : deathRecord.getGestationalAgeWeeks());
        BirthFacts judged;
        if (typeOrTimingSet) {
            judged = judgeBirthFacts(patient, deathRecord.getHospital().getId(), amendedPerinatalType(deathRecord, request),
                diedAt, stated);
        } else {
            DeathClassificationRules.requireBirthFactsPossible(patient, null, stated.gestationWeeks(), diedAt);
            judged = stated.withAgeBasis(deathRecord.getPerinatalAgeBasis());
        }
        deathRecord.setBornAt(judged.bornAt());
        deathRecord.setBirthStatus(judged.status());
        deathRecord.setGestationalAgeWeeks(judged.gestationWeeks());
        deathRecord.setPerinatalAgeBasis(judged.ageBasis());
    }

    private static PerinatalDeathType amendedPerinatalType(DeathRecord deathRecord, DeathRecordAmendmentDTO request) {
        if (request.getPerinatalDeath() != null) {
            return Boolean.TRUE.equals(request.getPerinatalDeath()) ? request.getPerinatalType() : null;
        }
        return ofPerinatalType(deathRecord);
    }

    /** The type this death is classified as; null when the flag is off, whatever the column holds. */
    private static PerinatalDeathType ofPerinatalType(DeathRecord deathRecord) {
        return Boolean.TRUE.equals(deathRecord.getPerinatalDeath()) ? deathRecord.getPerinatalType() : null;
    }

    private static int countOfType(List<DeathRecord> deaths, PerinatalDeathType type) {
        return (int) deaths.stream().filter(d -> ofPerinatalType(d) == type).count();
    }

    // ── Guards and helpers ──────────────────────────────────────────────

    private void validateMortalityFlags(DeathRecordRequestDTO request) {
        if (Boolean.TRUE.equals(request.getMaternalDeath()) && request.getMaternalDeathTiming() == null) {
            throw new com.example.hms.exception.FieldBusinessException("maternalDeathTiming", "mortality.maternalTimingRequiredWho");
        }
        if (Boolean.TRUE.equals(request.getPerinatalDeath()) && request.getPerinatalType() == null) {
            throw new com.example.hms.exception.FieldBusinessException("perinatalType", "mortality.perinatalTypeRequiredWhy");
        }
    }

    /** 404-not-403 — a record at another hospital is indistinguishable from a missing one. */
    private DeathRecord loadScoped(UUID recordId) {
        DeathRecord deathRecord = deathRecordRepository.findById(recordId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, recordId));
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && deathRecord.getHospital() != null
            && !scope.equals(deathRecord.getHospital().getId())) {
            throw new ResourceNotFoundException(MSG_DEATH_NOT_FOUND, recordId);
        }
        return deathRecord;
    }

    /**
     * A patient linked to several facilities can have open records at each. This
     * cascade only closes what belongs to the recording hospital — reaching into
     * another facility's ward board would be acting outside the caller's scope.
     */
    private boolean inScope(Hospital hospital, UUID hospitalId) {
        return hospital == null || Objects.equals(hospital.getId(), hospitalId);
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("mortality.hospitalRequired");
        }
        return hospitalId;
    }

    private Hospital hospitalRef(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private Staff resolveCertifier(UUID staffId, UUID hospitalId) {
        if (staffId == null) {
            return null;
        }
        return staffRepository.findById(staffId)
            .filter(s -> s.getHospital() == null || Objects.equals(s.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
    }

    private Staff currentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || hospitalId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }
}
