package com.example.hms.controller;

import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.PatientService;
import com.example.hms.service.SuperAdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/super-admin")
@RequiredArgsConstructor
public class SuperAdminDashboardController {

    private final SuperAdminDashboardService dashboardService;
    private final AppointmentService appointmentService;
    private final PatientService patientService;

    /**
     * Aggregated metrics + a slice of recent audit events for dashboard widgets.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<SuperAdminSummaryDTO> getSummary(
        @RequestParam(name = "auditLimit", required = false, defaultValue = "10") int auditLimit
    ) {
        return ResponseEntity.ok(dashboardService.getSummary(auditLimit));
    }

    /**
     * Get recent appointments across all hospitals for super admin dashboard.
     */
    @GetMapping("/appointments")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<AppointmentResponseDTO>> getRecentAppointments(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        org.springframework.security.core.Authentication authentication
    ) {
        Locale locale = Locale.getDefault(); // Super admin context - use default locale
        String username = authentication.getName();
        
        // Get all appointments for super admin and limit on the backend
        List<AppointmentResponseDTO> appointments = appointmentService.getAppointmentsForUser(username, locale);
        
        // Sort by creation date descending and limit
        return ResponseEntity.ok(
            appointments.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .limit(limit)
                .collect(java.util.stream.Collectors.toList())
        );
    }

    /**
     * Get recent patients for super admin dashboard.
     */
    @GetMapping("/patients")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<PatientResponseDTO>> getRecentPatients(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        Locale locale = Locale.getDefault(); // Super admin context - use default locale
        
        // Get all patients globally (hospitalId = null) and limit results
        List<PatientResponseDTO> allPatients = patientService.getAllPatients(null, locale);
        
        // Sort by creation date descending and limit
        return ResponseEntity.ok(
            allPatients.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .limit(limit)
                .toList()
        );
    }

    /**
     * Get recent encounters across all hospitals for super admin dashboard.
     */
    @GetMapping("/encounters")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<EncounterResponseDTO>> getRecentEncounters(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        Locale locale = Locale.getDefault();
        return ResponseEntity.ok(dashboardService.getRecentEncounters(limit, locale));
    }

    /**
     * Get recent staff availability records for super admin dashboard.
     */
    @GetMapping("/staff-availability")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<StaffAvailabilityResponseDTO>> getRecentStaffAvailability(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getRecentStaffAvailability(limit));
    }

    /**
     * Get recent patient consents for super admin dashboard.
     */
    @GetMapping("/patient-consents")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<PatientConsentResponseDTO>> getRecentPatientConsents(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getRecentPatientConsents(limit));
    }
}
