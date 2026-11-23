package com.example.hms.controller;

import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
import com.example.hms.service.TransfusionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D3352 (r71): « Demander du sang » asks for the patient's current type and screen before the request is
 * written. A patient with none yet (every patient before the first one) was answered 404 « Groupe sanguin
 * introuvable », and the browser logged it as an error. No group yet is an ordinary answer: 204, no body.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = TransfusionController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(TransfusionCurrentBloodGroupTest.MethodSecurity.class)
@DisplayName("QA D3352: the current blood group of a patient who has none yet")
class TransfusionCurrentBloodGroupTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TransfusionService transfusionService;

    private static final UUID PATIENT = UUID.randomUUID();
    private static final String URL = "/transfusions/blood-groups/patient/" + PATIENT;

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void noGroupYetIsNoContentNotAnError() throws Exception {
        when(transfusionService.findCurrentBloodGroup(PATIENT)).thenReturn(Optional.empty());

        mockMvc.perform(get(URL))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(authorities = "ROLE_NURSE")
    void aGroupOnRecordIsReturned() throws Exception {
        PatientBloodGroupResponseDTO group = new PatientBloodGroupResponseDTO();
        group.setPatientId(PATIENT);
        group.setAlloImmunised(true);
        when(transfusionService.findCurrentBloodGroup(PATIENT)).thenReturn(Optional.of(group));

        mockMvc.perform(get(URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alloImmunised").value(true));
    }
}
