package com.example.hms.controller;

import com.example.hms.enums.PatientDocumentType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.PatientDocumentService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring of the staff document routes: the active hospital comes from
 * {@link RoleValidator}, the patient id from the path, and the download
 * answers with the stored content type and a safe attachment file name.
 * Role gating is expressed in {@code @PreAuthorize} and covered by
 * {@link PatientDocumentStaffControllerAuthorizationTest}.
 */
@WebMvcTest(
    controllers = PatientDocumentStaffController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class PatientDocumentStaffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientDocumentService documentService;

    @MockitoBean
    private RoleValidator roleValidator;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @Test
    void listPassesTheActiveHospitalAndOptionalType() throws Exception {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PatientDocumentResponseDTO dto = new PatientDocumentResponseDTO();
        dto.setId(documentId);
        dto.setDocumentType(PatientDocumentType.REFERRAL_LETTER);
        when(documentService.listForPatient(eq(hospitalId), eq(patientId),
                eq(PatientDocumentType.REFERRAL_LETTER), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/patients/{patientId}/documents", patientId)
                .param("documentType", "REFERRAL_LETTER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(documentId.toString()))
            .andExpect(jsonPath("$.content[0].documentType").value("REFERRAL_LETTER"));
    }

    @Test
    void listWithoutTypeFiltersNothing() throws Exception {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(documentService.listForPatient(eq(hospitalId), eq(patientId), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/patients/{patientId}/documents", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void noActiveHospitalIsA400() throws Exception {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(documentService.listForPatient(isNull(), eq(patientId), isNull(), any(Pageable.class)))
            .thenThrow(new BusinessException("An active hospital is required: select a hospital first."));

        mockMvc.perform(get("/patients/{patientId}/documents", patientId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("An active hospital is required: select a hospital first."));
    }

    @Test
    void unregisteredPatientIsA404() throws Exception {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(documentService.getForPatient(hospitalId, patientId, documentId))
            .thenThrow(new ResourceNotFoundException("Patient not found: " + patientId));

        mockMvc.perform(get("/patients/{patientId}/documents/{documentId}", patientId, documentId))
            .andExpect(status().isNotFound());
    }

    @Test
    void downloadStreamsWithStoredContentTypeAndSafeFileName() throws Exception {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Path tmp = Files.createTempFile("staff-doc", ".pdf");
        Files.writeString(tmp, "%PDF-1.4 test");
        tmp.toFile().deleteOnExit();
        // Patient-chosen name with a quote, a space and an accent: must neither
        // break the header nor leak unencoded.
        when(documentService.downloadForPatient(hospitalId, patientId, documentId))
            .thenReturn(new PatientDocumentService.DocumentPayload(tmp, "application/pdf", "résumé \"q\".pdf"));

        mockMvc.perform(get("/patients/{patientId}/documents/{documentId}/download", patientId, documentId))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
            .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''r%C3%A9sum%C3%A9%20%22q%22.pdf")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string("%PDF-1.4 test"));
    }
}
