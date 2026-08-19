package com.example.hms.controller;

import com.example.hms.service.AuthBootstrapService;
import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.WsTicketService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap row 8 — companion to {@link AuthControllerOidcRequiredTest}.
 * Verifies that when {@code app.auth.oidc.issuer-uri} is empty, the 410
 * Gone response on the legacy issuer endpoints does NOT carry a
 * {@code Link: rel="oauth2-issuer"} header. Local dev runs without OIDC
 * configured still get the runbook copy in the JSON body — they just
 * don't advertise a non-existent discovery document.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
// AuthControllerProperties is a @Component but @WebMvcTest doesn't
// auto-scan components — import it explicitly so the controller's
// constructor dependency resolves.
@org.springframework.context.annotation.Import(AuthControllerProperties.class)
@TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.frontend.base-url=https://localhost",
    "app.auth.oidc.required=true",
    "app.auth.oidc.issuer-uri="
})
class AuthControllerOidcRequiredNoIssuerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private AuthNotificationFacade authNotificationFacade;
    @MockitoBean private UserService userService;
    @MockitoBean private UserCredentialLifecycleService userCredentialLifecycleService;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private com.example.hms.security.IdleSessionGate idleSessionGate;
    @MockitoBean private com.example.hms.security.ImpersonationSessionTracker impersonationSessionTracker;
    @MockitoBean private LoginAttemptService loginAttemptService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;

    @Test
    void login_returns410Gone_withoutLinkHeader_whenIssuerUnset() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("irrelevant");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Link"));
    }

    @Test
    void refresh_returns410Gone_withoutLinkHeader_whenIssuerUnset() throws Exception {
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"anything\"}"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Link"));
    }
}
