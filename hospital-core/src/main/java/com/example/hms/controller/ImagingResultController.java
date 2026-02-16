package com.example.hms.controller;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.service.ImagingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/imaging/results")
@Validated
@RequiredArgsConstructor
@Tag(name = "Imaging Results", description = "View radiology reports, PACS images, and acknowledge critical findings")
public class ImagingResultController {

    private final ImagingReportService imagingReportService;

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
    @Operation(summary = "Get imaging report by ID", 
               description = "Retrieve full imaging report with findings, impression, and PACS viewer URL")
    public ResponseEntity<ImagingReportResponseDTO> getReport(@PathVariable UUID reportId) {
        ImagingReportResponseDTO report = imagingReportService.getReport(reportId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
    @Operation(summary = "Get latest report for imaging order",
               description = "Retrieve the most recent report version for a specific imaging order")
    public ResponseEntity<ImagingReportResponseDTO> getLatestReportForOrder(@PathVariable UUID orderId) {
        ImagingReportResponseDTO report = imagingReportService.getLatestReportForOrder(orderId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/order/{orderId}/all")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "Get all report versions for imaging order",
               description = "Retrieve complete history of report versions (preliminary, final, addenda)")
    public ResponseEntity<List<ImagingReportResponseDTO>> getAllReportsForOrder(@PathVariable UUID orderId) {
        List<ImagingReportResponseDTO> reports = imagingReportService.getReportsForOrder(orderId);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "List imaging reports for hospital",
               description = "Filter by status (e.g., PRELIMINARY, FINAL, CRITICAL) or modality (CT, MRI, X-RAY)")
    public ResponseEntity<List<ImagingReportResponseDTO>> getReportsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) ImagingReportStatus status,
        @RequestParam(required = false) ImagingModality modality
    ) {
        List<ImagingReportResponseDTO> reports;
        
        if (status != null) {
            reports = imagingReportService.getReportsByHospitalAndStatus(hospitalId, status);
        } else if (modality != null) {
            reports = imagingReportService.getReportsByHospitalAndModality(hospitalId, modality);
        } else {
            // If no filter specified, return by status=FINAL as default
            reports = imagingReportService.getReportsByHospitalAndStatus(hospitalId, ImagingReportStatus.FINAL);
        }
        
        return ResponseEntity.ok(reports);
    }

    @PutMapping("/{reportId}/status")
    @PreAuthorize("hasAuthority('ACKNOWLEDGE_CRITICAL_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "Update report status or acknowledge critical result",
               description = "Mark critical results as acknowledged by ordering provider. Tracks acknowledgment timestamp and staff.")
    public ResponseEntity<ImagingReportResponseDTO> updateReportStatus(
        @PathVariable UUID reportId,
        @Valid @RequestBody ImagingReportStatusUpdateRequestDTO request
    ) {
        ImagingReportResponseDTO report = imagingReportService.updateReportStatus(reportId, request);
        return ResponseEntity.ok(report);
    }

    @PutMapping("/{reportId}/acknowledge-critical")
    @PreAuthorize("hasAuthority('ACKNOWLEDGE_CRITICAL_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR')")
    @Operation(summary = "Acknowledge critical imaging result",
               description = "Specialized endpoint for STAT/critical result acknowledgment workflow")
    public ResponseEntity<ImagingReportResponseDTO> acknowledgeCriticalResult(
        @PathVariable UUID reportId,
        @RequestParam UUID acknowledgingStaffId
    ) {
        ImagingReportStatusUpdateRequestDTO request = ImagingReportStatusUpdateRequestDTO.builder()
            .changedByStaffId(acknowledgingStaffId)
            .statusReason("Critical result acknowledged")
            .build();
        
        ImagingReportResponseDTO report = imagingReportService.updateReportStatus(reportId, request);
        return ResponseEntity.ok(report);
    }
}
