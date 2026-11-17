package com.example.hms.controller;

import com.example.hms.service.PasswordResetRequestDispatcher;
import com.example.hms.service.PasswordResetRequestDispatcherTestSupport;
import com.example.hms.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    /** QA D802: the dispatcher's pool, held here so a test runs (or inspects) the queued work. */
    private List<Runnable> queued;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PasswordResetService.class);
        queued = new ArrayList<>();
        PasswordResetRequestDispatcher dispatcher =
            PasswordResetRequestDispatcherTestSupport.onExecutor(service, queued::add);
        mockMvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(service, dispatcher)).build();
    }

    /** What the pool would do after the answer has gone. */
    private void drainQueue() {
        List<Runnable> work = new ArrayList<>(queued);
        queued.clear();
        work.forEach(Runnable::run);
    }

    @Test
    @DisplayName("QA D802: the answer is sent before any account work; the lookup and send are queued")
    void theAccountWorkIsHandedToTheExecutor() throws Exception {
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"known@hospital.test\"}"))
            .andExpect(status().isNoContent());

        // The request thread did nothing with the account: no lookup, no token, no send.
        verifyNoInteractions(service);
        assertThat(queued).hasSize(1);

        drainQueue();
        verify(service).requestResetByIdentifier(eq("known@hospital.test"), any(Locale.class), anyString());
    }

    @Test
    @DisplayName("QA D802: request-reset queues its work the same way")
    void requestResetIsHandedToTheExecutor() throws Exception {
        mockMvc.perform(post("/auth/password/request-reset").param("identifier", "patient_a3"))
            .andExpect(status().isNoContent());

        verifyNoInteractions(service);
        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("the QA call: request-reset with a phone number is a 204, routed by identifier")
    void requestResetWithAPhoneNumber() throws Exception {
        mockMvc.perform(post("/auth/password/request-reset").param("email", "+22670202020"))
            .andExpect(status().isNoContent());
        drainQueue();

        verify(service).requestResetByIdentifier(eq("+22670202020"), any(Locale.class), anyString());
    }

    @Test
    @DisplayName("the dialog's body: an identifier (a username here) is a 204")
    void requestWithAnIdentifier() throws Exception {
        mockMvc.perform(post("/auth/password/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"patient_a3\"}"))
            .andExpect(status().isNoContent());
        drainQueue();

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
        drainQueue();

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

        assertThat(queued).isEmpty();
        verify(service, never()).requestResetByIdentifier(any(), any(), any());
    }
}
