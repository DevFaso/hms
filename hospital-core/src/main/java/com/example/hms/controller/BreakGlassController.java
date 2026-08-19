package com.example.hms.controller;

import com.example.hms.payload.dto.BreakGlassDeclareRequestDTO;
import com.example.hms.payload.dto.BreakGlassRevokeRequestDTO;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.service.BreakGlassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/break-glass")
@RequiredArgsConstructor
@Tag(name = "Break-the-Glass",
     description = "Emergency-access overrides that grant a clinician temporary read access to a patient's chart "
                 + "without a pre-existing consent. Every read served under a live session emits a "
                 + "BREAK_GLASS_ACCESS audit event.")
public class BreakGlassController {

    private final BreakGlassService breakGlassService;

    @PostMapping
    @Operation(summary = "Declare a break-the-glass session",
               description = "Creates a short-lived (default 4 h) emergency-access override "
                           + "for the calling user. Only privileged clinical and administrative roles "
                           + "may declare a session.")
    @ApiResponse(responseCode = "201", description = "Session created")
    @ApiResponse(responseCode = "400", description = "Validation failure (missing reason, ttl out of range, …)")
    @ApiResponse(responseCode = "401", description = "Unauthenticated")
    @ApiResponse(responseCode = "403", description = "Caller lacks a privileged role at the hospital")
    @ApiResponse(responseCode = "404", description = "Patient or hospital not found")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<BreakGlassSessionResponseDTO> declare(
            @Valid @RequestBody BreakGlassDeclareRequestDTO request) {
        BreakGlassSessionResponseDTO created = breakGlassService.declare(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{sessionId}/revoke")
    @Operation(summary = "Revoke an active break-the-glass session",
               description = "Allowed for: the declaring user, any HOSPITAL_ADMIN of the session's hospital, "
                           + "or SUPER_ADMIN. Idempotent — re-revoking an already-closed session is a no-op.")
    @ApiResponse(responseCode = "200", description = "Session revoked (or was already closed)")
    @ApiResponse(responseCode = "401", description = "Unauthenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not the owner / a hospital admin / super admin")
    @ApiResponse(responseCode = "404", description = "Session not found")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<BreakGlassSessionResponseDTO> revoke(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) BreakGlassRevokeRequestDTO request) {
        return ResponseEntity.ok(breakGlassService.revoke(sessionId, request));
    }

    @GetMapping("/active")
    @Operation(summary = "List live sessions for a patient",
               description = "Powers the patient-detail emergency-access banner. Returns only sessions "
                           + "that have not been revoked and have not yet expired. Receptionists are "
                           + "intentionally excluded — sessions carry clinical justification text.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<List<BreakGlassSessionResponseDTO>> listActiveForPatient(
            @RequestParam UUID patientId) {
        return ResponseEntity.ok(breakGlassService.listLiveForPatient(patientId));
    }

    @GetMapping("/me")
    @Operation(summary = "Find the caller's live session for a patient",
               description = "Used by clients that need to know whether the current user already holds "
                           + "an emergency-access session for the patient (e.g. before showing the "
                           + "'Declare break-glass' button). Receptionists are allowed because the "
                           + "patient-detail page they share with clinicians renders the break-glass "
                           + "banner on this endpoint; a 403 here would surface as a noisy error "
                           + "card on every patient open. Receptionists who lack the clinical-role "
                           + "predicate get 204 No Content (never an active session), so the "
                           + "endpoint stays informational without granting any data they shouldn't "
                           + "see — the heavier {@code /active} listing remains clinician-only.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_RECEPTIONIST')")
    public ResponseEntity<BreakGlassSessionResponseDTO> findMyLiveSession(
            @RequestParam UUID patientId) {
        Optional<BreakGlassSessionResponseDTO> live =
            breakGlassService.findLiveForCurrentUserAndPatient(patientId);
        return live.map(ResponseEntity::ok)
                   .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/audit")
    @Operation(summary = "Hospital-scoped break-glass review",
               description = "Returns all sessions (live, expired, and revoked) for a hospital, "
                           + "ordered most-recent first. Intended for admin compliance review screens.")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<Page<BreakGlassSessionResponseDTO>> listForHospital(
            @RequestParam UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(breakGlassService.listForHospital(hospitalId, pageable));
    }
}
