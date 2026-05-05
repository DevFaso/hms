package com.example.hms.controller;

import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.PatientService;
import com.example.hms.service.PlatformAnalyticsService;
import com.example.hms.service.SuperAdminDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminDashboardControllerTest {

    @Mock private SuperAdminDashboardService dashboardService;
    @Mock private AppointmentService appointmentService;
    @Mock private PatientService patientService;
    @Mock private PlatformAnalyticsService analyticsService;

    @InjectMocks private SuperAdminDashboardController controller;

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
}
