package com.example.hms.controller;

import com.example.hms.payload.dto.AdvanceDirectiveRequestDTO;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import com.example.hms.service.AdvanceDirectiveService;
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
 * Advance directives (P2 #13).
 *
 * <p>The entity, repository, mapper and response DTO all existed and the record
 * was surfaced by the storyboard and by record-sharing — but nothing could
 * write one. A DNR that cannot be entered is a DNR that does not exist.
 */
@RestController
@RequestMapping(value = "/advance-directives", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Advance Directives", description = "Record and maintain patient advance directives")
@SecurityRequirement(name = "bearerAuth")
public class AdvanceDirectiveController {

    private static final String CLINICAL_ROLES =
        "hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final AdvanceDirectiveService advanceDirectiveService;

    @GetMapping("/patient/{patientId}")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "List a patient's advance directives")
    public ResponseEntity<List<AdvanceDirectiveResponseDTO>> listForPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(advanceDirectiveService.listForPatient(patientId));
    }

    @PostMapping(value = "/patient/{patientId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Record an advance directive for a patient")
    public ResponseEntity<AdvanceDirectiveResponseDTO> create(
        @PathVariable UUID patientId,
        @Valid @RequestBody AdvanceDirectiveRequestDTO request) {
        return new ResponseEntity<>(advanceDirectiveService.create(patientId, request), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Update an advance directive")
    public ResponseEntity<AdvanceDirectiveResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody AdvanceDirectiveRequestDTO request) {
        return ResponseEntity.ok(advanceDirectiveService.update(id, request));
    }

    /**
     * Revoke, not delete. A directive that was in force and later withdrawn is
     * part of the clinical record; deleting the row would destroy the evidence
     * that it ever applied.
     */
    @PutMapping("/{id}/revoke")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Revoke an advance directive",
        description = "Marks the directive REVOKED. Directives are never deleted — a directive "
            + "that was once in force is part of the record.")
    public ResponseEntity<AdvanceDirectiveResponseDTO> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok(advanceDirectiveService.revoke(id));
    }
}
