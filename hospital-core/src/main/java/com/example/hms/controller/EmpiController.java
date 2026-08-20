package com.example.hms.controller;

import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeRequestDTO;
import com.example.hms.payload.dto.empi.EmpiPatientMergeRequestDTO;
import com.example.hms.service.empi.EmpiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * EMPI identity administration (P1 #8): the first REST surface over
 * {@code EmpiService.mergeIdentities}, which previously had zero callers.
 * <p>
 * Merge is deliberately narrower than the /empi/candidates allowlist —
 * receptionists can surface duplicates but only HOSPITAL_ADMIN /
 * SUPER_ADMIN can execute an irreversible identity merge (empi-identity
 * skill: the explicit admin provisioning flow). Merge remains
 * identity-graph-only: it never touches clinical rows.
 */
@RestController
@RequestMapping("/empi")
@Tag(name = "EMPI Administration", description = "Master patient identity lookup and duplicate merge")
public class EmpiController {

    private static final String ADMIN_ROLES = "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')";

    private final EmpiService empiService;

    public EmpiController(EmpiService empiService) {
        this.empiService = empiService;
    }

    @GetMapping("/identities/by-patient/{patientId}")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Fetch the master identity linked to a patient (404 when none exists)")
    public ResponseEntity<EmpiIdentityResponseDTO> getIdentityByPatient(@PathVariable UUID patientId) {
        return empiService.findIdentityByPatientId(patientId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/identities/{primaryIdentityId}/merge")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Merge a duplicate identity into the surviving one",
        description = "Irreversible identity-graph merge: appends the merge event, reassigns aliases, "
            + "soft-deletes the secondary identity, and emits the PATIENT_MERGE audit event.")
    public ResponseEntity<EmpiMergeEventResponseDTO> mergeIdentities(
        @PathVariable UUID primaryIdentityId,
        @Valid @RequestBody EmpiMergeRequestDTO request
    ) {
        return ResponseEntity.ok(empiService.mergeIdentities(primaryIdentityId, request));
    }

    @PostMapping("/merge-by-patient")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Merge two duplicate patients at the identity-graph level",
        description = "Provisions master identities for either patient when absent, then merges the "
            + "secondary into the primary. Clinical rows are not touched — both Patient records remain.")
    public ResponseEntity<EmpiMergeEventResponseDTO> mergeByPatient(
        @Valid @RequestBody EmpiPatientMergeRequestDTO request
    ) {
        return ResponseEntity.ok(empiService.mergePatients(
            request.getPrimaryPatientId(),
            request.getSecondaryPatientId(),
            EmpiMergeType.MANUAL,
            request.getNotes()));
    }
}
