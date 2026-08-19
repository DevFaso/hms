package com.example.hms.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterType;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.platform.AdtIntakeProviderConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = AdtIntakeProviderConfigController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class AdtIntakeProviderConfigControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AdtIntakeProviderConfigService service;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UUID hospitalId;
    private UUID configId;
    private AdtIntakeProviderConfigResponseDTO sample;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        configId = UUID.randomUUID();
        sample = new AdtIntakeProviderConfigResponseDTO(
            configId,
            hospitalId,
            "Test Hospital",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            AdmissionType.EMERGENCY,
            AcuityLevel.LEVEL_2_MODERATE,
            EncounterType.INPATIENT,
            "Auto-created from ADT^A01",
            true,
            LocalDateTime.of(2026, 5, 17, 9, 0),
            LocalDateTime.of(2026, 5, 17, 9, 0));
    }

    @Test
    void listReturnsAllConfigsWhenHospitalAbsent() throws Exception {
        when(service.findAll()).thenReturn(List.of(sample));

        mockMvc.perform(get("/admin/adt-intake-configs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(configId.toString()))
            .andExpect(jsonPath("$[0].hospitalId").value(hospitalId.toString()))
            .andExpect(jsonPath("$[0].defaultAdmissionType").value("EMERGENCY"))
            .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void listFiltersByHospitalIdWhenPresent() throws Exception {
        when(service.findByHospital(hospitalId)).thenReturn(Optional.of(sample));

        mockMvc.perform(get("/admin/adt-intake-configs")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(configId.toString()));
    }

    @Test
    void listReturnsEmptyArrayWhenHospitalFilterMisses() throws Exception {
        when(service.findByHospital(hospitalId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/adt-intake-configs")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByIdReturnsConfig() throws Exception {
        when(service.getById(eq(configId), any(Locale.class))).thenReturn(sample);

        mockMvc.perform(get("/admin/adt-intake-configs/{id}", configId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultAcuityLevel").value("LEVEL_2_MODERATE"));
    }

    @Test
    void upsertReturns201WithLocationHeader() throws Exception {
        AdtIntakeProviderConfigRequestDTO request = new AdtIntakeProviderConfigRequestDTO(
            hospitalId,
            sample.admittingProviderId(),
            sample.departmentId(),
            null,
            AdmissionType.EMERGENCY,
            AcuityLevel.LEVEL_2_MODERATE,
            EncounterType.INPATIENT,
            "Auto-created from ADT^A01",
            true);
        when(service.upsert(any(AdtIntakeProviderConfigRequestDTO.class), any(Locale.class)))
            .thenReturn(sample);

        mockMvc.perform(post("/admin/adt-intake-configs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/admin/adt-intake-configs/" + configId))
            .andExpect(jsonPath("$.id").value(configId.toString()));
    }

    @Test
    void upsertRejectsMissingRequiredFields() throws Exception {
        AdtIntakeProviderConfigRequestDTO bad = new AdtIntakeProviderConfigRequestDTO(
            null, null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/admin/adt-intake-configs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/admin/adt-intake-configs/{id}", configId))
            .andExpect(status().isNoContent());
        verify(service).delete(eq(configId), any(Locale.class));
    }
}
