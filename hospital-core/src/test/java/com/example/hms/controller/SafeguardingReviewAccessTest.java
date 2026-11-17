package com.example.hms.controller;

import com.example.hms.service.safeguarding.SafeguardingReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * r53 (provisional rule 2026-11-14): the safeguarding endpoints, through real method security. Every role that is
 * not the treating clinical team or the hospital administrator is refused before the service is reached: the
 * patient (and every proxy or family member, who acts with her role in the patient portal and the native apps),
 * reception, billing, the lab, the pharmacy. The platform super-admin, who inherits clinical roles in production
 * (RoleExpansion), and another hospital's staff are refused by the service, which requires the role at the review's
 * own hospital (SafeguardingReviewServiceTest); no other endpoint can return a review (SafeguardingAccessGuardTest).
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = SafeguardingReviewController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(SafeguardingReviewAccessTest.MethodSecurity.class)
@DisplayName("r53: the safeguarding review is never reachable outside the treating team and the hospital's administrators")
class SafeguardingReviewAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SafeguardingReviewService service;

    private static final UUID REVIEW = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    /** The filters are off in this slice, so the caller is put in the context the method security reads. */
    private static void actAs(String role) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken("someone", "n/a", role));
    }

    @org.junit.jupiter.api.AfterEach
    void clearCaller() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static List<MockHttpServletRequestBuilder> everyEndpoint() {
        return List.of(
            get("/safeguarding/reviews"),
            get("/safeguarding/reviews").param("status", "REVIEWED"),
            get("/safeguarding/patients/" + PATIENT + "/reviews"),
            post("/safeguarding/reviews/" + REVIEW + "/review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"seen\"}"));
    }

    @ParameterizedTest(name = "{0} is refused on every safeguarding endpoint")
    @ValueSource(strings = {"ROLE_PATIENT", "ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT",
        "ROLE_CLAIMS_REVIEWER", "ROLE_PHARMACIST", "ROLE_LAB_SCIENTIST", "ROLE_RADIOLOGIST", "ROLE_STAFF",
        "ROLE_QUALITY_MANAGER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
    void everyOtherRoleIsRefusedAndNothingIsRead(String role) throws Exception {
        clearInvocations(service);
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            actAs(role);
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0} reads the reviews")
    @ValueSource(strings = {"ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_MIDWIFE", "ROLE_NURSE", "ROLE_HOSPITAL_ADMIN"})
    void theTreatingTeamAndTheHospitalAdministratorRead(String role) throws Exception {
        when(service.listForPatient(any())).thenReturn(List.of());
        actAs(role);
        mockMvc.perform(get("/safeguarding/patients/" + PATIENT + "/reviews")).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} does not record the review")
    @ValueSource(strings = {"ROLE_MIDWIFE", "ROLE_NURSE", "ROLE_HOSPITAL_ADMIN"})
    void onlyAPhysicianRecordsTheReview(String role) throws Exception {
        clearInvocations(service);
        actAs(role);
        mockMvc.perform(post("/safeguarding/reviews/" + REVIEW + "/review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"seen\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void aDoctorRecordsItWithANote() throws Exception {
        mockMvc.perform(post("/safeguarding/reviews/" + REVIEW + "/review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"seen\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void anEmptyNoteIsRefused() throws Exception {
        mockMvc.perform(post("/safeguarding/reviews/" + REVIEW + "/review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }
}
