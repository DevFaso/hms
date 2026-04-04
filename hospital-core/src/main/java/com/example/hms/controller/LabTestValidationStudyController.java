package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabTestValidationStudyRequestDTO;
import com.example.hms.payload.dto.LabTestValidationStudyResponseDTO;
import com.example.hms.payload.dto.LabValidationSummaryDTO;
import com.example.hms.service.LabTestValidationStudyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Lab Test Validation Studies", description = "CLIA/CLSI validation study records for lab test definitions")
@RequiredArgsConstructor
public class LabTestValidationStudyController {

    private final LabTestValidationStudyService service;

    private static final String LAB_ROLES =
        "hasAnyRole('LAB_SCIENTIST', 'LAB_MANAGER', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')";

    // ── Nested resource (definition-scoped) ──────────────────────────────────

    @PostMapping("/lab-test-definitions/{definitionId}/validation-studies")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "Add a validation study to a lab test definition")
    public ResponseEntity<ApiResponseWrapper<LabTestValidationStudyResponseDTO>> create(
            @PathVariable UUID definitionId,
            @Valid @RequestBody LabTestValidationStudyRequestDTO dto) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.success(service.create(definitionId, dto)));
    }

    @GetMapping("/lab-test-definitions/{definitionId}/validation-studies")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "List all validation studies for a lab test definition")
    public ResponseEntity<ApiResponseWrapper<List<LabTestValidationStudyResponseDTO>>> getByDefinition(
            @PathVariable UUID definitionId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getByDefinitionId(definitionId)));
    }

    // ── Individual resource ──────────────────────────────────────────────────

    @GetMapping("/lab-test-validation-studies/summary")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "Aggregated validation study statistics per test definition")
    public ResponseEntity<ApiResponseWrapper<List<LabValidationSummaryDTO>>> getSummary() {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getValidationSummary()));
    }

    @GetMapping("/lab-test-validation-studies/{id}")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "Get a validation study by ID")
    public ResponseEntity<ApiResponseWrapper<LabTestValidationStudyResponseDTO>> getById(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getById(id)));
    }

    @PutMapping("/lab-test-validation-studies/{id}")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "Update a validation study")
    public ResponseEntity<ApiResponseWrapper<LabTestValidationStudyResponseDTO>> update(
            @PathVariable UUID id,
            @Valid @RequestBody LabTestValidationStudyRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.update(id, dto)));
    }

    @DeleteMapping("/lab-test-validation-studies/{id}")
    @PreAuthorize(LAB_ROLES)
    @Operation(summary = "Delete a validation study")
    public ResponseEntity<ApiResponseWrapper<String>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponseWrapper.success("Deleted successfully."));
    }
}
