package com.example.hms.controller;

import com.example.hms.payload.dto.isolation.DiscontinuePrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import com.example.hms.service.IsolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Isolation precautions (Tier 2 item 32).
 *
 * <p>There is no {@code /isolation/**} matcher in SecurityConfig, so the
 * class-level {@link PreAuthorize} is load-bearing rather than decorative —
 * without it these endpoints would fall through to
 * {@code anyRequest().authenticated()} and any logged-in user could raise or
 * lift a precaution.
 */
@RestController
@RequestMapping("/isolation")
@RequiredArgsConstructor
@PreAuthorize(IsolationController.CLINICAL_ROLES)
@Tag(name = "Isolation Precautions", description = "Transmission-based precautions in force for a patient")
public class IsolationController {

    /** Nursing raises and lifts precautions in practice, so NURSE and MIDWIFE are included. */
    static final String CLINICAL_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE')";

    /** Reads additionally reach the desk — placement decisions are made there. */
    private static final String READ_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')";

    private final IsolationService isolationService;

    @PostMapping("/precautions")
    @Operation(summary = "Put a patient on an isolation precaution")
    public ResponseEntity<IsolationPrecautionResponseDTO> startPrecaution(
        @Valid @RequestBody IsolationPrecautionRequestDTO request) {
        return new ResponseEntity<>(isolationService.startPrecaution(request), HttpStatus.CREATED);
    }

    @PostMapping("/precautions/{precautionId}/discontinue")
    @Operation(summary = "Lift a precaution, with the reason it was lifted")
    public ResponseEntity<IsolationPrecautionResponseDTO> discontinuePrecaution(
        @PathVariable UUID precautionId,
        @Valid @RequestBody DiscontinuePrecautionRequestDTO request) {
        return ResponseEntity.ok(isolationService.discontinuePrecaution(precautionId, request));
    }

    @GetMapping("/precautions/patient/{patientId}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Precautions in force for a patient")
    public ResponseEntity<List<IsolationPrecautionResponseDTO>> getActiveForPatient(
        @PathVariable UUID patientId) {
        return ResponseEntity.ok(isolationService.getActiveForPatient(patientId));
    }

    @GetMapping("/precautions/patient/{patientId}/history")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Every precaution ever recorded for a patient — the contact-tracing view")
    public ResponseEntity<List<IsolationPrecautionResponseDTO>> getHistoryForPatient(
        @PathVariable UUID patientId) {
        return ResponseEntity.ok(isolationService.getHistoryForPatient(patientId));
    }
}
