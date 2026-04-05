package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.service.LabSpecimenService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping
@Tag(name = "Lab Specimen Management", description = "Endpoints for managing lab specimens and chain of custody")
@RequiredArgsConstructor
public class LabSpecimenController {

    private final LabSpecimenService labSpecimenService;

    /**
     * Collect a new specimen for a lab order.
     * POST /lab-orders/{id}/specimens
     */
    @PostMapping("/lab-orders/{id}/specimens")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Collect Specimen",
               description = "Creates a new specimen record for the given lab order, generating an accession number and barcode.")
    @ApiResponse(responseCode = "201", description = "Specimen created successfully")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<LabSpecimenResponseDTO>> collectSpecimen(
        @PathVariable UUID id,
        @RequestBody LabSpecimenRequestDTO requestDTO,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        // Override labOrderId with path variable to avoid inconsistency
        requestDTO.setLabOrderId(id);
        LabSpecimenResponseDTO created = labSpecimenService.createSpecimen(requestDTO, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    /**
     * List all specimens for a lab order.
     * GET /lab-orders/{id}/specimens
     */
    @GetMapping("/lab-orders/{id}/specimens")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'DOCTOR', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List Specimens for Lab Order",
               description = "Returns all specimen records linked to the given lab order.")
    @ApiResponse(responseCode = "200", description = "Specimens retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Lab order not found")
    public ResponseEntity<ApiResponseWrapper<List<LabSpecimenResponseDTO>>> getSpecimensByLabOrder(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        List<LabSpecimenResponseDTO> specimens = labSpecimenService.getSpecimensByLabOrder(id, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(specimens));
    }

    /**
     * Get a single specimen by ID.
     * GET /lab-specimens/{id}
     */
    @GetMapping("/lab-specimens/{id}")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'DOCTOR', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get Specimen by ID",
               description = "Fetches a single lab specimen by its UUID.")
    @ApiResponse(responseCode = "200", description = "Specimen retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Specimen not found")
    public ResponseEntity<ApiResponseWrapper<LabSpecimenResponseDTO>> getSpecimenById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabSpecimenResponseDTO specimen = labSpecimenService.getSpecimenById(id, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(specimen));
    }

    /**
     * Mark a specimen as received at the laboratory.
     * POST /lab-specimens/{id}/receive
     */
    @PostMapping("/lab-specimens/{id}/receive")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Receive Specimen at Lab",
               description = "Records the specimen as received at the laboratory, updating status to RECEIVED and capturing receiving user and timestamp.")
    @ApiResponse(responseCode = "200", description = "Specimen received successfully")
    @ApiResponse(responseCode = "400", description = "Invalid specimen state for receipt")
    @ApiResponse(responseCode = "404", description = "Specimen not found")
    public ResponseEntity<ApiResponseWrapper<LabSpecimenResponseDTO>> receiveSpecimen(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabSpecimenResponseDTO received = labSpecimenService.receiveSpecimen(id, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(received));
    }
}
