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

        LocalDate assignedOn = request.getAssignedOn() != null
            ? request.getAssignedOn() : LocalDate.now(clock);

        // Supersede, never refuse: the new owner is the fact being recorded,
        // and the ended row keeps the old one.
        panelRepository.findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
                patientId, hospitalId, request.getPanelRole(), PanelAssignmentStatus.ACTIVE)
            .ifPresent(previous -> {
                previous.setStatus(PanelAssignmentStatus.ENDED);
                previous.setEndedOn(LocalDate.now(clock));
                previous.setEndReason("Superseded by reassignment");
                panelRepository.save(previous);
            });

        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        PanelAssignment saved = panelRepository.save(PanelAssignment.builder()
            .patient(patient)
            .hospital(hospital)
            .providerStaff(provider)
            .panelRole(request.getPanelRole())
            .status(PanelAssignmentStatus.ACTIVE)
            .assignedOn(assignedOn)
            .assignedBy(resolveCurrentStaff(hospitalId))
            .build());

        log.info("Empanelment: patient {} -> staff {} as {} at hospital {}",
            patientId, provider.getId(), request.getPanelRole(), hospitalId);
        emitAudit(AuditEventType.PANEL_ASSIGNED, saved,
            "Empaneled as " + saved.getPanelRole() + " (from " + assignedOn + ")");
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
        PanelAssignment saved = panelRepository.save(assignment);

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

    @Transactional(readOnly = true)
    public Page<PanelAssignmentResponseDTO> providerPanel(UUID staffId, int page, int size) {
        UUID hospitalId = requireHospital();
        requireStaffInTenant(staffId, hospitalId);
        return providerPanelPage(staffId, hospitalId, page, size);
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
        int boundedSize = Math.min(Math.max(size, 1), MAX_PANEL_PAGE);
        return panelRepository
            .findByProviderStaff_IdAndHospital_IdAndStatusOrderByAssignedOnDesc(
                staffId, hospitalId, PanelAssignmentStatus.ACTIVE,
                PageRequest.of(Math.max(page, 0), boundedSize))
            .map(mapper::toDto);
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

    private Staff requireStaffInTenant(UUID staffId, UUID hospitalId) {
        return staffRepository.findById(staffId)
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
                .entityType("PanelAssignment")
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
