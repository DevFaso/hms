package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabQcEventRequestDTO;
import com.example.hms.payload.dto.LabQcEventResponseDTO;
import com.example.hms.service.LabQcEventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab-qc-events")
@Tag(name = "Lab QC Events", description = "Endpoints for recording and reviewing analyzer quality-control events")
@RequiredArgsConstructor
public class LabQcEventController {

    private final LabQcEventService labQcEventService;

    @PostMapping
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Record QC Event", description = "Records a new QC calibration event for an analyzer run. Pass/fail is computed automatically (±10% tolerance).")
    @ApiResponse(responseCode = "201", description = "QC event recorded")
    public ResponseEntity<ApiResponseWrapper<LabQcEventResponseDTO>> recordQcEvent(
        @RequestBody LabQcEventRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabQcEventResponseDTO created = labQcEventService.recordQcEvent(request, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get QC Event by ID")
    @ApiResponse(responseCode = "200", description = "QC event retrieved")
    @ApiResponse(responseCode = "404", description = "QC event not found")
    public ResponseEntity<ApiResponseWrapper<LabQcEventResponseDTO>> getQcEventById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            labQcEventService.getQcEventById(id, locale)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List QC Events", description = "Paginated list of QC events. Scoped to the caller's hospital; SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "QC events retrieved")
    public ResponseEntity<ApiResponseWrapper<Page<LabQcEventResponseDTO>>> listQcEvents(
        @RequestParam(required = false) UUID hospitalId,
        @PageableDefault(size = 20, sort = "recordedAt") Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        Page<LabQcEventResponseDTO> page = labQcEventService.getQcEventsByHospital(hospitalId, pageable, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(page));
    }
}
