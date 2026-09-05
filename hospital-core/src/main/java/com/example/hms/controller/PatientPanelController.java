package com.example.hms.controller;

import com.example.hms.payload.dto.panel.PanelAssignmentRequestDTO;
import com.example.hms.payload.dto.panel.PanelAssignmentResponseDTO;
import com.example.hms.payload.dto.panel.PanelEndRequestDTO;
import com.example.hms.service.panel.PanelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * One patient's empanelments (Tier 2 item 37).
 *
 * <p>Class-level gate and the {@code /patients/{patientId}} prefix follow
 * the ProgramEnrollmentController stance exactly: writes must not fall
 * through to {@code anyRequest().authenticated()} unguarded, and living
 * under {@code /patients} puts these reads on the patient-access audit
 * interceptor with no further wiring.
 */
@RestController
@RequestMapping(value = "/patients/{patientId}/panel",
    produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR',"
    + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Panel Management",
    description = "Empanelments (primary provider / CHW) for one patient.")
@SecurityRequirement(name = "bearerAuth")
public class PatientPanelController {

    private final PanelService panelService;

    @PostMapping
    @Operation(summary = "Empanel the patient to a provider or CHW",
        description = "An existing ACTIVE owner of the same role is superseded (ENDED, dated "
            + "today) in the same transaction — reassignment is normal, history accumulates.")
    public ResponseEntity<PanelAssignmentResponseDTO> assign(
        @PathVariable UUID patientId,
        @Valid @RequestBody PanelAssignmentRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(panelService.assign(patientId, request));
    }

    @GetMapping
    @Operation(summary = "The patient's empanelments — current owners and reassignment history")
    public ResponseEntity<List<PanelAssignmentResponseDTO>> list(@PathVariable UUID patientId) {
        return ResponseEntity.ok(panelService.patientAssignments(patientId));
    }

    @PutMapping("/{assignmentId}/end")
    @Operation(summary = "End an empanelment without a successor",
        description = "Needs a reason — moved away, deceased, opted out. Reassignment does not "
            + "use this: assigning the new owner supersedes automatically.")
    public ResponseEntity<PanelAssignmentResponseDTO> end(
        @PathVariable UUID patientId,
        @PathVariable UUID assignmentId,
        @Valid @RequestBody PanelEndRequestDTO request) {
        return ResponseEntity.ok(panelService.end(patientId, assignmentId, request));
    }
}
