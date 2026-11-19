package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabQcTargetRequestDTO;
import com.example.hms.payload.dto.LabQcTargetResponseDTO;
import com.example.hms.service.LabQcTargetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * r63 D2304: QC targets. Every laboratory role reads them (the technician
 * compares a run against them); only the laboratory director sets one, which
 * is the director's approval of it.
 */
@RestController
@RequestMapping("/lab-qc-targets")
@Tag(name = "Lab QC Targets", description = "Target mean and SD per test and control level, set by the lab director")
@RequiredArgsConstructor
public class LabQcTargetController {

    private final LabQcTargetService targetService;

    @GetMapping
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List QC targets", description = "The acting hospital's QC targets, optionally for one test.")
    public ResponseEntity<ApiResponseWrapper<List<LabQcTargetResponseDTO>>> list(
        @RequestParam(required = false) UUID testDefinitionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(targetService.list(testDefinitionId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('LAB_DIRECTOR')")
    @Operation(summary = "Set a QC target", description = "Creates or replaces the target mean and SD for one test and "
        + "control level at the acting hospital. Lab director only; saving is the approval.")
    public ResponseEntity<ApiResponseWrapper<LabQcTargetResponseDTO>> save(
        @jakarta.validation.Valid @RequestBody LabQcTargetRequestDTO request) {
        return ResponseEntity.ok(ApiResponseWrapper.success(targetService.save(request)));
    }
}
