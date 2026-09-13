package com.example.hms.controller;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.payload.dto.consultation.AssignConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.CancelConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.CompleteConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationStatsDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import com.example.hms.payload.dto.consultation.DeclineConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ReassignConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ScheduleConsultationRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.ConsultationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/consultations")
@Validated
@RequiredArgsConstructor
@Tag(name = "Consultations", description = "General consultation and specialist referral management beyond OB-GYN")
public class ConsultationController {

    private final ConsultationService consultationService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Request a specialist consultation",
               description = "Create consultation request for any specialty (Cardiology, Neurology, Surgery, etc.)")
    public ResponseEntity<ConsultationResponseDTO> createConsultation(
        @Valid @RequestBody ConsultationRequestDTO request,
        Authentication authentication
    ) {
        UUID requestingProviderId = extractUserId(authentication);
        ConsultationResponseDTO response = consultationService.createConsultation(request, requestingProviderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{consultationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Get consultation details")
    public ResponseEntity<ConsultationResponseDTO> getConsultation(@PathVariable UUID consultationId) {
        ConsultationResponseDTO response = consultationService.getConsultation(consultationId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE','PATIENT')")
    @Operation(summary = "List all consultations for a patient")
    public ResponseEntity<List<ConsultationResponseDTO>> getConsultationsForPatient(@PathVariable UUID patientId) {
        List<ConsultationResponseDTO> consultations = consultationService.getConsultationsForPatient(patientId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    @Operation(summary = "List all consultations")
    public ResponseEntity<List<ConsultationResponseDTO>> getAllConsultations(
        @RequestParam(required = false) ConsultationStatus status
    ) {
        List<ConsultationResponseDTO> consultations = consultationService.getAllConsultations(status);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "List consultations for hospital",
               description = "Filter by status: REQUESTED, ACKNOWLEDGED, SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED")
    public ResponseEntity<List<ConsultationResponseDTO>> getConsultationsForHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) ConsultationStatus status
    ) {
        List<ConsultationResponseDTO> consultations = consultationService.getConsultationsForHospital(hospitalId, status);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/hospital/{hospitalId}/pending")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Get pending consultations requiring action",
               description = "Returns consultations in REQUESTED or ACKNOWLEDGED status")
    public ResponseEntity<List<ConsultationResponseDTO>> getPendingConsultations(@PathVariable UUID hospitalId) {
        List<ConsultationResponseDTO> consultations = consultationService.getPendingConsultations(hospitalId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/mine")
    // Deliberately NOT widened to NURSE: "mine" means consultations where the
    // caller is the CONSULTANT, which a nurse never is.
    //
    // This endpoint used to return nothing for anyone, doctors included:
    // extractUserId yields a users.id while findByConsultant_Id matches a
    // Staff id, and Staff has its own PK plus a separate user_id FK, so the
    // two are never equal. The service now resolves the caller to their Staff
    // row first. A previous version of this comment said the defect was
    // "recorded as standing debt"; it was not recorded anywhere, which is part
    // of why it survived — so it is fixed here instead.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "My consultations", description = "List consultations assigned to the authenticated consultant")
    public ResponseEntity<List<ConsultationResponseDTO>> getMyConsultations(Authentication authentication) {
        UUID staffId = extractUserId(authentication);
        List<ConsultationResponseDTO> consultations = consultationService.getMyConsultations(staffId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/overdue")
    // NURSE is added here and deliberately not on the two hospital-path reads
    // above. This was the only consultation list a nurse could not reach: the
    // plain list endpoint already admits ROLE_NURSE, and the portal's four
    // status tabs all filter that single response client-side. The two
    // hospital-path reads have no caller in the portal at all — and they now
    // validate the path hospital against the caller's active one rather than
    // trusting it, so the reason for keeping them narrow is weaker than it was.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Overdue consultations", description = "List consultations past their SLA due date")
    public ResponseEntity<List<ConsultationResponseDTO>> getOverdueConsultations(
        @RequestParam(required = false) UUID hospitalId
    ) {
        List<ConsultationResponseDTO> consultations = consultationService.getOverdueConsultations(hospitalId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Consultation statistics", description = "Aggregated counts and averages for analytics")
    public ResponseEntity<ConsultationStatsDTO> getStats(
        @RequestParam(required = false) UUID hospitalId
    ) {
        return ResponseEntity.ok(consultationService.getStats(hospitalId));
    }

    @GetMapping("/requested-by/{providerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "List consultations requested by a provider")
    public ResponseEntity<List<ConsultationResponseDTO>> getConsultationsRequestedBy(@PathVariable UUID providerId) {
        List<ConsultationResponseDTO> consultations = consultationService.getConsultationsRequestedBy(providerId);
        return ResponseEntity.ok(consultations);
    }

    @GetMapping("/assigned-to/{consultantId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "List consultations assigned to a consultant",
               description = "View consultation requests assigned to specific specialist")
    public ResponseEntity<List<ConsultationResponseDTO>> getConsultationsAssignedTo(
        @PathVariable UUID consultantId,
        @RequestParam(required = false) ConsultationStatus status
    ) {
        List<ConsultationResponseDTO> consultations = consultationService.getConsultationsAssignedTo(consultantId, status);
        return ResponseEntity.ok(consultations);
    }

    @PostMapping("/{consultationId}/acknowledge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Acknowledge consultation request",
               description = "Consultant accepts the consultation request and assigns themselves")
    public ResponseEntity<ConsultationResponseDTO> acknowledgeConsultation(
        @PathVariable UUID consultationId,
        Authentication authentication
    ) {
        UUID consultantId = extractUserId(authentication);
        ConsultationResponseDTO response = consultationService.acknowledgeConsultation(consultationId, consultantId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{consultationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Update consultation details",
               description = "Update scheduled time, consultant notes, recommendations, etc.")
    public ResponseEntity<ConsultationResponseDTO> updateConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody ConsultationUpdateDTO updateDTO
    ) {
        ConsultationResponseDTO response = consultationService.updateConsultation(consultationId, updateDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/schedule")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Schedule consultation", description = "Set scheduled date/time; status transitions to SCHEDULED")
    public ResponseEntity<ConsultationResponseDTO> scheduleConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody ScheduleConsultationRequestDTO request
    ) {
        ConsultationResponseDTO response = consultationService.scheduleConsultation(
            consultationId, request.getScheduledAt(), request.getScheduleNote());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/start")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Start consultation", description = "Mark consultation as in progress; status transitions to IN_PROGRESS")
    public ResponseEntity<ConsultationResponseDTO> startConsultation(@PathVariable UUID consultationId) {
        ConsultationResponseDTO response = consultationService.startConsultation(consultationId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Complete consultation",
               description = "Submit final recommendations and mark consultation as complete")
    public ResponseEntity<ConsultationResponseDTO> completeConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody CompleteConsultationRequestDTO request
    ) {
        ConsultationResponseDTO response = consultationService.completeConsultation(consultationId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/decline")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Decline consultation", description = "Decline consultation with required reason; status transitions to DECLINED")
    public ResponseEntity<ConsultationResponseDTO> declineConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody DeclineConsultationRequestDTO request
    ) {
        ConsultationResponseDTO response = consultationService.declineConsultation(consultationId, request.getDeclineReason());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','MIDWIFE')")
    @Operation(summary = "Cancel consultation",
               description = "Cancel consultation with reason (patient improved, consultation no longer needed, etc.)")
    public ResponseEntity<ConsultationResponseDTO> cancelConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody CancelConsultationRequestDTO request
    ) {
        ConsultationResponseDTO response = consultationService.cancelConsultation(consultationId, request.getCancellationReason());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/assign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Assign consultation to consultant",
               description = "Assign a REQUESTED consultation to a specific consultant; status changes to ASSIGNED")
    public ResponseEntity<ConsultationResponseDTO> assignConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody AssignConsultationRequestDTO request,
        Authentication authentication
    ) {
        UUID assignedById = extractUserId(authentication);
        ConsultationResponseDTO response = consultationService.assignConsultation(
            consultationId, request.getConsultantId(), assignedById, request.getAssignmentNote());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{consultationId}/reassign")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Reassign consultation to a different consultant",
               description = "Reassign a consultation to a different consultant with a required reason; status reverts to ASSIGNED")
    public ResponseEntity<ConsultationResponseDTO> reassignConsultation(
        @PathVariable UUID consultationId,
        @Valid @RequestBody ReassignConsultationRequestDTO request,
        Authentication authentication
    ) {
        UUID assignedById = extractUserId(authentication);
        ConsultationResponseDTO response = consultationService.reassignConsultation(
            consultationId, request.getConsultantId(), assignedById, request.getReassignmentReason());
        return ResponseEntity.ok(response);
    }

    // Helper method to extract user ID from authentication
    private UUID extractUserId(Authentication authentication) {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication required");
        }

        return resolveUserId(authentication)
            .orElseThrow(() -> new IllegalArgumentException("Invalid user ID format in authentication"));
    }

    private Optional<UUID> resolveUserId(Authentication authentication) {
        Object principal = authentication.getPrincipal();

        if (principal instanceof CustomUserDetails customUserDetails) {
            return Optional.ofNullable(customUserDetails.getUserId());
        }

        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            Jwt jwt = jwtAuthenticationToken.getToken();
            for (String claim : JWT_USER_ID_CLAIMS) {
                String raw = jwt.getClaimAsString(claim);
                Optional<UUID> candidate = parseUuidSafely(raw);
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }

        if (principal instanceof UserDetails userDetails) {
            Optional<UUID> candidate = parseUuidSafely(userDetails.getUsername());
            if (candidate.isPresent()) {
                return candidate;
            }
        }

        if (principal instanceof String principalString) {
            Optional<UUID> candidate = parseUuidSafely(principalString);
            if (candidate.isPresent()) {
                return candidate;
            }
        }

        return Optional.empty();
    }

    private Optional<UUID> parseUuidSafely(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static final List<String> JWT_USER_ID_CLAIMS = List.of(
        "staffId",
        "staff_id",
        "providerId",
        "provider_id",
        "uid",
        "userId",
        "user_id",
        "id",
        "sub"
    );
}
