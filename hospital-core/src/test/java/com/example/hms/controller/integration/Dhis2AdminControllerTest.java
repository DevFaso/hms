package com.example.hms.controller.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingResponseDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.integration.Dhis2ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = Dhis2AdminController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class Dhis2AdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private Dhis2ConfigService configService;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), any(Boolean.class)))
            .thenReturn(hospitalId);
    }

    @Test
    void getFacilityReturns200WhenConfigExists() throws Exception {
        when(configService.getFacilityConfig(hospitalId))
            .thenReturn(Optional.of(facilityResponse()));

        mockMvc.perform(get("/admin/integrations/dhis2/facility")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hospitalId").value(hospitalId.toString()))
            .andExpect(jsonPath("$.baseUrl").value("https://dhis2.example.org"));
    }

    @Test
    void getFacilityReturns404WhenConfigMissing() throws Exception {
        when(configService.getFacilityConfig(hospitalId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/integrations/dhis2/facility")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void getFacilityReturns403OnCrossHospitalAccess() throws Exception {
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), any(Boolean.class)))
            .thenThrow(new BusinessException("Access denied"));

        mockMvc.perform(get("/admin/integrations/dhis2/facility")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void putFacilityUpsertsAndReturns200() throws Exception {
        when(configService.upsertFacilityConfig(eq(hospitalId), any())).thenReturn(facilityResponse());

        Dhis2FacilityConfigRequestDTO body = new Dhis2FacilityConfigRequestDTO(
            "https://dhis2.example.org", Dhis2AuthMode.PAT, "DHIS2_TOKEN",
            Dhis2PeriodType.MONTHLY, "DS00000DEFK", true);

        mockMvc.perform(put("/admin/integrations/dhis2/facility")
                .param("hospitalId", hospitalId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.baseUrl").value("https://dhis2.example.org"));
        verify(configService).upsertFacilityConfig(eq(hospitalId), any());
    }

    @Test
    void putFacilityReturns400OnInvalidRequest() throws Exception {
        // Missing required baseUrl + authMode + periodType => 400 from @Valid.
        String invalid = "{}";

        mockMvc.perform(put("/admin/integrations/dhis2/facility")
                .param("hospitalId", hospitalId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listMappingsReturnsPagedResponse() throws Exception {
        Page<Dhis2DataElementMappingResponseDTO> page =
            new PageImpl<>(java.util.List.of(mappingResponse()));
        when(configService.listMappings(eq(hospitalId), eq("DS00000DEFK"), any())).thenReturn(page);

        mockMvc.perform(get("/admin/integrations/dhis2/mappings")
                .param("hospitalId", hospitalId.toString())
                .param("datasetUid", "DS00000DEFK"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].dhis2DataElementUid").value("DE000000049"));
    }

    @Test
    void postMappingReturnsCreatedDto() throws Exception {
        when(configService.createMapping(eq(hospitalId), any())).thenReturn(mappingResponse());

        Dhis2DataElementMappingRequestDTO body = new Dhis2DataElementMappingRequestDTO(
            "http://hl7.org/fhir/sid/cvx", "49", "DE000000049", null,
            Dhis2PeriodType.MONTHLY, "DS00000DEFK", true);

        mockMvc.perform(post("/admin/integrations/dhis2/mappings")
                .param("hospitalId", hospitalId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hmsConceptCode").value("49"));
    }

    @Test
    void deleteMappingReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(delete("/admin/integrations/dhis2/mappings/" + id)
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isNoContent());
        verify(configService).deleteMapping(id, hospitalId);
    }

    private Dhis2FacilityConfigResponseDTO facilityResponse() {
        return new Dhis2FacilityConfigResponseDTO(
            UUID.randomUUID(), hospitalId, "https://dhis2.example.org",
            Dhis2AuthMode.PAT, "DHIS2_TOKEN", true,
            Dhis2PeriodType.MONTHLY, "DS00000DEFK", null, true,
            LocalDateTime.now(), LocalDateTime.now());
    }

    private Dhis2DataElementMappingResponseDTO mappingResponse() {
        return new Dhis2DataElementMappingResponseDTO(
            UUID.randomUUID(), hospitalId, "http://hl7.org/fhir/sid/cvx", "49",
            "DE000000049", null, Dhis2PeriodType.MONTHLY, "DS00000DEFK", true,
            LocalDateTime.now(), LocalDateTime.now());
    }
}
