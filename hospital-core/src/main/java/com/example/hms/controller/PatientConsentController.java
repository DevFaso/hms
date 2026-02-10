package com.example.hms.controller;

import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/patient-consents")
@RequiredArgsConstructor
@Tag(name = "Patient Consents", description = "Endpoints for granting, revoking, listing, and checking patient record sharing consents.")
public class PatientConsentController {

    private static final int MAX_AUDIT_DETAILS_LENGTH = 2000;

    private final PatientConsentService patientConsentService;
    private final AuditEventLogService auditEventLogService;
    private final ObjectMapper objectMapper;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final PatientService patientService;
    private final HospitalService hospitalService;

    @PostMapping("/grant")
    @Operation(summary = "Grant or Update Patient Consent", description = "Grants or updates patient consent for sharing records between hospitals.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<PatientConsentResponseDTO> grantConsent(
            @Valid @RequestBody PatientConsentRequestDTO requestDTO
    ) {
        Locale locale = LocaleContextHolder.getLocale();

        PatientResponseDTO patient = patientService.getPatientById(
                requestDTO.getPatientId(),
                requestDTO.getFromHospitalId(),
                locale
        );

        HospitalResponseDTO fromHospital = hospitalService.getHospitalById(
                requestDTO.getFromHospitalId(),
                locale
        );

        HospitalResponseDTO toHospital = hospitalService.getHospitalById(
                requestDTO.getToHospitalId(),
                locale
        );

        PatientConsentResponseDTO responseDTO = patientConsentService.grantConsentWithDetails(
                requestDTO, patient, fromHospital, toHospital);

        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(responseDTO);
            if (detailsJson.length() > MAX_AUDIT_DETAILS_LENGTH) {
                detailsJson = detailsJson.substring(0, MAX_AUDIT_DETAILS_LENGTH) + "...";
            }
        } catch (Exception e) {
            detailsJson = "Could not serialize consent: " + e.getMessage();
        }

        User currentUser = getCurrentUser();
        UUID assignmentId = resolveAssignmentId(currentUser);

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentUser.getId())
                .assignmentId(assignmentId)
                .eventType(AuditEventType.CONSENT_UPDATE)
                .eventDescription("Consent granted for patient " + patient.getEmail() +
                        " from hospital " + fromHospital.getName() +
                        " to hospital " + toHospital.getName() +
                        " for: " + responseDTO.getPurpose() +
                        " by user " + currentUser.getEmail())
                .resourceId(responseDTO.getId().toString())
                .entityType("PATIENT_CONSENT")
                .status(AuditStatus.SUCCESS)
                .details(detailsJson)
                .build()
        );

        return ResponseEntity.ok(responseDTO);
    }

    @PostMapping("/revoke")
    @Operation(summary = "Revoke Patient Consent", description = "Revokes patient consent for sharing records between specific hospitals.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Void> revokeConsent(
            @RequestParam @NotNull UUID patientId,
            @RequestParam @NotNull UUID fromHospitalId,
            @RequestParam @NotNull UUID toHospitalId
    ) {
        patientConsentService.revokeConsent(patientId, fromHospitalId, toHospitalId);

        User currentUser = getCurrentUser();

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(currentUser.getId())
                .eventType(AuditEventType.CONSENT_REVOKED)
                .eventDescription("Consent revoked for patient " + patientId +
                        " from hospital " + fromHospitalId +
                        " to hospital " + toHospitalId +
                        " by user " + currentUser.getEmail())
                .resourceId(patientId.toString())
                .entityType("PATIENT_CONSENT")
                .status(AuditStatus.SUCCESS)
                .details("Revoke operation executed.")
                .build()
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping
    @Operation(summary = "List All Patient Consents", description = "Returns a paginated list of all patient consents.")
    @ApiResponse(responseCode = "200", description = "Paginated list of consents.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getAllConsents(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getAllConsents(pageable));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "List Consents by Patient", description = "Returns all consents for the given patient.")
    @ApiResponse(responseCode = "200", description = "List of consents for patient.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getConsentsByPatient(
            @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getConsentsByPatient(patientId, pageable));
    }

    @GetMapping("/from-hospital/{fromHospitalId}")
    @Operation(summary = "List Consents by Source Hospital", description = "Returns all consents where the given hospital is the source.")
    @ApiResponse(responseCode = "200", description = "List of consents for source hospital.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getConsentsByFromHospital(
            @PathVariable UUID fromHospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getConsentsByFromHospital(fromHospitalId, pageable));
    }

    @GetMapping("/to-hospital/{toHospitalId}")
    @Operation(summary = "List Consents by Target Hospital", description = "Returns all consents where the given hospital is the target.")
    @ApiResponse(responseCode = "200", description = "List of consents for target hospital.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Page<PatientConsentResponseDTO>> getConsentsByToHospital(
            @PathVariable UUID toHospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(patientConsentService.getConsentsByToHospital(toHospitalId, pageable));
    }

    @GetMapping("/active")
    @Operation(summary = "Check if Consent is Active", description = "Returns true if there is an active consent between the given patient, source hospital, and target hospital.")
    @ApiResponse(responseCode = "200", description = "Consent status returned.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<Boolean> isConsentActive(
            @RequestParam UUID patientId,
            @RequestParam UUID fromHospitalId,
            @RequestParam UUID toHospitalId) {
        boolean active = patientConsentService.isConsentActive(patientId, fromHospitalId, toHospitalId);
        return ResponseEntity.ok(active);
    }

    private @NotNull User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("User is not authenticated.");
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof CustomUserDetails userDetails) {
            return userRepository.findById(userDetails.getUserId())
                    .orElseThrow(() -> new IllegalStateException("User not found with ID: " + userDetails.getUserId()));
        }

        throw new IllegalArgumentException("Unexpected principal type: " + principal.getClass().getName());
    }

    public UUID resolveAssignmentId(User user) {
        return assignmentRepository
            .findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId())
            .map(UserRoleHospitalAssignment::getId)
            .orElseGet(() -> {
                if (isGlobalAdmin(user)) return null; 
                throw new IllegalStateException("No active hospital assignment found for user: " + user.getEmail());
            });
    }

    private boolean isGlobalAdmin(User user) {
        return user.getUserRoles() != null && user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(java.util.Objects::nonNull)
            .map(Role::getCode)
            .anyMatch(code -> "ROLE_SUPER_ADMIN".equals(code)
                || "SYSTEM_ADMIN".equals(code)
                || "ROLE_HOSPITAL_ADMIN".equals(code));
    }

}

