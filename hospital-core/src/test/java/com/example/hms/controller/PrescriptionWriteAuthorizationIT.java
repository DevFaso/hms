package com.example.hms.controller;

import com.example.hms.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA round 33 (RF, not filed): a receptionist's unticked POST /prescriptions
 * with "au besoin" read a 400 about the frequency, not a 403: Spring validates
 * the @Valid body before a method's @PreAuthorize. Authorization comes first,
 * at the edge, through the REAL filter chain; a prescriber still reaches the
 * body's rule.
 */
@AutoConfigureMockMvc
class PrescriptionWriteAuthorizationIT extends BaseIT {

    private static final String API_CONTEXT = "/api";
    private static final String BODY = "{\"frequency\":\"au besoin\",\"medicationName\":\"Paracetamol\","
        + "\"quantity\":1,\"route\":\"PO\"}";

    @Autowired
    private MockMvc mockMvc;

    private static Authentication as(String role) {
        return new UsernamePasswordAuthenticationToken("caller", "n/a", List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    @DisplayName("a role that may not prescribe gets 403 on create and edit, whatever the body says")
    void aRoleThatMayNotPrescribeIsRefusedBeforeTheBodyIsRead() throws Exception {
        for (String role : List.of("ROLE_RECEPTIONIST", "ROLE_HOSPITAL_ADMIN", "ROLE_PHARMACIST", "ROLE_PATIENT")) {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions").contextPath(API_CONTEXT)
                    .with(authentication(as(role))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
            mockMvc.perform(put(API_CONTEXT + "/prescriptions/{id}", UUID.randomUUID()).contextPath(API_CONTEXT)
                    .with(authentication(as(role))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"prnMinIntervalHours\":999}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("each prescribing role passes the edge and reaches the rule on the body")
    void aPrescriberReachesTheBodyRule() throws Exception {
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE")) {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions").contextPath(API_CONTEXT)
                    .with(authentication(as(role))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.asNeeded").exists());
        }
    }
}
