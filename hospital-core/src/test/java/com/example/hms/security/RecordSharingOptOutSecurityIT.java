package com.example.hms.security;

import com.example.hms.BaseIT;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.recordaccess.RecordSharingOptOutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The record-sharing opt-out through the REAL filter chain.
 *
 * <p>{@code RecordAccessControllerTest} covers the annotation layer and passed
 * throughout, because a {@code @WebMvcTest} slice never runs the matchers.
 * Dev, 2026-09-13, patient001 on /my-sharing:
 * {@code GET /api/patients/{id}/record-sharing/opt-out -> 403}. The blanket
 * {@code GET /patients/**} matcher lists no patient role, so the controller's
 * own {@code requireSelfIfPatient} never ran; {@code DELETE /patients/**}
 * admitted only HOSPITAL_ADMIN and SUPER_ADMIN, while POST matched the bare
 * {@code /patients} alone and fell through to authenticated(). A patient could
 * close the door to other hospitals and then neither read the setting back nor
 * revoke it.
 *
 * <p>So these assertions must go through the chain: {@code addFilters = false},
 * which the other patient ITs use, is exactly the blindfold that let this ship.
 */
@AutoConfigureMockMvc
class RecordSharingOptOutSecurityIT extends BaseIT {

    /** The servlet context path: Spring Security matches what is left after it is stripped. */
    private static final String API = "/api";

    private static final String OPT_OUT_PATH = "/api/patients/{id}/record-sharing/opt-out";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecordSharingOptOutService optOutService;

    @MockitoBean
    private PatientRepository patientRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID myPatientId = UUID.randomUUID();
    private final UUID otherPatientId = UUID.randomUUID();

    @BeforeEach
    void linkTheAccountToItsPatientRow() {
        Patient mine = new Patient();
        mine.setId(myPatientId);
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(mine));
    }

    /** A signed-in patient, as the JWT filter builds one: CustomUserDetails carrying the user id. */
    private RequestPostProcessor patient() {
        CustomUserDetails details = new CustomUserDetails(
            userId, "patient001", "n/a", true, List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private RecordSharingOptOutDTO inForce(boolean value) {
        return new RecordSharingOptOutDTO(myPatientId, value, LocalDateTime.now(), null, null);
    }

    @Test
    @DisplayName("a patient reads the opt-out on their own record — the dev 403")
    void patientReadsOwnOptOut() throws Exception {
        when(optOutService.status(eq(myPatientId), any())).thenReturn(inForce(false));

        mockMvc.perform(get(OPT_OUT_PATH, myPatientId).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(false));
    }

    @Test
    @DisplayName("a patient revokes their own opt-out — DELETE admitted only admins")
    void patientRevokesOwnOptOut() throws Exception {
        when(optOutService.revoke(eq(myPatientId), eq(userId), any())).thenReturn(inForce(false));

        mockMvc.perform(delete(OPT_OUT_PATH, myPatientId).contextPath(API).with(patient()).with(csrf()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a patient opts out of their own record")
    void patientOptsOutOfOwnRecord() throws Exception {
        when(optOutService.optOut(eq(myPatientId), any(), eq(userId), any())).thenReturn(inForce(true));

        mockMvc.perform(post(OPT_OUT_PATH, myPatientId).contextPath(API).with(patient()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(true));
    }

    @Test
    @DisplayName("the matcher opens the path, not the record: another patient's opt-out is still 403")
    void patientCannotTouchAnotherRecord() throws Exception {
        mockMvc.perform(get(OPT_OUT_PATH, otherPatientId).contextPath(API).with(patient()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(OPT_OUT_PATH, otherPatientId).contextPath(API).with(patient()).with(csrf()))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete(OPT_OUT_PATH, otherPatientId).contextPath(API).with(patient()).with(csrf()))
            .andExpect(status().isForbidden());

        verify(optOutService, never()).status(eq(otherPatientId), any());
        verify(optOutService, never()).optOut(eq(otherPatientId), any(), any(), any());
        verify(optOutService, never()).revoke(eq(otherPatientId), any(), any());
    }

    /** A receptionist, as the desk staff who records an opt-out for a walk-in. */
    private RequestPostProcessor receptionist() {
        CustomUserDetails details = new CustomUserDetails(
            UUID.randomUUID(), "reception01", "n/a", true,
            List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST")));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    @Test
    @DisplayName("a receptionist reads and records an opt-out at the desk")
    void receptionistReadsAndRecords() throws Exception {
        when(optOutService.status(eq(otherPatientId), any())).thenReturn(inForce(false));
        when(optOutService.optOut(eq(otherPatientId), any(), any(), any())).thenReturn(inForce(true));

        mockMvc.perform(get(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()))
            .andExpect(status().isOk());
        mockMvc.perform(post(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()).with(csrf()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a receptionist may NOT revoke an opt-out — that re-opens the record to other hospitals")
    void receptionistCannotRevoke() throws Exception {
        mockMvc.perform(delete(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()).with(csrf()))
            .andExpect(status().isForbidden());

        // Not merely refused at the edge: the service is never reached. revoke()
        // resolves the patient with findByIdUnscoped, so a receptionist who got
        // through would not even be confined to their own hospital's patients.
        verify(optOutService, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("the rest of /patients stays shut to a patient, including their own chart")
    void theBlanketStillHolds() throws Exception {
        for (String path : List.of("/api/patients/{id}", "/api/patients/{id}/storyboard",
                "/api/patients/{id}/lab-results", "/api/patients/{id}/record-access")) {
            mockMvc.perform(get(path, myPatientId).contextPath(API).with(patient()))
                .andExpect(status().isForbidden());
        }
    }
}
