package com.example.hms.controller;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
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

    @InjectMocks private SuperAdminDashboardController controller;

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
}
