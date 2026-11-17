package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.security.PrincipalUserIds;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.clinical.ClinicalAlertDTO;
import com.example.hms.payload.dto.clinical.ClinicalDashboardResponseDTO;
import com.example.hms.payload.dto.clinical.ClinicalInboxItemDTO;
import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorResultQueueItemDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;
import com.example.hms.payload.dto.clinical.InboxCountsDTO;
import com.example.hms.payload.dto.clinical.OnCallStatusDTO;
import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;
import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;
import com.example.hms.payload.dto.clinical.RoomedPatientDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.ClinicalDashboardService;
import com.example.hms.service.DashboardConfigService;
import com.example.hms.service.DoctorWorklistService;
import com.example.hms.service.PatientFlowService;
import com.example.hms.service.PatientSnapshotService;
import com.example.hms.service.ResultReviewService;
import com.example.hms.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "Me", description = "Endpoints for the current authenticated user")
public class MeController {

    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ClinicalDashboardService clinicalDashboardService;
    private final StaffService staffService;
    private final DashboardConfigService dashboardConfigService;
    private final DoctorWorklistService doctorWorklistService;
    private final PatientFlowService patientFlowService;
    private final ResultReviewService resultReviewService;
    private final PatientSnapshotService patientSnapshotService;
    private final ActingScopeResolver actingScopeResolver;

    public record HospitalMinimalDTO(UUID id, String name) {
    }

    public record AssignmentSummaryDTO(
        UUID id,
        UUID hospitalId,
        String hospitalName,
        UUID roleId,
        String roleName,
        String roleCode,
        boolean active
    ) {
    }

    @Operation(summary = "Get dashboard configuration for current user")
    @GetMapping("/dashboard-config")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DashboardConfigResponseDTO> getDashboardConfig(Authentication auth) {
        UUID userId = resolveUserId(auth);
        DashboardConfigResponseDTO config = dashboardConfigService.getDashboardConfig(userId);
        return ResponseEntity.ok(config);
    }

