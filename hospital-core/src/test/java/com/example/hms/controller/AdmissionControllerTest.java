package com.example.hms.controller;

import com.example.hms.enums.*;
import com.example.hms.payload.dto.*;
import com.example.hms.service.AdmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AdmissionController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(AdmissionControllerTest.Config.class)
class AdmissionControllerTest {

    private static final String BASE = "/api/admissions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AdmissionService admissionService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(admissionService);
    }

    // ───────────────────────── helpers ─────────────────────────

    private AdmissionRequestDTO buildAdmitRequest() {
        AdmissionRequestDTO dto = new AdmissionRequestDTO();
        dto.setPatientId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());
        dto.setAdmittingProviderId(UUID.randomUUID());
        dto.setDepartmentId(UUID.randomUUID());
        dto.setRoomBed("ICU-101-A");
        dto.setAdmissionType(AdmissionType.EMERGENCY);
        dto.setAcuityLevel(AcuityLevel.LEVEL_4_SEVERE);
        dto.setAdmissionDateTime(LocalDateTime.now());
        dto.setExpectedDischargeDateTime(LocalDateTime.now().plusDays(5));
        dto.setChiefComplaint("Chest pain radiating to left arm");
        dto.setPrimaryDiagnosisCode("I21.0");
        dto.setPrimaryDiagnosisDescription("Acute ST-elevation MI");
        dto.setSecondaryDiagnoses(List.of(Map.of("code", "I10", "description", "Essential hypertension")));
        dto.setAdmissionSource("ED");
        dto.setOrderSetIds(List.of(UUID.randomUUID()));
        dto.setCustomOrders(List.of(Map.of("type", "lab", "name", "Troponin I")));
        dto.setAdmissionNotes("High acuity — cardiology consult requested");
        dto.setAttendingPhysicianId(UUID.randomUUID());
        dto.setInsuranceAuthNumber("AUTH-2026-98765");
        dto.setMetadata(Map.of("source", "ED-triage"));
        return dto;
    }

    private AdmissionResponseDTO buildAdmissionResponse(UUID admissionId) {
        AdmissionResponseDTO dto = new AdmissionResponseDTO();
        dto.setId(admissionId);
        dto.setPatientId(UUID.randomUUID());
        dto.setPatientName("Jane Doe");
        dto.setPatientMrn("MRN-0001");
        dto.setHospitalId(UUID.randomUUID());
        dto.setHospitalName("General Hospital");
        dto.setAdmittingProviderId(UUID.randomUUID());
        dto.setAdmittingProviderName("Dr. Smith");
        dto.setDepartmentId(UUID.randomUUID());
        dto.setDepartmentName("Cardiology");
        dto.setRoomBed("ICU-101-A");
        dto.setAdmissionType(AdmissionType.EMERGENCY);
        dto.setStatus(AdmissionStatus.ACTIVE);
        dto.setAcuityLevel(AcuityLevel.LEVEL_4_SEVERE);
        dto.setAdmissionDateTime(LocalDateTime.now());
        dto.setChiefComplaint("Chest pain");
        dto.setPrimaryDiagnosisCode("I21.0");
        dto.setPrimaryDiagnosisDescription("Acute ST-elevation MI");
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }

    private AdmissionUpdateRequestDTO buildUpdateRequest() {
        AdmissionUpdateRequestDTO dto = new AdmissionUpdateRequestDTO();
        dto.setRoomBed("ICU-102-B");
        dto.setAcuityLevel(AcuityLevel.LEVEL_5_CRITICAL);
        dto.setAdmissionNotes("Patient escalated to critical");
        return dto;
    }

    private AdmissionDischargeRequestDTO buildDischargeRequest() {
        AdmissionDischargeRequestDTO dto = new AdmissionDischargeRequestDTO();
        dto.setDischargeDisposition(DischargeDisposition.HOME);
        dto.setDischargeSummary("Stable for home discharge");
        dto.setDischargeInstructions("Follow up cardiology in 7 days");
        dto.setDischargingProviderId(UUID.randomUUID());
        dto.setFollowUpAppointments(List.of(Map.of("specialty", "Cardiology", "daysOut", 7)));
        return dto;
    }

    private AdmissionOrderExecutionRequestDTO buildApplyOrderSetsRequest() {
        AdmissionOrderExecutionRequestDTO dto = new AdmissionOrderExecutionRequestDTO();
        dto.setOrderSetIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        dto.setAppliedByStaffId(UUID.randomUUID());
        return dto;
    }

    private AdmissionOrderSetRequestDTO buildOrderSetRequest() {
        AdmissionOrderSetRequestDTO dto = new AdmissionOrderSetRequestDTO();
        dto.setName("Cardiac Admission Order Set");
        dto.setDescription("Standard orders for STEMI admissions");
        dto.setAdmissionType(AdmissionType.EMERGENCY);
        dto.setDepartmentId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());
        dto.setOrderItems(List.of(
            Map.of("type", "medication", "name", "Aspirin 325mg", "route", "PO"),
            Map.of("type", "lab", "name", "CBC", "frequency", "daily")
        ));
        dto.setClinicalGuidelines("AHA STEMI guidelines 2025");
        dto.setActive(true);
        dto.setCreatedByStaffId(UUID.randomUUID());
        return dto;
    }

    private AdmissionOrderSetResponseDTO buildOrderSetResponse(UUID orderSetId) {
        AdmissionOrderSetResponseDTO dto = new AdmissionOrderSetResponseDTO();
        dto.setId(orderSetId);
        dto.setName("Cardiac Admission Order Set");
        dto.setDescription("Standard orders for STEMI admissions");
        dto.setAdmissionType(AdmissionType.EMERGENCY);
        dto.setHospitalId(UUID.randomUUID());
        dto.setHospitalName("General Hospital");
        dto.setOrderItems(List.of(Map.of("type", "medication", "name", "Aspirin")));
        dto.setActive(true);
        dto.setOrderCount(2);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }

    // ═══════════════════════ Admission CRUD ═══════════════════════

    @Nested
    @DisplayName("POST /api/admissions — admitPatient")
    class AdmitPatient {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 201 CREATED with admission response")
        void admitPatient_created() throws Exception {
            UUID id = UUID.randomUUID();
            when(admissionService.admitPatient(any(AdmissionRequestDTO.class)))
                .thenReturn(buildAdmissionResponse(id));

            mockMvc.perform(post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildAdmitRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.patientName").value("Jane Doe"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(admissionService).admitPatient(any(AdmissionRequestDTO.class));
        }
    }

    @Nested
    @DisplayName("GET /api/admissions/{admissionId} — getAdmission")
    class GetAdmission {

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 200 OK with admission details")
        void getAdmission_ok() throws Exception {
            UUID id = UUID.randomUUID();
            when(admissionService.getAdmission(id)).thenReturn(buildAdmissionResponse(id));

            mockMvc.perform(get(BASE + "/{admissionId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.hospitalName").value("General Hospital"));

            verify(admissionService).getAdmission(id);
        }
    }

    @Nested
    @DisplayName("PUT /api/admissions/{admissionId} — updateAdmission")
    class UpdateAdmission {

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 200 OK with updated admission")
        void updateAdmission_ok() throws Exception {
            UUID id = UUID.randomUUID();
            AdmissionResponseDTO response = buildAdmissionResponse(id);
            response.setRoomBed("ICU-102-B");
            when(admissionService.updateAdmission(eq(id), any(AdmissionUpdateRequestDTO.class)))
                .thenReturn(response);

            mockMvc.perform(put(BASE + "/{admissionId}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomBed").value("ICU-102-B"));

            verify(admissionService).updateAdmission(eq(id), any(AdmissionUpdateRequestDTO.class));
        }
    }

    @Nested
    @DisplayName("POST /api/admissions/{admissionId}/apply-order-sets — applyOrderSets")
    class ApplyOrderSets {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK after applying order sets")
        void applyOrderSets_ok() throws Exception {
            UUID id = UUID.randomUUID();
            when(admissionService.applyOrderSets(eq(id), any(AdmissionOrderExecutionRequestDTO.class)))
                .thenReturn(buildAdmissionResponse(id));

            mockMvc.perform(post(BASE + "/{admissionId}/apply-order-sets", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildApplyOrderSetsRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

            verify(admissionService).applyOrderSets(eq(id), any(AdmissionOrderExecutionRequestDTO.class));
        }
    }

    @Nested
    @DisplayName("POST /api/admissions/{admissionId}/discharge — dischargePatient")
    class DischargePatient {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with discharged admission")
        void dischargePatient_ok() throws Exception {
            UUID id = UUID.randomUUID();
            AdmissionResponseDTO response = buildAdmissionResponse(id);
            response.setDischargeDisposition(DischargeDisposition.HOME);
            response.setDischargeSummary("Stable for home discharge");
            when(admissionService.dischargePatient(eq(id), any(AdmissionDischargeRequestDTO.class)))
                .thenReturn(response);

            mockMvc.perform(post(BASE + "/{admissionId}/discharge", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildDischargeRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dischargeDisposition").value("HOME"))
                .andExpect(jsonPath("$.dischargeSummary").value("Stable for home discharge"));

            verify(admissionService).dischargePatient(eq(id), any(AdmissionDischargeRequestDTO.class));
        }
    }

    @Nested
    @DisplayName("DELETE /api/admissions/{admissionId} — cancelAdmission")
    class CancelAdmission {

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 204 NO CONTENT")
        void cancelAdmission_noContent() throws Exception {
            UUID id = UUID.randomUUID();
            doNothing().when(admissionService).cancelAdmission(id);

            mockMvc.perform(delete(BASE + "/{admissionId}", id))
                .andExpect(status().isNoContent());

            verify(admissionService).cancelAdmission(id);
        }
    }

    // ═══════════════════════ Patient queries ═══════════════════════

    @Nested
    @DisplayName("GET /api/admissions/patient/{patientId} — getAdmissionsByPatient")
    class GetAdmissionsByPatient {

        @Test
        @WithMockUser(authorities = "ROLE_RECEPTIONIST")
        @DisplayName("returns 200 OK with admission list")
        void getAdmissionsByPatient_ok() throws Exception {
            UUID patientId = UUID.randomUUID();
            List<AdmissionResponseDTO> list = List.of(
                buildAdmissionResponse(UUID.randomUUID()),
                buildAdmissionResponse(UUID.randomUUID())
            );
            when(admissionService.getAdmissionsByPatient(patientId)).thenReturn(list);

            mockMvc.perform(get(BASE + "/patient/{patientId}", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

            verify(admissionService).getAdmissionsByPatient(patientId);
        }
    }

    @Nested
    @DisplayName("GET /api/admissions/patient/{patientId}/current — getCurrentAdmissionForPatient")
    class GetCurrentAdmission {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK when active admission exists")
        void getCurrentAdmission_found() throws Exception {
            UUID patientId = UUID.randomUUID();
            UUID admissionId = UUID.randomUUID();
            when(admissionService.getCurrentAdmissionForPatient(patientId))
                .thenReturn(buildAdmissionResponse(admissionId));

            mockMvc.perform(get(BASE + "/patient/{patientId}/current", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admissionId.toString()));

            verify(admissionService).getCurrentAdmissionForPatient(patientId);
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 404 NOT FOUND when no active admission")
        void getCurrentAdmission_notFound() throws Exception {
            UUID patientId = UUID.randomUUID();
            when(admissionService.getCurrentAdmissionForPatient(patientId)).thenReturn(null);

            mockMvc.perform(get(BASE + "/patient/{patientId}/current", patientId))
                .andExpect(status().isNotFound());

            verify(admissionService).getCurrentAdmissionForPatient(patientId);
        }
    }

    // ═══════════════════════ Hospital queries ═══════════════════════

    @Nested
    @DisplayName("GET /api/admissions/hospital/{hospitalId} — getAdmissionsByHospital")
    class GetAdmissionsByHospital {

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 200 OK with filtered admissions — all params")
        void getAdmissionsByHospital_allParams() throws Exception {
            UUID hospitalId = UUID.randomUUID();
            LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
            LocalDateTime end = LocalDateTime.of(2026, 1, 31, 23, 59);

            when(admissionService.getAdmissionsByHospital(eq(hospitalId), eq("ACTIVE"), eq(start), eq(end)))
                .thenReturn(List.of(buildAdmissionResponse(UUID.randomUUID())));

            mockMvc.perform(get(BASE + "/hospital/{hospitalId}", hospitalId)
                    .param("status", "ACTIVE")
                    .param("startDate", "2026-01-01T00:00:00")
                    .param("endDate", "2026-01-31T23:59:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

            verify(admissionService).getAdmissionsByHospital(hospitalId, "ACTIVE", start, end);
        }

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with no filters")
        void getAdmissionsByHospital_noFilters() throws Exception {
            UUID hospitalId = UUID.randomUUID();
            when(admissionService.getAdmissionsByHospital(eq(hospitalId), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

            mockMvc.perform(get(BASE + "/hospital/{hospitalId}", hospitalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

            verify(admissionService).getAdmissionsByHospital(hospitalId, null, null, null);
        }
    }

    // ═══════════════════════ Order Set Management ═══════════════════════

    @Nested
    @DisplayName("POST /api/admissions/order-sets — createOrderSet")
    class CreateOrderSet {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 201 CREATED with order set response")
        void createOrderSet_created() throws Exception {
            UUID orderSetId = UUID.randomUUID();
            when(admissionService.createOrderSet(any(AdmissionOrderSetRequestDTO.class)))
                .thenReturn(buildOrderSetResponse(orderSetId));

            mockMvc.perform(post(BASE + "/order-sets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(buildOrderSetRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderSetId.toString()))
                .andExpect(jsonPath("$.name").value("Cardiac Admission Order Set"));

            verify(admissionService).createOrderSet(any(AdmissionOrderSetRequestDTO.class));
        }
    }

    @Nested
    @DisplayName("GET /api/admissions/order-sets/{orderSetId} — getOrderSet")
    class GetOrderSet {

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 200 OK with order set details")
        void getOrderSet_ok() throws Exception {
            UUID orderSetId = UUID.randomUUID();
            when(admissionService.getOrderSet(orderSetId))
                .thenReturn(buildOrderSetResponse(orderSetId));

            mockMvc.perform(get(BASE + "/order-sets/{orderSetId}", orderSetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderSetId.toString()))
                .andExpect(jsonPath("$.active").value(true));

            verify(admissionService).getOrderSet(orderSetId);
        }
    }

    @Nested
    @DisplayName("GET /api/admissions/order-sets/hospital/{hospitalId} — getOrderSetsByHospital")
    class GetOrderSetsByHospital {

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 200 OK with order sets — with admissionType filter")
        void getOrderSetsByHospital_withFilter() throws Exception {
            UUID hospitalId = UUID.randomUUID();
            when(admissionService.getOrderSetsByHospital(hospitalId, "EMERGENCY"))
                .thenReturn(List.of(buildOrderSetResponse(UUID.randomUUID())));

            mockMvc.perform(get(BASE + "/order-sets/hospital/{hospitalId}", hospitalId)
                    .param("admissionType", "EMERGENCY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

            verify(admissionService).getOrderSetsByHospital(hospitalId, "EMERGENCY");
        }

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with order sets — no filter")
        void getOrderSetsByHospital_noFilter() throws Exception {
            UUID hospitalId = UUID.randomUUID();
            when(admissionService.getOrderSetsByHospital(eq(hospitalId), isNull()))
                .thenReturn(List.of());

            mockMvc.perform(get(BASE + "/order-sets/hospital/{hospitalId}", hospitalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

            verify(admissionService).getOrderSetsByHospital(hospitalId, null);
        }
    }

    @Nested
    @DisplayName("POST /api/admissions/order-sets/{orderSetId}/deactivate — deactivateOrderSet")
    class DeactivateOrderSet {

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 204 NO CONTENT")
        void deactivateOrderSet_noContent() throws Exception {
            UUID orderSetId = UUID.randomUUID();
            UUID staffId = UUID.randomUUID();
            doNothing().when(admissionService).deactivateOrderSet(orderSetId, "Outdated protocol", staffId);

            mockMvc.perform(post(BASE + "/order-sets/{orderSetId}/deactivate", orderSetId)
                    .param("reason", "Outdated protocol")
                    .param("deactivatedByStaffId", staffId.toString()))
                .andExpect(status().isNoContent());

            verify(admissionService).deactivateOrderSet(orderSetId, "Outdated protocol", staffId);
        }
    }

    // ───────────────────────── test config ─────────────────────────

    @TestConfiguration
    static class Config {
        @Bean
        AdmissionService admissionService() {
            return Mockito.mock(AdmissionService.class);
        }
    }
}
