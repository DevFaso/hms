package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.service.LabTestDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequestMapping("/lab-test-definitions")
@Tag(name = "Lab Test Definition Management", description = "CRUD operations for lab test definitions")
@RequiredArgsConstructor
public class LabTestDefinitionController {

    private final LabTestDefinitionService service;
    private static final String MANAGE_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'SUPER_ADMIN')";
    private static final String VIEW_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'SUPER_ADMIN', 'DOCTOR', 'NURSE', 'MIDWIFE')";

    // Only ADMIN/LAB_MANAGER/SCIENTIST/SUPER_ADMIN can create lab tests
    @PostMapping("/batch")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Create multiple Lab Test Definitions")
    public ResponseEntity<ApiResponseWrapper<List<LabTestDefinitionResponseDTO>>> createBatch(
        @RequestBody List<LabTestDefinitionRequestDTO> dtoList) {
        List<LabTestDefinitionResponseDTO> responses = dtoList.stream()
            .map(service::create)
            .toList();
        return ResponseEntity.ok(ApiResponseWrapper.success(responses));
    }

    @PostMapping
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Create a Lab Test Definition")
    public ResponseEntity<ApiResponseWrapper<LabTestDefinitionResponseDTO>> create(
        @Valid @RequestBody LabTestDefinitionRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.create(dto)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "Get Lab Test Definition by ID")
    public ResponseEntity<ApiResponseWrapper<LabTestDefinitionResponseDTO>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getById(id)));
    }

    @GetMapping
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "List all Lab Test Definitions")
    public ResponseEntity<ApiResponseWrapper<List<LabTestDefinitionResponseDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getAll()));
    }

    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "List active Lab Test Definitions for a hospital")
    public ResponseEntity<ApiResponseWrapper<List<LabTestDefinitionResponseDTO>>> getActiveByHospital(
        @PathVariable UUID hospitalId
    ) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.getActiveByHospital(hospitalId)));
    }

    @GetMapping("/search")
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "Search Lab Test Definitions by keyword and unit with pagination")
    public ResponseEntity<ApiResponseWrapper<Page<LabTestDefinitionResponseDTO>>> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String unit,
        @RequestParam(required = false) String category,
        @RequestParam(name = "isActive", required = false) Boolean isActive,
        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.search(keyword, unit, category, isActive, pageable)));
    }

    // Only ADMIN/LAB_MANAGER/SCIENTIST can update lab tests
    @PutMapping("/{id}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Update Lab Test Definition")
    public ResponseEntity<ApiResponseWrapper<LabTestDefinitionResponseDTO>> update(
        @PathVariable UUID id,
        @Valid @RequestBody LabTestDefinitionRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.update(id, dto)));
    }

    // Only ADMIN/LAB_MANAGER/SCIENTIST can delete lab tests
    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Delete Lab Test Definition")
    public ResponseEntity<ApiResponseWrapper<String>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponseWrapper.success("Deleted successfully."));
    }
}


