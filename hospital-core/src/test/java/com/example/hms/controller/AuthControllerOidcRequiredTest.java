package com.example.hms.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KC-5 prep — verifies that when {@code app.auth.oidc.required=true} the
 * legacy internal token issuer ({@code POST /auth/login} and
 * {@code POST /auth/token/refresh}) is sealed off with 410 Gone so
 * clients migrate to the Keycloak Auth Code + PKCE flow.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.auth.oidc.required=true"
})
class AuthControllerOidcRequiredTest {

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
    @MockitoBean private LoginAttemptService loginAttemptService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;

    @Test
    void login_returns410Gone_whenOidcRequired() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("irrelevant-because-the-flag-blocks-this");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message")
                        .value("Legacy username/password login is disabled. Sign in via Single Sign-On."));
    }

    @Test
    void refresh_returns410Gone_whenOidcRequired() throws Exception {
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"anything\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message")
                        .value("Legacy token refresh is disabled. Sign in via Single Sign-On."));
    }
}
