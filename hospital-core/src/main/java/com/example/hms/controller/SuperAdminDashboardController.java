package com.example.hms.controller;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.RecentActivityDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.payload.dto.analytics.PlatformAnalyticsDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.HospitalService;
import com.example.hms.service.PatientService;
import com.example.hms.service.PlatformAnalyticsService;
import com.example.hms.service.SuperAdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/super-admin")
@RequiredArgsConstructor
public class SuperAdminDashboardController {

    private final SuperAdminDashboardService dashboardService;
    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final PlatformAnalyticsService analyticsService;
    private final HospitalService hospitalService;
    private final CrossTenantReadAudit crossTenantReadAudit;

    /** Hard cap on the typeahead page size to keep payloads small and predictable. */
    private static final int HOSPITAL_SEARCH_MAX_LIMIT = 20;
    /** Default page size for the hospital typeahead when no {@code limit} param is supplied. */
    private static final int HOSPITAL_SEARCH_DEFAULT_LIMIT = 20;
    /** Minimum query length before we hit the database (avoids a full table scan from a stray keystroke). */
    private static final int HOSPITAL_SEARCH_MIN_QUERY_LENGTH = 2;

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
                .toList()
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
     * Get recent encounters across all hospitals for the super-admin
     * dashboard (docs/super-admin-cross-tenant-design.md, rollout step 3).
     *
     * <p>Both URL forms resolve to this method:
     *
     * <ul>
     *   <li>{@code /super-admin/recent-encounters} — canonical, matching
     *       the {@code /recent-*} naming convention shared by the eight
     *       sister endpoints introduced in commit {@code 2678681f}.</li>
     *   <li>{@code /super-admin/encounters} — kept as a deprecated alias
     *       so existing frontend callers don't break. New callers should
     *       use the canonical path.</li>
     * </ul>
     *
     * <p>Accepts {@link Locale} so message-source-driven fields (e.g.
     * status labels) honour the request's {@code Accept-Language}, in
     * line with {@code getRecentLabOrders} / {@code getRecentLabResults}
     * / {@code getRecentPrescriptions}.
     */
    @GetMapping({"/recent-encounters", "/encounters"})
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<EncounterResponseDTO>> getRecentEncounters(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        Locale locale
    ) {
        List<EncounterResponseDTO> rows = dashboardService.getRecentEncounters(limit, locale);
        crossTenantReadAudit.recordCrossTenantRead("ENCOUNTER", "recent-encounters", rows.size());
        return ResponseEntity.ok(rows);
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

    /**
     * Platform analytics with trend data, status breakdowns, and utilisation metrics.
     */
    @GetMapping("/analytics")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PlatformAnalyticsDTO> getAnalytics(
        @RequestParam(name = "trendDays", required = false, defaultValue = "14") int trendDays
    ) {
        return ResponseEntity.ok(analyticsService.getAnalytics(trendDays));
    }

    @GetMapping("/recent-consultations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<ConsultationResponseDTO>> getRecentConsultations(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<ConsultationResponseDTO> rows = dashboardService.getRecentConsultations(limit);
        crossTenantReadAudit.recordCrossTenantRead("CONSULTATION", "recent-consultations", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-lab-orders")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<LabOrderResponseDTO>> getRecentLabOrders(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        Locale locale
    ) {
        List<LabOrderResponseDTO> rows = dashboardService.getRecentLabOrders(limit, locale);
        crossTenantReadAudit.recordCrossTenantRead("LAB_ORDER", "recent-lab-orders", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-lab-results")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<LabResultResponseDTO>> getRecentLabResults(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        Locale locale
    ) {
        List<LabResultResponseDTO> rows = dashboardService.getRecentLabResults(limit, locale);
        crossTenantReadAudit.recordCrossTenantRead("LAB_RESULT", "recent-lab-results", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-lab-test-definitions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<LabTestDefinitionResponseDTO>> getRecentLabTestDefinitions(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<LabTestDefinitionResponseDTO> rows = dashboardService.getRecentLabTestDefinitions(limit);
        crossTenantReadAudit.recordCrossTenantRead("LAB_TEST_DEFINITION", "recent-lab-test-definitions", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-admissions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<AdmissionResponseDTO>> getRecentAdmissions(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<AdmissionResponseDTO> rows = dashboardService.getRecentAdmissions(limit);
        crossTenantReadAudit.recordCrossTenantRead("ADMISSION", "recent-admissions", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-prescriptions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<PrescriptionResponseDTO>> getRecentPrescriptions(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        Locale locale
    ) {
        List<PrescriptionResponseDTO> rows = dashboardService.getRecentPrescriptions(limit, locale);
        crossTenantReadAudit.recordCrossTenantRead("PRESCRIPTION", "recent-prescriptions", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-treatment-plans")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<TreatmentPlanResponseDTO>> getRecentTreatmentPlans(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<TreatmentPlanResponseDTO> rows = dashboardService.getRecentTreatmentPlans(limit);
        crossTenantReadAudit.recordCrossTenantRead("TREATMENT_PLAN", "recent-treatment-plans", rows.size());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/recent-referrals")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<GeneralReferralResponseDTO>> getRecentReferrals(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<GeneralReferralResponseDTO> rows = dashboardService.getRecentReferrals(limit);
        crossTenantReadAudit.recordCrossTenantRead("REFERRAL", "recent-referrals", rows.size());
        return ResponseEntity.ok(rows);
    }

    /**
     * Aggregate recent-activity feed — F5 from
     * {@code docs/super-admin-cross-tenant-design.md}. Returns the same
     * nine lists the per-feed endpoints above produce, but in a single
     * round-trip so the dashboard's "Recent clinical activity" panel
     * doesn't fan out 8 HTTP calls per page load.
     *
     * <p>Audit: a single {@code DATA_ACCESS} entry per call, classified
     * as {@code RECENT_ACTIVITY_BUNDLE}, instead of nine separate
     * entries — keeps the audit trail compact while preserving
     * traceability (the bundle's {@code rowsReturned} carries the
     * total row count across all nine feeds, and any analyst can drill
     * into the per-feed sort fields from the design doc).</p>
     */
    @GetMapping("/recent-activity")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<RecentActivityDTO> getRecentActivity(
        @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
        Locale locale
    ) {
        RecentActivityDTO bundle = dashboardService.getRecentActivity(limit, locale);
        int totalRows =
            bundle.getEncounters().size()
                + bundle.getConsultations().size()
                + bundle.getLabOrders().size()
                + bundle.getLabResults().size()
                + bundle.getLabTestDefinitions().size()
                + bundle.getAdmissions().size()
                + bundle.getPrescriptions().size()
                + bundle.getTreatmentPlans().size()
                + bundle.getReferrals().size();
        crossTenantReadAudit.recordCrossTenantRead(
            "RECENT_ACTIVITY_BUNDLE", "recent-activity", totalRows);
        return ResponseEntity.ok(bundle);
    }

    /**
     * Server-side typeahead for the super-admin hospital-scope chip
     * (see {@code docs/super-admin-cross-tenant-design.md}). Returns up to
     * {@value #HOSPITAL_SEARCH_MAX_LIMIT} hospitals whose name matches the
     * caller-supplied prefix/substring.
     *
     * <p>Belt-and-braces gating: {@link PreAuthorize} blocks anyone without
     * {@code ROLE_SUPER_ADMIN}, and we additionally re-check the dedicated
     * {@code isSuperAdmin} JWT claim via {@link HospitalContextHolder} so
     * impersonation contexts (where a real super-admin has briefly inflated
     * their authorities to debug as another role) cannot reach cross-tenant
     * data through this endpoint. See design-call #1 in the doc.
     */
    @GetMapping("/hospitals/search")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<HospitalResponseDTO>> searchHospitalsForScopeChip(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "limit", required = false,
            defaultValue = "" + HOSPITAL_SEARCH_DEFAULT_LIMIT) int limit,
        Locale locale
    ) {
        if (!HospitalContextHolder.getContextOrEmpty().isSuperAdmin()) {
            // The PreAuthorize check above can succeed for impersonation contexts
            // where authorities were inflated; the JWT claim is the only signal
            // that the caller is *really* a super-admin right now.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Cross-tenant hospital search is restricted to super-admins.");
        }

        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < HOSPITAL_SEARCH_MIN_QUERY_LENGTH) {
            // Empty / too-short query → empty result (do not return all 10k+).
            return ResponseEntity.ok(Collections.emptyList());
        }

        int safeLimit = Math.max(1, Math.min(limit, HOSPITAL_SEARCH_MAX_LIMIT));
        // active=true: typeahead never offers archived/suspended tenants as a scope.
        List<HospitalResponseDTO> results = hospitalService.searchHospitals(
            trimmed, null, null, Boolean.TRUE, 0, safeLimit, locale);
        crossTenantReadAudit.recordCrossTenantRead(
            "HOSPITAL", "hospitals/search?q=" + trimmed, results.size());
        return ResponseEntity.ok(results);
    }
}
