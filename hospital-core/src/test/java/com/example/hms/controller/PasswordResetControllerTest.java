package com.example.hms.controller;

import com.example.hms.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D787: PATIENT_A3 (a phone, no e-mail) could not ask for a reset. The
 * dialog posted {@code {email}}, and {@code POST /auth/password/request-reset}
 * with a phone number answered 400 ({@code @Email} on the parameter). Both
 * endpoints now take an e-mail, a username or a phone and always answer 204.
 */
class PasswordResetControllerTest {

    private PasswordResetService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PasswordResetService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(service)).build();
    }

    @Test
    @DisplayName("the QA call: request-reset with a phone number is a 204, routed by identifier")
    void requestResetWithAPhoneNumber() throws Exception {
        mockMvc.perform(post("/auth/password/request-reset").param("email", "+22670202020"))
            .andExpect(status().isNoContent());

        verify(service).requestResetByIdentifier(eq("+22670202020"), any(Locale.class), anyString());
    }

    @Test
    @DisplayName("the dialog's body: an identifier (a username here) is a 204")
    void requestWithAnIdentifier() throws Exception {
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"patient_a3\"}"))
            .andExpect(status().isNoContent());

        verify(service).requestResetByIdentifier(eq("patient_a3"), any(Locale.class), anyString());
    }

    @Test
    @DisplayName("the older bodies still work: {email} and {phone}, as the mobile apps send them")
    void olderBodies() throws Exception {
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.test\"}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"70202020\"}"))
            .andExpect(status().isNoContent());

        verify(service).requestResetByIdentifier(eq("a@b.test"), any(Locale.class), anyString());
        verify(service).requestResetByIdentifier(eq("70202020"), any(Locale.class), anyString());
    }

    @Test
    @DisplayName("an unknown account is the same 204 (the service is silent)")
    void unknownAccountIsTheSame204() throws Exception {
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"nobody@nowhere.test\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("request-reset with nothing named is a 400, and the service is not called")
    void requestResetWithNothing() throws Exception {
        mockMvc.perform(post("/auth/password/request-reset"))
            .andExpect(status().isBadRequest());

        verify(service, never()).requestResetByIdentifier(any(), any(), any());
    }
}
