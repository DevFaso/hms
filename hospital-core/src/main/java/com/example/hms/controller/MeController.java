package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.clinical.*;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.ClinicalDashboardService;
import com.example.hms.service.DashboardConfigService;
import com.example.hms.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
@Tag(name = "Me", description = "Endpoints for the current authenticated user")
public class MeController {

    private static final String HOSPITAL_ID_CLAIM = "hospitalId";

    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ClinicalDashboardService clinicalDashboardService;
    private final StaffService staffService;
    private final DashboardConfigService dashboardConfigService;

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

    @Operation(summary = "Get my hospital (receptionist/admin)")
    @GetMapping("/hospital")
    @PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<HospitalMinimalDTO> myHospital(Authentication auth) {
        UUID hospitalId = resolveHospitalId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve hospital from your context."));

        Hospital h = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + hospitalId));

        return ResponseEntity.ok(new HospitalMinimalDTO(h.getId(), h.getName()));
    }

    @Operation(summary = "Get unified clinical dashboard data for current doctor")
    @GetMapping("/clinical-dashboard")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON')")
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

    /* ---------- Resolution chain ---------- */
    private Optional<UUID> resolveHospitalId(Authentication auth) {
        // 1) Try JWT hospitalId claim
        Optional<UUID> fromClaim = extractHospitalIdFromJwt(auth);
        if (fromClaim.isPresent())
            return fromClaim;

        // 2) Try last active assignment using userId
        return activeAssignmentHospital(auth);
    }

    private Optional<UUID> extractHospitalIdFromJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            // flat claim
            String str = jwt.getClaimAsString(HOSPITAL_ID_CLAIM);
            if (str != null && !str.isBlank()) {
                try {
                    return Optional.of(UUID.fromString(str));
                } catch (Exception ignored) {
                }
            }
            // nested/custom formats
            Object raw = jwt.getClaims().get(HOSPITAL_ID_CLAIM);
            if (raw instanceof UUID u)
                return Optional.of(u);
            if (raw instanceof String s && !s.isBlank()) {
                try {
                    return Optional.of(UUID.fromString(s));
                } catch (Exception ignored) {
                }
            }
        }
        return Optional.empty();
    }

    private Optional<UUID> activeAssignmentHospital(Authentication auth) {
        UUID userId = tryUserIdFromJwtSub(auth).orElse(null);
        if (userId == null) {
            String principal = (auth != null ? auth.getName() : null);
            if (principal != null && !principal.isBlank()) {
                userId = userRepository
                        .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(principal, principal, principal)
                        .map(User::getId)
                        .orElse(null);
            }
        }
        if (userId == null)
            return Optional.empty();

        return assignmentRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .map(a -> a.getHospital() != null ? a.getHospital().getId() : null);
    }

    private Optional<UUID> tryUserIdFromJwtSub(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Object sub = jat.getToken().getClaims().get("sub");
            if (sub == null)
                return Optional.empty();
            try {
                return Optional.of(sub instanceof UUID ? (UUID) sub : UUID.fromString(String.valueOf(sub)));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the current user ID from authentication
     */
    private UUID resolveUserId(Authentication auth) {
        return tryUserIdFromJwtSub(auth)
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
