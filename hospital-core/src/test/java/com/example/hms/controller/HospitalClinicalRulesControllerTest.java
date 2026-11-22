package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.RuleSignoffRequestDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.clinicalrules.HospitalRuleSignoffService;
import com.example.hms.service.clinicalrules.RuleSignoffPackPdfService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V356: wiring, wire shape, tenancy and declared roles of « Règles cliniques et validation ».
 *
 * <p>The slice does not run {@code @PreAuthorize} (security auto-configuration is excluded, as in every controller
 * slice here), so the gates are read off the annotations; the acting-hospital pin runs for real.
 */
@WebMvcTest(
    controllers = HospitalClinicalRulesController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("V356: the clinical-rules sign-off endpoints")
class HospitalClinicalRulesControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HospitalRuleSignoffService signoffService;
    @MockitoBean private RuleSignoffPackPdfService packService;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void actingAtTheHospital() {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(userId));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(userId).activeHospitalId(hospitalId).superAdmin(false).build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("the list reads back each rule set's status for the acting hospital")
    void list() throws Exception {
        when(signoffService.overview(hospitalId, userId))
            .thenReturn(new ClinicalRulesOverviewDTO(hospitalId, true, 0, 0, List.of()));

        mockMvc.perform(get("/hospitals/{id}/clinical-rules", hospitalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canManage").value(true))
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("another hospital than the one the request acts at is refused before the service is asked")
    void anotherHospitalIsRefused() throws Exception {
        UUID other = UUID.randomUUID();

        mockMvc.perform(get("/hospitals/{id}/clinical-rules", other)).andExpect(status().isForbidden());
        mockMvc.perform(post("/hospitals/{id}/clinical-rules/{key}/revocation", other, "PARACETAMOL_LIMITS")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Erreur de saisie\"}"))
            .andExpect(status().isForbidden());

        verify(signoffService, never()).overview(eq(other), any());
        verify(signoffService, never()).revoke(eq(other), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a sign-off carries the version, signer and date to the service; missing fields are a 400")
    void sign() throws Exception {
        when(signoffService.sign(eq(hospitalId), eq("PARACETAMOL_LIMITS"), any(), eq(userId)))
            .thenReturn(new ClinicalRuleStatusDTO("PARACETAMOL_LIMITS", "v", null, null, null, null, List.of(),
                RuleSignoffStatus.SIGNED, null, null));

        mockMvc.perform(post("/hospitals/{id}/clinical-rules/{key}/signoffs", hospitalId, "PARACETAMOL_LIMITS")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleVersion\":\"2026-11-07.1\",\"signerName\":\"Dr Awa Sawadogo\","
                    + "\"signerTitle\":\"Médecin-chef\",\"signedOn\":\"2026-11-20\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SIGNED"));
        verify(signoffService).sign(hospitalId, "PARACETAMOL_LIMITS", new RuleSignoffRequestDTO("2026-11-07.1",
            "Dr Awa Sawadogo", "Médecin-chef", null, LocalDate.of(2026, 11, 20), null), userId);

        for (String body : List.of(
                "{\"ruleVersion\":\"v\",\"signerTitle\":\"Médecin-chef\",\"signedOn\":\"2026-11-20\"}",
                "{\"ruleVersion\":\"v\",\"signerName\":\"Dr A\",\"signedOn\":\"2026-11-20\"}",
                "{\"ruleVersion\":\"v\",\"signerName\":\"Dr A\",\"signerTitle\":\"Médecin-chef\"}",
                "{\"signerName\":\"Dr A\",\"signerTitle\":\"Médecin-chef\",\"signedOn\":\"2026-11-20\"}",
                "{\"ruleVersion\":\"v\",\"signerName\":\"Dr A\",\"signerTitle\":\"Médecin-chef\","
                    + "\"signedOn\":\"2999-01-01\"}")) {
            mockMvc.perform(post("/hospitals/{id}/clinical-rules/{key}/signoffs", hospitalId, "PARACETAMOL_LIMITS")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("a revocation needs a reason")
    void revokeNeedsAReason() throws Exception {
        mockMvc.perform(post("/hospitals/{id}/clinical-rules/{key}/revocation", hospitalId, "PARACETAMOL_LIMITS")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verify(signoffService, never()).revoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the sign-off pack is a PDF download, never cached")
    void pack() throws Exception {
        when(packService.render(hospitalId, userId)).thenReturn(new byte[] {'%', 'P', 'D', 'F'});

        mockMvc.perform(get("/hospitals/{id}/clinical-rules/signoff-pack", hospitalId))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("reads admit the staff who see a « provisoire » label; writes only the hospital admin and super-admin")
    void declaredRoles() throws Exception {
        Method list = HospitalClinicalRulesController.class.getMethod("list", UUID.class,
            org.springframework.security.core.Authentication.class);
        Method sign = HospitalClinicalRulesController.class.getMethod("sign", UUID.class, String.class,
            RuleSignoffRequestDTO.class, org.springframework.security.core.Authentication.class);
        String read = list.getAnnotation(PreAuthorize.class).value();
        String write = sign.getAnnotation(PreAuthorize.class).value();

        assertThat(read).contains("ROLE_DOCTOR", "ROLE_MIDWIFE", "ROLE_NURSE", "ROLE_PHARMACIST",
            "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN").doesNotContain("ROLE_RECEPTIONIST").doesNotContain("ROLE_PATIENT");
        assertThat(write).isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')");
    }
}
