package com.example.hms.service.panel;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PanelAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.panel.PanelAssignmentRequestDTO;
import com.example.hms.payload.dto.panel.PanelAssignmentResponseDTO;
import com.example.hms.payload.dto.panel.PanelEndRequestDTO;
import com.example.hms.payload.dto.panel.PanelOverviewRowDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Panel management / empanelment (Tier 2 item 37), following the
 * {@code ProgramEnrollmentService} house pattern: hospital scope pinned via
 * {@link RoleValidator}, cross-tenant rows collapse to not-found, audit is
 * best-effort.
 *
 * <p>The one behaviour worth defending: <b>reassignment supersedes</b>. An
 * assign call while an ACTIVE owner of the same role exists ENDs that row
 * (dated today, reason recorded) and creates the new ACTIVE one in the same
 * transaction — never an error, never an overwrite. Empanelment churn is
 * normal; the history rows are the point.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PanelService {

    /** Worklist page-size ceiling: a panel read is a page, never a dump. */
    static final int MAX_PANEL_PAGE = 200;

    private final PanelAssignmentRepository panelRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;
    private final PanelAssignmentMapper mapper;
    private final AuditEventLogService auditService;
    private final Clock clock;

    @Transactional
    public PanelAssignmentResponseDTO assign(UUID patientId, PanelAssignmentRequestDTO request) {
        UUID hospitalId = requireHospital();
        Patient patient = requirePatientInTenant(patientId, hospitalId);
        Staff provider = requireStaffInTenant(request.getProviderStaffId(), hospitalId);

        LocalDate today = LocalDate.now(clock);
        LocalDate assignedOn = request.getAssignedOn() != null ? request.getAssignedOn() : today;
        if (assignedOn.isAfter(today)) {
            throw new BusinessException("The empanelment date cannot be in the future.");
        }

        // Supersede, never refuse: the new owner is the fact being recorded,
        // and the ended row keeps the old one. The previous row ends on the
        // TAKEOVER date, not "today" — a backfilled reassignment must not
        // leave an overlap the history cannot answer.
        PanelAssignment previous = panelRepository
            .findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, request.getPanelRole(), PanelAssignmentStatus.ACTIVE)
            .orElse(null);
        if (previous != null) {
            if (previous.getAssignedOn() != null && assignedOn.isBefore(previous.getAssignedOn())) {
                throw new BusinessException(
                    "The empanelment date is before the current owner's start ("
                        + previous.getAssignedOn() + ") — end that assignment first if the "
                        + "history really needs rewriting.");
            }
            previous.setStatus(PanelAssignmentStatus.ENDED);
            previous.setEndedOn(assignedOn);
            previous.setEndReason("Superseded by reassignment");
        }

        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        PanelAssignment saved;
        // Concurrency story: @Version on the row plus V149's partial unique
        // index are the real guards; two concurrent assigns cannot both win.
        // This catch turns the loser's flush failure into a clean retryable
        // refusal instead of a 500 (flushes forced so it surfaces HERE, not
        // at commit outside the method).
        try {
            if (previous != null) {
                panelRepository.saveAndFlush(previous);
            }
            saved = panelRepository.saveAndFlush(PanelAssignment.builder()
                .patient(patient)
                .hospital(hospital)
                .providerStaff(provider)
                .panelRole(request.getPanelRole())
                .status(PanelAssignmentStatus.ACTIVE)
                .assignedOn(assignedOn)
                .assignedBy(resolveCurrentStaff(hospitalId))
                .build());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            throw new BusinessException(
                "Another empanelment change for this patient landed at the same time — "
                    + "reload and retry.");
        }

        log.info("Empanelment: patient {} -> staff {} as {} at hospital {}",
            patientId, provider.getId(), request.getPanelRole(), hospitalId);
        emitAudit(AuditEventType.PANEL_ASSIGNED, saved,
            "Empaneled as " + saved.getPanelRole() + " (from " + assignedOn + ")");
        if (previous != null) {
            // The superseded row changed state too; its transition gets its
            // own actor-attributed trail entry, emitted only after the
            // replacement was durably recorded.
            emitAudit(AuditEventType.PANEL_ENDED, previous,
                previous.getPanelRole() + " empanelment superseded by reassignment (ended "
                    + previous.getEndedOn() + ")");
        }
        return mapper.toDto(saved);
    }

    @Transactional
    public PanelAssignmentResponseDTO end(UUID patientId, UUID assignmentId,
                                          PanelEndRequestDTO request) {
        UUID hospitalId = requireHospital();
        PanelAssignment assignment = requireAssignmentInTenant(assignmentId, patientId, hospitalId);
        if (assignment.getStatus() != PanelAssignmentStatus.ACTIVE) {
            throw new BusinessException("The empanelment is already ended.");
        }
        assignment.setStatus(PanelAssignmentStatus.ENDED);
        assignment.setEndedOn(LocalDate.now(clock));
        assignment.setEndReason(request.getReason().strip());
        PanelAssignment saved;
        try {
            // Flush inside the method so a concurrent end surfaces as the
            // @Version conflict here — end-once means the second caller gets
            // a refusal, not a silent overwrite of the first reason.
            saved = panelRepository.saveAndFlush(assignment);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(
                "The empanelment was changed at the same time by someone else — reload and retry.");
        }

        log.info("Empanelment {} ended (patient {}, {})",
            assignmentId, patientId, assignment.getPanelRole());
        emitAudit(AuditEventType.PANEL_ENDED, saved,
            saved.getPanelRole() + " empanelment ended");
        return mapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<PanelAssignmentResponseDTO> patientAssignments(UUID patientId) {
        UUID hospitalId = requireHospital();
        requirePatientInTenant(patientId, hospitalId);
        return panelRepository
            .findByPatient_IdAndHospital_IdOrderByAssignedOnDescCreatedAtDesc(patientId, hospitalId)
            .stream()
            .map(mapper::toDto)
            .toList();
    }

    /** The caller's own live panel — refuses callers with no staff row at this hospital. */
    @Transactional(readOnly = true)
    public Page<PanelAssignmentResponseDTO> myPanel(int page, int size) {
        UUID hospitalId = requireHospital();
        Staff self = resolveCurrentStaff(hospitalId);
        if (self == null) {
            throw new BusinessException(
                "You have no staff profile at the active hospital, so you have no panel here.");
        }
        return providerPanelPage(self.getId(), hospitalId, page, size);
    }

    /** {@code role} narrows the page to one panel role — the overview drills into a (provider, role) pair. */
    @Transactional(readOnly = true)
    public Page<PanelAssignmentResponseDTO> providerPanel(UUID staffId, PanelRole role,
                                                          int page, int size) {
        UUID hospitalId = requireHospital();
        requireProviderRowInTenant(staffId, hospitalId);
        if (role == null) {
            return providerPanelPage(staffId, hospitalId, page, size);
        }
        return panelRepository
            .findByProviderStaff_IdAndHospital_IdAndPanelRoleAndStatusOrderByAssignedOnDesc(
                staffId, hospitalId, role, PanelAssignmentStatus.ACTIVE,
                PageRequest.of(Math.max(page, 0), clampPageSize(size)))
            .map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public List<PanelOverviewRowDTO> overview() {
        UUID hospitalId = requireHospital();
        return panelRepository.activePanelSizes(hospitalId).stream()
            .map(row -> PanelOverviewRowDTO.builder()
                .providerStaffId((UUID) row[0])
                .providerName((String) row[1])
                .panelRole((PanelRole) row[2])
                .activeCount((Long) row[3])
                .build())
            .toList();
    }

    // ── guards ──────────────────────────────────────────────────────────

    private Page<PanelAssignmentResponseDTO> providerPanelPage(UUID staffId, UUID hospitalId,
                                                               int page, int size) {
        return panelRepository
            .findByProviderStaff_IdAndHospital_IdAndStatusOrderByAssignedOnDesc(
                staffId, hospitalId, PanelAssignmentStatus.ACTIVE,
                PageRequest.of(Math.max(page, 0), clampPageSize(size)))
            .map(mapper::toDto);
    }

    private static int clampPageSize(int size) {
        return Math.clamp(size, 1, MAX_PANEL_PAGE);
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // A panel without a hospital is every hospital's panel.
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
     * A panel OWNER must be a live staff row at the hospital — the portal's
     * dropdown happens to list only active staff, but the API boundary must
     * not accept a deactivated profile as the responsible clinician.
     */
    private Staff requireStaffInTenant(UUID staffId, UUID hospitalId) {
        return staffRepository.findById(staffId)
            .filter(Staff::isActive)
            .filter(s -> s.getHospital() != null && hospitalId.equals(s.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));
    }

    /**
     * Read-side variant: viewing a panel does not require the provider to
     * still be active — an admin reassigning a departed provider's panel
     * must be able to SEE it.
     */
    private void requireProviderRowInTenant(UUID staffId, UUID hospitalId) {
        staffRepository.findById(staffId)
            .filter(s -> s.getHospital() != null && hospitalId.equals(s.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("staff.notfound"));
    }

    private PanelAssignment requireAssignmentInTenant(UUID assignmentId, UUID patientId,
                                                      UUID hospitalId) {
        return panelRepository.findById(assignmentId)
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .filter(a -> a.getPatient() != null && patientId.equals(a.getPatient().getId()))
            .orElseThrow(() -> new ResourceNotFoundException("panel.assignment.notfound"));
    }

    private Staff resolveCurrentStaff(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
    }

    /** Best-effort: an audit failure must never undo a recorded empanelment. */
    private void emitAudit(AuditEventType type, PanelAssignment assignment, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("PANEL_ASSIGNMENT")
                .resourceId(assignment.getId() != null ? assignment.getId().toString() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(assignment.getHospital() != null
                    ? assignment.getHospital().getName() : null)
                .patientId(assignment.getPatient() != null
                    ? assignment.getPatient().getId() : null)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit audit for PanelAssignment {}: {}",
                assignment.getId(), ex.getMessage());
        }
    }
}
