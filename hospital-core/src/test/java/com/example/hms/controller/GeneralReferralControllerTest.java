package com.example.hms.controller;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.enums.ReferralEventType;
import com.example.hms.payload.dto.referral.ReferralEventResponseDTO;
import com.example.hms.payload.dto.referral.RejectReferralRequestDTO;
import com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ReferralExpiryService;
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

import java.time.Duration;
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

    @Autowired
    private ReferralExpiryService referralExpiryService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(referralService, referralExpiryService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /api/referrals creates new referral")
    void createReferral_returnsCreatedReferral() throws Exception {
        UUID referralId = UUID.randomUUID();
        GeneralReferralRequestDTO request = buildRequest();
        GeneralReferralResponseDTO response = buildResponse(referralId);

        when(referralService.createReferral(any(GeneralReferralRequestDTO.class))).thenReturn(response);

        mockMvc.perform(post("/referrals")
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

        mockMvc.perform(post("/referrals/{referralId}/submit", referralId))
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

        mockMvc.perform(post("/referrals/{id}/acknowledge", referralId)
                .param("notes", "notes")
                .param("receivingProviderId", providerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.ACKNOWLEDGED.name()));

        verify(referralService).acknowledgeReferral(referralId, "notes", providerId);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void scheduleReferral_returnsScheduledStatus() throws Exception {
        UUID referralId = UUID.randomUUID();
        ScheduleReferralRequestDTO request = ScheduleReferralRequestDTO.builder()
            .appointmentTime(LocalDateTime.now().plusDays(3))
            .location("Clinic 4 — Room 12")
            .build();
        GeneralReferralResponseDTO response = buildResponse(referralId);
        response.setStatus(ReferralStatus.SCHEDULED);
        response.setScheduledAppointmentAt(request.getAppointmentTime());
        response.setAppointmentLocation(request.getLocation());

        when(referralService.scheduleReferral(Mockito.eq(referralId), any(ScheduleReferralRequestDTO.class)))
            .thenReturn(response);

        mockMvc.perform(post("/referrals/{id}/schedule", referralId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.SCHEDULED.name()))
            .andExpect(jsonPath("$.appointmentLocation").value("Clinic 4 — Room 12"));

        verify(referralService).scheduleReferral(Mockito.eq(referralId), any(ScheduleReferralRequestDTO.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void scheduleReferral_missingAppointmentTime_returns400() throws Exception {
        UUID referralId = UUID.randomUUID();
        ScheduleReferralRequestDTO request = ScheduleReferralRequestDTO.builder()
            .location("only location")
            .build();

        mockMvc.perform(post("/referrals/{id}/schedule", referralId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(referralService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void scheduleReferral_pastAppointmentTime_returns400() throws Exception {
        UUID referralId = UUID.randomUUID();
        ScheduleReferralRequestDTO request = ScheduleReferralRequestDTO.builder()
            .appointmentTime(LocalDateTime.now().minusDays(1))
            .location("Clinic 4")
            .build();

        mockMvc.perform(post("/referrals/{id}/schedule", referralId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(referralService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void startReferral_returnsInProgressStatus() throws Exception {
        UUID referralId = UUID.randomUUID();
        GeneralReferralResponseDTO response = buildResponse(referralId);
        response.setStatus(ReferralStatus.IN_PROGRESS);
        response.setStartedAt(LocalDateTime.now());

        when(referralService.startReferral(referralId)).thenReturn(response);

        mockMvc.perform(post("/referrals/{id}/start", referralId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.IN_PROGRESS.name()));

        verify(referralService).startReferral(referralId);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void rejectReferral_returnsRejectedStatus() throws Exception {
        UUID referralId = UUID.randomUUID();
        RejectReferralRequestDTO request = RejectReferralRequestDTO.builder()
            .reason("Out of scope for our service")
            .build();
        GeneralReferralResponseDTO response = buildResponse(referralId);
        response.setStatus(ReferralStatus.REJECTED);
        response.setCancellationReason(request.getReason());

        when(referralService.rejectReferral(Mockito.eq(referralId), any(RejectReferralRequestDTO.class)))
            .thenReturn(response);

        mockMvc.perform(post("/referrals/{id}/reject", referralId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(ReferralStatus.REJECTED.name()))
            .andExpect(jsonPath("$.cancellationReason").value(request.getReason()));

        verify(referralService).rejectReferral(Mockito.eq(referralId), any(RejectReferralRequestDTO.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void rejectReferral_blankReason_returns400() throws Exception {
        UUID referralId = UUID.randomUUID();
        RejectReferralRequestDTO request = RejectReferralRequestDTO.builder()
            .reason("   ")
            .build();

        mockMvc.perform(post("/referrals/{id}/reject", referralId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(referralService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    void cancelReferral_returns204() throws Exception {
        UUID referralId = UUID.randomUUID();

        mockMvc.perform(post("/referrals/{id}/cancel", referralId)
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

        mockMvc.perform(get("/referrals/hospital/{hospitalId}", hospitalId)
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

        mockMvc.perform(get("/referrals/overdue"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].isOverdue").value(true));

        verify(referralService).getOverdueReferrals();
    }

    @SuppressWarnings("java:S1075")
    private static final String EXPIRE_OVERDUE_PATH = "/referrals/admin/expire-overdue";

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /{id}/events returns the chronological audit trail")
    void getReferralEventsReturnsTimeline() throws Exception {
        UUID referralId = UUID.randomUUID();
        ReferralEventResponseDTO submit = ReferralEventResponseDTO.builder()
            .id(UUID.randomUUID())
            .referralId(referralId)
            .eventType(ReferralEventType.SUBMIT)
            .toStatus(ReferralStatus.SUBMITTED)
            .actorUsername("dr.amy")
            .actorLabel("USER")
            .recordedAt(LocalDateTime.now())
            .build();
        ReferralEventResponseDTO expire = ReferralEventResponseDTO.builder()
            .id(UUID.randomUUID())
            .referralId(referralId)
            .eventType(ReferralEventType.EXPIRE)
            .toStatus(ReferralStatus.EXPIRED)
            .actorLabel("SYSTEM:scheduler")
            .recordedAt(LocalDateTime.now().plusHours(1))
            .build();
        when(referralService.getReferralEvents(referralId)).thenReturn(List.of(submit, expire));

        mockMvc.perform(get("/referrals/{id}/events", referralId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].eventType").value("SUBMIT"))
            .andExpect(jsonPath("$[0].actorLabel").value("USER"))
            .andExpect(jsonPath("$[1].eventType").value("EXPIRE"))
            .andExpect(jsonPath("$[1].actorLabel").value("SYSTEM:scheduler"));

        verify(referralService).getReferralEvents(referralId);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_HOSPITAL_ADMIN"})
    @DisplayName("POST /admin/expire-overdue returns expired count for admin")
    void expireOverdueReferralsReturnsCount() throws Exception {
        when(referralExpiryService.expireOverdueReferrals(any(Duration.class))).thenReturn(7);

        mockMvc.perform(post(EXPIRE_OVERDUE_PATH)
                .param("graceHours", "6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expired").value(7));

        verify(referralExpiryService).expireOverdueReferrals(Duration.ofHours(6));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_HOSPITAL_ADMIN"})
    @DisplayName("POST /admin/expire-overdue defaults graceHours to 0 when omitted")
    void expireOverdueReferralsDefaultsGraceToZero() throws Exception {
        when(referralExpiryService.expireOverdueReferrals(any(Duration.class))).thenReturn(0);

        mockMvc.perform(post(EXPIRE_OVERDUE_PATH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expired").value(0));

        verify(referralExpiryService).expireOverdueReferrals(Duration.ZERO);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_HOSPITAL_ADMIN"})
    @DisplayName("POST /admin/expire-overdue clamps negative graceHours to 0")
    void expireOverdueReferralsClampsNegativeGrace() throws Exception {
        when(referralExpiryService.expireOverdueReferrals(any(Duration.class))).thenReturn(0);

        mockMvc.perform(post(EXPIRE_OVERDUE_PATH)
                .param("graceHours", "-3"))
            .andExpect(status().isOk());

        verify(referralExpiryService).expireOverdueReferrals(Duration.ZERO);
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

        @Bean
        ReferralExpiryService referralExpiryService() {
            return Mockito.mock(ReferralExpiryService.class);
        }
    }
}
