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

    /** A signed-in user as the JWT filter builds one: CustomUserDetails carrying the user id and the roles. */
    private static RequestPostProcessor signedInAs(UUID id, String username, String... roles) {
        CustomUserDetails details = new CustomUserDetails(id, username, "n/a", true,
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private RequestPostProcessor patient() {
        return signedInAs(userId, "patient001", "ROLE_PATIENT");
    }

    private RequestPostProcessor receptionist() {
        return signedInAs(UUID.randomUUID(), "reception01", "ROLE_RECEPTIONIST");
    }

    /** The same person, receptionist by day and a patient of the hospital: authorities are the union. */
    private RequestPostProcessor receptionistWhoIsAlsoAPatient() {
        return signedInAs(userId, "reception01", "ROLE_RECEPTIONIST", "ROLE_PATIENT");
    }

    private RecordSharingOptOutDTO inForce(UUID patientId, boolean value) {
        return new RecordSharingOptOutDTO(patientId, value, LocalDateTime.now(), null, null);
    }

    @Test
    @DisplayName("a patient reads the opt-out on their own record — the dev 403")
    void patientReadsOwnOptOut() throws Exception {
        when(optOutService.status(eq(myPatientId), eq(userId), any())).thenReturn(inForce(myPatientId, false));

        mockMvc.perform(get(OPT_OUT_PATH, myPatientId).contextPath(API).with(patient()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(false));
    }

    @Test
    @DisplayName("a patient revokes their own opt-out — DELETE admitted only admins")
    void patientRevokesOwnOptOut() throws Exception {
        when(optOutService.revoke(eq(myPatientId), eq(userId), any())).thenReturn(inForce(myPatientId, false));

        mockMvc.perform(delete(OPT_OUT_PATH, myPatientId).contextPath(API).with(patient()).with(csrf()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a patient opts out of their own record")
    void patientOptsOutOfOwnRecord() throws Exception {
        when(optOutService.optOut(eq(myPatientId), any(), eq(userId), any())).thenReturn(inForce(myPatientId, true));

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

        verify(optOutService, never()).status(eq(otherPatientId), any(), any());
        verify(optOutService, never()).optOut(eq(otherPatientId), any(), any(), any());
        verify(optOutService, never()).revoke(eq(otherPatientId), any(), any());
    }

    @Test
    @DisplayName("a receptionist reads and records an opt-out at the desk, and the service is asked for that patient")
    void receptionistReadsAndRecords() throws Exception {
        when(optOutService.status(eq(otherPatientId), any(), any())).thenReturn(inForce(otherPatientId, false));
        when(optOutService.optOut(eq(otherPatientId), any(), any(), any())).thenReturn(inForce(otherPatientId, true));

        mockMvc.perform(get(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.patientId").value(otherPatientId.toString()));
        mockMvc.perform(post(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(true));

        verify(optOutService).optOut(eq(otherPatientId), any(), any(), any());
    }

    @Test
    @DisplayName("a receptionist may NOT revoke an opt-out — that re-opens the record to other hospitals")
    void receptionistCannotRevoke() throws Exception {
        mockMvc.perform(delete(OPT_OUT_PATH, otherPatientId).contextPath(API).with(receptionist()).with(csrf()))
            .andExpect(status().isForbidden());
        verify(optOutService, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("a receptionist who is also a patient revokes their OWN opt-out only")
    void dualRoleReceptionistIsBoundToTheirOwnRow() throws Exception {
        // Both the matcher and the annotation admit this caller on ROLE_PATIENT; the
        // self-check must then bind them to their own row, not wave them through as staff.
        when(optOutService.revoke(eq(myPatientId), eq(userId), any())).thenReturn(inForce(myPatientId, false));

        mockMvc.perform(delete(OPT_OUT_PATH, otherPatientId).contextPath(API)
                .with(receptionistWhoIsAlsoAPatient()).with(csrf()))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete(OPT_OUT_PATH, myPatientId).contextPath(API)
                .with(receptionistWhoIsAlsoAPatient()).with(csrf()))
            .andExpect(status().isOk());

        verify(optOutService, never()).revoke(eq(otherPatientId), any(), any());
    }

    @Test
    @DisplayName("the two admin roles revoke through the real chain — the positive side of the posture")
    void adminsRevoke() throws Exception {
        for (String role : List.of("ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN")) {
            UUID admin = UUID.randomUUID();
            when(optOutService.revoke(eq(otherPatientId), eq(admin), any())).thenReturn(inForce(otherPatientId, false));
            mockMvc.perform(delete(OPT_OUT_PATH, otherPatientId).contextPath(API)
                    .with(signedInAs(admin, role.toLowerCase(), role)).with(csrf()))
                .andExpect(status().isOk());
        }
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
