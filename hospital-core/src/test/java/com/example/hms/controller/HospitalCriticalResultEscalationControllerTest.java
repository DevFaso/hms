package com.example.hms.controller;

import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.lab.CriticalResultEscalationSettingsService;
import com.example.hms.service.lab.CriticalResultEscalationSettingsService.Settings;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA r70 (C5), rule CRITICAL_RESULT_ESCALATION: wiring, wire shape, tenancy and declared roles of a hospital's
 * own escalation steps.
 *
 * <p>The slice does not run {@code @PreAuthorize} (security auto-configuration is excluded, as in every controller
 * slice here), so the gate is read off the annotations; the acting-hospital pin runs for real.
 */
@WebMvcTest(
    controllers = HospitalCriticalResultEscalationController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("QA r70 (C5): a hospital's critical-result escalation steps")
class HospitalCriticalResultEscalationControllerTest {

    private static final String PATH = "/hospitals/{id}/critical-result-escalation";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CriticalResultEscalationSettingsService settingsService;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID chief = UUID.randomUUID();

    @BeforeEach
    void actingAtTheHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(userId).activeHospitalId(hospitalId).superAdmin(false).build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private Settings settings(UUID hospital, int cover, int lead, int unresolved) {
        return new Settings(hospital, cover, lead, unresolved, false, chief,
            List.of(new CriticalResultEscalationSettingsService.Doctor(chief, "Awa Chef")));
    }

    @Test
    @DisplayName("the steps of the acting hospital are read back with its doctors, and carry no result and no patient")
    void read() throws Exception {
        when(settingsService.get(hospitalId)).thenReturn(settings(hospitalId, 10, 20, 45));

        mockMvc.perform(get(PATH, hospitalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverAfterMinutes").value(10))
            .andExpect(jsonPath("$.leadAfterMinutes").value(20))
            .andExpect(jsonPath("$.unresolvedAfterMinutes").value(45))
            .andExpect(jsonPath("$.usesDefaults").value(false))
            .andExpect(jsonPath("$.medicalLeadUserId").value(chief.toString()))
            .andExpect(jsonPath("$.doctors[0].name").value("Awa Chef"))
            .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    @DisplayName("a hospital admin reads and sets only the hospital they act at: another one is refused before the service is asked")
    void anotherHospitalIsRefused() throws Exception {
        UUID other = UUID.randomUUID();

        mockMvc.perform(get(PATH, other)).andExpect(status().isForbidden());
        mockMvc.perform(put(PATH, other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"coverAfterMinutes\":5,\"leadAfterMinutes\":6,\"unresolvedAfterMinutes\":7}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(settingsService);
    }

    @Test
    @DisplayName("a request with no acting hospital is refused; the verified super-admin in global view reads any hospital")
    void noScopeIsRefusedAndTheSuperAdminIsGlobal() throws Exception {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(userId).superAdmin(false).build());
        mockMvc.perform(get(PATH, hospitalId)).andExpect(status().isForbidden());
        verifyNoInteractions(settingsService);

        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(userId).superAdmin(true).build());
        UUID any = UUID.randomUUID();
        when(settingsService.get(any)).thenReturn(settings(any, 15, 30, 60));
        mockMvc.perform(get(PATH, any)).andExpect(status().isOk());
        verify(settingsService).get(any);
    }

    @Test
    @DisplayName("a complete, increasing set reaches the service as sent, with the médecin-chef")
    void set() throws Exception {
        when(settingsService.update(hospitalId, 10, 20, 45, chief)).thenReturn(settings(hospitalId, 10, 20, 45));

        mockMvc.perform(put(PATH, hospitalId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"coverAfterMinutes\":10,\"leadAfterMinutes\":20,\"unresolvedAfterMinutes\":45,"
                    + "\"medicalLeadUserId\":\"" + chief + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unresolvedAfterMinutes").value(45));

        verify(settingsService).update(hospitalId, 10, 20, 45, chief);
    }

    @Test
    @DisplayName("an empty body, a missing step, steps that do not increase or leave the range are a 400 and change nothing")
    void aBadSetChangesNothing() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"coverAfterMinutes\":15,\"unresolvedAfterMinutes\":60}",
                "{\"coverAfterMinutes\":30,\"leadAfterMinutes\":20,\"unresolvedAfterMinutes\":60}",
                "{\"coverAfterMinutes\":15,\"leadAfterMinutes\":15,\"unresolvedAfterMinutes\":60}",
                "{\"coverAfterMinutes\":4,\"leadAfterMinutes\":30,\"unresolvedAfterMinutes\":60}",
                "{\"coverAfterMinutes\":15,\"leadAfterMinutes\":30,\"unresolvedAfterMinutes\":1441}",
                "{\"coverAfterMinutes\":0,\"leadAfterMinutes\":0,\"unresolvedAfterMinutes\":0}")) {
            mockMvc.perform(put(PATH, hospitalId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verify(settingsService, never()).update(any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("both ways in are the hospital admin's and the super-admin's, nobody else's")
    void declaredRoles() throws Exception {
        Method read = HospitalCriticalResultEscalationController.class.getMethod("get", UUID.class);
        Method write = HospitalCriticalResultEscalationController.class.getMethod("set", UUID.class,
            HospitalCriticalResultEscalationController.UpdateRequest.class);

        assertThat(read.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')");
        assertThat(write.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')");
    }
}
