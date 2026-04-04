package com.example.hms.controller;

import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.dashboard.HospitalAdminSummaryDTO;
import com.example.hms.payload.dto.dashboard.LabDirectorDashboardDTO;
import com.example.hms.payload.dto.dashboard.LabOpsSummaryDTO;
import com.example.hms.payload.dto.dashboard.QualityManagerDashboardDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.DashboardConfigurationService;
import com.example.hms.service.HospitalAdminDashboardService;
import com.example.hms.service.LabDirectorDashboardService;
import com.example.hms.service.LabOpsDashboardService;
import com.example.hms.service.QualityManagerDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard")
public class DashboardController {

    private static final String NO_HOSPITAL_CONTEXT = "No hospital context available";

    private final DashboardConfigurationService dashboardConfigurationService;
    private final HospitalAdminDashboardService hospitalAdminDashboardService;
    private final LabDirectorDashboardService labDirectorDashboardService;
    private final LabOpsDashboardService labOpsDashboardService;
    private final QualityManagerDashboardService qualityManagerDashboardService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get dashboard configuration for the authenticated user")
    public ResponseEntity<DashboardConfigResponseDTO> getDashboardForCurrentUser() {
        DashboardConfigResponseDTO response = dashboardConfigurationService.getDashboardForCurrentUser();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/hospital-admin/summary")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get hospital admin operations summary")
    public ResponseEntity<HospitalAdminSummaryDTO> getHospitalAdminSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") int auditLimit) {

        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElseThrow(() -> new IllegalStateException(NO_HOSPITAL_CONTEXT));

        LocalDate asOfDate = (date != null) ? date : LocalDate.now();
        HospitalAdminSummaryDTO summary = hospitalAdminDashboardService.getSummary(hospitalId, asOfDate, auditLimit);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/lab-director/summary")
    @PreAuthorize("hasRole('LAB_DIRECTOR')")
    @Operation(summary = "Get Lab Director operational dashboard summary")
    public ResponseEntity<LabDirectorDashboardDTO> getLabDirectorSummary() {
        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElseThrow(() -> new IllegalStateException(NO_HOSPITAL_CONTEXT));

        return ResponseEntity.ok(labDirectorDashboardService.getSummary(hospitalId));
    }

    @GetMapping("/quality-manager/summary")
    @PreAuthorize("hasRole('QUALITY_MANAGER')")
    @Operation(summary = "Get Quality Manager dashboard summary")
    public ResponseEntity<QualityManagerDashboardDTO> getQualityManagerSummary() {
        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElseThrow(() -> new IllegalStateException(NO_HOSPITAL_CONTEXT));

        return ResponseEntity.ok(qualityManagerDashboardService.getSummary(hospitalId));
    }

    @GetMapping("/lab-ops/summary")
    @PreAuthorize("hasAnyRole('LAB_DIRECTOR', 'LAB_MANAGER', 'QUALITY_MANAGER', 'HOSPITAL_ADMIN', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get Lab Operations dashboard summary")
    public ResponseEntity<LabOpsSummaryDTO> getLabOpsSummary() {
        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElseThrow(() -> new IllegalStateException(NO_HOSPITAL_CONTEXT));

        return ResponseEntity.ok(labOpsDashboardService.getSummary(hospitalId));
    }
}
