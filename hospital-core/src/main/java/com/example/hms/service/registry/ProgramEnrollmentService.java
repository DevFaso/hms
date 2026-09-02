package com.example.hms.service.registry;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.enums.RecallStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ProgramEnrollmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentRequestDTO;
import com.example.hms.payload.dto.registry.ProgramEnrollmentResponseDTO;
import com.example.hms.payload.dto.registry.ProgramStatusUpdateDTO;
import com.example.hms.payload.dto.registry.ProgramVisitDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disease-programme registries (Tier 2 item 35).
 *
 * <p>The registry answers "who is in the HIV programme here, and who among
 * them is overdue" — the question the paper register answers today and the
 * question item 36's defaulter tracing automates next. This service owns the
 * enrolment lifecycle; it deliberately does NOT own outreach (that is item
 * 36, on {@code PatientOutreachNotifier}'s existing transport) and does NOT
 * decide visit cadence (typed in by the clinician — see {@link CareProgram}
 * for why the server refuses to have an opinion).
 *
 * <p>Tenancy: 404-not-403 throughout (#483's stance). A patient not
 * registered at the caller's hospital is indistinguishable from one that
 * does not exist, and so is another hospital's enrolment row.
 *
 * <p>Every successful write emits a best-effort audit event keyed by
 * patient. Best-effort as everywhere else: an audit failure must never
 * undo a recorded enrolment — but "best-effort" is about the emission, not
 * the intent; the request log is not an actor/resource trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgramEnrollmentService {

    /** Registry page-size ceiling: a cohort read is a page, never a dump. */
    static final int MAX_REGISTRY_PAGE = 200;

    /** The tracing-recall states a lifecycle change still owns. */
    private static final java.util.Set<RecallStatus> OPEN_RECALL_STATUSES =
        java.util.Set.of(RecallStatus.PENDING, RecallStatus.NOTIFIED, RecallStatus.SCHEDULED);

    private final ProgramEnrollmentRepository enrollmentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PatientRecallRepository recallRepository;
    private final RoleValidator roleValidator;
    private final ProgramEnrollmentMapper mapper;
    private final AuditEventLogService auditService;
    private final Clock clock;

    @Transactional
    public ProgramEnrollmentResponseDTO enroll(UUID patientId, ProgramEnrollmentRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);

        enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
                patientId, hospitalId, request.getProgram(), ProgramEnrollmentStatus.ACTIVE)
            .ifPresent(existing -> {
                throw new BusinessException(
                    "The patient is already enrolled in this programme. Close the existing "
                        + "enrolment first if this is a new episode.");
            });

        LocalDate enrolledOn = request.getEnrolledOn() != null
            ? request.getEnrolledOn() : LocalDate.now(clock);

        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        Staff enrolledBy = resolveCurrentStaff(hospitalId);

        ProgramEnrollment saved = enrollmentRepository.save(ProgramEnrollment.builder()
            .patient(patient)
            .hospital(hospital)
            .program(request.getProgram())
            .status(ProgramEnrollmentStatus.ACTIVE)
            .enrolledOn(enrolledOn)
            .enrolledBy(enrolledBy)
            .visitCadenceDays(request.getVisitCadenceDays())
            .nextExpectedVisit(enrolledOn.plusDays(request.getVisitCadenceDays()))
            .notes(trimToNull(request.getNotes()))
            .build());

        log.info("Programme enrolment: patient {} into {} at hospital {} (cadence {}d)",
            patientId, request.getProgram(), hospitalId, request.getVisitCadenceDays());
        emitAudit(AuditEventType.PROGRAM_ENROLLED, saved,
            "Enrolled in " + saved.getProgram() + " (cadence " + saved.getVisitCadenceDays()
                + "d, from " + enrolledOn + ")");
        return toDto(saved, hospitalId);
    }

    @Transactional
    public ProgramEnrollmentResponseDTO updateStatus(UUID patientId, UUID enrollmentId,
                                                     ProgramStatusUpdateDTO request) {
        UUID hospitalId = requireHospital();
        ProgramEnrollment enrollment = requireEnrollmentInTenant(enrollmentId, patientId, hospitalId);

        ProgramEnrollmentStatus previous = enrollment.getStatus();
        ProgramEnrollmentStatus target = request.getStatus();
        if (previous == target) {
            throw new BusinessException("The enrolment is already in that state.");
        }

        String reason = trimToNull(request.getReason());
        if (target == ProgramEnrollmentStatus.ACTIVE) {
            // Re-opening a wrongly closed enrolment. The reason field is
            // refused rather than ignored: text supplied here would vanish
            // silently, and the caller deserves to know it has nowhere to go.
            if (reason != null) {
                throw new BusinessException(
                    "A move back to ACTIVE takes no reason - use the enrolment notes.");
            }
            enrollmentRepository.findByPatientIdAndHospitalIdAndProgramAndStatus(
                    patientId, hospitalId, enrollment.getProgram(), ProgramEnrollmentStatus.ACTIVE)
                .ifPresent(other -> {
                    throw new BusinessException(
                        "The patient already has an active enrolment in this programme.");
                });
            enrollment.setStatus(ProgramEnrollmentStatus.ACTIVE);
            enrollment.setClosedOn(null);
            enrollment.setClosureReason(null);
        } else {
            if (reason == null) {
                throw new BusinessException(
                    "Closing an enrolment needs a reason - it is the outcome the "
                        + "programme reports, not bookkeeping.");
            }
            enrollment.setStatus(target);
            enrollment.setClosedOn(LocalDate.now(clock));
            enrollment.setClosureReason(reason);
            // A closed enrolment owes no tracing. CANCELLED, not CLOSED:
            // the visit did not happen, the need lapsed. For DECEASED this
            // is the line that stops the outreach sweep from texting a
            // family about a missed programme visit.
            resolveOpenTracingRecalls(enrollment, RecallStatus.CANCELLED);
        }

        ProgramEnrollment saved = enrollmentRepository.save(enrollment);
        log.info("Programme enrolment {} -> {} (patient {}, {})",
            enrollmentId, target, patientId, enrollment.getProgram());
        emitAudit(AuditEventType.PROGRAM_STATUS_CHANGED, saved,
            saved.getProgram() + " enrolment " + previous + " -> " + target);
        return toDto(saved, hospitalId);
    }

    @Transactional
    public ProgramEnrollmentResponseDTO recordVisit(UUID patientId, UUID enrollmentId,
                                                    ProgramVisitDTO request) {
        UUID hospitalId = requireHospital();
        ProgramEnrollment enrollment = requireEnrollmentInTenant(enrollmentId, patientId, hospitalId);

        if (enrollment.getStatus() != ProgramEnrollmentStatus.ACTIVE) {
            throw new BusinessException(
                "Only an active enrolment records programme visits. Re-open it first.");
        }

        LocalDate visitDate = request != null && request.getVisitDate() != null
            ? request.getVisitDate() : LocalDate.now(clock);
        if (visitDate.isBefore(enrollment.getEnrolledOn())) {
            throw new BusinessException("A programme visit cannot predate the enrolment.");
        }
        // lastVisitOn/nextExpectedVisit summarize the LATEST visit. A
        // backfilled earlier visit would move nextExpectedVisit backwards
        // and mark an adherent patient overdue — refused rather than
        // silently reordered, because there is no visit history table to
        // absorb it (deliberately: the summary pair is all item 36 needs).
        if (enrollment.getLastVisitOn() != null
            && visitDate.isBefore(enrollment.getLastVisitOn())) {
            throw new BusinessException(
                "A visit before the last recorded one (" + enrollment.getLastVisitOn()
                    + ") cannot be recorded - the registry tracks the latest visit.");
        }

        enrollment.setLastVisitOn(visitDate);
        enrollment.setNextExpectedVisit(visitDate.plusDays(enrollment.getVisitCadenceDays()));
        // The visit the tracing recall was chasing happened - leaving the
        // recall open would have the desk pursue a patient who came.
        resolveOpenTracingRecalls(enrollment, RecallStatus.CLOSED);
        ProgramEnrollment saved = enrollmentRepository.save(enrollment);
        emitAudit(AuditEventType.PROGRAM_VISIT_RECORDED, saved,
            saved.getProgram() + " visit on " + visitDate + ", next expected "
                + saved.getNextExpectedVisit());
        return toDto(saved, hospitalId);
    }

    @Transactional(readOnly = true)
    public Page<ProgramEnrollmentResponseDTO> registry(CareProgram program,
                                                       ProgramEnrollmentStatus status,
                                                       int page, int size) {
        UUID hospitalId = requireHospital();
        ProgramEnrollmentStatus effective = status != null ? status : ProgramEnrollmentStatus.ACTIVE;
        int boundedSize = Math.min(Math.max(size, 1), MAX_REGISTRY_PAGE);
        LocalDate today = LocalDate.now(clock);
        return enrollmentRepository.findRegistry(hospitalId, program, effective,
                PageRequest.of(Math.max(page, 0), boundedSize))
            .map(e -> mapper.toDto(e, hospitalId, today));
    }

    @Transactional(readOnly = true)
    public Map<ProgramEnrollmentStatus, Long> registryCounts(CareProgram program) {
        UUID hospitalId = requireHospital();
        Map<ProgramEnrollmentStatus, Long> counts = new EnumMap<>(ProgramEnrollmentStatus.class);
        for (Object[] row : enrollmentRepository.countByStatus(hospitalId, program)) {
            counts.put((ProgramEnrollmentStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public List<ProgramEnrollmentResponseDTO> patientEnrollments(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        LocalDate today = LocalDate.now(clock);
        return enrollmentRepository.findByPatient(hospitalId, patientId).stream()
            .map(e -> mapper.toDto(e, hospitalId, today))
            .toList();
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // A registry without a hospital is every hospital's registry.
            throw new BusinessException("An active hospital context is required.");
        }
        return hospitalId;
    }

    private Patient requirePatientInTenant(UUID patientId, UUID hospitalId) {
        return patientRepository.findById(patientId)
            .filter(p -> p.isRegisteredInHospital(hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
    }

    private ProgramEnrollment requireEnrollmentInTenant(UUID enrollmentId, UUID patientId,
                                                        UUID hospitalId) {
        return enrollmentRepository.findById(enrollmentId)
            .filter(e -> e.getHospital() != null && hospitalId.equals(e.getHospital().getId()))
            .filter(e -> e.getPatient() != null && patientId.equals(e.getPatient().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("program.enrollment.notfound"));
    }

    private void resolveOpenTracingRecalls(ProgramEnrollment enrollment, RecallStatus outcome) {
        var open = recallRepository.findByProgramEnrollment_IdAndStatusIn(
            enrollment.getId(), OPEN_RECALL_STATUSES);
        for (var recall : open) {
            recall.setStatus(outcome);
            recall.setClosedAt(LocalDateTime.now(clock));
            recallRepository.save(recall);
        }
    }

    private Staff resolveCurrentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    private ProgramEnrollmentResponseDTO toDto(ProgramEnrollment e, UUID hospitalId) {
        return mapper.toDto(e, hospitalId, LocalDate.now(clock));
    }

    /** Best-effort: an audit failure must never undo a recorded enrolment. */
    private void emitAudit(AuditEventType type, ProgramEnrollment enrollment, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("ProgramEnrollment")
                .resourceId(enrollment.getId() != null ? enrollment.getId().toString() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(enrollment.getHospital() != null
                    ? enrollment.getHospital().getName() : null)
                .patientId(enrollment.getPatient() != null
                    ? enrollment.getPatient().getId() : null)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit audit for ProgramEnrollment {}: {}",
                enrollment.getId(), ex.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