    @Operation(summary = "Get my hospital context for any hospital-scoped role")
    @GetMapping("/hospital")
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<HospitalMinimalDTO> myHospital(Authentication auth) {
        UUID hospitalId = actingScopeResolver.current() instanceof ActingScope.Pinned pinned
                ? pinned.hospitalId()
                : null;
        if (hospitalId == null) {
            throw new BusinessException("Unable to resolve hospital from your context.");
        }

        Hospital h = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));

        return ResponseEntity.ok(new HospitalMinimalDTO(h.getId(), h.getName()));
    }

    @Operation(summary = "Get unified clinical dashboard data for current clinical user")
    @GetMapping("/clinical-dashboard")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<ApiResponseWrapper<ClinicalDashboardResponseDTO>> getClinicalDashboard(Authentication auth) {
        UUID userId = resolveUserId(auth);
        ClinicalDashboardResponseDTO dashboard = clinicalDashboardService.getClinicalDashboard(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(dashboard));
    }

    @Operation(summary = "Get role assignments for current user")
    @GetMapping("/assignments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AssignmentSummaryDTO>> getAssignments(Authentication auth) {
        UUID userId = resolveUserId(auth);
        List<AssignmentSummaryDTO> assignments = assignmentRepository.findAllDetailedByUserId(userId).stream()
            .filter(assignment -> Boolean.TRUE.equals(assignment.getActive()))
            .map(assignment -> {
                var hospital = assignment.getHospital();
                var role = assignment.getRole();
                return new AssignmentSummaryDTO(
                    assignment.getId(),
                    hospital != null ? hospital.getId() : null,
                    hospital != null ? hospital.getName() : null,
                    role != null ? role.getId() : null,
                    role != null ? role.getName() : null,
                    role != null ? role.getCode() : null,
                    Boolean.TRUE.equals(assignment.getActive())
                );
            })
            .toList();
        return ResponseEntity.ok(assignments);
    }

    @Operation(summary = "Get critical alerts for current doctor")
    @GetMapping("/critical-alerts")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_MIDWIFE')")
    public ResponseEntity<ApiResponseWrapper<java.util.List<ClinicalAlertDTO>>> getCriticalAlerts(
            @RequestParam(defaultValue = "24") int hours,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        java.util.List<ClinicalAlertDTO> alerts = clinicalDashboardService.getCriticalAlerts(userId, hours);
        return ResponseEntity.ok(ApiResponseWrapper.success(alerts));
    }

    @Operation(summary = "Acknowledge a clinical alert")
    @PostMapping("/alerts/{alertId}/acknowledge")
    // Nurses and midwives receive actionRequired alerts on the shared nurse
    // dashboard view and its Ack button rendered for them — but the
    // doctor-only annotation made every click a silent 403 (2026-08-23 role
    // audit, B4).
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Void> acknowledgeAlert(@PathVariable UUID alertId, Authentication auth) {
        UUID userId = resolveUserId(auth);
        clinicalDashboardService.acknowledgeAlert(alertId, userId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get inbox counts")
    @GetMapping("/inbox-counts")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<InboxCountsDTO>> getInboxCounts(Authentication auth) {
        UUID userId = resolveUserId(auth);
        InboxCountsDTO counts = clinicalDashboardService.getInboxCounts(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(counts));
    }

    @Operation(summary = "Get roomed patients")
    @GetMapping("/roomed-patients")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<java.util.List<RoomedPatientDTO>>> getRoomedPatients(Authentication auth) {
        UUID userId = resolveUserId(auth);
        java.util.List<RoomedPatientDTO> patients = clinicalDashboardService.getRoomedPatients(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(patients));
    }

    @Operation(summary = "Get recently seen patients for current doctor")
    @GetMapping("/recent-patients")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<java.util.List<com.example.hms.patient.dto.PatientResponse>>> getRecentPatients(Authentication auth) {
        UUID userId = resolveUserId(auth);
        java.util.List<com.example.hms.patient.dto.PatientResponse> patients = clinicalDashboardService.getRecentPatients(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(patients));
    }

    @Operation(summary = "Get on-call status")
    @GetMapping("/on-call-status")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<OnCallStatusDTO>> getOnCallStatus(Authentication auth) {
        UUID userId = resolveUserId(auth);
        OnCallStatusDTO status = clinicalDashboardService.getOnCallStatus(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(status));
    }

    @Operation(summary = "Get active staff records for current user")
    @GetMapping("/staff/active")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_SCIENTIST','ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<StaffResponseDTO>> getActiveStaff(Authentication auth) {
        UUID userId = resolveUserId(auth);
        List<StaffResponseDTO> staff = staffService.getActiveStaffByUserId(userId, LocaleContextHolder.getLocale());
        return ResponseEntity.ok(staff);
    }

    // ── Physician Cockpit endpoints ───────────────────────────────

    @Operation(summary = "Get critical-strip counts for physician cockpit")
    @GetMapping("/critical-strip")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<CriticalStripDTO>> getCriticalStrip(Authentication auth) {
        UUID userId = resolveUserId(auth);
        CriticalStripDTO strip = doctorWorklistService.getCriticalStrip(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(strip));
    }

    @Operation(summary = "Get merged physician worklist")
    @GetMapping("/worklist")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<List<DoctorWorklistItemDTO>>> getWorklist(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String urgency,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            Authentication auth) {
        UUID userId = resolveUserId(auth);
        List<DoctorWorklistItemDTO> items = doctorWorklistService.getWorklist(userId, status, urgency, date);
        return ResponseEntity.ok(ApiResponseWrapper.success(items));
    }

    @Operation(summary = "Get patient-flow board grouped by encounter state")
    @GetMapping("/patient-flow")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<Map<String, List<PatientFlowItemDTO>>>> getPatientFlow(Authentication auth) {
        UUID userId = resolveUserId(auth);
        Map<String, List<PatientFlowItemDTO>> flow = patientFlowService.getPatientFlow(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(flow));
    }

    @Operation(summary = "Get clinical inbox items with detail")
    @GetMapping("/inbox")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<List<ClinicalInboxItemDTO>>> getInbox(Authentication auth) {
        UUID userId = resolveUserId(auth);
        List<ClinicalInboxItemDTO> items = resultReviewService.getInboxItems(userId);
        return ResponseEntity.ok(ApiResponseWrapper.success(items));
    }

    @Operation(summary = "Get lab/imaging results review queue",
               description = "Scoped to the hospital the caller is acting at: the X-Hospital-Id hospital, or the only "
                   + "one they hold. A caller with a staff row and no such hospital is refused with 404 rather than "
                   + "served that clinician's orders from every hospital, because a cross-hospital disclosure cannot "
                   + "be recorded without an acting hospital to record it against. A super-admin in global view is "
                   + "asked to select a hospital (403).")
    @GetMapping("/results/review-queue")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
    public ResponseEntity<ApiResponseWrapper<List<DoctorResultQueueItemDTO>>> getResultReviewQueue(Authentication auth) {
        UUID userId = resolveUserId(auth);
        // The same resolution getPatientSnapshot below uses: one scope for the
        // whole platform, so two panels on one page cannot disagree about
        // which hospital the caller is at.
        UUID hospitalId = perPatientScope();
        List<DoctorResultQueueItemDTO> items = resultReviewService.getResultReviewQueue(userId, hospitalId);
        return ResponseEntity.ok(ApiResponseWrapper.success(items));
    }

    @Operation(summary = "Get compact patient snapshot for drawer",
               description = "Requires a hospital scope: the X-Hospital-Id hospital, or the only one the caller "
                   + "holds. A caller with none is refused with 404, with the same answer a missing patient gives, "
                   + "rather than served the patient's record from every tenant with no disclosure recorded. A "
                   + "restricted chart is refused with 403. A super-admin in global view is asked to select a "
                   + "hospital (403) instead of being told the patient does not exist.")
    @GetMapping("/patients/{patientId}/snapshot")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<ApiResponseWrapper<PatientSnapshotDTO>> getPatientSnapshot(
            @PathVariable UUID patientId, Authentication auth) {
        UUID hospitalId = perPatientScope();
        PatientSnapshotDTO snapshot = patientSnapshotService.getSnapshot(patientId, hospitalId);
        return ResponseEntity.ok(ApiResponseWrapper.success(snapshot));
    }

    /* ---------- Resolution chain ---------- */
    /**
     * The hospital a per-patient read acts at (design Q1, option B). Both
     * {@code /results/review-queue} and {@code /patients/{id}/snapshot} refuse
     * when it is {@code null}, with the 404 a missing patient gives.
     *
     * <p>It used to fall back to the caller's NEWEST active assignment, a
     * super-admin included: a platform admin holding any clinical assignment
     * was silently scoped to it, and the snapshot's by-design 404 then told
     * them a patient registered elsewhere did not exist (D3). A super-admin in
     * global view is now asked to select a hospital instead.
     */
    private UUID perPatientScope() {
        return switch (actingScopeResolver.current()) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> throw new HospitalScopeRefusedException(
                "Select a hospital: a patient's record is read at one hospital, and this request is in global view.");
            case ActingScope.Refused refused -> null;
            case ActingScope.PatientOwned owned -> throw HospitalScopeRefusedException.patientOwned();
        };
    }

    /**
     * Resolve the current user ID from authentication
     */
    private UUID resolveUserId(Authentication auth) {
        return PrincipalUserIds.of(auth)
                .orElseGet(() -> {
                    String principal = (auth != null ? auth.getName() : null);
                    if (principal != null && !principal.isBlank()) {
                        return userRepository
                                .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(principal, principal,
                                        principal)
                                .map(User::getId)
                                .orElseThrow(() -> new BusinessException("Unable to resolve user ID"));
                    }
                    throw new BusinessException("Unable to resolve user ID from authentication");
                });
    }

}
