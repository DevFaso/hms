package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.recordaccess.SensitivityTaggingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring and wire shape for the E8 #51 tagging endpoints.
 *
 * <p>Role gating is {@code @PreAuthorize}, which this slice does not run
 * (security auto-configuration is excluded, as in every controller slice
 * here); identity arrives through the mocked {@link ControllerAuthUtils}.
 */
@WebMvcTest(
    controllers = SensitivityTaggingController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SensitivityTaggingController")
class SensitivityTaggingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SensitivityTaggingService taggingService;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID encounterId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(userId));
    }

    @Test
    @DisplayName("GET returns the explicit and the effective category separately")
    void getEncounterTag() throws Exception {
        // They differ whenever the department carries a default, which is the
        // whole reason both are on the wire.
        when(taggingService.getEncounterTag(encounterId)).thenReturn(new SensitivityTagResponseDTO(
            encounterId, null, SensitivityCategory.BEHAVIOURAL_HEALTH,
            SensitivityCategory.BEHAVIOURAL_HEALTH, false));

        mockMvc.perform(get("/encounters/{id}/sensitivity", encounterId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.explicitCategory").doesNotExist())
            .andExpect(jsonPath("$.effectiveCategory").value("BEHAVIOURAL_HEALTH"))
            .andExpect(jsonPath("$.departmentDefault").value("BEHAVIOURAL_HEALTH"))
            .andExpect(jsonPath("$.travelsCrossHospital").value(false));
    }

    @Test
    @DisplayName("PUT tags the encounter and reports it as not travelling")
    void tagEncounter() throws Exception {
        when(taggingService.tagEncounter(encounterId, SensitivityCategory.HIV, userId))
            .thenReturn(new SensitivityTagResponseDTO(encounterId, SensitivityCategory.HIV,
                SensitivityCategory.HIV, null, false));

        mockMvc.perform(put("/encounters/{id}/sensitivity", encounterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"HIV\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.explicitCategory").value("HIV"))
            .andExpect(jsonPath("$.travelsCrossHospital").value(false));
    }

    @Test
    @DisplayName("a null category is accepted and clears the override — not a 400")
    void clearTag() throws Exception {
        // null is meaningful on this endpoint: it drops the row's override so
        // the department default applies again. A validation annotation that
        // rejected it would make the tag one-way.
        when(taggingService.tagEncounter(encounterId, null, userId))
            .thenReturn(new SensitivityTagResponseDTO(encounterId, null,
                SensitivityCategory.BEHAVIOURAL_HEALTH, SensitivityCategory.BEHAVIOURAL_HEALTH, false));

        mockMvc.perform(put("/encounters/{id}/sensitivity", encounterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveCategory").value("BEHAVIOURAL_HEALTH"));

        verify(taggingService).tagEncounter(encounterId, null, userId);
    }

    @Test
    @DisplayName("an unrecognised category is a 400, not a silently ignored tag")
    void unknownCategoryRejected() throws Exception {
        mockMvc.perform(put("/encounters/{id}/sensitivity", encounterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"ONCOLOGY\"}"))
            .andExpect(status().isBadRequest());

        verify(taggingService, never()).tagEncounter(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown encounter surfaces the service's 404")
    void unknownEncounter() throws Exception {
        when(taggingService.getEncounterTag(encounterId))
            .thenThrow(new ResourceNotFoundException("encounter.notfound", encounterId));

        mockMvc.perform(get("/encounters/{id}/sensitivity", encounterId))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET and PUT on a department's default round-trip the category")
    void departmentDefault() throws Exception {
        when(taggingService.getDepartmentDefault(departmentId)).thenReturn(
            new SensitivityTagResponseDTO(departmentId, null, null, null, true));
        when(taggingService.setDepartmentDefault(departmentId, SensitivityCategory.SUBSTANCE_USE, userId))
            .thenReturn(new SensitivityTagResponseDTO(departmentId, SensitivityCategory.SUBSTANCE_USE,
                SensitivityCategory.SUBSTANCE_USE, SensitivityCategory.SUBSTANCE_USE, false));

        mockMvc.perform(get("/departments/{id}/default-sensitivity", departmentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.travelsCrossHospital").value(true));

        mockMvc.perform(put("/departments/{id}/default-sensitivity", departmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"SUBSTANCE_USE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveCategory").value("SUBSTANCE_USE"))
            .andExpect(jsonPath("$.travelsCrossHospital").value(false));
    }

    @Test
    @DisplayName("the acting user is threaded through to the service, not dropped")
    void actorIsThreaded() throws Exception {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.empty());
        when(taggingService.setDepartmentDefault(any(), any(), isNull()))
            .thenReturn(new SensitivityTagResponseDTO(departmentId, null, null, null, true));

        mockMvc.perform(put("/departments/{id}/default-sensitivity", departmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":null}"))
            .andExpect(status().isOk());

        verify(taggingService).setDepartmentDefault(departmentId, null, null);
    }
}
