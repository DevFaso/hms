package com.example.hms.controller;

import com.example.hms.payload.dto.bed.BedBoardDTO;
import com.example.hms.payload.dto.bed.BedRequestDTO;
import com.example.hms.payload.dto.bed.BedResponseDTO;
import com.example.hms.payload.dto.bed.BedStatusUpdateRequestDTO;
import com.example.hms.payload.dto.bed.WardRequestDTO;
import com.example.hms.payload.dto.bed.WardResponseDTO;
import com.example.hms.service.BedBoardService;
import com.example.hms.service.BedManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Ward and bed administration (P0 #4). The V25 ward/bed schema previously had
 * zero writers — the occupancy dashboard could only ever show zeros.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Bed Management", description = "Ward and bed administration + the available-bed picker")
public class BedManagementController {

    private static final String READ_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')";
    private static final String MANAGE_ROLES =
        "hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')";

    private final BedManagementService bedManagementService;
    private final BedBoardService bedBoardService;

    /* ── The board (Tier 2 item 31) ───────────────────────────────────── */

    @GetMapping("/bed-board")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Ward → room → bed with occupants, isolation flags and the census")
    public ResponseEntity<BedBoardDTO> getBedBoard() {
        return ResponseEntity.ok(bedBoardService.getBoard());
    }

    /* ── Wards ────────────────────────────────────────────────────────── */

    @GetMapping("/wards")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List wards for the active hospital with bed counts")
    public ResponseEntity<List<WardResponseDTO>> getWards(
        @RequestParam(name = "includeInactive", required = false, defaultValue = "false") boolean includeInactive
    ) {
        return ResponseEntity.ok(bedManagementService.getWards(includeInactive));
    }

    @PostMapping("/wards")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Create a ward in the active hospital")
    public ResponseEntity<WardResponseDTO> createWard(@Valid @RequestBody WardRequestDTO request) {
        return new ResponseEntity<>(bedManagementService.createWard(request), HttpStatus.CREATED);
    }

    @PutMapping("/wards/{wardId}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Update a ward (including activation state)")
    public ResponseEntity<WardResponseDTO> updateWard(
        @PathVariable UUID wardId,
        @Valid @RequestBody WardRequestDTO request
    ) {
        return ResponseEntity.ok(bedManagementService.updateWard(wardId, request));
    }

    @DeleteMapping("/wards/{wardId}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Delete a ward (only when it has no beds)")
    public ResponseEntity<Void> deleteWard(@PathVariable UUID wardId) {
        bedManagementService.deleteWard(wardId);
        return ResponseEntity.noContent().build();
    }

    /* ── Beds ─────────────────────────────────────────────────────────── */

    @GetMapping("/wards/{wardId}/beds")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List all beds of a ward")
    public ResponseEntity<List<BedResponseDTO>> getBedsByWard(@PathVariable UUID wardId) {
        return ResponseEntity.ok(bedManagementService.getBedsByWard(wardId));
    }

    @GetMapping("/beds/available")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List assignable beds (active + AVAILABLE) across the active hospital")
    public ResponseEntity<List<BedResponseDTO>> getAvailableBeds() {
        return ResponseEntity.ok(bedManagementService.getAvailableBeds());
    }

    @PostMapping("/wards/{wardId}/beds")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Create a bed in a ward")
    public ResponseEntity<BedResponseDTO> createBed(
        @PathVariable UUID wardId,
        @Valid @RequestBody BedRequestDTO request
    ) {
        return new ResponseEntity<>(bedManagementService.createBed(wardId, request), HttpStatus.CREATED);
    }

    @PutMapping("/beds/{bedId}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Update a bed's details")
    public ResponseEntity<BedResponseDTO> updateBed(
        @PathVariable UUID bedId,
        @Valid @RequestBody BedRequestDTO request
    ) {
        return ResponseEntity.ok(bedManagementService.updateBed(bedId, request));
    }

    @PatchMapping("/beds/{bedId}/status")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Change a bed's status (maintenance, reserve, back in service)")
    public ResponseEntity<BedResponseDTO> updateBedStatus(
        @PathVariable UUID bedId,
        @Valid @RequestBody BedStatusUpdateRequestDTO request
    ) {
        return ResponseEntity.ok(bedManagementService.updateBedStatus(bedId, request));
    }

    @DeleteMapping("/beds/{bedId}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Delete a bed (not while occupied)")
    public ResponseEntity<Void> deleteBed(@PathVariable UUID bedId) {
        bedManagementService.deleteBed(bedId);
        return ResponseEntity.noContent().build();
    }
}
