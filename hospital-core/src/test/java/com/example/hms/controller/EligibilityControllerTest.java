package com.example.hms.controller;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import com.example.hms.service.EligibilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for {@link EligibilityController}. Pinned behaviour:
 *  - /check forces COVERAGE; /prior-auth forces PRIOR_AUTH
 *  - /patient/{id}/latest returns 204 when the service returns Optional.empty()
 *  - clinical roles can call every endpoint
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = EligibilityController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(EligibilityControllerTest.ControllerTestConfig.class)
class EligibilityControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EligibilityService eligibilityService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(eligibilityService);
    }

    private EligibilityCheckRequestDTO baseRequest(EligibilityScheme scheme,
                                                   EligibilityCheckType type) {
        return EligibilityCheckRequestDTO.builder()
            .patientId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .scheme(scheme)
            .checkType(type)
            .memberId("NHIS-001")
            .build();
    }

    private EligibilityResponseDTO buildResponse(EligibilityCheckType type, EligibilityStatus status) {
        return EligibilityResponseDTO.builder()
            .id(UUID.randomUUID())
            .patientId(UUID.randomUUID())
            .hospitalId(UUID.randomUUID())
            .scheme(EligibilityScheme.NHIS_GH)
            .checkType(type)
            .memberId("NHIS-001")
            .requestedAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .status(status)
            .responseCode("ACTIVE")
            .copayAmount(new BigDecimal("0.00"))
            .copayCurrency("GHS")
            .priorAuthRequired(true)
            .validUntil(LocalDate.now().plusDays(30))
            .build();
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /eligibility/check returns 201 and forces COVERAGE")
    void checkCoverage_returnsCreated() throws Exception {
        EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.PRIOR_AUTH);
        EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
        when(eligibilityService.submit(any(EligibilityCheckRequestDTO.class))).thenReturn(resp);

        mockMvc.perform(post("/eligibility/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ELIGIBLE"))
            .andExpect(jsonPath("$.checkType").value("COVERAGE"))
            .andExpect(jsonPath("$.copayCurrency").value("GHS"));

        verify(eligibilityService).submit(any(EligibilityCheckRequestDTO.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /eligibility/prior-auth returns 201 and forces PRIOR_AUTH")
    void priorAuth_returnsCreated() throws Exception {
        EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE);
        req.setServiceCode("CT-HEAD");
        EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.PRIOR_AUTH, EligibilityStatus.ELIGIBLE);
        resp.setPriorAuthNumber("PA-123");
        when(eligibilityService.submit(any(EligibilityCheckRequestDTO.class))).thenReturn(resp);

        mockMvc.perform(post("/eligibility/prior-auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.checkType").value("PRIOR_AUTH"))
            .andExpect(jsonPath("$.priorAuthNumber").value("PA-123"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /eligibility/{id} returns 200 with the persisted check")
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
        resp.setId(id);
        when(eligibilityService.get(id)).thenReturn(resp);

        mockMvc.perform(get("/eligibility/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /eligibility/patient/{id} pages through history")
    void listByPatient_returnsPage() throws Exception {
        UUID patientId = UUID.randomUUID();
        EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
        Page<EligibilityResponseDTO> page = new PageImpl<>(List.of(resp));
        when(eligibilityService.listByPatient(eq(patientId), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/eligibility/patient/{patientId}", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("ELIGIBLE"));

        verify(eligibilityService).listByPatient(eq(patientId), any(Pageable.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_RECEPTIONIST"})
    @DisplayName("GET /eligibility/patient/{id}/latest returns 200 when present")
    void latestForPatient_returnsResultWhenPresent() throws Exception {
        UUID patientId = UUID.randomUUID();
        EligibilityResponseDTO resp = buildResponse(EligibilityCheckType.COVERAGE, EligibilityStatus.ELIGIBLE);
        when(eligibilityService.findLatestForPatient(
            patientId, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE))
            .thenReturn(Optional.of(resp));

        mockMvc.perform(get("/eligibility/patient/{patientId}/latest", patientId)
                .param("scheme", "NHIS_GH"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ELIGIBLE"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /eligibility/patient/{id}/latest returns 204 when no prior check")
    void latestForPatient_returns204WhenAbsent() throws Exception {
        UUID patientId = UUID.randomUUID();
        when(eligibilityService.findLatestForPatient(
            patientId, EligibilityScheme.CNAMGS_GA, EligibilityCheckType.PRIOR_AUTH))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/eligibility/patient/{patientId}/latest", patientId)
                .param("scheme", "CNAMGS_GA")
                .param("type", "PRIOR_AUTH"))
            .andExpect(status().isNoContent());
    }

    static class ControllerTestConfig {
        @Bean
        EligibilityService eligibilityService() {
            return Mockito.mock(EligibilityService.class);
        }
    }
}
