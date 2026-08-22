package com.example.hms.controller;

import com.example.hms.payload.dto.scheduling.VisitTypeRequestDTO;
import com.example.hms.payload.dto.scheduling.VisitTypeResponseDTO;
import com.example.hms.service.scheduling.VisitTypeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Visit-type catalog (P2 #11).
 *
 * <p>The 2026-08-21 reassessment's finding on the slot foundation: "no row can
 * enter either parent table". This controller is the first way a visit type
 * can exist. Role sets mirror {@link SlotInventoryController} — reads for
 * everyone who searches slots, writes for the roles that may generate them.
 */
@RestController
@RequestMapping(value = "/visit-types", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Visit Types", description = "Catalog of appointment kinds and their durations")
@SecurityRequirement(name = "bearerAuth")
public class VisitTypeController {

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private static final String ADMIN_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private final VisitTypeService visitTypeService;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List visit types for the caller's hospital")
    public ResponseEntity<List<VisitTypeResponseDTO>> list(
        @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(visitTypeService.list(includeInactive));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Create a visit type")
    public ResponseEntity<VisitTypeResponseDTO> create(
        @Valid @RequestBody VisitTypeRequestDTO request) {
        return new ResponseEntity<>(visitTypeService.create(request), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Update a visit type")
    public ResponseEntity<VisitTypeResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody VisitTypeRequestDTO request) {
        return ResponseEntity.ok(visitTypeService.update(id, request));
    }

    /** Retire, don't delete — existing slots and reports keep their meaning. */
    @PutMapping("/{id}/deactivate")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Retire a visit type")
    public ResponseEntity<VisitTypeResponseDTO> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(visitTypeService.deactivate(id));
    }

    @PutMapping("/{id}/reactivate")
    @PreAuthorize(ADMIN_ROLES)
    @Operation(summary = "Reactivate a retired visit type")
    public ResponseEntity<VisitTypeResponseDTO> reactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(visitTypeService.reactivate(id));
    }
}
