package com.example.hms.controller;

import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D3351 (r70), review of #1291 (H1), through real method security. The patient's allo-immunisation is read by
 * everyone on the transfusion path: the bench, the prescriber, and the nurse who gives the unit. A build that
 * admitted the bench and the prescriber only answered 403 to the nurse, and the portal, which asks for the status
 * whenever it opens a flagged request, took the request off her screen: the bedside lost the request exactly for
 * the allo-immunised patients.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = TransfusionController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(TransfusionAntibodyStatusAccessTest.MethodSecurity.class)
@DisplayName("QA D3351: who reads a patient's allo-immunisation")
class TransfusionAntibodyStatusAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TransfusionService transfusionService;

    private static final UUID PATIENT = UUID.randomUUID();
    private static final String URL = "/transfusions/antibody-status/patient/" + PATIENT;

    /** What another hospital's record gives: the antibody, the date, that hospital's name. */
    private void knownFromAnotherHospital() {
        when(transfusionService.getAntibodyStatus(PATIENT)).thenReturn(AntibodyStatusResponseDTO.builder()
            .patientId(PATIENT).alloImmunised(true).identified(true).antibodySpecificities("anti-K")
            .identifiedHospitalName("CHU A").identifiedAt(LocalDateTime.of(2026, 5, 4, 10, 0))
            .otherHospitalNames(List.of("CHU A"))
            .workups(List.of(AntibodyStatusResponseDTO.Workup.builder().antibodySpecificities("anti-K")
                .hospitalName("CHU A").otherHospital(true).build()))
            .build());
    }

    private void readsIt() throws Exception {
        knownFromAnotherHospital();
        mockMvc.perform(get(URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alloImmunised").value(true))
            .andExpect(jsonPath("$.antibodySpecificities").value("anti-K"))
            .andExpect(jsonPath("$.identifiedHospitalName").value("CHU A"))
            .andExpect(jsonPath("$.otherHospitalNames[0]").value("CHU A"))
            .andExpect(jsonPath("$.workups[0].otherHospital").value(true))
            .andExpect(jsonPath("$.workups[0].notes").doesNotExist());
        verify(transfusionService).getAntibodyStatus(PATIENT);
    }

    @Test
    @WithMockUser(authorities = "ROLE_NURSE")
    @DisplayName("review H1: the nurse who gives the unit reads it (200), with the same minimum of another hospital's record")
    void theNurseReadsIt() throws Exception {
        readsIt();
    }

    @Test
    @WithMockUser(authorities = "ROLE_MIDWIFE")
    void theMidwifeReadsIt() throws Exception {
        readsIt();
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void thePrescriberReadsIt() throws Exception {
        readsIt();
    }

    @Test
    @WithMockUser(authorities = "ROLE_LAB_TECHNICIAN")
    void theBenchReadsIt() throws Exception {
        readsIt();
    }

    @Test
    @WithMockUser(authorities = "ROLE_RECEPTIONIST")
    @DisplayName("somebody who is not on the transfusion path is refused, and nothing is read")
    void theFrontDeskIsRefused() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        verify(transfusionService, never()).getAntibodyStatus(any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_PHARMACIST")
    void thePharmacyIsRefused() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        verify(transfusionService, never()).getAntibodyStatus(any());
    }
}
