package com.example.hms.controller;

import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
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
    @MockitoBean private AuthNotificationFacade authNotificationFacade;
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

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!", null);

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

        LoginRequest req = new LoginRequest("drjones", "WrongPassword!", null);

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password."));
    }

    @Test
    void verifyPassword_usernameMismatch_returns403() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("someoneelse", "AnyPassword1!", null);

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

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!", null);

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is disabled."));
    }

    @Test
    void verifyPassword_blankPassword_returns400() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("drjones", "", null);

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyPassword_blankUsername_returns400() throws Exception {
        authenticateAs("drjones");

        LoginRequest req = new LoginRequest("", "SomePassword1!", null);

        mockMvc.perform(post("/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // =====================================================================
    // POST /auth/login  — success with hospital context
    // =====================================================================

    @Test
    void login_success_includesHospitalContext() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.util.UUID hospitalId = java.util.UUID.randomUUID();

        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("admin@hospital-a.com")
                .email("admin@hospital-a.com")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);

        com.example.hms.model.Hospital hospital = com.example.hms.model.Hospital.builder()
                .name("Hospital A")
                .code("HOSP-A")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(hospital, "id", hospitalId);

        com.example.hms.model.Role role = com.example.hms.model.Role.builder()
                .code("ROLE_HOSPITAL_ADMIN")
                .build();

        com.example.hms.model.UserRoleHospitalAssignment assignment =
                com.example.hms.model.UserRoleHospitalAssignment.builder()
                        .user(user)
                        .hospital(hospital)
                        .role(role)
                        .active(true)
                        .assignedAt(java.time.LocalDateTime.now())
                        .build();

        var auth = new UsernamePasswordAuthenticationToken(
                "admin@hospital-a.com", null,
                AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN"));

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);
        when(jwtTokenProvider.generateAccessToken(any(
                        com.example.hms.security.TokenUserDescriptor.class)))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class)))
                .thenReturn("mock.refresh.token");
        when(userRepository.findByUsername("admin@hospital-a.com"))
                .thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.resolvePreferredRole(java.util.List.of("ROLE_HOSPITAL_ADMIN")))
                .thenReturn("ROLE_HOSPITAL_ADMIN");
        when(assignmentRepository.findAllDetailedByUserId(userId))
                .thenReturn(java.util.List.of(assignment));

        LoginRequest loginReq = new LoginRequest("admin@hospital-a.com", "Password1!", null);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"))
                .andExpect(jsonPath("$.username").value("admin@hospital-a.com"))
                .andExpect(jsonPath("$.primaryHospitalId").value(hospitalId.toString()))
                .andExpect(jsonPath("$.primaryHospitalName").value("Hospital A"))
                .andExpect(jsonPath("$.hospitalIds[0]").value(hospitalId.toString()));
    }

    @Test
    void login_success_noAssignment_hospitalFieldsNull() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();

        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("newuser@example.com")
                .email("newuser@example.com")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);

        var auth = new UsernamePasswordAuthenticationToken(
                "newuser@example.com", null,
                AuthorityUtils.createAuthorityList("ROLE_DOCTOR"));

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);
        when(jwtTokenProvider.generateAccessToken(any(
                        com.example.hms.security.TokenUserDescriptor.class)))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class)))
                .thenReturn("mock.refresh.token");
        when(userRepository.findByUsername("newuser@example.com"))
                .thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.resolvePreferredRole(java.util.List.of("ROLE_DOCTOR")))
                .thenReturn("ROLE_DOCTOR");
        when(assignmentRepository.findAllDetailedByUserId(userId))
                .thenReturn(java.util.List.of());

        LoginRequest loginReq = new LoginRequest("newuser@example.com", "Password1!", null);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"))
                .andExpect(jsonPath("$.primaryHospitalId").doesNotExist())
                .andExpect(jsonPath("$.primaryHospitalName").doesNotExist())
                .andExpect(jsonPath("$.hospitalIds").doesNotExist());
    }

    // =====================================================================
    // GET /auth/csrf-token  (CSRF bootstrap)
    // =====================================================================

    @Test
    void csrfToken_withTokenAttribute_returns204() throws Exception {
        // Simulate Spring Security having set the CSRF token attribute on the request.
        // The endpoint touches token.getToken() so the CookieCsrfTokenRepository writes
        // the XSRF-TOKEN Set-Cookie header, then returns 204 No Content.
        org.springframework.security.web.csrf.DefaultCsrfToken csrfToken =
            new org.springframework.security.web.csrf.DefaultCsrfToken(
                "X-XSRF-TOKEN", "_csrf", "test-csrf-value");

        mockMvc.perform(get("/auth/csrf-token")
                        .requestAttr(
                            org.springframework.security.web.csrf.CsrfToken.class.getName(),
                            csrfToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void csrfToken_withoutTokenAttribute_returns204() throws Exception {
        // When filters are disabled (as in this WebMvcTest slice) the attribute is absent.
        // The endpoint must handle null gracefully and still return 204.
        mockMvc.perform(get("/auth/csrf-token"))
                .andExpect(status().isNoContent());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // POST /auth/token/refresh
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void refreshToken_nullBody_returns401() throws Exception {
        // Sending {} (no refreshToken key) → body.get("refreshToken") == null → 401
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_blankToken_returns401() throws Exception {
        // refreshToken present but empty string → isBlank() == true → 401
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_missingBody_returns401() throws Exception {
        // No body at all (required=false) → body == null → 401
        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_invalidToken_returns401() throws Exception {
        // validateToken() returns false → token invalid/expired → 401
        when(jwtTokenProvider.validateToken("bad.token.here")).thenReturn(false);

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad.token.here\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("invalid or has expired")));
    }

    @Test
    void refreshToken_userNotFound_returns401() throws Exception {
        // Valid token but no matching user → 401
        when(jwtTokenProvider.validateToken("valid.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("valid.refresh.token"))
                .thenReturn("ghost@example.com");
        when(userRepository.findByUsername("ghost@example.com"))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid.refresh.token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("User account not found or inactive."));
    }

    @Test
    void refreshToken_inactiveUser_returns401() throws Exception {
        // Valid token, user found but isActive == false → 401
        com.example.hms.model.User inactiveUser = com.example.hms.model.User.builder()
                .username("inactive@example.com")
                .build();
        // Override @Builder.Default isActive=true → use toBuilder
        inactiveUser = inactiveUser.toBuilder().isActive(false).build();

        when(jwtTokenProvider.validateToken("valid.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("valid.refresh.token"))
                .thenReturn("inactive@example.com");
        when(userRepository.findByUsername("inactive@example.com"))
                .thenReturn(java.util.Optional.of(inactiveUser));

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid.refresh.token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("User account not found or inactive."));
    }

    @Test
    void refreshToken_validToken_returns200WithNewTokens() throws Exception {
        // Happy-path: valid token, active user, active assignment → 200 with new tokens
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User activeUser = com.example.hms.model.User.builder()
                .username("doctor@example.com")
                .build();
        // id is in BaseEntity; set via reflection since there's no setter
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", userId);

        com.example.hms.model.Role role = com.example.hms.model.Role.builder()
                .code("ROLE_DOCTOR")
                .build();
        com.example.hms.model.UserRoleHospitalAssignment assignment =
                com.example.hms.model.UserRoleHospitalAssignment.builder()
                        .user(activeUser)
                        .role(role)
                        .active(true)
                        .assignedAt(java.time.LocalDateTime.now())
                        .build();

        long nowMs      = System.currentTimeMillis();
        long accessExp  = nowMs + 86_400_000L;   // +24 h
        long refreshExp = nowMs + 7 * 86_400_000L; // +7 d

        when(jwtTokenProvider.validateToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("old.refresh.token"))
                .thenReturn("doctor@example.com");
        when(userRepository.findByUsername("doctor@example.com"))
                .thenReturn(java.util.Optional.of(activeUser));
        when(assignmentRepository.findByUser(activeUser))
                .thenReturn(java.util.Set.of(assignment));
        when(assignmentRepository.findAllDetailedByUserId(userId))
                .thenReturn(java.util.List.of(assignment));
        when(jwtTokenProvider.generateAccessToken(
                org.mockito.ArgumentMatchers.any(
                        com.example.hms.security.TokenUserDescriptor.class)))
                .thenReturn("new.access.token");
        when(jwtTokenProvider.generateRefreshToken(
                org.mockito.ArgumentMatchers.any(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class)))
                .thenReturn("new.refresh.token");
        when(jwtTokenProvider.getExpiration("new.access.token"))
                .thenReturn(new java.util.Date(accessExp));
        when(jwtTokenProvider.getExpiration("new.refresh.token"))
                .thenReturn(new java.util.Date(refreshExp));

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new.refresh.token"))
                .andExpect(jsonPath("$.accessTokenExpiresAt").value(accessExp))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").value(refreshExp));
    }
}
