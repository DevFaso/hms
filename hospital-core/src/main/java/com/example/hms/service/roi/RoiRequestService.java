package com.example.hms.service.roi;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.enums.RoiRequesterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.RoiRequestMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.RoiRequest;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.roi.RoiDecisionDTO;
import com.example.hms.payload.dto.roi.RoiRequestCreateDTO;
import com.example.hms.payload.dto.roi.RoiRequestResponseDTO;
import com.example.hms.payload.dto.roi.RoiSelfRequestCreateDTO;
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
 * {@code @Version} column (the #549 lesson).
 *
 * <p><b>Fulfilment is a disclosure, and the disclosure row is NOT
 * best-effort.</b> Fulfilling emits a {@code PATIENT_EXPORT} row keyed by
 * patient — the event the disclosure whitelist classifies as
 * COPY_RELEASED — and if that row cannot be persisted the fulfilment is
 * rolled back: a release the accounting cannot see must not be recorded
 * as fulfilled. The workflow's own trail entries (ROI_REQUESTED etc.)
 * stay best-effort, and audit descriptions carry no request narrative —
 * {@code event_description} is a plaintext column, and {@code resourceId}
 * already links the row to the encrypted request.
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
    private final RoiRequestMapper mapper;
    private final Clock clock;

    // ── intake ──────────────────────────────────────────────────────────

    @Transactional
    public RoiRequestResponseDTO create(UUID patientId, RoiRequestCreateDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);
        if (request.getRequesterType() == RoiRequesterType.THIRD_PARTY
                && trimToNull(request.getRequesterName()) == null) {
            throw new BusinessException(
                "A third-party request needs the requester's name - a release to an "
                    + "unnamed outside party cannot be accounted for.");
        }
        return persistRequest(patient, hospitalId, request);
    }

    /**
     * The patient's own submission through the /me surface. The self DTO
     * carries no requester identity by design — the type is PATIENT and
     * the name the patient's own, always.
     */
    @Transactional
    public RoiRequestResponseDTO createForSelf(Patient patient, UUID hospitalId,
                                               RoiSelfRequestCreateDTO request) {
        if (patient == null || hospitalId == null) {
            throw new BusinessException("A registered hospital is required to file a request.");
        }
        return persistRequest(patient, hospitalId, RoiRequestCreateDTO.builder()
            .requesterType(RoiRequesterType.PATIENT)
            .requesterName(patient.getFullName())
            .requesterContact(request.getRequesterContact())
            .purpose(request.getPurpose())
            .scopeDescription(request.getScopeDescription())
            .requestedOn(request.getRequestedOn())
            .build());
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
            .patientId(patient.getId())
            // The snapshot that keeps this legal record legible if the
            // patient row is ever purged (no FK - the V141 pattern).
            .patientName(patient.getFullName())
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
            "ROI request logged (" + saved.getRequesterType() + ")");
        return mapper.toDto(saved);
    }

    // ── decisions ───────────────────────────────────────────────────────

    @Transactional
    public RoiRequestResponseDTO fulfil(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.FULFILLED, trimToNull(decision.getNote()), hospitalId);

        // THE DISCLOSURE ROW: PATIENT_EXPORT keyed by patient is what the
        // item-39 whitelist shows the patient as COPY_RELEASED. This is the
        // whole point of the workflow, so it is NOT best-effort - if it
        // cannot be persisted, the fulfilment above rolls back with it.
        emitDisclosureOrFail(request);
        emitAudit(AuditEventType.ROI_FULFILLED, request, "ROI request fulfilled");
        return mapper.toDto(request);
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
        return mapper.toDto(request);
    }

    @Transactional
    public RoiRequestResponseDTO cancel(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.CANCELLED, trimToNull(decision.getNote()), hospitalId);
        emitAudit(AuditEventType.ROI_CANCELLED, request, "ROI request cancelled");
        return mapper.toDto(request);
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
            .map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> patientRequests(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        return roiRepository.findByPatientIdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId)
            .stream()
            .map(mapper::toDto)
            .toList();
    }

    /** The patient's own requests across hospitals — the /me surface. */
    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> requestsForSelf(Patient patient) {
        if (patient == null || patient.getId() == null) {
            return List.of();
        }
        return roiRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId()).stream()
            .map(mapper::toDto)
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The one MANDATORY audit write. {@code logEvent} commits in its own
     * transaction and returns null instead of throwing when the sink
     * fails — so the null is checked, and the thrown refusal rolls the
     * FULFILLED status back. The guarantee this buys: a fulfilment can
     * never commit without its PATIENT_EXPORT disclosure row. (The
     * inverse window — disclosure committed, fulfilment lost to a crash
     * at commit — errs toward over-reporting a release, the safe side
     * for a disclosure ledger.)
     */
    private void emitDisclosureOrFail(RoiRequest request) {
        var persisted = auditService.logEvent(
            buildAudit(AuditEventType.PATIENT_EXPORT, request,
                "Record copy released under ROI request " + request.getId()));
        if (persisted == null) {
            throw new BusinessException(
                "The release could not be recorded in the disclosure log, so the "
                    + "fulfilment was not saved - try again.");
        }
    }

    /** Best-effort: a trail-entry failure must never undo a recorded request or decision. */
    private void emitAudit(AuditEventType type, RoiRequest request, String description) {
        try {
            auditService.logEvent(buildAudit(type, request, description));
        } catch (RuntimeException ex) {
            log.warn("Failed to emit {} audit for ROI request {}: {}",
                type, request.getId(), ex.getMessage());
        }
    }

    /**
     * Descriptions must stay narrative-free: {@code event_description} is
     * a plaintext column, while scope, purpose and requester identity are
     * encrypted on the request row {@code resourceId} points at.
     */
    private AuditEventRequestDTO buildAudit(AuditEventType type, RoiRequest request,
                                            String description) {
        return AuditEventRequestDTO.builder()
            .eventType(type)
            .status(AuditStatus.SUCCESS)
            .entityType("ROI_REQUEST")
            .resourceId(request.getId() != null ? request.getId().toString() : null)
            .patientId(request.getPatientId())
            .userId(roleValidator.getCurrentUserId())
            .userName(SecurityUtils.getCurrentUsername())
            .hospitalName(request.getHospital() != null ? request.getHospital().getName() : null)
            .eventDescription(description)
            .build();
    }
}
