package com.example.hms.controller;

import com.example.hms.enums.ShareScope;
import com.example.hms.payload.dto.PatientRecordDTO;
import com.example.hms.payload.dto.RecordShareRequestDTO;
import com.example.hms.payload.dto.RecordShareResultDTO;
import com.example.hms.service.PatientRecordSharingService;
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
import java.util.UUID;


import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = PatientRecordSharingController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(PatientRecordSharingControllerTest.Config.class)
class PatientRecordSharingControllerTest {

    private static final String SHARE_URL = "/records/share";
    private static final String RESOLVE_URL = "/records/resolve";
    private static final String AGGREGATE_URL = "/records/aggregate";
    private static final String EXPORT_URL = "/records/export";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PatientRecordSharingService sharingService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(sharingService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID FROM_HOSPITAL_ID = UUID.randomUUID();
    private static final UUID TO_HOSPITAL_ID = UUID.randomUUID();

    private RecordShareRequestDTO buildShareRequest() {
        return RecordShareRequestDTO.builder()
                .patientId(PATIENT_ID)
                .fromHospitalId(FROM_HOSPITAL_ID)
                .toHospitalId(TO_HOSPITAL_ID)
                .build();
    }

    private PatientRecordDTO buildPatientRecord() {
        return PatientRecordDTO.builder()
                .patientId(PATIENT_ID)
                .consentId(UUID.randomUUID())
                .fromHospitalId(FROM_HOSPITAL_ID)
                .toHospitalId(TO_HOSPITAL_ID)
                .build();
    }

    private RecordShareResultDTO buildResolveResult() {
        return RecordShareResultDTO.builder()
                .shareScope(ShareScope.INTRA_ORG)
                .shareScopeLabel("Intra-organisation share")
                .resolvedFromHospitalId(FROM_HOSPITAL_ID)
                .requestingHospitalId(TO_HOSPITAL_ID)
                .consentActive(true)
                .resolvedAt(LocalDateTime.now())
                .patientRecord(buildPatientRecord())
                .build();
    }

    // ═══════════════════════ POST /records/share ═══════════════════════

    @Nested
    @DisplayName("POST /records/share — shareRecords")
    class ShareRecords {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with valid request")
        void shareRecords_ok() throws Exception {
            PatientRecordDTO patientRecord = buildPatientRecord();
            when(sharingService.getPatientRecord(PATIENT_ID, FROM_HOSPITAL_ID, TO_HOSPITAL_ID))
                    .thenReturn(patientRecord);

            mockMvc.perform(post(SHARE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildShareRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patientId").value(PATIENT_ID.toString()))
                    .andExpect(jsonPath("$.fromHospitalId").value(FROM_HOSPITAL_ID.toString()))
                    .andExpect(jsonPath("$.toHospitalId").value(TO_HOSPITAL_ID.toString()));

            verify(sharingService).getPatientRecord(PATIENT_ID, FROM_HOSPITAL_ID, TO_HOSPITAL_ID);
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 400 when patientId is missing")
        void shareRecords_missingPatientId() throws Exception {
            RecordShareRequestDTO request = RecordShareRequestDTO.builder()
                    .fromHospitalId(FROM_HOSPITAL_ID)
                    .toHospitalId(TO_HOSPITAL_ID)
                    .build();

            mockMvc.perform(post(SHARE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_MIDWIFE")
        @DisplayName("returns 400 when fromHospitalId is missing")
        void shareRecords_missingFromHospitalId() throws Exception {
            RecordShareRequestDTO request = RecordShareRequestDTO.builder()
                    .patientId(PATIENT_ID)
                    .toHospitalId(TO_HOSPITAL_ID)
                    .build();

            mockMvc.perform(post(SHARE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_HOSPITAL_ADMIN")
        @DisplayName("returns 400 when toHospitalId is missing")
        void shareRecords_missingToHospitalId() throws Exception {
            RecordShareRequestDTO request = RecordShareRequestDTO.builder()
                    .patientId(PATIENT_ID)
                    .fromHospitalId(FROM_HOSPITAL_ID)
                    .build();

            mockMvc.perform(post(SHARE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 400 when body is empty")
        void shareRecords_emptyBody() throws Exception {
            mockMvc.perform(post(SHARE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════ GET /records/resolve ═══════════════════════

    @Nested
    @DisplayName("GET /records/resolve — resolveAndShare")
    class ResolveAndShare {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with valid params")
        void resolveAndShare_ok() throws Exception {
            RecordShareResultDTO result = buildResolveResult();
            when(sharingService.resolveAndShare(PATIENT_ID, TO_HOSPITAL_ID))
                    .thenReturn(result);

            mockMvc.perform(get(RESOLVE_URL)
                            .param("patientId", PATIENT_ID.toString())
                            .param("requestingHospitalId", TO_HOSPITAL_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shareScope").value("INTRA_ORG"))
                    .andExpect(jsonPath("$.consentActive").value(true))
                    .andExpect(jsonPath("$.patientRecord.patientId").value(PATIENT_ID.toString()));

            verify(sharingService).resolveAndShare(PATIENT_ID, TO_HOSPITAL_ID);
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 400 when patientId is missing")
        void resolveAndShare_missingPatientId() throws Exception {
            mockMvc.perform(get(RESOLVE_URL)
                            .param("requestingHospitalId", TO_HOSPITAL_ID.toString()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 400 when requestingHospitalId is missing")
        void resolveAndShare_missingRequestingHospitalId() throws Exception {
            mockMvc.perform(get(RESOLVE_URL)
                            .param("patientId", PATIENT_ID.toString()))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════ GET /records/aggregate ═══════════════════════

    @Nested
    @DisplayName("GET /records/aggregate — aggregateRecords")
    class AggregateRecords {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with valid params")
        void aggregateRecords_ok() throws Exception {
            PatientRecordDTO patientRecord = buildPatientRecord();
            when(sharingService.getAggregatedPatientRecord(PATIENT_ID, TO_HOSPITAL_ID))
                    .thenReturn(patientRecord);

            mockMvc.perform(get(AGGREGATE_URL)
                            .param("patientId", PATIENT_ID.toString())
                            .param("requestingHospitalId", TO_HOSPITAL_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patientId").value(PATIENT_ID.toString()));

            verify(sharingService).getAggregatedPatientRecord(PATIENT_ID, TO_HOSPITAL_ID);
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 400 when patientId is missing")
        void aggregateRecords_missingPatientId() throws Exception {
            mockMvc.perform(get(AGGREGATE_URL)
                            .param("requestingHospitalId", TO_HOSPITAL_ID.toString()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 400 when requestingHospitalId is missing")
        void aggregateRecords_missingRequestingHospitalId() throws Exception {
            mockMvc.perform(get(AGGREGATE_URL)
                            .param("patientId", PATIENT_ID.toString()))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════ POST /records/export ═══════════════════════

    @Nested
    @DisplayName("POST /records/export — exportRecords")
    class ExportRecords {

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 200 OK with PDF content for format=pdf")
        void exportRecords_pdf() throws Exception {
            byte[] pdfContent = "mock-pdf-content".getBytes();
            when(sharingService.exportPatientRecord(
                    PATIENT_ID, FROM_HOSPITAL_ID, TO_HOSPITAL_ID, "pdf"))
                    .thenReturn(pdfContent);

            mockMvc.perform(post(EXPORT_URL)
                            .param("patientId", PATIENT_ID.toString())
                            .param("fromHospitalId", FROM_HOSPITAL_ID.toString())
                            .param("toHospitalId", TO_HOSPITAL_ID.toString())
                            .param("format", "pdf"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"patient_record.pdf\""))
                    .andExpect(header().string("Content-Type", "application/pdf"));

            verify(sharingService).exportPatientRecord(PATIENT_ID, FROM_HOSPITAL_ID, TO_HOSPITAL_ID, "pdf");
        }

        @Test
        @WithMockUser(authorities = "ROLE_NURSE")
        @DisplayName("returns 200 OK with CSV content for format=csv")
        void exportRecords_csv() throws Exception {
            byte[] csvContent = "id,name\n1,Jane".getBytes();
            when(sharingService.exportPatientRecord(
                    PATIENT_ID, FROM_HOSPITAL_ID, TO_HOSPITAL_ID, "csv")
            ).thenReturn(csvContent);

            mockMvc.perform(post(EXPORT_URL)
                            .param("patientId", PATIENT_ID.toString())
                            .param("fromHospitalId", FROM_HOSPITAL_ID.toString())
                            .param("toHospitalId", TO_HOSPITAL_ID.toString())
                            .param("format", "csv"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"patient_record.csv\""))
                    .andExpect(header().string("Content-Type", "text/csv"));
        }

        @Test
        @WithMockUser(authorities = "ROLE_DOCTOR")
        @DisplayName("returns 400 when format param is blank")
        void exportRecords_blankFormat() throws Exception {
            mockMvc.perform(post(EXPORT_URL)
                            .param("patientId", PATIENT_ID.toString())
                            .param("fromHospitalId", FROM_HOSPITAL_ID.toString())
                            .param("toHospitalId", TO_HOSPITAL_ID.toString())
                            .param("format", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
        @DisplayName("returns 400 when patientId is missing")
        void exportRecords_missingPatientId() throws Exception {
            mockMvc.perform(post(EXPORT_URL)
                            .param("fromHospitalId", FROM_HOSPITAL_ID.toString())
                            .param("toHospitalId", TO_HOSPITAL_ID.toString())
                            .param("format", "pdf"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════ @PreAuthorize roles ═══════════════════════

    @Nested
    @DisplayName("@PreAuthorize role verification")
    class PreAuthorizeRoles {

        @Test
        @DisplayName("shareRecords allows ROLE_NURSE, ROLE_DOCTOR, ROLE_MIDWIFE, ROLE_HOSPITAL_ADMIN, ROLE_SUPER_ADMIN")
        void shareRecords_allowedRoles() throws Exception {
            var method = PatientRecordSharingController.class.getDeclaredMethod(
                    "shareRecords", RecordShareRequestDTO.class);
            var ann = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            org.assertj.core.api.Assertions.assertThat(ann).isNotNull();
            org.assertj.core.api.Assertions.assertThat(ann.value())
                    .contains("ROLE_NURSE")
                    .contains("ROLE_DOCTOR")
                    .contains("ROLE_MIDWIFE")
                    .contains("ROLE_HOSPITAL_ADMIN")
                    .contains("ROLE_SUPER_ADMIN");
        }

        @Test
        @DisplayName("resolveAndShare allows same 5 clinical roles")
        void resolveAndShare_allowedRoles() throws Exception {
            var method = PatientRecordSharingController.class.getDeclaredMethod(
                    "resolveAndShare", UUID.class, UUID.class);
            var ann = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            org.assertj.core.api.Assertions.assertThat(ann).isNotNull();
            org.assertj.core.api.Assertions.assertThat(ann.value())
                    .contains("ROLE_NURSE")
                    .contains("ROLE_DOCTOR")
                    .contains("ROLE_MIDWIFE")
                    .contains("ROLE_HOSPITAL_ADMIN")
                    .contains("ROLE_SUPER_ADMIN");
        }

        @Test
        @DisplayName("aggregateRecords allows same 5 clinical roles")
        void aggregateRecords_allowedRoles() throws Exception {
            var method = PatientRecordSharingController.class.getDeclaredMethod(
                    "aggregateRecords", UUID.class, UUID.class);
            var ann = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            org.assertj.core.api.Assertions.assertThat(ann).isNotNull();
            org.assertj.core.api.Assertions.assertThat(ann.value())
                    .contains("ROLE_NURSE")
                    .contains("ROLE_DOCTOR")
                    .contains("ROLE_MIDWIFE")
                    .contains("ROLE_HOSPITAL_ADMIN")
                    .contains("ROLE_SUPER_ADMIN");
        }

        @Test
        @DisplayName("exportRecords allows same 5 clinical roles")
        void exportRecords_allowedRoles() throws Exception {
            var method = PatientRecordSharingController.class.getDeclaredMethod(
                    "exportRecords", UUID.class, UUID.class, UUID.class, String.class);
            var ann = method.getAnnotation(
                    org.springframework.security.access.prepost.PreAuthorize.class);
            org.assertj.core.api.Assertions.assertThat(ann).isNotNull();
            org.assertj.core.api.Assertions.assertThat(ann.value())
                    .contains("ROLE_NURSE")
                    .contains("ROLE_DOCTOR")
                    .contains("ROLE_MIDWIFE")
                    .contains("ROLE_HOSPITAL_ADMIN")
                    .contains("ROLE_SUPER_ADMIN");
        }
    }

    // ───────────────────────── test config ─────────────────────────

    @TestConfiguration
    static class Config {
        @Bean
        PatientRecordSharingService sharingService() {
            return Mockito.mock(PatientRecordSharingService.class);
        }
    }
}
