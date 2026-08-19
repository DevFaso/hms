package com.example.hms.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.payload.dto.platform.MllpAllowedSenderRequestDTO;
import com.example.hms.payload.dto.platform.MllpAllowedSenderResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
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
    controllers = MllpAllowedSenderController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class MllpAllowedSenderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MllpAllowedSenderService service;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UUID hospitalId;
    private UUID senderId;
    private MllpAllowedSenderResponseDTO sample;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        sample = new MllpAllowedSenderResponseDTO(
            senderId, hospitalId, "Allowlisted Hospital",
            "ROCHE_COBAS", "LAB_A",
            "Roche c8000 at main lab",
            true,
            LocalDateTime.of(2026, 4, 29, 8, 30),
            LocalDateTime.of(2026, 4, 29, 8, 30)
        );
    }

    @Test
    void listReturnsAllSendersWhenHospitalIdAbsent() throws Exception {
        when(service.findAll(any(Locale.class))).thenReturn(List.of(sample));

        mockMvc.perform(get("/admin/mllp/allowed-senders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(senderId.toString()))
            .andExpect(jsonPath("$[0].sendingApplication").value("ROCHE_COBAS"))
            .andExpect(jsonPath("$[0].sendingFacility").value("LAB_A"))
            .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void listFiltersByHospitalIdWhenProvided() throws Exception {
        when(service.findByHospital(eq(hospitalId), any(Locale.class)))
            .thenReturn(List.of(sample));

        mockMvc.perform(get("/admin/mllp/allowed-senders").param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].hospitalId").value(hospitalId.toString()));
    }

    @Test
    void getByIdReturnsSender() throws Exception {
        when(service.getById(eq(senderId), any(Locale.class))).thenReturn(sample);

        mockMvc.perform(get("/admin/mllp/allowed-senders/{id}", senderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sendingApplication").value("ROCHE_COBAS"));
    }

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        MllpAllowedSenderRequestDTO request = new MllpAllowedSenderRequestDTO(
            hospitalId, "ROCHE_COBAS", "LAB_A", "Roche c8000 at main lab", true);
        when(service.create(any(MllpAllowedSenderRequestDTO.class), any(Locale.class)))
            .thenReturn(sample);

        mockMvc.perform(post("/admin/mllp/allowed-senders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/admin/mllp/allowed-senders/" + senderId))
            .andExpect(jsonPath("$.id").value(senderId.toString()));
    }

    @Test
    void createRejectsRequestWithBlankFields() throws Exception {
        MllpAllowedSenderRequestDTO bad = new MllpAllowedSenderRequestDTO(
            hospitalId, "", "", null, null);

        mockMvc.perform(post("/admin/mllp/allowed-senders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsRefreshedSender() throws Exception {
        MllpAllowedSenderRequestDTO request = new MllpAllowedSenderRequestDTO(
            hospitalId, "ROCHE_COBAS", "LAB_A", "Updated description", false);
        MllpAllowedSenderResponseDTO updated = new MllpAllowedSenderResponseDTO(
            senderId, hospitalId, "Allowlisted Hospital",
            "ROCHE_COBAS", "LAB_A", "Updated description", false,
            sample.createdAt(), LocalDateTime.now());
        when(service.update(eq(senderId), any(MllpAllowedSenderRequestDTO.class), any(Locale.class)))
            .thenReturn(updated);

        mockMvc.perform(put("/admin/mllp/allowed-senders/{id}", senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    void deactivateReturns204() throws Exception {
        mockMvc.perform(delete("/admin/mllp/allowed-senders/{id}", senderId))
            .andExpect(status().isNoContent());
        verify(service).deactivate(eq(senderId), any(Locale.class));
    }
}
