package com.example.hms.controller;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.service.GeneralReferralService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = GeneralReferralController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(GeneralReferralControllerTest.ControllerTestConfig.class)
class GeneralReferralControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneralReferralService referralService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(referralService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /api/referrals creates new referral")
    void createReferral_returnsCreatedReferral() throws Exception {
        UUID referralId = UUID.randomUUID();
        GeneralReferralRequestDTO request = buildRequest();
        GeneralReferralResponseDTO response = buildResponse(referralId);

        when(referralService.createReferral(any(GeneralReferralRequestDTO.class))).thenReturn(response);

        mockMvc.perform(post("/api/referrals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(referralId.toString()))
            .andExpect(jsonPath("$.status").value(ReferralStatus.DRAFT.name()))
            .andExpect(jsonPath("$.patientId").value(response.getPatientId().toString()));

        verify(referralService).createReferral(any(GeneralReferralRequestDTO.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void submitReferral_returnsUpdatedStatus() throws Exception {
        UUID referralId = UUID.randomUUID();
        GeneralReferralResponseDTO response = buildResponse(referralId);
        response.setStatus(ReferralStatus.SUBMITTED);
        response.setSubmittedAt(LocalDateTime.now());

        when(referralService.submitReferral(referralId)).thenReturn(response);

        mockMvc.perform(post("/api/referrals/{referralId}/submit", referralId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.SUBMITTED.name()));

        verify(referralService).submitReferral(referralId);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void acknowledgeReferral_requiresReceivingProvider() throws Exception {
        UUID referralId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        GeneralReferralResponseDTO response = buildResponse(referralId);
        response.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(referralService.acknowledgeReferral(referralId, "notes", providerId)).thenReturn(response);

        mockMvc.perform(post("/api/referrals/{id}/acknowledge", referralId)
                .param("notes", "notes")
                .param("receivingProviderId", providerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.ACKNOWLEDGED.name()));

        verify(referralService).acknowledgeReferral(referralId, "notes", providerId);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void cancelReferral_returns204() throws Exception {
        UUID referralId = UUID.randomUUID();

        mockMvc.perform(post("/api/referrals/{id}/cancel", referralId)
                .param("reason", "duplicate"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(referralService).cancelReferral(referralId, "duplicate");
    }

    @Test
    @WithMockUser(authorities = {"ROLE_HOSPITAL_ADMIN"})
    void getReferralsByHospital_supportsStatusFilter() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        GeneralReferralResponseDTO response = buildResponse(UUID.randomUUID());
        response.setStatus(ReferralStatus.SUBMITTED);

        when(referralService.getReferralsByHospital(hospitalId, "submitted")).thenReturn(List.of(response));

        mockMvc.perform(get("/api/referrals/hospital/{hospitalId}", hospitalId)
                .param("status", "submitted"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value(ReferralStatus.SUBMITTED.name()));

        verify(referralService).getReferralsByHospital(hospitalId, "submitted");
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void getOverdueReferrals_returnsList() throws Exception {
        GeneralReferralResponseDTO overdue = buildResponse(UUID.randomUUID());
        overdue.setIsOverdue(true);

        when(referralService.getOverdueReferrals()).thenReturn(List.of(overdue));

        mockMvc.perform(get("/api/referrals/overdue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].isOverdue").value(true));

        verify(referralService).getOverdueReferrals();
    }

    private GeneralReferralRequestDTO buildRequest() {
        GeneralReferralRequestDTO request = new GeneralReferralRequestDTO();
        request.setPatientId(UUID.randomUUID());
        request.setHospitalId(UUID.randomUUID());
        request.setReferringProviderId(UUID.randomUUID());
        request.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        request.setReferralType(ReferralType.CONSULTATION);
        request.setUrgency(ReferralUrgency.PRIORITY);
        request.setReferralReason("Consult opinion");
        request.setCurrentMedications(List.of(Map.of("name", "Med")));
        return request;
    }

    private GeneralReferralResponseDTO buildResponse(UUID referralId) {
        GeneralReferralResponseDTO dto = new GeneralReferralResponseDTO();
        dto.setId(referralId);
        dto.setPatientId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());
        dto.setReferringProviderId(UUID.randomUUID());
        dto.setStatus(ReferralStatus.DRAFT);
        dto.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        dto.setReferralType(ReferralType.CONSULTATION);
        dto.setUrgency(ReferralUrgency.PRIORITY);
        dto.setCurrentMedications(List.of(Map.of("name", "Med")));
        dto.setDiagnoses(List.of(Map.of("code", "A00")));
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }
    
    @TestConfiguration
    static class ControllerTestConfig {
        @Bean
        GeneralReferralService referralService() {
            return Mockito.mock(GeneralReferralService.class);
        }
    }
}
