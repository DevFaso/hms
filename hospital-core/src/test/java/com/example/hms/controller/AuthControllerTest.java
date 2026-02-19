package com.example.hms.controller;

import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.service.PasswordResetService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private PasswordResetService passwordResetService;
    @MockitoBean private com.example.hms.service.EmailService emailService;
    @MockitoBean private UserService userService;
    @MockitoBean private UserCredentialLifecycleService userCredentialLifecycleService;

    @Test
    void register_returns410Gone() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Public self-registration is no longer available."));
    }

    @Test
    void ping_returns200Pong() throws Exception {
        mockMvc.perform(get("/auth/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("pong"));
    }

    @Test
    void logout_returns200() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully."));
    }

    @Test
    void login_withBlankUsername_returns400() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("");
        login.setPassword("password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withBlankPassword_returns400() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("testuser");
        login.setPassword("");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withInvalidCredentials_returns401() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("nonexistent_user");
        login.setPassword("wrongpassword");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bootstrapStatus_returnsAllowedFlag() throws Exception {
        when(userRepository.count()).thenReturn(0L);

        mockMvc.perform(get("/auth/bootstrap-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.userCount").value(0));
    }

    @Test
    void tokenEcho_withoutHeader_returns400() throws Exception {
        mockMvc.perform(get("/auth/token/echo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));
    }

    @Test
    void tokenEcho_withInvalidBearer_returns400() throws Exception {
        when(jwtTokenProvider.validateToken("invalid.token.value"))
                .thenThrow(new RuntimeException("Invalid JWT"));

        mockMvc.perform(get("/auth/token/echo")
                        .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void credentialsMe_returnsUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/auth/credentials/me"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // POST /auth/verify-password  (lock-screen unlock)
    // =====================================================================

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, "jwt-token-placeholder",
                AuthorityUtils.createAuthorityList("ROLE_STAFF"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void verifyPassword_correctPassword_returns200() throws Exception {
        authenticateAs("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken("drjones", null));

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password verified."));
    }

    @Test
    void verifyPassword_wrongPassword_returns401() throws Exception {
        authenticateAs("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        LoginRequest req = new LoginRequest("drjones", "WrongPassword!");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password."));
    }

    @Test
    void verifyPassword_usernameMismatch_returns403() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("someoneelse", "AnyPassword1!");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Cannot verify password for a different user."));
    }

    @Test
    void verifyPassword_disabledAccount_returns401() throws Exception {
        authenticateAs("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new DisabledException("Account is disabled"));

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is disabled."));
    }

    @Test
    void verifyPassword_blankPassword_returns400() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("drjones", "");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyPassword_blankUsername_returns400() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("", "SomePassword1!");

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}