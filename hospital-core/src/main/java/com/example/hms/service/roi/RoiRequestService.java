package com.example.hms.service.roi;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoiRequesterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.RoiRequest;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.roi.RoiDecisionDTO;
import com.example.hms.payload.dto.roi.RoiRequestCreateDTO;
import com.example.hms.payload.dto.roi.RoiRequestResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoiRequestRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Release-of-information requests (Tier 2 item 39b), the request side of
 * item 39's disclosure accounting. House contract throughout: hospital
 * scope pinned via {@link RoleValidator}; a foreign or nonexistent row
 * collapses to the IDENTICAL not-found (the #550 oracle lesson);
 * concurrent decisions surface as a clean retryable refusal via the
 * {@code @Version} column (the #549 lesson); audit is best-effort.
 *
 * <p><b>Fulfilment is a disclosure.</b> Besides its own ROI_FULFILLED
 * trail entry, fulfilling emits a {@code PATIENT_EXPORT} row keyed by
 * patient — the event the disclosure whitelist classifies as
 * COPY_RELEASED — so every fulfilled request appears on the patient's own
 * report with no further wiring. Denials stay off the patient report by
 * design: nothing was disclosed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoiRequestService {

    /** Worklist page-size ceiling: a triage read is a page, never a dump. */
    static final int MAX_WORKLIST_PAGE = 200;

    private static final String NOT_FOUND = "ROI request not found.";

    private final RoiRequestRepository roiRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditService;
    private final Clock clock;

    // ── intake ──────────────────────────────────────────────────────────

    @Transactional
    public RoiRequestResponseDTO create(UUID patientId, RoiRequestCreateDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);
        return persistRequest(patient, hospitalId, request);
    }

    /**
     * The patient's own submission through the /me surface. The requester
     * type is forced to PATIENT and the name to the patient's own — a
     * self-service caller cannot file as a third party.
     */
    @Transactional
    public RoiRequestResponseDTO createForSelf(Patient patient, UUID hospitalId,
                                               RoiRequestCreateDTO request) {
        if (patient == null || hospitalId == null) {
            throw new BusinessException("A registered hospital is required to file a request.");
        }
        request.setRequesterType(RoiRequesterType.PATIENT);
        request.setRequesterName(patient.getFullName());
        return persistRequest(patient, hospitalId, request);
    }

    private RoiRequestResponseDTO persistRequest(Patient patient, UUID hospitalId,
                                                 RoiRequestCreateDTO request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate requestedOn = request.getRequestedOn() != null ? request.getRequestedOn() : today;
        if (requestedOn.isAfter(today)) {
            throw new BusinessException("The request date cannot be in the future.");
        }
        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        RoiRequest saved = roiRepository.save(RoiRequest.builder()
            .patient(patient)
            .hospital(hospital)
            .requesterType(request.getRequesterType())
            .requesterName(trimToNull(request.getRequesterName()))
            .requesterContact(trimToNull(request.getRequesterContact()))
            .purpose(request.getPurpose().strip())
            .scopeDescription(request.getScopeDescription().strip())
            .status(RoiRequestStatus.PENDING)
            .requestedOn(requestedOn)
            .build());
        log.info("ROI request {} logged for patient {} at hospital {} ({})",
            saved.getId(), patient.getId(), hospitalId, request.getRequesterType());
        emitAudit(AuditEventType.ROI_REQUESTED, saved,
            "ROI request logged (" + saved.getRequesterType() + ", scope: "
                + saved.getScopeDescription() + ")");
        return toDto(saved);
    }

    // ── decisions ───────────────────────────────────────────────────────

    @Transactional
    public RoiRequestResponseDTO fulfil(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.FULFILLED, trimToNull(decision.getNote()), hospitalId);

        emitAudit(AuditEventType.ROI_FULFILLED, request,
            "ROI request fulfilled (scope: " + request.getScopeDescription() + ")");
        // THE DISCLOSURE ROW: PATIENT_EXPORT keyed by patient is what the
        // item-39 whitelist shows the patient as COPY_RELEASED. This is the
        // whole point of the workflow — the release is accounted for.
        emitAudit(AuditEventType.PATIENT_EXPORT, request,
            "Record copy released under ROI request " + request.getId()
                + " to " + describeRequester(request)
                + " (scope: " + request.getScopeDescription() + ")");
        return toDto(request);
    }

    @Transactional
    public RoiRequestResponseDTO deny(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        String note = trimToNull(decision.getNote());
        if (note == null) {
            throw new BusinessException(
                "Denying a request needs a reason - it is the outcome the requester is told.");
        }
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.DENIED, note, hospitalId);
        emitAudit(AuditEventType.ROI_DENIED, request, "ROI request denied");
        return toDto(request);
    }

    @Transactional
    public RoiRequestResponseDTO cancel(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.CANCELLED, trimToNull(decision.getNote()), hospitalId);
        emitAudit(AuditEventType.ROI_CANCELLED, request, "ROI request cancelled");
        return toDto(request);
    }

    private void decide(RoiRequest request, RoiRequestStatus target, String note, UUID hospitalId) {
        request.setStatus(target);
        request.setDecidedAt(LocalDateTime.now(clock));
        request.setDecidedBy(resolveCurrentStaff(hospitalId));
        request.setDecisionNote(note);
        try {
            // Flush inside the method so a concurrent decision surfaces as
            // the @Version conflict HERE - decide-once means the second
            // caller gets a refusal, not a silent overwrite.
            roiRepository.saveAndFlush(request);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(
                "The request was decided at the same time by someone else - reload and retry.");
        }
        log.info("ROI request {} -> {} at hospital {}", request.getId(), target, hospitalId);
    }

    // ── reads ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<RoiRequestResponseDTO> worklist(RoiRequestStatus status, int page, int size) {
        UUID hospitalId = requireHospital();
        RoiRequestStatus effective = status != null ? status : RoiRequestStatus.PENDING;
        int boundedSize = Math.clamp(size, 1, MAX_WORKLIST_PAGE);
        return roiRepository.findByHospital_IdAndStatusOrderByRequestedOnAscCreatedAtAsc(
                hospitalId, effective, PageRequest.of(Math.max(page, 0), boundedSize))
            .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> patientRequests(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        return roiRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId)
            .stream()
            .map(this::toDto)
            .toList();
    }

    /** The patient's own requests across hospitals — the /me surface. */
    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> requestsForSelf(Patient patient) {
        if (patient == null || patient.getId() == null) {
            return List.of();
        }
        return roiRepository.findByPatient_IdOrderByCreatedAtDesc(patient.getId()).stream()
            .map(this::toDto)
            .toList();
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("An active hospital context is required.");
        }
        return hospitalId;
    }

    private Patient requirePatientInTenant(UUID patientId, UUID hospitalId) {
        return patientRepository.findById(patientId)
            .filter(p -> p.isRegisteredInHospital(hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound"));
    }

    /**
     * Foreign, nonexistent and already-decided-elsewhere rows collapse to
     * the identical not-found; only a tenant-owned PENDING row proceeds —
     * an already-decided one gets its own explicit refusal.
     */
    private RoiRequest requirePendingInTenant(UUID requestId, UUID hospitalId) {
        RoiRequest request = roiRepository.findById(requestId)
            .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (request.getStatus() != RoiRequestStatus.PENDING) {
            throw new BusinessException("The request is already " + request.getStatus() + ".");
        }
        return request;
    }

    private Staff resolveCurrentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    private static String describeRequester(RoiRequest request) {
        if (request.getRequesterType() == RoiRequesterType.PATIENT) {
            return "the patient";
        }
        return request.getRequesterName() != null ? request.getRequesterName() : "a third party";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private RoiRequestResponseDTO toDto(RoiRequest r) {
        return RoiRequestResponseDTO.builder()
            .id(r.getId())
            .patientId(r.getPatient() != null ? r.getPatient().getId() : null)
            .patientName(r.getPatient() != null ? r.getPatient().getFullName() : null)
            .hospitalName(r.getHospital() != null ? r.getHospital().getName() : null)
            .requesterType(r.getRequesterType())
            .requesterName(r.getRequesterName())
            .requesterContact(r.getRequesterContact())
            .purpose(r.getPurpose())
            .scopeDescription(r.getScopeDescription())
            .status(r.getStatus())
            .requestedOn(r.getRequestedOn())
            .decidedAt(r.getDecidedAt())
            .decidedByName(r.getDecidedBy() != null ? r.getDecidedBy().getName() : null)
            .decisionNote(r.getDecisionNote())
            .build();
    }

    /** Best-effort: an audit failure must never undo a recorded request or decision. */
    private void emitAudit(AuditEventType type, RoiRequest request, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("ROI_REQUEST")
                .resourceId(request.getId() != null ? request.getId().toString() : null)
                .patientId(request.getPatient() != null ? request.getPatient().getId() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(request.getHospital() != null ? request.getHospital().getName() : null)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit {} audit for ROI request {}: {}",
                type, request.getId(), ex.getMessage());
        }
    }
}
