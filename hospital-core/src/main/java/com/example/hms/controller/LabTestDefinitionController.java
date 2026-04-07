package com.example.hms.controller;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabTestDefinitionApprovalRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.LabTestReferenceRangeDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.LabTestDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/lab-test-definitions")
@Tag(name = "Lab Test Definition Management", description = "CRUD operations for lab test definitions")
@RequiredArgsConstructor
public class LabTestDefinitionController {

    private final LabTestDefinitionService service;

    // LAB_SCIENTIST and LAB_MANAGER may draft definitions; LAB_DIRECTOR/QUALITY_MANAGER/SUPER_ADMIN/HOSPITAL_ADMIN manage all
    private static final String MANAGE_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'LAB_DIRECTOR', 'QUALITY_MANAGER', 'SUPER_ADMIN')";
    private static final String VIEW_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'SUPER_ADMIN', 'DOCTOR', 'NURSE', 'MIDWIFE', 'LAB_DIRECTOR', 'QUALITY_MANAGER')";
    // Gate-level check: all roles that may initiate any approval transition.
    // Per-action role enforcement is performed inside LabTestDefinitionServiceImpl.
    private static final String APPROVAL_ROLES = "hasAnyRole('LAB_DIRECTOR', 'LAB_MANAGER', 'LAB_SCIENTIST', 'QUALITY_MANAGER', 'SUPER_ADMIN')";

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
    @Operation(summary = "Search Lab Test Definitions by keyword, unit, category, status and approval status")
    public ResponseEntity<ApiResponseWrapper<Page<LabTestDefinitionResponseDTO>>> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String unit,
        @RequestParam(required = false) String category,
        @RequestParam(name = "isActive", required = false) Boolean isActive,
        @RequestParam(required = false) LabTestDefinitionApprovalStatus approvalStatus,
        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.search(keyword, unit, category, isActive, approvalStatus, pageable)));
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

    /**
     * Approval workflow endpoint.
     * Actions: SUBMIT_FOR_QA, COMPLETE_QA_REVIEW, APPROVE, ACTIVATE, REJECT, RETIRE.
     * Role requirements are enforced in the service layer per action.
     */
    @PostMapping("/{id}/approval")
    @PreAuthorize(APPROVAL_ROLES)
    @Operation(summary = "Process an approval action on a Lab Test Definition")
    public ResponseEntity<ApiResponseWrapper<LabTestDefinitionResponseDTO>> processApproval(
        @PathVariable UUID id,
        @Valid @RequestBody LabTestDefinitionApprovalRequestDTO dto) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.processApprovalAction(id, dto)));
    }

    @PutMapping("/{id}/reference-ranges")
    @PreAuthorize(MANAGE_ROLES)
    @Operation(summary = "Update reference ranges for a Lab Test Definition")
    public ResponseEntity<ApiResponseWrapper<LabTestDefinitionResponseDTO>> updateReferenceRanges(
        @PathVariable UUID id,
        @Valid @RequestBody java.util.List<LabTestReferenceRangeDTO> ranges) {
        return ResponseEntity.ok(ApiResponseWrapper.success(service.updateReferenceRanges(id, ranges)));
    }

    @GetMapping("/export/pdf")
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "Export Lab Test Definitions as PDF")
    public void exportPdf(HttpServletResponse response) throws IOException {
        byte[] pdfBytes = service.exportPdf();
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=\"lab-test-definitions.pdf\"");
        response.setContentLength(pdfBytes.length);
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    @GetMapping("/export")
    @PreAuthorize(VIEW_ROLES)
    @Operation(summary = "Export Lab Test Definitions as CSV (hospital-scoped: global + hospital-specific)")
    public void exportCsv(HttpServletResponse response) throws IOException {
        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElse(null);

        List<LabTestDefinitionResponseDTO> definitions = hospitalId != null
                ? service.getActiveByHospital(hospitalId)
                : service.getAll();

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"lab-test-definitions.csv\"");
        PrintWriter writer = response.getWriter();
        writer.println("Test Code,Test Name,Category,Unit,Sample Type,Active,Approval Status,Turnaround Time (min)");
        for (LabTestDefinitionResponseDTO dto : definitions) {
            writer.printf("%s,%s,%s,%s,%s,%s,%s,%s%n",
                    escapeCsv(dto.getTestCode()),
                    escapeCsv(dto.getName()),
                    escapeCsv(dto.getCategory()),
                    escapeCsv(dto.getUnit()),
                    escapeCsv(dto.getSampleType()),
                    dto.isActive(),
                    escapeCsv(dto.getApprovalStatus()),
                    dto.getTurnaroundTime() != null ? dto.getTurnaroundTime() : "");
        }
        writer.flush();
    }

    private static String escapeCsv(String val) {
        if (val == null) return "";
        // Neutralize CSV formula injection (values starting with =, +, -, @, tab, CR)
        if (!val.isEmpty() && "=+-@\t\r".indexOf(val.charAt(0)) >= 0) {
            val = "'" + val;
        }
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}


