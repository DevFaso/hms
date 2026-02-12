package com.example.hms.controller;

import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;
import com.example.hms.service.HighRiskPregnancyCarePlanService;
import com.example.hms.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = HighRiskPregnancyCarePlanController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class HighRiskPregnancyCarePlanControllerTest {

    private static final String AUTH_USER = "clinician";
    private static final String AUTH_PASSWORD = "password";
    private static final String CONTEXT_PATH = "/api";
    private static final String BASE_PATH = "/high-risk-care-plans";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HighRiskPregnancyCarePlanService carePlanService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void createPlanReturnsCreatedResponse() throws Exception {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        HighRiskPregnancyCarePlanRequestDTO request = HighRiskPregnancyCarePlanRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();

        HighRiskPregnancyCarePlanResponseDTO response = HighRiskPregnancyCarePlanResponseDTO.builder()
            .id(UUID.randomUUID())
            .patientId(patientId)
            .hospitalId(hospitalId)
            .build();

        when(carePlanService.createPlan(any(HighRiskPregnancyCarePlanRequestDTO.class), eq(AUTH_USER)))
            .thenReturn(response);

        Authentication auth = new TestingAuthenticationToken(AUTH_USER, AUTH_PASSWORD);
        auth.setAuthenticated(true);

    mockMvc.perform(post(CONTEXT_PATH + BASE_PATH)
        .contextPath(CONTEXT_PATH)
        .with(authentication(auth))
        .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(response.getId().toString()))
            .andExpect(jsonPath("$.patientId").value(patientId.toString()));
    }

    @Test
    void getActivePlanReturnsNoContentWhenAbsent() throws Exception {
        UUID patientId = UUID.randomUUID();
    when(carePlanService.getActivePlan(eq(patientId), eq(AUTH_USER))).thenReturn(null);

    Authentication auth = new TestingAuthenticationToken(AUTH_USER, AUTH_PASSWORD);
        auth.setAuthenticated(true);

    mockMvc.perform(get(CONTEXT_PATH + BASE_PATH + "/patient/{patientId}/active", patientId)
        .contextPath(CONTEXT_PATH)
        .with(authentication(auth))
        .principal(auth))
            .andExpect(status().isNoContent());
    }

    @Test
    void markMilestoneCompleteForwardsCompletionDate() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        LocalDate completionDate = LocalDate.of(2024, 5, 1);

        HighRiskPregnancyCarePlanResponseDTO response = HighRiskPregnancyCarePlanResponseDTO.builder()
            .id(planId)
            .milestones(List.of())
            .build();
        when(carePlanService.markMilestoneComplete(eq(planId), eq(milestoneId), eq(completionDate), eq(AUTH_USER)))
            .thenReturn(response);

        Authentication auth = new TestingAuthenticationToken(AUTH_USER, AUTH_PASSWORD);
        auth.setAuthenticated(true);

    mockMvc.perform(post(CONTEXT_PATH + BASE_PATH + "/{planId}/milestones/{milestoneId}/complete", planId, milestoneId)
        .contextPath(CONTEXT_PATH)
        .param("completionDate", completionDate.toString())
        .with(authentication(auth))
        .principal(auth))
            .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
    verify(carePlanService).markMilestoneComplete(eq(planId), eq(milestoneId), captor.capture(), eq(AUTH_USER));
        assertThat(captor.getValue()).isEqualTo(completionDate);
    }

    @Test
    void updatePlanDelegatesToService() throws Exception {
        UUID planId = UUID.randomUUID();
        HighRiskPregnancyCarePlanRequestDTO request = HighRiskPregnancyCarePlanRequestDTO.builder()
            .patientId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .build();

        HighRiskPregnancyCarePlanResponseDTO response = HighRiskPregnancyCarePlanResponseDTO.builder()
            .id(planId)
            .build();
        when(carePlanService.updatePlan(eq(planId), any(HighRiskPregnancyCarePlanRequestDTO.class), eq(AUTH_USER)))
            .thenReturn(response);

        Authentication auth = new TestingAuthenticationToken(AUTH_USER, AUTH_PASSWORD);
        auth.setAuthenticated(true);

    mockMvc.perform(put(CONTEXT_PATH + BASE_PATH + "/{planId}", planId)
        .contextPath(CONTEXT_PATH)
        .with(authentication(auth))
        .principal(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(planId.toString()));
    }
}
