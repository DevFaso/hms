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
import com.example.hms.service.RequiredAuditEventLogPort;
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
 * also fail closed; an unavailable audit sink is a 503, not an invalid
 * request field. Audit descriptions carry no request narrative —
 * {@code event_description} is a plaintext column, and {@code resourceId}
 * already links the row to the encrypted request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoiRequestService {

    /** Worklist page-size ceiling: a triage read is a page, never a dump. */
    static final int MAX_WORKLIST_PAGE = 200;

    @org.springframework.beans.factory.annotation.Value("${hms.roi.response-target-days:30}")
    private int responseTargetDays = 30;

    /**
     * QA D4270: how long after the decision an approved copy stays collectable (by the patient, the clinicians and
     * the approving administrator). Past it the request is expired: a new request is made and decided.
     */
    @org.springframework.beans.factory.annotation.Value("${hms.roi.copy-download-days:30}")
    private int copyDownloadDays = 30;


    private final RoiRequestRepository roiRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;
    private final RequiredAuditEventLogPort auditService;
    private final RoiRequestMapper mapper;
    private final Clock clock;

    // ── intake ──────────────────────────────────────────────────────────

    @Transactional
    public RoiRequestResponseDTO create(UUID patientId, RoiRequestCreateDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);
        if (request.getRequesterType() == RoiRequesterType.THIRD_PARTY
                && trimToNull(request.getRequesterName()) == null) {
            throw new com.example.hms.exception.FieldBusinessException("requesterType", "roi.request.thirdPartyNameRequired");
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
            throw new com.example.hms.exception.FieldBusinessException("hospitalId", "roi.request.hospitalRequired");
        }
        return persistRequest(patient, hospitalId, RoiRequestCreateDTO.builder()
            .requestKind(request.getRequestKind())
            .requesterType(RoiRequesterType.PATIENT)
            .requesterName(patient.getFullName())
            .requesterContact(request.getRequesterContact())
            .purpose(request.getPurpose())
            .scopeDescription(request.getScopeDescription())
            .requestedOn(LocalDate.now(clock))
            .build());
    }

    private RoiRequestResponseDTO persistRequest(Patient patient, UUID hospitalId,
                                                 RoiRequestCreateDTO request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate requestedOn = request.getRequestedOn() != null ? request.getRequestedOn() : today;
        if (requestedOn.isAfter(today)) {
            throw new com.example.hms.exception.FieldBusinessException("requestedOn", "roi.request.dateInFuture");
        }
        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        RoiRequest saved = roiRepository.save(RoiRequest.builder()
            .requestKind(request.getRequestKind() == null ? com.example.hms.enums.RoiRequestKind.COPY : request.getRequestKind())
            .dueOn(requestedOn.plusDays(Math.clamp(responseTargetDays, 1, 365)))
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
        decide(request, RoiRequestStatus.FULFILLED, requiredReason(decision), hospitalId);

        // THE DISCLOSURE ROW: PATIENT_EXPORT keyed by patient is what the
        // item-39 whitelist shows the patient as COPY_RELEASED. This is the
        // whole point of the workflow, so it is NOT best-effort - if it
        // cannot be persisted, the fulfilment above rolls back with it.
        if (request.getRequestKind() != com.example.hms.enums.RoiRequestKind.CORRECTION) emitDisclosureOrFail(request);
        emitAudit(AuditEventType.ROI_FULFILLED, request, "ROI request fulfilled");
        return withCopyAvailability(mapper.toDto(request), request);
    }

    @Transactional
    public RoiRequestResponseDTO deny(UUID requestId, RoiDecisionDTO decision) {
        // QA round 29 sweep (D1333 class): read before the scope and the lookup,
        // named under fieldErrors.note and translated (it was English prose with no field).
        String note = trimToNull(decision == null ? null : decision.getNote());
        if (note == null) {
            throw new com.example.hms.exception.FieldBusinessException("note", "roi.deny.noteRequired");
        }
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.DENIED, note, hospitalId);
        emitAudit(AuditEventType.ROI_DENIED, request, "ROI request denied");
        return mapper.toDto(request);
    }

    @Transactional
    public RoiRequestResponseDTO cancel(UUID requestId, RoiDecisionDTO decision) {
        UUID hospitalId = requireHospital();
        RoiRequest request = requirePendingInTenant(requestId, hospitalId);
        decide(request, RoiRequestStatus.CANCELLED, requiredReason(decision), hospitalId);
        emitAudit(AuditEventType.ROI_CANCELLED, request, "ROI request cancelled");
        return mapper.toDto(request);
    }

    private void decide(RoiRequest request, RoiRequestStatus target, String note, UUID hospitalId) {
        request.setStatus(target);
        request.setDecidedAt(LocalDateTime.now(clock));
        request.setDecidedBy(resolveCurrentStaff(hospitalId));
        request.setDecisionNote(note);
        request.setDecisionActorUserId(roleValidator.getCurrentUserId());
        try {
            // Flush inside the method so a concurrent decision surfaces as
            // the @Version conflict HERE - decide-once means the second
            // caller gets a refusal, not a silent overwrite.
            roiRepository.saveAndFlush(request);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException("roi.request.decideConflict");
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
            .map(row -> withCopyAvailability(mapper.toDto(row), row));
    }

    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> patientRequests(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        return roiRepository.findByPatientIdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId)
            .stream()
            .map(row -> withCopyAvailability(mapper.toDto(row), row))
            .toList();
    }

    /** The patient's own requests across hospitals — the /me surface. */
    @Transactional(readOnly = true)
    public List<RoiRequestResponseDTO> requestsForSelf(Patient patient) {
        if (patient == null || patient.getId() == null) {
            return List.of();
        }
        return roiRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId()).stream()
            .map(row -> withCopyAvailability(mapper.toPatientDto(row), row))
            .toList();
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("roi.request.hospitalContextRequired");
        }
        return hospitalId;
    }

    private Patient requirePatientInTenant(UUID patientId, UUID hospitalId) {
        return patientRepository.findById(patientId)
            .filter(p -> p.isRegisteredInHospital(hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", patientId));
    }

    /**
     * Foreign, nonexistent and already-decided-elsewhere rows collapse to
     * the identical not-found; only a tenant-owned PENDING row proceeds —
     * an already-decided one gets its own explicit refusal.
     */
    private RoiRequest requirePendingInTenant(UUID requestId, UUID hospitalId) {
        RoiRequest request = roiRepository.findById(requestId)
            .filter(r -> r.getHospital() != null && hospitalId.equals(r.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("roi.request.notFound", requestId));
        if (request.getStatus() != RoiRequestStatus.PENDING) {
            throw new BusinessException("roi.request.alreadyDecided", com.example.hms.utility.MessageUtil.resolveOrRaw("roi.request.status." + request.getStatus().name()));
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

    private static String requiredReason(RoiDecisionDTO decision) {
        String reason = trimToNull(decision == null ? null : decision.getNote());
        if (reason == null) throw new com.example.hms.exception.FieldBusinessException("note", "roi.decision.reasonRequired");
        return reason;
    }

    /**
     * Resolve the PDF's patient only after the request's hospital, the caller's part in the release, the kind, the
     * release decision and its collection period all match.
     *
     * <p>QA D4270: who collects an approved copy at the hospital. The clinical roles as before, held AT the
     * request's hospital (a role held at another hospital does not count); the verified super-admin acting there;
     * and the hospital administrator who approved THIS request. Another administrator is refused: approving a release
     * is what entitles an administrator to produce it. Reception is refused: it reads no clinical content.
     */
    @Transactional(readOnly = true)
    public ApprovedCopy copyForExport(UUID requestId) {
        UUID hospitalId = requireHospital();
        RoiRequest request = roiRepository.findById(requestId)
            .filter(row -> row.getHospital() != null && hospitalId.equals(row.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("roi.request.notFound", requestId));
        UUID caller = roleValidator.getCurrentUserId();
        boolean collects = roleValidator.isSuperAdminActingAt(hospitalId)
            || (caller != null && roleValidator.collectsReleaseCopies(caller, hospitalId));
        if (!collects) {
            // The approver collects only while they still administer the request's hospital: an administrator
            // demoted there (but holding a role elsewhere) no longer produces its copies.
            if (caller == null || !caller.equals(request.getDecisionActorUserId())
                    || !roleValidator.isHospitalAdmin(caller, hospitalId)) {
                throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                    com.example.hms.utility.MessageUtil.resolveOrRaw("roi.request.copyApproverOnly"));
            }
        }
        requireCollectable(request);
        return new ApprovedCopy(request.getPatientId(), request.getId(), hospitalId);
    }

    /**
     * QA D4270: the patient's own approved copy, from « Mes documents ». Only a request the patient filed
     * themselves: a copy approved for a third party goes to that third party, not to the portal. Another patient's
     * request answers exactly as a missing one.
     */
    @Transactional(readOnly = true)
    public ApprovedCopy copyForSelf(Patient patient, UUID requestId) {
        if (patient == null || patient.getId() == null || requestId == null) {
            throw new ResourceNotFoundException("roi.request.notFound", requestId);
        }
        RoiRequest request = roiRepository.findById(requestId)
            .filter(row -> patient.getId().equals(row.getPatientId())
                && row.getRequesterType() == RoiRequesterType.PATIENT && row.getHospital() != null)
            .orElseThrow(() -> new ResourceNotFoundException("roi.request.notFound", requestId));
        requireCollectable(request);
        return new ApprovedCopy(request.getPatientId(), request.getId(), request.getHospital().getId(), true);
    }

    private void requireCollectable(RoiRequest request) {
        if (!isApprovedFullCopy(request)) {
            throw new BusinessException("roi.request.copyNotApproved");
        }
        LocalDate until = copyAvailableUntil(request);
        if (until != null && LocalDate.now(clock).isAfter(until)) {
            throw new BusinessException("roi.request.copyExpired");
        }
    }

    private static boolean isApprovedFullCopy(RoiRequest request) {
        return request.getRequestKind() != com.example.hms.enums.RoiRequestKind.CORRECTION
            && request.getStatus() == RoiRequestStatus.FULFILLED
            && "FULL_RECORD".equals(request.getScopeDescription());
    }

    /** The last day an approved copy can be collected; null when the row carries no decision time. */
    private LocalDate copyAvailableUntil(RoiRequest request) {
        return request.getDecidedAt() == null ? null
            : request.getDecidedAt().toLocalDate().plusDays(Math.clamp(copyDownloadDays, 1, 3650));
    }

    /** What the screens need to offer the download: an approved full copy, and until when. */
    private RoiRequestResponseDTO withCopyAvailability(RoiRequestResponseDTO dto, RoiRequest request) {
        if (dto == null || !isApprovedFullCopy(request)) {
            return dto;
        }
        LocalDate until = copyAvailableUntil(request);
        dto.setCopyAvailableUntil(until);
        dto.setCopyDownloadable(until == null || !LocalDate.now(clock).isAfter(until));
        return dto;
    }

    /** The approved request, patient and owning hospital travel together to the release renderer. */
    public record ApprovedCopy(UUID patientId, UUID requestId, UUID hospitalId, boolean selfService) {
        public ApprovedCopy {
            java.util.Objects.requireNonNull(patientId, "patientId");
            java.util.Objects.requireNonNull(requestId, "requestId");
            java.util.Objects.requireNonNull(hospitalId, "hospitalId");
        }

        /** A copy collected by staff acting at the hospital that approved it. */
        public ApprovedCopy(UUID patientId, UUID requestId, UUID hospitalId) {
            this(patientId, requestId, hospitalId, false);
        }
    }

    /** Disclosure and workflow trail participate in the SAME decision transaction. */
    private void emitDisclosureOrFail(RoiRequest request) {
        emitAudit(AuditEventType.PATIENT_EXPORT, request,
            "Record copy released under ROI request " + request.getId());
    }

    /** A request or decision must not commit without its attributed audit event. */
    private void emitAudit(AuditEventType type, RoiRequest request, String description) {
        try {
            if (auditService.logRequiredEvent(buildAudit(type, request, description)) == null)
                throw new com.example.hms.exception.RoiAuditUnavailableException("roi.request.auditUnavailable");
        } catch (RuntimeException ex) {
            log.warn("Required ROI audit failed: request={}, event={}, failure={}",
                request.getId(), type, ex.getClass().getSimpleName());
            throw new com.example.hms.exception.RoiAuditUnavailableException("roi.request.auditUnavailable", ex);
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
            .hospitalId(request.getHospital() != null ? request.getHospital().getId() : null) // QA D533
            .eventDescription(description)
            .build();
    }
}
