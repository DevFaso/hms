package com.example.hms.controller;

import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.service.PatientPortalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for Phase 2 patient portal endpoints.
 * Tests HTTP method, path, status codes, JSON response structure, and service delegation.
 */
@WebMvcTest(
    controllers = PatientPortalController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@AutoConfigureMockMvc(addFilters = false)
@Import(PatientPortalControllerPhase2Test.TestConfig.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalControllerPhase2Test {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public PatientPortalService patientPortalService() {
            return mock(PatientPortalService.class);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PatientPortalService portalService;

    private ObjectMapper objectMapper;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        reset(portalService);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        auth = new UsernamePasswordAuthenticationToken(
                "patient.jane", "password",
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cancel Appointment
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /me/patient/appointments/cancel")
    class CancelAppointment {

        @Test
        @DisplayName("should return 200 with cancelled appointment")
        void cancelAppointment_success() throws Exception {
            UUID apptId = UUID.randomUUID();
            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .reason("Schedule conflict")
                    .build();

            AppointmentResponseDTO response = AppointmentResponseDTO.builder()
                    .id(apptId)
                    .build();

            when(portalService.cancelMyAppointment(any(Authentication.class),
                    any(CancelAppointmentRequestDTO.class), any(Locale.class)))
                    .thenReturn(response);

            mockMvc.perform(put("/me/patient/appointments/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(apptId.toString()));

            verify(portalService).cancelMyAppointment(any(), any(CancelAppointmentRequestDTO.class), any());
        }

        @Test
        @DisplayName("should return 400 when appointmentId is missing")
        void cancelAppointment_missingId_returns400() throws Exception {
            CancelAppointmentRequestDTO dto = CancelAppointmentRequestDTO.builder()
                    .reason("Just reason, no ID")
                    .build();

            mockMvc.perform(put("/me/patient/appointments/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reschedule Appointment
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /me/patient/appointments/reschedule")
    class RescheduleAppointment {

        @Test
        @DisplayName("should return 200 with rescheduled appointment")
        void rescheduleAppointment_success() throws Exception {
            UUID apptId = UUID.randomUUID();
            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .appointmentId(apptId)
                    .newDate(LocalDate.of(2026, 6, 1))
                    .newStartTime(LocalTime.of(10, 0))
                    .newEndTime(LocalTime.of(10, 30))
                    .reason("Need different time")
                    .build();

            AppointmentResponseDTO response = AppointmentResponseDTO.builder()
                    .id(apptId)
                    .appointmentDate(LocalDate.of(2026, 6, 1))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(10, 30))
                    .build();

            when(portalService.rescheduleMyAppointment(any(Authentication.class),
                    any(RescheduleAppointmentRequestDTO.class), any(Locale.class)))
                    .thenReturn(response);

            mockMvc.perform(put("/me/patient/appointments/reschedule")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(apptId.toString()));
        }

        @Test
        @DisplayName("should return 400 when required fields missing")
        void reschedule_missingFields_returns400() throws Exception {
            // appointmentId and newDate are required
            RescheduleAppointmentRequestDTO dto = RescheduleAppointmentRequestDTO.builder()
                    .reason("No ids")
                    .build();

            mockMvc.perform(put("/me/patient/appointments/reschedule")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Grant Consent
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /me/patient/consents")
    class GrantConsent {

        @Test
        @DisplayName("should return 201 with granted consent")
        void grantConsent_success() throws Exception {
            UUID fromHospital = UUID.randomUUID();
            UUID toHospital = UUID.randomUUID();

            PortalConsentRequestDTO dto = PortalConsentRequestDTO.builder()
                    .fromHospitalId(fromHospital)
                    .toHospitalId(toHospital)
                    .purpose("Treatment coordination")
                    .build();

            PatientConsentResponseDTO response = new PatientConsentResponseDTO();
            when(portalService.grantMyConsent(any(Authentication.class),
                    any(PortalConsentRequestDTO.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/me/patient/consents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("should return 400 when hospital IDs missing")
        void grantConsent_missingFields_returns400() throws Exception {
            PortalConsentRequestDTO dto = PortalConsentRequestDTO.builder()
                    .purpose("No hospitals")
                    .build();

            mockMvc.perform(post("/me/patient/consents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Revoke Consent
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /me/patient/consents")
    class RevokeConsent {

        @Test
        @DisplayName("should return 200 on successful revocation")
        void revokeConsent_success() throws Exception {
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();

            doNothing().when(portalService).revokeMyConsent(any(Authentication.class),
                    eq(from), eq(to));

            mockMvc.perform(delete("/me/patient/consents")
                            .param("fromHospitalId", from.toString())
                            .param("toHospitalId", to.toString())
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }

        @Test
        @DisplayName("should return 400 when fromHospitalId missing")
        void revokeConsent_missingParam_returns400() throws Exception {
            mockMvc.perform(delete("/me/patient/consents")
                            .param("toHospitalId", UUID.randomUUID().toString())
                            .principal(auth))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Record Home Vital
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /me/patient/vitals")
    class RecordHomeVital {

        @Test
        @DisplayName("should return 201 with recorded vital")
        void recordVital_success() throws Exception {
            HomeVitalReadingDTO dto = HomeVitalReadingDTO.builder()
                    .systolicBpMmHg(120)
                    .diastolicBpMmHg(80)
                    .heartRateBpm(72)
                    .notes("Morning check")
                    .build();

            PatientVitalSignResponseDTO response = new PatientVitalSignResponseDTO();
            when(portalService.recordHomeVital(any(Authentication.class),
                    any(HomeVitalReadingDTO.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/me/patient/vitals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Request Medication Refill
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /me/patient/refills")
    class RequestMedicationRefill {

        @Test
        @DisplayName("should return 201 with refill response")
        void requestRefill_success() throws Exception {
            UUID prescriptionId = UUID.randomUUID();
            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .prescriptionId(prescriptionId)
                    .preferredPharmacy("CVS")
                    .notes("Running low")
                    .build();

            MedicationRefillResponseDTO response = MedicationRefillResponseDTO.builder()
                    .id(UUID.randomUUID())
                    .prescriptionId(prescriptionId)
                    .medicationName("Metformin 500mg")
                    .status("REQUESTED")
                    .build();

            when(portalService.requestMedicationRefill(any(Authentication.class),
                    any(MedicationRefillRequestDTO.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/me/patient/refills")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                    .andExpect(jsonPath("$.data.medicationName").value("Metformin 500mg"));
        }

        @Test
        @DisplayName("should return 400 when prescriptionId missing")
        void requestRefill_missingPrescription_returns400() throws Exception {
            MedicationRefillRequestDTO dto = MedicationRefillRequestDTO.builder()
                    .notes("No prescription ID")
                    .build();

            mockMvc.perform(post("/me/patient/refills")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto))
                            .principal(auth))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Get My Refills
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /me/patient/refills")
    class GetMyRefills {

        @Test
        @DisplayName("should return 200 with paged refills")
        void getRefills_success() throws Exception {
            MedicationRefillResponseDTO refill = MedicationRefillResponseDTO.builder()
                    .id(UUID.randomUUID())
                    .status("REQUESTED")
                    .medicationName("Aspirin")
                    .build();

            Page<MedicationRefillResponseDTO> page =
                    new PageImpl<>(List.of(refill));

            when(portalService.getMyRefills(any(Authentication.class), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/me/patient/refills")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].medicationName").value("Aspirin"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cancel Refill
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /me/patient/refills/{refillId}/cancel")
    class CancelRefill {

        @Test
        @DisplayName("should return 200 with cancelled refill")
        void cancelRefill_success() throws Exception {
            UUID refillId = UUID.randomUUID();
            MedicationRefillResponseDTO response = MedicationRefillResponseDTO.builder()
                    .id(refillId)
                    .status("CANCELLED")
                    .build();

            when(portalService.cancelMyRefill(any(Authentication.class), eq(refillId)))
                    .thenReturn(response);

            mockMvc.perform(put("/me/patient/refills/{refillId}/cancel", refillId)
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // After-Visit Summaries
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /me/patient/after-visit-summaries")
    class AfterVisitSummaries {

        @Test
        @DisplayName("should return 200 with summaries list")
        void getAfterVisitSummaries_success() throws Exception {
            DischargeSummaryResponseDTO summary = new DischargeSummaryResponseDTO();

            when(portalService.getMyAfterVisitSummaries(any(Authentication.class), any(Locale.class)))
                    .thenReturn(List.of(summary));

            mockMvc.perform(get("/me/patient/after-visit-summaries")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)));
        }

        @Test
        @DisplayName("should return 200 with empty list when none exist")
        void getAfterVisitSummaries_empty() throws Exception {
            when(portalService.getMyAfterVisitSummaries(any(Authentication.class), any(Locale.class)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/me/patient/after-visit-summaries")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Care Team
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /me/patient/care-team")
    class CareTeam {

        @Test
        @DisplayName("should return 200 with care team")
        void getCareTeam_success() throws Exception {
            CareTeamDTO.PrimaryCareEntry entry = CareTeamDTO.PrimaryCareEntry.builder()
                    .doctorDisplay("Dr. Smith")
                    .current(true)
                    .startDate(LocalDate.of(2025, 1, 1))
                    .build();

            CareTeamDTO careTeam = CareTeamDTO.builder()
                    .primaryCare(entry)
                    .primaryCareHistory(List.of(entry))
                    .build();

            when(portalService.getMyCareTeam(any(Authentication.class)))
                    .thenReturn(careTeam);

            mockMvc.perform(get("/me/patient/care-team")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.primaryCare.doctorDisplay").value("Dr. Smith"))
                    .andExpect(jsonPath("$.data.primaryCareHistory", hasSize(1)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Access Log
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /me/patient/access-log")
    class AccessLog {

        @Test
        @DisplayName("should return 200 with paged access log entries")
        void getAccessLog_success() throws Exception {
            AccessLogEntryDTO logEntry = AccessLogEntryDTO.builder()
                    .actor("nurse.mary")
                    .eventType("VIEW")
                    .entityType("PATIENT")
                    .description("Viewed vitals")
                    .status("SUCCESS")
                    .timestamp(LocalDateTime.of(2026, 2, 10, 14, 30))
                    .build();

            Page<AccessLogEntryDTO> page = new PageImpl<>(List.of(logEntry));

            when(portalService.getMyAccessLog(any(Authentication.class), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/me/patient/access-log")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].actor").value("nurse.mary"))
                    .andExpect(jsonPath("$.data.content[0].eventType").value("VIEW"));
        }

        @Test
        @DisplayName("should support custom pagination parameters")
        void getAccessLog_withPagination() throws Exception {
            when(portalService.getMyAccessLog(any(Authentication.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            mockMvc.perform(get("/me/patient/access-log")
                            .param("page", "2")
                            .param("size", "5")
                            .principal(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(0)));
        }
    }
}
