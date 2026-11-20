package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.AdolescentConfidentialityAgeDTO;
import com.example.hms.payload.dto.recordaccess.MinorReleaseOverviewDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.recordaccess.AdolescentConfidentialityAgeService;
import com.example.hms.service.recordaccess.MinorResultReleaseService;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Policy 2026-11-20 (provisional): wiring, wire shape and declared roles of
 * the release of a minor's result and of the adolescent confidentiality age.
 *
 * <p>This slice does not run {@code @PreAuthorize} (security
 * auto-configuration is excluded, as in every controller slice here), so the
 * gates are read off the annotations: a release is a clinical act of a doctor
 * or a midwife on their own role, never a super-admin's alone.
 */
@WebMvcTest(
    controllers = {MinorResultReleaseController.class, HospitalAdolescentConfidentialityAgeController.class},
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Policy 2026-11-20: the family-release and adolescent-age endpoints")
class SensitivePolicyControllersTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MinorResultReleaseService releaseService;
    @MockitoBean private AdolescentConfidentialityAgeService ageService;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID patientId = UUID.randomUUID();
    private final UUID labOrderId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final MinorReleaseOverviewDTO overview =
        new MinorReleaseOverviewDTO(patientId, true, 12, true, List.of(), List.of());

    @BeforeEach
    void identity() {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(userId));
    }

    @Test
    @DisplayName("the overview says whether the patient is a minor and which adolescent age applies")
    void overview() throws Exception {
        when(releaseService.overview(patientId, userId)).thenReturn(overview);

        mockMvc.perform(get("/patients/{id}/family-releases", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minor").value(true))
            .andExpect(jsonPath("$.adolescentAge").value(12))
            .andExpect(jsonPath("$.adolescentAgeIsDefault").value(true));
    }

    @Test
    @DisplayName("a release carries the grant, the reason and the attestation to the service")
    void release() throws Exception {
        when(releaseService.release(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(overview);

        mockMvc.perform(post("/patients/{id}/family-releases/lab-orders/{order}", patientId, labOrderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantId\":\"" + grantId + "\",\"reason\":\"Annonce faite après conseil.\","
                    + "\"relationshipVerified\":true}"))
            .andExpect(status().isOk());

        verify(releaseService).release(patientId, labOrderId, grantId, "Annonce faite après conseil.", true, userId);
    }

    @Test
    @DisplayName("a release with no reason, a short reason or no grant is a 400 and reaches no service")
    void aReleaseNeedsAReasonAndAGrant() throws Exception {
        for (String body : List.of(
                "{\"grantId\":\"" + grantId + "\"}",
                "{\"grantId\":\"" + grantId + "\",\"reason\":\"court\"}",
                "{\"reason\":\"Annonce faite après conseil.\"}")) {
            mockMvc.perform(post("/patients/{id}/family-releases/lab-orders/{order}", patientId, labOrderId)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verify(releaseService, never()).release(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("verifying a guardian is a POST that says so, to the service")
    void verifyGuardian() throws Exception {
        when(releaseService.verifyGuardian(patientId, grantId, userId)).thenReturn(overview);

        mockMvc.perform(post("/patients/{id}/family-releases/guardians/{grant}/verified", patientId, grantId))
            .andExpect(status().isOk());

        verify(releaseService).verifyGuardian(patientId, grantId, userId);
    }

    @Test
    @DisplayName("the adolescent age reads back with the provisional default, and an age outside 0 to 18 is a 400")
    void adolescentAge() throws Exception {
        when(ageService.get(hospitalId)).thenReturn(new AdolescentConfidentialityAgeDTO(hospitalId, null, 12, 12, true));

        mockMvc.perform(get("/hospitals/{id}/adolescent-confidentiality-age", hospitalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configuredAge").doesNotExist())
            .andExpect(jsonPath("$.effectiveAge").value(12))
            .andExpect(jsonPath("$.provisional").value(true));

        for (String body : List.of("{\"age\":19}", "{\"age\":-1}")) {
            mockMvc.perform(put("/hospitals/{id}/adolescent-confidentiality-age", hospitalId)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verify(ageService, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("every release endpoint is a doctor's or a midwife's own role; the age is the hospital admin's or a super-admin's")
    void declaredRoles() {
        for (Method method : MinorResultReleaseController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            assertThat(gate).as("%s is gated", method.getName()).isNotNull();
            assertThat(gate.value()).as(method.getName())
                .isEqualTo("T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','MIDWIFE')");
        }
        List<Method> ageWrites = Arrays.stream(HospitalAdolescentConfidentialityAgeController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("set")).toList();
        assertThat(ageWrites).singleElement().satisfies(method ->
            assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')"));
    }
}
