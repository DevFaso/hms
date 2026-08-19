package com.example.hms.controller;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.RecentActivityDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.HospitalService;
import com.example.hms.service.PatientService;
import com.example.hms.service.PlatformAnalyticsService;
import com.example.hms.service.SuperAdminDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminDashboardControllerTest {

    @Mock private SuperAdminDashboardService dashboardService;
    @Mock private AppointmentService appointmentService;
    @Mock private PatientService patientService;
    @Mock private PlatformAnalyticsService analyticsService;
    @Mock private HospitalService hospitalService;
    @Mock private CrossTenantReadAudit crossTenantReadAudit;

    @InjectMocks private SuperAdminDashboardController controller;

    @org.junit.jupiter.api.BeforeEach
    void setupSuperAdminContext() {
        // After the F1 / Copilot follow-up, every cross-tenant endpoint
        // re-checks the JWT-claim super-admin flag via
        // requireRealSuperAdminFromJwtClaim(). Default the HospitalContext
        // to a real super-admin so the existing happy-path tests don't
        // each have to set it; gate-test cases override this with
        // nonSuperAdminContext().
        HospitalContextHolder.setContext(superAdminContext());
    }

    @AfterEach
    void clearTenantContext() {
        // Tests below set HospitalContextHolder; clear so we don't leak state
        // into other tests run on the same thread.
        HospitalContextHolder.clear();
    }

    private static HospitalContext superAdminContext() {
        return HospitalContext.builder().superAdmin(true).build();
    }

    private static HospitalContext nonSuperAdminContext() {
        return HospitalContext.builder().superAdmin(false).build();
    }

    private static String preAuthorizeOf(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = SuperAdminDashboardController.class.getDeclaredMethod(methodName, paramTypes);
        PreAuthorize a = method.getAnnotation(PreAuthorize.class);
        assertThat(a).as("@PreAuthorize on %s", methodName).isNotNull();
        return a.value();
    }

    @Test
    void allRecentEndpoints_requireSuperAdmin() throws Exception {
        assertThat(preAuthorizeOf("getRecentConsultations", int.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentLabOrders", int.class, Locale.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentLabResults", int.class, Locale.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentLabTestDefinitions", int.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentAdmissions", int.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentPrescriptions", int.class, Locale.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentTreatmentPlans", int.class)).contains("SUPER_ADMIN");
        assertThat(preAuthorizeOf("getRecentReferrals", int.class)).contains("SUPER_ADMIN");
        // Step 3 of docs/super-admin-cross-tenant-design.md — encounters
        // joined the family with the canonical /recent-encounters path.
        assertThat(preAuthorizeOf("getRecentEncounters", int.class, Locale.class)).contains("SUPER_ADMIN");
    }

    @Test
    void getRecentConsultations_returnsBodyFromService() {
        List<ConsultationResponseDTO> stub = List.of(new ConsultationResponseDTO());
        when(dashboardService.getRecentConsultations(anyInt())).thenReturn(stub);

        ResponseEntity<List<ConsultationResponseDTO>> result = controller.getRecentConsultations(20);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).hasSize(1);
    }

    @Test
    void getRecentLabOrders_returnsBodyFromService() {
        when(dashboardService.getRecentLabOrders(anyInt(), any())).thenReturn(List.of(new LabOrderResponseDTO()));
        assertThat(controller.getRecentLabOrders(20, Locale.ENGLISH).getBody()).hasSize(1);
    }

    @Test
    void getRecentLabOrders_passesCallerLocale() {
        when(dashboardService.getRecentLabOrders(anyInt(), any())).thenReturn(List.of(new LabOrderResponseDTO()));
        controller.getRecentLabOrders(20, Locale.FRENCH);
        org.mockito.Mockito.verify(dashboardService).getRecentLabOrders(20, Locale.FRENCH);
    }

    @Test
    void getRecentLabResults_returnsBodyFromService() {
        when(dashboardService.getRecentLabResults(anyInt(), any()))
            .thenReturn(List.of(LabResultResponseDTO.builder().build()));
        assertThat(controller.getRecentLabResults(20, Locale.ENGLISH).getBody()).hasSize(1);
    }

    @Test
    void getRecentLabTestDefinitions_returnsBodyFromService() {
        when(dashboardService.getRecentLabTestDefinitions(anyInt()))
            .thenReturn(List.of(new LabTestDefinitionResponseDTO()));
        assertThat(controller.getRecentLabTestDefinitions(20).getBody()).hasSize(1);
    }

    @Test
    void getRecentAdmissions_returnsBodyFromService() {
        when(dashboardService.getRecentAdmissions(anyInt())).thenReturn(List.of(new AdmissionResponseDTO()));
        assertThat(controller.getRecentAdmissions(20).getBody()).hasSize(1);
    }

    @Test
    void getRecentPrescriptions_returnsBodyFromService() {
        when(dashboardService.getRecentPrescriptions(anyInt(), any()))
            .thenReturn(List.of(new PrescriptionResponseDTO()));
        assertThat(controller.getRecentPrescriptions(20, Locale.ENGLISH).getBody()).hasSize(1);
    }

    @Test
    void getRecentPrescriptions_passesCallerLocale() {
        when(dashboardService.getRecentPrescriptions(anyInt(), any())).thenReturn(List.of(new PrescriptionResponseDTO()));
        controller.getRecentPrescriptions(20, Locale.GERMAN);
        org.mockito.Mockito.verify(dashboardService).getRecentPrescriptions(20, Locale.GERMAN);
    }

    @Test
    void getRecentTreatmentPlans_returnsBodyFromService() {
        when(dashboardService.getRecentTreatmentPlans(anyInt()))
            .thenReturn(List.of(new TreatmentPlanResponseDTO()));
        assertThat(controller.getRecentTreatmentPlans(20).getBody()).hasSize(1);
    }

    @Test
    void getRecentReferrals_returnsBodyFromService() {
        when(dashboardService.getRecentReferrals(anyInt()))
            .thenReturn(List.of(new GeneralReferralResponseDTO()));
        assertThat(controller.getRecentReferrals(20).getBody()).hasSize(1);
    }

    /* ────────────────────────────────────────────────────────────────────
     * GET /super-admin/recent-encounters (+ legacy /encounters alias) —
     * step 3 of docs/super-admin-cross-tenant-design.md. Closes the 1-of-9
     * naming gap noted in copilot-review.md.
     * ──────────────────────────────────────────────────────────────────── */

    @Test
    void getRecentEncounters_returnsBodyFromService() {
        when(dashboardService.getRecentEncounters(anyInt(), any()))
            .thenReturn(List.of(new EncounterResponseDTO()));
        assertThat(controller.getRecentEncounters(20, Locale.ENGLISH).getBody()).hasSize(1);
    }

    @Test
    void getRecentEncounters_passesCallerLocale() {
        // Honours Accept-Language for message-source-driven fields, in
        // line with getRecentLabOrders / getRecentLabResults / etc.
        when(dashboardService.getRecentEncounters(anyInt(), any()))
            .thenReturn(List.of(new EncounterResponseDTO()));
        controller.getRecentEncounters(20, Locale.FRENCH);
        verify(dashboardService).getRecentEncounters(20, Locale.FRENCH);
    }

    @Test
    void getRecentEncounters_isMappedToCanonicalAndLegacyPaths() throws Exception {
        // The canonical path is /recent-encounters (matching the eight
        // sister endpoints); /encounters is preserved as a deprecated
        // alias so existing frontend callers don't break. If either
        // disappears, this test catches it.
        var method = SuperAdminDashboardController.class.getDeclaredMethod(
            "getRecentEncounters", int.class, Locale.class);
        var mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).contains("/recent-encounters", "/encounters");
    }

    /* ────────────────────────────────────────────────────────────────────
     * GET /super-admin/hospitals/search — typeahead for the hospital-scope
     * chip on cross-tenant clinical pages
     * (docs/super-admin-cross-tenant-design.md, design call #1).
     * ──────────────────────────────────────────────────────────────────── */

    @Test
    void searchHospitalsForScopeChip_requiresSuperAdminAuthority() throws Exception {
        assertThat(preAuthorizeOf("searchHospitalsForScopeChip", String.class, int.class, Locale.class))
            .contains("SUPER_ADMIN");
    }

    @Test
    void searchHospitalsForScopeChip_blocksWhenIsSuperAdminClaimAbsent() {
        // The PreAuthorize check passes (test bypasses Spring Security), but
        // the controller must additionally enforce the dedicated isSuperAdmin
        // JWT claim — otherwise impersonation contexts could read cross-tenant.
        HospitalContextHolder.setContext(nonSuperAdminContext());

        assertThatThrownBy(() ->
            controller.searchHospitalsForScopeChip("memo", 20, Locale.ENGLISH))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));

        verify(hospitalService, never())
            .searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void searchHospitalsForScopeChip_returnsEmptyListWhenQueryTooShort() {
        HospitalContextHolder.setContext(superAdminContext());

        // null, "", " ", "a" — all below the 2-char minimum.
        assertThat(controller.searchHospitalsForScopeChip(null, 20, Locale.ENGLISH).getBody())
            .isEmpty();
        assertThat(controller.searchHospitalsForScopeChip("", 20, Locale.ENGLISH).getBody())
            .isEmpty();
        assertThat(controller.searchHospitalsForScopeChip("  ", 20, Locale.ENGLISH).getBody())
            .isEmpty();
        assertThat(controller.searchHospitalsForScopeChip("a", 20, Locale.ENGLISH).getBody())
            .isEmpty();

        verify(hospitalService, never())
            .searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void searchHospitalsForScopeChip_returnsServiceResultsForRealSuperAdmin() {
        HospitalContextHolder.setContext(superAdminContext());
        HospitalResponseDTO match = new HospitalResponseDTO();
        when(hospitalService.searchHospitals(eq("memo"), any(), any(), eq(Boolean.TRUE),
            eq(0), anyInt(), eq(Locale.ENGLISH)))
            .thenReturn(List.of(match));

        ResponseEntity<List<HospitalResponseDTO>> result =
            controller.searchHospitalsForScopeChip("memo", 20, Locale.ENGLISH);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).containsExactly(match);
    }

    @Test
    void searchHospitalsForScopeChip_capsLimitAtServerMaximum() {
        HospitalContextHolder.setContext(superAdminContext());
        when(hospitalService.searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        // Caller asked for 1000; server must clamp to 20 (HOSPITAL_SEARCH_MAX_LIMIT).
        controller.searchHospitalsForScopeChip("memorial", 1000, Locale.ENGLISH);

        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hospitalService).searchHospitals(
            eq("memorial"), any(), any(), eq(Boolean.TRUE),
            eq(0), sizeCaptor.capture(), eq(Locale.ENGLISH));
        assertThat(sizeCaptor.getValue()).isEqualTo(20);
    }

    @Test
    void searchHospitalsForScopeChip_clampsNegativeOrZeroLimitToOne() {
        HospitalContextHolder.setContext(superAdminContext());
        when(hospitalService.searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        controller.searchHospitalsForScopeChip("memo", -5, Locale.ENGLISH);

        ArgumentCaptor<Integer> sizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(hospitalService).searchHospitals(
            any(), any(), any(), any(), eq(0), sizeCaptor.capture(), any());
        assertThat(sizeCaptor.getValue()).isEqualTo(1);
    }

    @Test
    void searchHospitalsForScopeChip_excludesArchivedHospitals() {
        // active=true must be forwarded to the service — the chip should
        // never offer SUSPENDED / ARCHIVED tenants as a switching target.
        HospitalContextHolder.setContext(superAdminContext());
        when(hospitalService.searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        controller.searchHospitalsForScopeChip("memo", 20, Locale.ENGLISH);

        verify(hospitalService).searchHospitals(
            eq("memo"), any(), any(), eq(Boolean.TRUE), eq(0), eq(20), eq(Locale.ENGLISH));
    }

    @Test
    void searchHospitalsForScopeChip_trimsWhitespaceFromQuery() {
        HospitalContextHolder.setContext(superAdminContext());
        when(hospitalService.searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of());

        controller.searchHospitalsForScopeChip("  memo  ", 20, Locale.ENGLISH);

        verify(hospitalService).searchHospitals(
            eq("memo"), any(), any(), any(), anyInt(), anyInt(), any());
    }

    /* ────────────────────────────────────────────────────────────────────
     * F3 — cross-tenant read audit hook.
     * docs/super-admin-cross-tenant-design.md design call #4 / F5 follow-up:
     * every cross-tenant read must go through the audit layer. These tests
     * lock the wiring per endpoint so a future refactor that loses the
     * audit call is caught immediately.
     * ──────────────────────────────────────────────────────────────────── */

    @Test
    void getRecentEncounters_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentEncounters(anyInt(), any()))
            .thenReturn(List.of(new EncounterResponseDTO(), new EncounterResponseDTO()));

        controller.getRecentEncounters(20, Locale.ENGLISH);

        verify(crossTenantReadAudit).recordCrossTenantRead("ENCOUNTER", "recent-encounters", 2);
    }

    @Test
    void getRecentConsultations_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentConsultations(anyInt()))
            .thenReturn(List.of(new ConsultationResponseDTO()));

        controller.getRecentConsultations(20);

        verify(crossTenantReadAudit).recordCrossTenantRead("CONSULTATION", "recent-consultations", 1);
    }

    @Test
    void getRecentLabOrders_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentLabOrders(anyInt(), any()))
            .thenReturn(List.of(new LabOrderResponseDTO()));
        controller.getRecentLabOrders(20, Locale.ENGLISH);
        verify(crossTenantReadAudit).recordCrossTenantRead("LAB_ORDER", "recent-lab-orders", 1);
    }

    @Test
    void getRecentLabResults_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentLabResults(anyInt(), any()))
            .thenReturn(List.of(LabResultResponseDTO.builder().build()));
        controller.getRecentLabResults(20, Locale.ENGLISH);
        verify(crossTenantReadAudit).recordCrossTenantRead("LAB_RESULT", "recent-lab-results", 1);
    }

    @Test
    void getRecentLabTestDefinitions_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentLabTestDefinitions(anyInt()))
            .thenReturn(List.of(new LabTestDefinitionResponseDTO()));
        controller.getRecentLabTestDefinitions(20);
        verify(crossTenantReadAudit).recordCrossTenantRead(
            "LAB_TEST_DEFINITION", "recent-lab-test-definitions", 1);
    }

    @Test
    void getRecentAdmissions_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentAdmissions(anyInt()))
            .thenReturn(List.of(new AdmissionResponseDTO()));
        controller.getRecentAdmissions(20);
        verify(crossTenantReadAudit).recordCrossTenantRead("ADMISSION", "recent-admissions", 1);
    }

    @Test
    void getRecentPrescriptions_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentPrescriptions(anyInt(), any()))
            .thenReturn(List.of(new PrescriptionResponseDTO()));
        controller.getRecentPrescriptions(20, Locale.ENGLISH);
        verify(crossTenantReadAudit).recordCrossTenantRead("PRESCRIPTION", "recent-prescriptions", 1);
    }

    @Test
    void getRecentTreatmentPlans_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentTreatmentPlans(anyInt()))
            .thenReturn(List.of(new TreatmentPlanResponseDTO()));
        controller.getRecentTreatmentPlans(20);
        verify(crossTenantReadAudit).recordCrossTenantRead("TREATMENT_PLAN", "recent-treatment-plans", 1);
    }

    @Test
    void getRecentReferrals_recordsCrossTenantReadAudit() {
        when(dashboardService.getRecentReferrals(anyInt()))
            .thenReturn(List.of(new GeneralReferralResponseDTO()));
        controller.getRecentReferrals(20);
        verify(crossTenantReadAudit).recordCrossTenantRead("REFERRAL", "recent-referrals", 1);
    }

    @Test
    void searchHospitalsForScopeChip_recordsCrossTenantReadAudit() {
        HospitalContextHolder.setContext(superAdminContext());
        when(hospitalService.searchHospitals(any(), any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(List.of(new HospitalResponseDTO()));

        controller.searchHospitalsForScopeChip("memo", 20, Locale.ENGLISH);

        verify(crossTenantReadAudit).recordCrossTenantRead(
            "HOSPITAL", "hospitals/search?q=memo", 1);
    }

    @Test
    void searchHospitalsForScopeChip_doesNotAuditWhenJwtClaimAbsent() {
        // Belt-and-braces gate trips first → no audit emission.
        HospitalContextHolder.setContext(nonSuperAdminContext());

        assertThatThrownBy(() ->
            controller.searchHospitalsForScopeChip("memo", 20, Locale.ENGLISH))
            .isInstanceOf(ResponseStatusException.class);

        verify(crossTenantReadAudit, never()).recordCrossTenantRead(any(), any(), anyInt());
    }

    /* ────────────────────────────────────────────────────────────────────
     * F5 — aggregate GET /super-admin/recent-activity.
     * Replaces the dashboard's 8 fan-out calls with a single round-trip.
     * docs/super-admin-cross-tenant-design.md F5.
     * ──────────────────────────────────────────────────────────────────── */

    @Test
    void getRecentActivity_requiresSuperAdmin() throws Exception {
        assertThat(preAuthorizeOf("getRecentActivity", int.class, Locale.class))
            .contains("SUPER_ADMIN");
    }

    @Test
    void getRecentActivity_returnsBundleAndAuditsTotalRowCount() {
        // Each per-feed contributes a different number of rows so the
        // aggregate `rowsReturned` (passed to the audit hook) can only
        // be correct if the controller summed across all 9 feeds.
        RecentActivityDTO bundle = RecentActivityDTO.builder()
            .encounters(List.of(new EncounterResponseDTO(), new EncounterResponseDTO()))     // 2
            .consultations(List.of(new ConsultationResponseDTO()))                            // 1
            .labOrders(List.of())                                                             // 0
            .labResults(List.of(LabResultResponseDTO.builder().build()))                      // 1
            .labTestDefinitions(List.of(new LabTestDefinitionResponseDTO()))                  // 1
            .admissions(List.of(new AdmissionResponseDTO()))                                  // 1
            .prescriptions(List.of(new PrescriptionResponseDTO(), new PrescriptionResponseDTO()))// 2
            .treatmentPlans(List.of())                                                        // 0
            .referrals(List.of(new GeneralReferralResponseDTO()))                             // 1
            .build();
        when(dashboardService.getRecentActivity(anyInt(), any())).thenReturn(bundle);

        ResponseEntity<RecentActivityDTO> result =
            controller.getRecentActivity(20, Locale.ENGLISH);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isSameAs(bundle);
        // F3 + F5: a single audit entry for the bundle, with the sum of
        // all 9 per-feed row counts (2+1+0+1+1+1+2+0+1 = 9).
        verify(crossTenantReadAudit).recordCrossTenantRead(
            "RECENT_ACTIVITY_BUNDLE", "recent-activity", 9);
    }

    @Test
    void getRecentActivity_propagatesCallerLocale() {
        when(dashboardService.getRecentActivity(anyInt(), any()))
            .thenReturn(RecentActivityDTO.builder()
                .encounters(List.of()).consultations(List.of()).labOrders(List.of())
                .labResults(List.of()).labTestDefinitions(List.of()).admissions(List.of())
                .prescriptions(List.of()).treatmentPlans(List.of()).referrals(List.of())
                .build());

        controller.getRecentActivity(15, Locale.FRENCH);

        verify(dashboardService).getRecentActivity(15, Locale.FRENCH);
    }

    /**
     * Copilot review 2026-05-06: {@code RecentActivityDTO} fields are
     * nullable. The audit-hook row-count must treat null lists as 0
     * rather than NPE. Builds a bundle where every list is null and
     * verifies the controller (a) returns the body unchanged and (b)
     * records 0 rows on the audit hook.
     */
    @Test
    void getRecentActivity_isNullSafeWhenBundleListsAreNull() {
        RecentActivityDTO sparseBundle = RecentActivityDTO.builder().build(); // all 9 lists null
        when(dashboardService.getRecentActivity(anyInt(), any())).thenReturn(sparseBundle);

        ResponseEntity<RecentActivityDTO> result =
            controller.getRecentActivity(20, Locale.ENGLISH);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isSameAs(sparseBundle);
        verify(crossTenantReadAudit).recordCrossTenantRead(
            "RECENT_ACTIVITY_BUNDLE", "recent-activity", 0);
    }

    /* ────────────────────────────────────────────────────────────────────
     * Copilot review 2026-05-06 — JWT-claim re-check on every cross-tenant
     * endpoint. Each `recent-*` endpoint + `/recent-activity` must apply
     * the same belt-and-braces gate the search endpoint already had,
     * BEFORE calling the service and BEFORE emitting the audit event.
     * ──────────────────────────────────────────────────────────────────── */

    @Test
    void everyCrossTenantEndpoint_blocks403WhenJwtClaimAbsent() {
        // Override the @BeforeEach default with an inflated-authorities
        // / no-claim context — what an impersonation token looks like.
        HospitalContextHolder.setContext(nonSuperAdminContext());

        java.util.List<Runnable> calls = java.util.List.of(
            () -> controller.getRecentEncounters(10, Locale.ENGLISH),
            () -> controller.getRecentConsultations(10),
            () -> controller.getRecentLabOrders(10, Locale.ENGLISH),
            () -> controller.getRecentLabResults(10, Locale.ENGLISH),
            () -> controller.getRecentLabTestDefinitions(10),
            () -> controller.getRecentAdmissions(10),
            () -> controller.getRecentPrescriptions(10, Locale.ENGLISH),
            () -> controller.getRecentTreatmentPlans(10),
            () -> controller.getRecentReferrals(10),
            () -> controller.getRecentActivity(10, Locale.ENGLISH)
        );

        for (Runnable call : calls) {
            assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
        }

        // Critical: neither the data path nor the audit hook must have
        // been reached. The gate fires BEFORE both.
        org.mockito.Mockito.verifyNoInteractions(dashboardService);
        verify(crossTenantReadAudit, never()).recordCrossTenantRead(any(), any(), anyInt());
    }
}
