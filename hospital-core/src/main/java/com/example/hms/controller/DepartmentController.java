package com.example.hms.controller;

import com.example.hms.payload.dto.DepartmentFilterDTO;
import com.example.hms.payload.dto.DepartmentMinimalDTO;
import com.example.hms.payload.dto.DepartmentRequestDTO;
import com.example.hms.payload.dto.DepartmentResponseDTO;
import com.example.hms.payload.dto.DepartmentStatsDTO;
import com.example.hms.payload.dto.DepartmentWithStaffDTO;
import com.example.hms.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @Operation(summary = "Get all departments", description = "Returns a list of all departments with localized data.")
    @ApiResponse(responseCode = "200", description = "Successful retrieval of departments")
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<List<DepartmentResponseDTO>> getAllDepartments(
            @RequestParam(name = "organizationId", required = false) UUID organizationId,
            @RequestParam(name = "unassignedOnly", required = false) Boolean unassignedOnly,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "state", required = false) String state,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        List<DepartmentResponseDTO> departments = departmentService.getAllDepartments(
            organizationId,
            unassignedOnly,
            city,
            state,
            locale
        );
        return ResponseEntity.ok(departments);
    }

    @Operation(summary = "Get department by ID", description = "Returns a department by its ID with localized content.")
    @ApiResponse(responseCode = "200", description = "Department found")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentResponseDTO> getDepartmentById(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        DepartmentResponseDTO response = departmentService.getDepartmentById(id, locale);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Create department", description = "Creates a new department (head optional).")
    @ApiResponse(responseCode = "201", description = "Department created successfully")
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentResponseDTO> createDepartment(
            @Valid @RequestBody DepartmentRequestDTO request,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        DepartmentResponseDTO created = departmentService.createDepartment(request, locale);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/by-hospital/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Page<DepartmentResponseDTO>> getDepartmentsByHospital(
        @PathVariable UUID hospitalId,
        @ParameterObject Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.getDepartmentsByHospital(hospitalId, pageable, locale));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Page<DepartmentResponseDTO>> searchDepartments(
        @RequestParam String query,
        @ParameterObject Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.searchDepartments(query, pageable, locale));
    }

    @PostMapping("/filter")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN') or hasRole('DOCTOR') or hasRole('NURSE') or hasRole('MIDWIFE')")
    public ResponseEntity<Page<DepartmentResponseDTO>> filterDepartments(
        @RequestBody DepartmentFilterDTO filter,
        @ParameterObject Pageable pageable,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.filterDepartments(filter, pageable, locale));
    }

    @Operation(summary = "Update department", description = "Updates department details and optionally assigns a new head.")
    @ApiResponse(responseCode = "200", description = "Department updated successfully")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentResponseDTO> updateDepartment(
            @PathVariable UUID id,
            @Valid @RequestBody DepartmentRequestDTO request,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        DepartmentResponseDTO updated = departmentService.updateDepartment(id, request, locale);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Delete department", description = "Deletes the department if no staff are assigned.")
    @ApiResponse(responseCode = "204", description = "Department deleted successfully")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Void> deleteDepartment(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        departmentService.deleteDepartment(id, locale);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get department with staff", description = "Returns a department with full staff listing.")
    @GetMapping("/{id}/with-staff")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentWithStaffDTO> getDepartmentWithStaff(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.getDepartmentWithStaff(id, locale));
    }

    @Operation(summary = "Update head of department", description = "Assigns a new head of department.")
    @PutMapping("/{id}/update-head/{staffId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentResponseDTO> updateDepartmentHead(
            @PathVariable UUID id,
            @PathVariable UUID staffId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.updateDepartmentHead(id, staffId, locale));
    }

    @Operation(summary = "Get department statistics", description = "Returns statistics like total staff, doctors, nurses.")
    @GetMapping("/{id}/stats")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<DepartmentStatsDTO> getDepartmentStatistics(
            @PathVariable UUID id,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.getDepartmentStatistics(id, locale));
    }

    @Operation(summary = "Get active departments (minimal)", description = "Returns a minimal list of active departments in a hospital.")
    @GetMapping("/active-minimal/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN') or hasRole('DOCTOR')")
    public ResponseEntity<List<DepartmentMinimalDTO>> getActiveDepartmentsMinimal(
            @PathVariable UUID hospitalId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.getActiveDepartmentsMinimal(hospitalId, locale));
    }

    @Operation(summary = "Check if staff is head of any department", description = "Returns true if given staff is a department head.")
    @GetMapping("/is-head/{staffId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Boolean> isHeadOfDepartment(
            @PathVariable UUID staffId,
            @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(departmentService.isHeadOfDepartment(staffId, locale));
    }
}
