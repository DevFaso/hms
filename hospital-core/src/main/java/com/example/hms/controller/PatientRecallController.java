package com.example.hms.controller;

import com.example.hms.enums.RecallStatus;
import com.example.hms.payload.dto.scheduling.RecallRequestDTO;
import com.example.hms.payload.dto.scheduling.RecallResponseDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.scheduling.PatientRecallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Patient recalls (P3 #22).
 *
 * <p>Class-level gate on purpose: /recalls has no SecurityConfig matcher, so
 * it rides {@code anyRequest().authenticated()} and a forgotten method
 * annotation would fail OPEN to any authenticated user, including
 * ROLE_PATIENT.
 */
@RestController
@RequestMapping(value = "/recalls", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Patient Recalls", description = "Return visits the practice owes patients")
@SecurityRequirement(name = "bearerAuth")
public class PatientRecallController {

    private static final String SYSTEM_ACTOR = "system";

    private final PatientRecallService recallService;

    @PostMapping
    @Operation(summary = "Create a recall manually")
    public ResponseEntity<RecallResponseDTO> create(
            @Valid @RequestBody RecallRequestDTO request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID hospitalId = resolveHospitalId();
        String actor = principal != null ? principal.getUsername() : SYSTEM_ACTOR;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recallService.createRecall(request, hospitalId, actor));
    }

    @GetMapping
    @Operation(summary = "List recalls, soonest-due first",
        description = "Optionally filtered by status and patient.")
    public ResponseEntity<List<RecallResponseDTO>> list(
            @RequestParam(required = false) RecallStatus status,
            @RequestParam(required = false) UUID patientId) {
        return ResponseEntity.ok(recallService.getRecalls(resolveHospitalId(), status, patientId));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close a recall — the visit happened or the need lapsed")
    public ResponseEntity<RecallResponseDTO> close(@PathVariable UUID id) {
        return ResponseEntity.ok(recallService.closeRecall(id, resolveHospitalId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a recall created in error or no longer wanted")
    public ResponseEntity<RecallResponseDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(recallService.cancelRecall(id, resolveHospitalId()));
    }

    @PostMapping("/{id}/link-appointment")
    @Operation(summary = "Mark a recall scheduled by linking the booked appointment")
    public ResponseEntity<RecallResponseDTO> linkAppointment(
            @PathVariable UUID id,
            @RequestParam UUID appointmentId) {
        return ResponseEntity.ok(recallService.linkAppointment(id, resolveHospitalId(), appointmentId));
    }

    private UUID resolveHospitalId() {
        return HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElse(null);
    }
}
