package com.example.hms.controller;

import com.example.hms.service.AuthBootstrapService;
import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.MfaService;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.WsTicketService;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
// AuthControllerProperties is a @Component but @WebMvcTest doesn't
// auto-scan components — import it explicitly so the controller's
// constructor dependency resolves.
@org.springframework.context.annotation.Import(AuthControllerProperties.class)
@org.springframework.test.context.TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.frontend.base-url=https://localhost",
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    private org.springframework.context.MessageSource previousMessageSource;

    @org.junit.jupiter.api.BeforeEach
    void messageBundles() {
        previousMessageSource = (org.springframework.context.MessageSource)
            org.springframework.test.util.ReflectionTestUtils.getField(com.example.hms.utility.MessageUtil.class, "messageSource");
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        checkPasswordRunsTheComparison();
    }
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private AuthNotificationFacade authNotificationFacade;
    @MockitoBean private UserService userService;
    @MockitoBean private UserCredentialLifecycleService userCredentialLifecycleService;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private com.example.hms.security.GlobalSessionRevocationService globalSessionRevocationService;
    @MockitoBean private com.example.hms.security.IdleSessionGate idleSessionGate;
    @MockitoBean private com.example.hms.security.ImpersonationSessionTracker impersonationSessionTracker;
    @MockitoBean private LoginAttemptService loginAttemptService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerMfaGate;

    /**
     * Spring Security 7's DaoAuthenticationProvider adds a
     * FactorGrantedAuthority — FACTOR_PASSWORD — recording WHICH factor
     * authenticated the user. It arrived with the Spring Boot 4.1 upgrade and
     * it is not a role: nobody holds it, nobody can be authorised by it, and
     * no guard names it.
     *
     * Unfiltered it broke three things at once, all of them live:
     *   - every SINGLE-role user gained a second authority, so the
     *     `allRoles.size() > 1` gate forced them through a role picker they
     *     had never seen;
     *   - the picker rendered it as a card reading "FACTOR PASSWORD";
     *   - selecting it passed the holds-this-role check and minted a token
     *     whose only role no guard admits.
     */
    @Test
    void login_singleRoleUserWithFactorAuthority_doesNotAskThemToPickARole() throws Exception {
        givenAuthenticatedWith("ROLE_NURSE", "FACTOR_PASSWORD");

        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("nurse1")
                .email("nurse1@example.com")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findByUsername("nurse1")).thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.refresh.token");

        LoginRequest login = new LoginRequest();
        login.setUsername("nurse1");
        login.setPassword("password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                // One ROLE_ authority means one role: straight in, no picker.
                .andExpect(jsonPath("$.roleSelectionRequired").value(false))
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"));
    }

    @Test
    void login_multiRoleUser_isNeverOfferedAFactorAuthorityAsARole() throws Exception {
        givenAuthenticatedWith("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "FACTOR_PASSWORD");

        LoginRequest login = new LoginRequest();
        login.setUsername("admin1");
        login.setPassword("password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleSelectionRequired").value(true))
                .andExpect(jsonPath("$.availableRoles", hasSize(2)))
                .andExpect(jsonPath("$.availableRoles",
                        containsInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN")));
    }

    @Test
    void qaD536_multiRoleLoginWithoutARoleReportsTheRealForcePasswordChange() throws Exception {
        // POST /auth/login with no selectedRole, for a multi-role account on a temporary password.
        givenAuthenticatedWith("ROLE_DOCTOR", "ROLE_NURSE");
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("Multi1");
        user.setForcePasswordChange(true);
        user.setForceUsernameChange(true);
        // Typed in another case: the picker must find the account the way authentication did.
        when(userRepository.findByUsernameIgnoreCase("multi1")).thenReturn(java.util.Optional.of(user));

        LoginRequest login = new LoginRequest();
        login.setUsername("multi1");
        login.setPassword("password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleSelectionRequired").value(true))
                .andExpect(jsonPath("$.forcePasswordChange").value(true))
                .andExpect(jsonPath("$.forceUsernameChange").value(true));
    }

    /**
     * QA D1973 / D2050: a super-admin whose only assignment is SUPER_ADMIN was
     * offered eight roles (the login-time widening); picking « Médecin » minted
     * a DOCTOR token the session bootstrap did not know, and the portal signed
     * them out with no word. The picker now reads the principal's own roles.
     */
    @Test
    void qaD2050_superAdminIsOfferedOnlyTheRolesTheAccountHolds() throws Exception {
        var raw = java.util.List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("sa").password("x").authorities(raw).build();
        var widened = com.example.hms.security.RoleExpansion.authoritiesMapper().mapAuthorities(raw);
        when(authenticationManager.authenticate(any()))
                .thenReturn(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, "password", widened));

        LoginRequest pick = new LoginRequest();
        pick.setUsername("sa");
        pick.setPassword("password123");
        pick.setSelectedRole("ROLE_DOCTOR");
        mockMvc.perform(post("/auth/login").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pick)))
                .andExpect(status().isForbidden());

        com.example.hms.model.User user = com.example.hms.model.User.builder().username("sa")
                .email("sa@example.com").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", java.util.UUID.randomUUID());
        when(userRepository.findByUsername("sa")).thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.refresh.token");
        LoginRequest plain = new LoginRequest();
        plain.setUsername("sa");
        plain.setPassword("password123");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(plain)))
                .andExpect(status().isOk())
                // One held role: straight in as super-admin, no picker of inherited roles.
                .andExpect(jsonPath("$.roleSelectionRequired").value(false));
        var descriptor = org.mockito.ArgumentCaptor.forClass(com.example.hms.security.TokenUserDescriptor.class);
        verify(jwtTokenProvider).generateAccessToken(descriptor.capture(), any(), any());
        org.assertj.core.api.Assertions.assertThat(descriptor.getValue().roles()).containsExactly("ROLE_SUPER_ADMIN");
    }

    /**
     * QA D2182: a nurse (no role in app.mfa.required-roles) who enrolled an
     * authenticator gets the challenge, never a session; without one, the
     * session. The one rule: enrolling means being asked.
     */
    @Test
    void login_enrolledUserOutsideRequiredRoles_isChallenged_unenrolledIsNot() throws Exception {
        givenAuthenticatedWith("ROLE_NURSE");
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("nurse1").email("nurse1@example.com").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findByUsername("nurse1")).thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.generateMfaToken(eq("nurse1"), any())).thenReturn("mock.mfa.token");
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.refresh.token");
        LoginRequest login = new LoginRequest();
        login.setUsername("nurse1");
        login.setPassword("password123");

        when(mfaService.isMfaEnabled(userId)).thenReturn(true);
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaEnrolled").value(true))
                .andExpect(jsonPath("$.mfaToken").value("mock.mfa.token"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        verify(jwtTokenProvider, never()).generateAccessToken(
                any(com.example.hms.security.TokenUserDescriptor.class), any(), any());

        when(mfaService.isMfaEnabled(userId)).thenReturn(false);
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"));
    }

    /** QA D2184: login stamps one session id on both tokens of the pair. */
    @Test
    void login_stampsOneSessionIdOnThePair() throws Exception {
        givenAuthenticatedWith("ROLE_NURSE");
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("nurse1").email("nurse1@example.com").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", java.util.UUID.randomUUID());
        when(userRepository.findByUsername("nurse1")).thenReturn(java.util.Optional.of(user));
        LoginRequest login = new LoginRequest();
        login.setUsername("nurse1");
        login.setPassword("password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk());

        var accessSid = org.mockito.ArgumentCaptor.forClass(String.class);
        var refreshSid = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jwtTokenProvider).generateAccessToken(
                any(com.example.hms.security.TokenUserDescriptor.class), any(), accessSid.capture());
        verify(jwtTokenProvider).generateRefreshToken(
                any(com.example.hms.security.TokenUserDescriptor.class), any(), refreshSid.capture());
        assertThat(accessSid.getValue()).isNotBlank().isEqualTo(refreshSid.getValue());
    }

    /** QA D2184: logout revokes the session the bearer and the refresh token name, not only their jtis. */
    @Test
    void logout_revokesTheWholeSession() throws Exception {
        givenToken("access.jwt", "patient1", "jti-access");
        givenToken("refresh.jwt", "patient1", "jti-refresh");
        when(jwtTokenProvider.getSessionId("access.jwt")).thenReturn("session-1");
        when(jwtTokenProvider.getSessionId("refresh.jwt")).thenReturn("session-1");
        when(jwtTokenProvider.getRefreshTokenExpirationMs()).thenReturn(172_800_000L);
        // Revoked once: the second sighting of the same session finds it revoked.
        when(tokenBlacklistService.isSessionRevoked("session-1")).thenReturn(false, true);

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer access.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).revokeSession(eq("session-1"), org.mockito.ArgumentMatchers.anyLong());
    }

    /** QA D2184: a refresh token whose session was ended mints nothing. */
    @Test
    void refresh_ofARevokedSession_returns401() throws Exception {
        when(jwtTokenProvider.isRefreshToken("refresh.jwt")).thenReturn(true);
        when(jwtTokenProvider.getSessionId("refresh.jwt")).thenReturn("session-1");
        when(tokenBlacklistService.isSessionRevoked("session-1")).thenReturn(true);

        mockMvc.perform(post("/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isUnauthorized());

        verify(tokenBlacklistService, never()).claim(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** Authenticate as a user carrying exactly these authorities. */
    private void givenAuthenticatedWith(String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList();
        var authentication =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "user", "password", granted);
        org.mockito.Mockito.when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(authentication);
    }

    @Test
    void register_returns410Gone() throws Exception {
        mockMvc.perform(post("/auth/register").header("Accept-Language", "en")
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
        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully."));
    }

    // ───────────── logout revokes the refresh token the mobile apps hold ─────────────

    private void givenToken(String token, String username, String jti) {
        when(jwtTokenProvider.validateToken(token)).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT(token)).thenReturn(username);
        when(jwtTokenProvider.getJtiFromToken(token)).thenReturn(jti);
        when(jwtTokenProvider.getExpiration(token)).thenReturn(new java.util.Date(4_102_444_800_000L));
    }

    @Test
    void logout_withBodyRefreshTokenOfTheSameUser_revokesBothTokens() throws Exception {
        givenToken("access.jwt", "patient1", "jti-access");
        givenToken("refresh.jwt", "patient1", "jti-refresh");

        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en")
                        .header("Authorization", "Bearer access.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("jti-access", 4_102_444_800_000L);
        verify(tokenBlacklistService).blacklist("jti-refresh", 4_102_444_800_000L);
    }

    @Test
    void logout_revokesBothTheCookieAndTheBodyRefreshToken() throws Exception {
        // The Android cookie jar keeps the login-time refresh cookie while the
        // app rotates the token it really uses in the body: the cookie can be
        // stale, so the body token must be revoked too.
        givenToken("access.jwt", "patient1", "jti-access");
        givenToken("cookie.refresh", "patient1", "jti-cookie");
        givenToken("body.refresh", "patient1", "jti-body");
        when(refreshTokenCookieService.read(any())).thenReturn("cookie.refresh");

        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en")
                        .header("Authorization", "Bearer access.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"body.refresh\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("jti-cookie", 4_102_444_800_000L);
        verify(tokenBlacklistService).blacklist("jti-body", 4_102_444_800_000L);
    }

    @Test
    void logout_neverRevokesAnotherUsersRefreshToken() throws Exception {
        givenToken("access.jwt", "patient1", "jti-access");
        givenToken("refresh.jwt", "patient2", "jti-other");

        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en")
                        .header("Authorization", "Bearer access.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("jti-access", 4_102_444_800_000L);
        verify(tokenBlacklistService, never()).blacklist(eq("jti-other"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void logout_afterTheAccessTokenExpired_stillRevokesTheRefreshToken() throws Exception {
        // An idle patient signs out with an expired access token: no bearer
        // identity, but the refresh token they hand back must still die.
        when(jwtTokenProvider.getJtiFromToken("expired.jwt"))
                .thenThrow(new IllegalArgumentException("expired"));
        givenToken("refresh.jwt", "patient1", "jti-refresh");

        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en")
                        .header("Authorization", "Bearer expired.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.jwt\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("jti-refresh", 4_102_444_800_000L);
    }

    @Test
    void logout_withAnExpiredRefreshToken_stillSucceeds() throws Exception {
        givenToken("access.jwt", "patient1", "jti-access");
        when(jwtTokenProvider.validateToken("stale.jwt")).thenReturn(false);

        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en")
                        .header("Authorization", "Bearer access.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"stale.jwt\"}"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("jti-access", 4_102_444_800_000L);
        verify(jwtTokenProvider, never()).getJtiFromToken("stale.jwt");
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

    /** D64: a French sign-in screen got "Invalid username or password." in English. */
    @Test
    void login_withInvalidCredentials_isTranslated() throws Exception {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "fr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody", "wrongpassword", null))))
                .andExpect(status().isUnauthorized())
                // The slice's MessageSource does not unescape '' for argument-less
                // messages the way the production LocaleConfig does, so match the
                // part without the apostrophe.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.endsWith("utilisateur ou mot de passe incorrect.")));

        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "es")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody", "wrongpassword", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Nombre de usuario o contraseña incorrectos."));
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
        com.example.hms.utility.MessageUtil.setMessageSource(previousMessageSource);
        SecurityContextHolder.clearContext();
    }

    /**
     * D2001: the mocked bucket still runs the comparison it is handed, so the
     * controller's own answers (200, 401, 400) stay under test here. The real
     * bucket is under test in LockScreenLockoutIntegrationTest.
     */
    void checkPasswordRunsTheComparison() {
        when(loginAttemptService.checkPassword(any(), any())).thenAnswer(inv ->
                ((java.util.function.BooleanSupplier) inv.getArgument(1)).getAsBoolean()
                        ? LoginAttemptService.PasswordCheck.ACCEPTED
                        : LoginAttemptService.PasswordCheck.REJECTED);
    }

    /** The lock screen's account: verify-password looks the signed-in user up. */
    private void lockScreenAccount(String username) {
        com.example.hms.model.User u = com.example.hms.model.User.builder().username(username).isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
        when(userRepository.findByUsername(username)).thenReturn(java.util.Optional.of(u));
    }

    private void authenticateAs(String username) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, "jwt-token-placeholder",
                AuthorityUtils.createAuthorityList("ROLE_STAFF"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void recoveryCodeRequiresAReadBackBodyBeforeCallingTheLifecycleService() throws Exception {
        String route = "/auth/credentials/recovery/" + java.util.UUID.randomUUID() + "/send-code";
        for (String body : java.util.List.of("{}", "{\"confirmedContactValue\":\"\"}", "{\"confirmedContactValue\":\"   \"}")) {
            mockMvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.confirmedContactValue").exists());
        }
        mockMvc.perform(post(route)).andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(userCredentialLifecycleService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"EMAIL", "PHONE"})
    void recoveryCodePassesTheExplicitReadBackAndActorToTheServerGuard(String type) throws Exception {
        java.util.UUID actorId = java.util.UUID.randomUUID();
        java.util.UUID contactId = java.util.UUID.randomUUID();
        var user = new com.example.hms.model.User();
        user.setId(actorId);
        authenticateAs("readback.test");
        when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(any(), any(), any()))
            .thenReturn(java.util.Optional.of(user));
        String value = type.equals("PHONE") ? "+22670123456" : "backup@example.test";
        when(userCredentialLifecycleService.sendRecoveryContactVerificationCode(actorId, contactId, value)).thenReturn(type);
        mockMvc.perform(post("/auth/credentials/recovery/" + contactId + "/send-code")
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmedContactValue\":\"" + value + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contactType").value(type));
        verify(userCredentialLifecycleService).sendRecoveryContactVerificationCode(actorId, contactId, value);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "fr", "es"})
    void recoveryDeliveryMessagesUseTheRequestLanguage(String language) throws Exception {
        String sent = switch (language) {
            case "fr" -> "Code de vérification envoyé.";
            case "es" -> "Código de verificación enviado.";
            default -> "Verification code sent";
        };
        UUID actorId = UUID.randomUUID();
        var actor = new com.example.hms.model.User();
        actor.setId(actorId);
        actor.setUsername("display.test");
        when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(any(), any(), any()))
            .thenReturn(Optional.of(actor));
        for (String type : java.util.List.of("PHONE", "EMAIL")) {
            UUID contactId = UUID.randomUUID();
            authenticateAs("display.test");
            when(userCredentialLifecycleService.sendRecoveryContactVerificationCode(actorId, contactId, "backup@example.test")).thenReturn(type);
            mockMvc.perform(post("/auth/credentials/recovery/" + contactId + "/send-code").header("Accept-Language", language)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"confirmedContactValue\":\"backup@example.test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value(sent))
                .andExpect(jsonPath("$.contactType").value(type));
            verify(userCredentialLifecycleService).sendRecoveryContactVerificationCode(actorId, contactId, "backup@example.test");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en", "fr", "es"})
    void refreshReplayMessagesUseTheRequestLanguage(String language) throws Exception {
        String replay = switch (language) {
            case "fr" -> "Ce jeton de renouvellement a déjà été utilisé. Reconnectez-vous.";
            case "es" -> "Este token de renovación ya se ha utilizado. Inicie sesión de nuevo.";
            default -> "Refresh token has already been used. Please log in again.";
        };
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("old.refresh.token")).thenReturn("jti-old");
        when(tokenBlacklistService.isBlacklisted("jti-old")).thenReturn(true);
        when(tokenBlacklistService.wasRecentlyRotated("jti-old")).thenReturn(false);
        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"old.refresh.token\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(replay));
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any());
    }

    // =====================================================================
    // POST /auth/me/complete-setup  (D17: the forced first sign-in, one call)
    // =====================================================================

    private com.example.hms.model.User forcedInvitee(boolean rename, boolean password) {
        com.example.hms.model.User u = com.example.hms.model.User.builder()
                .username("invitee.tmp").isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
        u.setForceUsernameChange(rename);
        u.setForcePasswordChange(password);
        when(userRepository.findByUsername("invitee.tmp")).thenReturn(java.util.Optional.of(u));
        authenticateAs("invitee.tmp");
        return u;
    }

    private void currentPasswordAccepted() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken("invitee.tmp", null));
    }

    private org.springframework.test.web.servlet.ResultActions completeSetup(String json) throws Exception {
        return mockMvc.perform(post("/auth/me/complete-setup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void completeSetup_appliesUsernameAndPasswordInOneServiceCall() throws Exception {
        var u = forcedInvitee(true, true);
        currentPasswordAccepted();

        completeSetup("{\"newUsername\":\" awa.traore \",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}")
                .andExpect(status().isOk());

        verify(userService).completeAccountSetup(u.getId(), "awa.traore", "New#Pass2026");
        verify(userService, never()).changeOwnUsername(any(), any());
        verify(userService, never()).changeOwnPassword(any(), any());
    }

    @Test
    void completeSetup_keepingTheCurrentUsernameIsAccepted() throws Exception {
        // QA D52: the forced step refused the name the account already had.
        var u = forcedInvitee(true, true);
        currentPasswordAccepted();

        completeSetup("{\"newUsername\":\"invitee.tmp\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}")
                .andExpect(status().isOk());

        verify(userService).completeAccountSetup(u.getId(), "invitee.tmp", "New#Pass2026");
    }

    @Test
    void completeSetup_onlyThePasswordWhenOnlyItIsForced() throws Exception {
        var u = forcedInvitee(false, true);
        currentPasswordAccepted();

        completeSetup("{\"newUsername\":\"sneaky.rename\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}")
                .andExpect(status().isOk());

        verify(userService).completeAccountSetup(u.getId(), null, "New#Pass2026");
    }

    @Test
    void completeSetup_wrongCurrentPasswordIs400AndChangesNothing() throws Exception {
        forcedInvitee(true, true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        completeSetup("{\"newUsername\":\"awa.traore\",\"currentPassword\":\"wrong\",\"newPassword\":\"New#Pass2026\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The current password is incorrect."));

        verify(userService, never()).completeAccountSetup(any(), any(), any());
    }

    @Test
    void completeSetup_refusesBadValuesBeforeChangingAnything() throws Exception {
        forcedInvitee(true, true);
        currentPasswordAccepted();
        when(passwordHistoryService.isPasswordReused(any(), eq("Old#Pass2025"))).thenReturn(true);

        for (String json : java.util.List.of(
                "{\"newUsername\":\"awa.traore\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"short\"}",
                "{\"newUsername\":\"bad name\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}",
                "{\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}",
                "{\"newUsername\":\"awa.traore\",\"currentPassword\":\"Temp#Pass1\"}",
                "{\"newUsername\":\"awa.traore\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"Temp#Pass1\"}",
                "{\"newUsername\":\"awa.traore\",\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"Old#Pass2025\"}")) {
            completeSetup(json).andExpect(status().isBadRequest());
        }

        verify(userService, never()).completeAccountSetup(any(), any(), any());
    }

    @Test
    void completeSetup_nothingPendingIs400() throws Exception {
        forcedInvitee(false, false);

        completeSetup("{\"currentPassword\":\"Temp#Pass1\",\"newPassword\":\"New#Pass2026\"}")
                .andExpect(status().isBadRequest());

        verify(authenticationManager, never()).authenticate(any());
        verify(userService, never()).completeAccountSetup(any(), any(), any());
    }

    @Test
    void completeSetup_aTakenUsernameIs400() throws Exception {
        forcedInvitee(true, false);
        currentPasswordAccepted();
        doThrow(new com.example.hms.exception.BusinessException("This username is already taken."))
                .when(userService).completeAccountSetup(any(), eq("awa.traore"), eq(null));

        completeSetup("{\"newUsername\":\"awa.traore\",\"currentPassword\":\"Temp#Pass1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This username is already taken."));
    }

    @Test
    void verifyPassword_correctPassword_returns200() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken("drjones", null));

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password verified."));
    }

    @Test
    void verifyPassword_wrongPassword_returns401() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        LoginRequest req = new LoginRequest("drjones", "WrongPassword!", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password."));
    }

    @Test
    void verifyPassword_usernameMismatch_returns403() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        LoginRequest req = new LoginRequest("someoneelse", "AnyPassword1!", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Cannot verify password for a different user."));
    }

    @Test
    void verifyPassword_disabledAccount_returns401() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new DisabledException("Account is disabled"));

        LoginRequest req = new LoginRequest("drjones", "CorrectPass1!", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Account is disabled."));
    }

    @Test
    void verifyPassword_blankPassword_returns400() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        LoginRequest req = new LoginRequest("drjones", "", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyPassword_blankUsername_returns400() throws Exception {
        authenticateAs("drjones");
        lockScreenAccount("drjones");

        LoginRequest req = new LoginRequest("", "SomePassword1!", null);

        mockMvc.perform(post("/auth/verify-password").header("Accept-Language", "en")
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
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
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
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
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

    /**
     * Regression for the "idle past the configured window" bug surfaced on
     * dev: a fresh login → bootstrap → 401 → refresh → 401 loop because
     * neither {@link com.example.hms.security.InMemoryIdleSessionTracker}
     * nor {@link com.example.hms.security.RedisIdleSessionTracker} can
     * distinguish "never touched" from "TTL-evicted" — both report
     * isIdle=true on a missing entry, and the JWT filter only touches
     * AFTER the gate has passed, so the entry is never created. The fix
     * is to seed the idle window on token issue. This test pins that
     * behaviour: a successful login MUST call {@code touchIfHuman} with
     * the user id and the effective authorities.
     */
    @Test
    void login_success_seedsIdleWindow() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();

        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("dr.jones@hospital-a.com")
                .email("dr.jones@hospital-a.com")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);

        var auth = new UsernamePasswordAuthenticationToken(
                "dr.jones@hospital-a.com", null,
                AuthorityUtils.createAuthorityList("ROLE_DOCTOR"));

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);
        when(jwtTokenProvider.generateAccessToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                        com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("mock.refresh.token");
        when(userRepository.findByUsername("dr.jones@hospital-a.com"))
                .thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.resolvePreferredRole(java.util.List.of("ROLE_DOCTOR")))
                .thenReturn("ROLE_DOCTOR");
        when(assignmentRepository.findAllDetailedByUserId(userId))
                .thenReturn(java.util.List.of());

        LoginRequest loginReq = new LoginRequest("dr.jones@hospital-a.com", "Password1!", null);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk());

        // The crux of the regression: idleSessionGate.touchIfHuman MUST be
        // called for this user with at least the selected authority, so the
        // very next request after login does not see a missing entry and
        // get rejected as "idle past the configured window".
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<? extends org.springframework.security.core.GrantedAuthority>> authoritiesCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(idleSessionGate).touchIfHuman(eq(userId), authoritiesCaptor.capture());
        java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> captured = authoritiesCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(
                captured.stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_DOCTOR"::equals),
                "touchIfHuman must receive the issued role authority so the "
                        + "machine-role carve-out has its data");
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
        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_blankToken_returns401() throws Exception {
        // refreshToken present but empty string → isBlank() == true → 401
        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_missingBody_returns401() throws Exception {
        // No body at all (required=false) → body == null → 401
        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token is required."));
    }

    @Test
    void refreshToken_invalidToken_returns401() throws Exception {
        // isRefreshToken() returns false → token invalid/expired → 401
        when(jwtTokenProvider.isRefreshToken("bad.token.here")).thenReturn(false);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad.token.here\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("invalid or has expired")));
    }

    @Test
    void refreshToken_userNotFound_returns401() throws Exception {
        // Valid token but no matching user → 401
        when(jwtTokenProvider.isRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("valid.refresh.token"))
                .thenReturn("ghost@example.com");
        when(userRepository.findByUsername("ghost@example.com"))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
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

        when(jwtTokenProvider.isRefreshToken("valid.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("valid.refresh.token"))
                .thenReturn("inactive@example.com");
        when(userRepository.findByUsername("inactive@example.com"))
                .thenReturn(java.util.Optional.of(inactiveUser));

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
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

        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        claimableRefresh("old.refresh.token");
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
                        com.example.hms.security.TokenUserDescriptor.class),
                org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn("new.access.token");
        when(jwtTokenProvider.generateRefreshToken(
                org.mockito.ArgumentMatchers.any(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class),
                org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn("new.refresh.token");
        when(jwtTokenProvider.getExpiration("new.access.token"))
                .thenReturn(new java.util.Date(accessExp));
        when(jwtTokenProvider.getExpiration("new.refresh.token"))
                .thenReturn(new java.util.Date(refreshExp));

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new.refresh.token"))
                .andExpect(jsonPath("$.accessTokenExpiresAt").value(accessExp))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").value(refreshExp));
    }

    /** The refresh token has a jti, and this refresh is the one that claims it. */
    private void claimableRefresh(String token) {
        long exp = System.currentTimeMillis() + 3_600_000L;
        when(jwtTokenProvider.getJtiFromToken(token)).thenReturn("jti-" + token);
        when(jwtTokenProvider.getExpiration(token)).thenReturn(new java.util.Date(exp));
        when(tokenBlacklistService.claim("jti-" + token, exp)).thenReturn(true);
    }

    @Test
    void refreshToken_aTokenAnotherRefreshClaimedFirst_mintsNothing() throws Exception {
        long exp = System.currentTimeMillis() + 3_600_000L;
        com.example.hms.model.User activeUser = com.example.hms.model.User.builder()
                .username("doctor@example.com").isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", java.util.UUID.randomUUID());
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("old.refresh.token")).thenReturn("jti-old");
        when(jwtTokenProvider.getExpiration("old.refresh.token")).thenReturn(new java.util.Date(exp));
        when(tokenBlacklistService.claim("jti-old", exp)).thenReturn(false);
        when(jwtTokenProvider.getUsernameFromJWT("old.refresh.token")).thenReturn("doctor@example.com");
        when(userRepository.findByUsername("doctor@example.com")).thenReturn(java.util.Optional.of(activeUser));

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                // A sibling tab won the race: superseded, retry with the
                // rotated cookie, never "sign in again" (which signs every
                // tab out).
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("refreshed by another")));
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any());
        // Rotated BEFORE spent: a sibling that sees the token spent always
        // sees it rotated too (409, never 401 and a sign-out).
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tokenBlacklistService);
        order.verify(tokenBlacklistService).markRotated("jti-old", AuthController.REFRESH_ROTATION_GRACE_MS);
        order.verify(tokenBlacklistService).claim("jti-old", exp);
    }

    @Test
    void refreshToken_aTokenAnotherTabJustRotated_isSuperseded_409_mintsNothing() throws Exception {
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("old.refresh.token")).thenReturn("jti-old");
        when(tokenBlacklistService.isBlacklisted("jti-old")).thenReturn(true);
        when(tokenBlacklistService.wasRecentlyRotated("jti-old")).thenReturn(true);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("refreshed by another")));
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any());
    }

    @Test
    void refreshToken_aTokenUsedLongAgo_isAReplay_401() throws Exception {
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("old.refresh.token")).thenReturn("jti-old");
        when(tokenBlacklistService.isBlacklisted("jti-old")).thenReturn(true);
        when(tokenBlacklistService.wasRecentlyRotated("jti-old")).thenReturn(false);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token has already been used. Please log in again."));
    }

    @Test
    void refreshToken_aSuccessfulRotation_opensTheGraceWindowForSiblingTabs() throws Exception {
        com.example.hms.model.User activeUser = com.example.hms.model.User.builder()
                .username("doctor@example.com").isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", java.util.UUID.randomUUID());
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        claimableRefresh("old.refresh.token");
        when(jwtTokenProvider.getUsernameFromJWT("old.refresh.token")).thenReturn("doctor@example.com");
        when(userRepository.findByUsername("doctor@example.com")).thenReturn(java.util.Optional.of(activeUser));
        // The refresh mints with the second-factor instant (#851): the two-argument forms.
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenReturn("new.access.token");
        when(jwtTokenProvider.generateRefreshToken(any(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class), any(), any()))
                .thenReturn("new.refresh.token");
        when(jwtTokenProvider.getExpiration("new.access.token"))
                .thenReturn(new java.util.Date(System.currentTimeMillis() + 60_000L));
        when(jwtTokenProvider.getExpiration("new.refresh.token"))
                .thenReturn(new java.util.Date(System.currentTimeMillis() + 3_600_000L));

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isOk());
        verify(tokenBlacklistService).markRotated("jti-old.refresh.token", AuthController.REFRESH_ROTATION_GRACE_MS);
    }

    @Test
    void refreshToken_aMintThatFails_givesTheOldTokenBack() throws Exception {
        com.example.hms.model.User activeUser = com.example.hms.model.User.builder()
                .username("doctor@example.com").isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", java.util.UUID.randomUUID());
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        claimableRefresh("old.refresh.token");
        when(jwtTokenProvider.getUsernameFromJWT("old.refresh.token")).thenReturn("doctor@example.com");
        when(userRepository.findByUsername("doctor@example.com")).thenReturn(java.util.Optional.of(activeUser));
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any()))
                .thenThrow(new IllegalStateException("transient"));

        try {
            mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"old.refresh.token\"}"));
        } catch (Exception propagated) {
            // the slice may rethrow the failure; what matters is the release below
        }
        // Not stranded: the next refresh with the same cookie can still succeed.
        verify(tokenBlacklistService).release("jti-old.refresh.token");
    }

    @Test
    void refreshToken_issuedBeforeAForceLogoutAll_returns401() throws Exception {
        java.util.Date issuedAt = new java.util.Date(System.currentTimeMillis() - 60_000L);
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getIssuedAt("old.refresh.token")).thenReturn(issuedAt);
        when(globalSessionRevocationService.revokesFresh(issuedAt)).thenReturn(true);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("Refresh token is invalid or has expired. Please log in again."));

        // Refused on the revocation itself, before the token's user is even looked up.
        verify(jwtTokenProvider, never()).getUsernameFromJWT(any());
        verify(userRepository, never()).findByUsername(any());
    }

    // ───────────────────────────── S-01: refresh-token cookie ─────────────────────────────

    @Test
    void logout_clearsRefreshCookieViaCookieService() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Accept-Language", "en"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(refreshTokenCookieService)
                .clear(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshToken_prefersCookieOverBody() throws Exception {
        // Setup minimal happy path with cookie carrying the refresh token,
        // body present but with a *different* value to prove cookie wins.
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User activeUser = com.example.hms.model.User.builder()
                .username("doctor@example.com")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(activeUser, "id", userId);

        com.example.hms.model.Role role = com.example.hms.model.Role.builder()
                .code("ROLE_DOCTOR").build();
        com.example.hms.model.UserRoleHospitalAssignment assignment =
                com.example.hms.model.UserRoleHospitalAssignment.builder()
                        .user(activeUser).role(role).active(true)
                        .assignedAt(java.time.LocalDateTime.now()).build();

        long nowMs = System.currentTimeMillis();
        when(refreshTokenCookieService.read(org.mockito.ArgumentMatchers.any()))
                .thenReturn("cookie.refresh.token");
        when(jwtTokenProvider.isRefreshToken("cookie.refresh.token")).thenReturn(true);
        claimableRefresh("cookie.refresh.token");
        when(jwtTokenProvider.getUsernameFromJWT("cookie.refresh.token"))
                .thenReturn("doctor@example.com");
        when(userRepository.findByUsername("doctor@example.com"))
                .thenReturn(java.util.Optional.of(activeUser));
        when(assignmentRepository.findByUser(activeUser))
                .thenReturn(java.util.Set.of(assignment));
        when(assignmentRepository.findAllDetailedByUserId(userId))
                .thenReturn(java.util.List.of(assignment));
        when(jwtTokenProvider.generateAccessToken(
                org.mockito.ArgumentMatchers.any(
                        com.example.hms.security.TokenUserDescriptor.class),
                org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn("new.access.token");
        when(jwtTokenProvider.generateRefreshToken(
                org.mockito.ArgumentMatchers.any(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class),
                org.mockito.ArgumentMatchers.isNull(), any()))
                .thenReturn("new.refresh.token");
        when(jwtTokenProvider.getExpiration("new.access.token"))
                .thenReturn(new java.util.Date(nowMs + 86_400_000L));
        when(jwtTokenProvider.getExpiration("new.refresh.token"))
                .thenReturn(new java.util.Date(nowMs + 7 * 86_400_000L));

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(new jakarta.servlet.http.Cookie("hms_refresh", "cookie.refresh.token"))
                        .content("{\"refreshToken\":\"body.refresh.token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"));

        // The cookie value, not the body value, must have been validated
        verify(jwtTokenProvider).isRefreshToken("cookie.refresh.token");
        // Rotation must have written a fresh cookie back to the response
        org.mockito.Mockito.verify(refreshTokenCookieService)
                .write(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("new.refresh.token"),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void refreshToken_missingCookieAndBody_returns401() throws Exception {
        when(refreshTokenCookieService.read(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // Lockout
    // =====================================================================

    @Test
    void login_locked_answers423BeforeAnyPasswordCheck() throws Exception {
        when(loginAttemptService.remainingLockMs("someone")).thenReturn(90_000L);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("someone", "AnyPass1!", null))))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.message").value(
                        "Account is temporarily locked. Try again in 2 minutes."));

        // The lock is read once, and the password is never tried: the answer
        // is the same whether "someone" exists or not.
        verify(loginAttemptService).remainingLockMs("someone");
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void login_locked_isTranslatedWithPlural() throws Exception {
        when(loginAttemptService.remainingLockMs("someone")).thenReturn(30_000L);

        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "fr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("someone", "AnyPass1!", null))))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.message").value(
                        "Compte temporairement verrouillé. Réessayez dans 1 minute."));

        when(loginAttemptService.remainingLockMs("someone")).thenReturn(12 * 60_000L);
        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "es")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("someone", "AnyPass1!", null))))
                .andExpect(jsonPath("$.message").value(
                        "La cuenta está bloqueada temporalmente. Vuelva a intentarlo en 12 minutos."));
    }

    @Test
    void login_badCredentials_auditsLoginFailureByAccountIdOnly() throws Exception {
        java.util.UUID accountId = java.util.UUID.randomUUID();
        when(userRepository.findIdsByUsernameIgnoreCase("someone")).thenReturn(java.util.List.of(accountId));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("someone", "Secret-Typed-1!", null))))
                .andExpect(status().isUnauthorized());

        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> audit =
                org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        org.assertj.core.api.Assertions.assertThat(audit.getValue().getEventType())
                .isEqualTo(com.example.hms.enums.AuditEventType.LOGIN_FAILURE);
        org.assertj.core.api.Assertions.assertThat(audit.getValue().getUserId()).isEqualTo(accountId);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(audit.getValue()))
                .doesNotContain("Secret-Typed-1!").doesNotContain("someone");
    }

    // =====================================================================
    // Disabled-account guidance
    // =====================================================================

    @Test
    void login_disabledAccount_namesBothRoutesAndDisclosesNoState() throws Exception {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new DisabledException("Account is disabled"));

        var result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("someone", "AnyPass1!", null))))
                .andExpect(status().isUnauthorized())
                // Both routes named, neither asserted: patients activate with a
                // link, staff with a role-confirmation code, and a deactivated
                // account has neither.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("activation link or confirmation code")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("contact your administrator")))
                // The old text sent everyone to an email-verification flow that
                // only self-registered patients have.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("verify your email"))))
                .andReturn();

        // isEnabled is checked before the password, so this arm answers
        // unauthenticated probes: it must not look the account up at all,
        // and it must count toward the lockout so the probe is throttled.
        verify(userRepository, never()).findByUsername("someone");
        verify(loginAttemptService).recordFailure("someone");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void verifyEmail_delegatesTheWholeActivationToTheTransactionalService() throws Exception {
        when(userService.verifyEmail("apatient@example.com", "tok-1")).thenReturn(true);

        mockMvc.perform(get("/auth/verify-email")
                        .param("email", "apatient@example.com")
                        .param("token", "tok-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        // QA D921: the controller has no session (open-in-view is off), so it
        // must not touch the account or its assignments itself — the lazy
        // getRole() it used to read there is what answered 500.
        verify(userService).verifyEmail("apatient@example.com", "tok-1");
        verify(assignmentRepository, never()).findByUserId(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyEmail_aRefusedLinkIsA400() throws Exception {
        when(userService.verifyEmail("apatient@example.com", "wrong-token")).thenReturn(false);

        mockMvc.perform(get("/auth/verify-email")
                        .param("email", "apatient@example.com")
                        .param("token", "wrong-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.verified").value(false));

        verify(loginAttemptService, never()).resetAttempts(any());
    }

    @Test
    void aSupportSessionCannotChangeHowTheUserSignsInOrRecovers() throws Exception {
        com.example.hms.security.context.ImpersonationContextHolder.set(
                com.example.hms.security.context.ImpersonationContext.of(java.util.UUID.randomUUID(), "support.agent"));
        try {
            for (var request : java.util.List.of(
                    post("/auth/me/change-username").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newUsername\":\"taken.over\"}"),
                    post("/auth/me/complete-setup").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newUsername\":\"taken.over\",\"currentPassword\":\"Old#Pass2026\"}"),
                    post("/auth/me/change-password").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"Old#Pass2026\",\"newPassword\":\"New#Pass2026x\"}"),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/auth/credentials/recovery")
                            .contentType(MediaType.APPLICATION_JSON).content("[]"),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/auth/credentials/mfa")
                            .contentType(MediaType.APPLICATION_JSON).content("[]"),
                    post("/auth/credentials/recovery/" + java.util.UUID.randomUUID() + "/send-code").contentType(MediaType.APPLICATION_JSON).content("{\"confirmedContactValue\":\"backup@example.test\"}"),
                    post("/auth/verify-password").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"target\",\"password\":\"guess\"}"),
                    post("/auth/credentials/recovery/" + java.util.UUID.randomUUID() + "/verify")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))) {
                mockMvc.perform(request).andExpect(status().isForbidden());
            }
        } finally {
            com.example.hms.security.context.ImpersonationContextHolder.clear();
        }
        org.mockito.Mockito.verifyNoInteractions(userCredentialLifecycleService, userService, authenticationManager);
    }

    @Test
    void refreshToken_aProviderUserWhoseSecondFactorLapsed_isSentBackToSignIn() throws Exception {
        com.example.hms.model.User pharmacist = com.example.hms.model.User.builder()
                .username("pharm@example.com").isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(pharmacist, "id", java.util.UUID.randomUUID());
        when(jwtTokenProvider.isRefreshToken("old.refresh.token")).thenReturn(true);
        claimableRefresh("old.refresh.token");
        when(jwtTokenProvider.getUsernameFromJWT("old.refresh.token")).thenReturn("pharm@example.com");
        when(userRepository.findByUsername("pharm@example.com")).thenReturn(java.util.Optional.of(pharmacist));
        when(jwtTokenProvider.secondFactorAt("old.refresh.token")).thenReturn(null);
        when(providerMfaGate.isProviderUser(pharmacist.getId(), "pharm@example.com")).thenReturn(true);

        mockMvc.perform(post("/auth/token/refresh").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old.refresh.token\"}"))
                .andExpect(status().isUnauthorized());

        // No pair every call would refuse; the old token is given back for the normal sign-in path.
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), any(), any());
        // The token can never yield a pair again: it stays spent, and the cookie is dropped.
        verify(tokenBlacklistService, never()).release("jti-old.refresh.token");
        verify(refreshTokenCookieService).clear(any());
        // ...and the refusal is in the audit trail, like the idle-timeout one.
        verify(auditEventLogService).logEvent(org.mockito.ArgumentMatchers.argThat(e ->
                e.getEventType() == com.example.hms.enums.AuditEventType.TOKEN_REFRESH
                        && e.getStatus() == com.example.hms.enums.AuditStatus.FAILURE
                        && pharmacist.getId().equals(e.getUserId())));
    }

    // ── QA D311/D418: activation re-send for phone-only and staff accounts ──

    private com.example.hms.model.UserRoleHospitalAssignment pendingAssignment(java.util.UUID id) {
        com.example.hms.model.UserRoleHospitalAssignment a = new com.example.hms.model.UserRoleHospitalAssignment();
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", id);
        a.setActive(false);
        a.setConfirmationCode("123456");
        return a;
    }

    @Test
    void resendVerification_byPhone_resendsEveryPendingAssignmentCode_D418() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("patient1").phoneNumber("+22670000000").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setActive(false);
        java.util.UUID pendingId = java.util.UUID.randomUUID();
        com.example.hms.model.UserRoleHospitalAssignment done = pendingAssignment(java.util.UUID.randomUUID());
        done.setActive(true);
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        when(userRepository.findByPhoneNumber("+22670000000")).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUserId(userId))
                .thenReturn(java.util.List.of(pendingAssignment(pendingId), done));

        mockMvc.perform(post("/auth/resend-verification").param("phone", " +22670000000 "))
                .andExpect(status().isOk());

        verify(notifier).resendPendingActivationCode(pendingId, AuthController.RESEND_COOLDOWN);
        verify(notifier, times(1)).resendPendingActivationCode(any(java.util.UUID.class), any());
    }

    /** QA D542: the portal sends the phone in the JSON body so it never lands in a URL. */
    @Test
    void resendVerification_phoneInTheBody_resendsTheCode_D542() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("patient9").phoneNumber("+22670000009").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setActive(false);
        java.util.UUID pendingId = java.util.UUID.randomUUID();
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        when(userRepository.findByPhoneNumber("+22670000009")).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUserId(userId)).thenReturn(java.util.List.of(pendingAssignment(pendingId)));

        mockMvc.perform(post("/auth/resend-verification")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"+22670000009\"}"))
                .andExpect(status().isOk());

        verify(notifier).resendPendingActivationCode(pendingId, AuthController.RESEND_COOLDOWN);
    }

    @Test
    void resendVerification_phoneOnlyPatientWhoNeverActivated_getsANewCode_D418() throws Exception {
        // A patient registered at the desk: a phone number, no e-mail, no
        // staff role - only the PATIENT assignment still waiting for its code.
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("deskpatient").phoneNumber("+22670000002").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setActive(false);
        java.util.UUID patientAssignmentId = java.util.UUID.randomUUID();
        com.example.hms.model.UserRoleHospitalAssignment patientRow = pendingAssignment(patientAssignmentId);
        com.example.hms.model.Role patientRole = new com.example.hms.model.Role();
        patientRole.setCode("ROLE_PATIENT");
        patientRow.setRole(patientRole);
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        // Typed as at the desk, stored in E.164.
        when(userRepository.findByPhoneNumber("+22670000002")).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUserId(userId)).thenReturn(java.util.List.of(patientRow));

        mockMvc.perform(post("/auth/resend-verification").param("phone", "70 00 00 02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If the number is registered, a new activation code has been sent."));

        verify(notifier).resendPendingActivationCode(patientAssignmentId, AuthController.RESEND_COOLDOWN);
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resendVerification_everyRequestGoesThroughTheSharedCooldownClaim_D418() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("patient2").phoneNumber("+22670000001").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setActive(false);
        java.util.UUID pendingId = java.util.UUID.randomUUID();
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        when(userRepository.findByPhoneNumber("+22670000001")).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUserId(userId))
                .thenReturn(java.util.List.of(pendingAssignment(pendingId)));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/resend-verification").param("phone", "+22670000001"))
                    .andExpect(status().isOk());
        }

        // No per-instance memory: every request asks the database claim
        // (claimActivationResend), which sends at most once per cooldown.
        verify(notifier, times(3)).resendPendingActivationCode(pendingId, AuthController.RESEND_COOLDOWN);
        verify(notifier, never()).sendNotifications(any(java.util.UUID.class));
    }

    @Test
    void resendVerification_unknownPhone_answersTheSameWithoutSending_D418() throws Exception {
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        when(userRepository.findByPhoneNumber(any())).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/auth/resend-verification").param("phone", "+22679999999"))
                .andExpect(status().isOk());

        verify(notifier, never()).resendPendingActivationCode(any(java.util.UUID.class), any());
    }

    /**
     * QA D1017: the anonymous resend answers the same status and body whether
     * the contact is unknown, belongs to an active account or to one waiting
     * for activation, for an e-mail and for a phone. An active e-mail used to
     * answer "Account is already verified", an account-enumeration oracle.
     */
    @Test
    void resendVerification_answersTheSameForUnknownActiveAndPendingContacts_D1017() throws Exception {
        com.example.hms.service.EmailService email = mock(com.example.hms.service.EmailService.class);
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.email()).thenReturn(email);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        com.example.hms.model.User active = com.example.hms.model.User.builder()
                .username("live").email("live@example.test").phoneNumber("+22670000011").build();
        org.springframework.test.util.ReflectionTestUtils.setField(active, "id", java.util.UUID.randomUUID());
        active.setActive(true);
        com.example.hms.model.User pending = com.example.hms.model.User.builder()
                .username("wait").email("wait@example.test").phoneNumber("+22670000012").build();
        org.springframework.test.util.ReflectionTestUtils.setField(pending, "id", java.util.UUID.randomUUID());
        pending.setActive(false);
        when(userRepository.findByEmail(any())).thenReturn(java.util.Optional.empty());
        when(userRepository.findByEmail("live@example.test")).thenReturn(java.util.Optional.of(active));
        when(userRepository.findByEmail("wait@example.test")).thenReturn(java.util.Optional.of(pending));
        when(userRepository.findByPhoneNumber(any())).thenReturn(java.util.Optional.empty());
        when(userRepository.findByPhoneNumber("+22670000011")).thenReturn(java.util.Optional.of(active));
        when(userRepository.findByPhoneNumber("+22670000012")).thenReturn(java.util.Optional.of(pending));
        when(assignmentRepository.findByUserId(any())).thenReturn(java.util.List.of());

        for (String channel : java.util.List.of("email", "phone")) {
            java.util.List<String> contacts = channel.equals("email")
                    ? java.util.List.of("nobody@example.test", "live@example.test", "wait@example.test")
                    : java.util.List.of("+22679999998", "+22670000011", "+22670000012");
            java.util.Set<String> answers = new java.util.HashSet<>();
            for (String contact : contacts) {
                String body = mockMvc.perform(post("/auth/resend-verification")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"" + channel + "\":\"" + contact + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                answers.add(body);
            }
            assertThat(answers).as("%s: one answer for unknown, active and pending (QA D1017)", channel).hasSize(1);
            assertThat(answers.iterator().next()).doesNotContain("already verified");
        }
        // Only the account waiting for activation is sent anything.
        verify(email, times(1)).sendActivationEmail(eq("wait@example.test"), any());
        verify(email, never()).sendActivationEmail(eq("live@example.test"), any());
    }

    @Test
    void resendVerification_withNeitherEmailNorPhone_isABadRequest_D418() throws Exception {
        mockMvc.perform(post("/auth/resend-verification"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendVerification_byEmail_stillSendsTheLinkAndAlsoTheStaffCode_D418() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        com.example.hms.model.User user = com.example.hms.model.User.builder()
                .username("nurse9").email("nurse9@example.com").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", userId);
        user.setActive(false);
        java.util.UUID pendingId = java.util.UUID.randomUUID();
        com.example.hms.service.EmailService email =
                mock(com.example.hms.service.EmailService.class);
        com.example.hms.service.UserRoleHospitalAssignmentService notifier =
                mock(com.example.hms.service.UserRoleHospitalAssignmentService.class);
        when(authNotificationFacade.email()).thenReturn(email);
        when(authNotificationFacade.assignments()).thenReturn(notifier);
        when(userRepository.findByEmail("nurse9@example.com")).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUserId(userId)).thenReturn(java.util.List.of(pendingAssignment(pendingId)));

        mockMvc.perform(post("/auth/resend-verification").param("email", "nurse9@example.com"))
                .andExpect(status().isOk());

        verify(email).sendActivationEmail(eq("nurse9@example.com"), any());
        verify(notifier).resendPendingActivationCode(pendingId, AuthController.RESEND_COOLDOWN);
    }

    /**
     * QA D787: the legacy {@code POST /auth/request-reset} answered 400 to a
     * phone number ({@code @Email} on the parameter). It names the account the
     * way {@code /auth/password/request} does and is a 204.
     */
    @Test
    void legacyRequestReset_phoneNumber_is204_routedByIdentifier_D787() throws Exception {
        com.example.hms.service.PasswordResetService resets = mock(com.example.hms.service.PasswordResetService.class);
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        when(authNotificationFacade.passwordResetRequests()).thenReturn(
                com.example.hms.service.PasswordResetRequestDispatcherTestSupport.onExecutor(resets, queued::add));

        mockMvc.perform(post("/auth/request-reset").param("email", "+22670202020"))
                .andExpect(status().isNoContent());

        // QA D802: nothing touched the account on the request thread; the work is queued.
        org.mockito.Mockito.verifyNoInteractions(resets);
        queued.forEach(Runnable::run);
        verify(resets).requestResetByIdentifier(eq("+22670202020"), any(), any());
        verify(resets, never()).requestReset(any(), any(), any());
    }

    @Test
    void legacyRequestReset_usernameInTheBody_is204_D787() throws Exception {
        com.example.hms.service.PasswordResetRequestDispatcher resets =
                mock(com.example.hms.service.PasswordResetRequestDispatcher.class);
        when(authNotificationFacade.passwordResetRequests()).thenReturn(resets);

        mockMvc.perform(post("/auth/request-reset")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"recep.a\"}"))
                .andExpect(status().isNoContent());

        verify(resets).dispatch(eq("recep.a"), any(), any());
    }

    @Test
    void legacyRequestReset_nothingNamed_is400_andSendsNothing_D787() throws Exception {
        com.example.hms.service.PasswordResetRequestDispatcher resets =
                mock(com.example.hms.service.PasswordResetRequestDispatcher.class);
        when(authNotificationFacade.passwordResetRequests()).thenReturn(resets);

        mockMvc.perform(post("/auth/request-reset"))
                .andExpect(status().isBadRequest());

        verify(resets, never()).dispatch(any(), any(), any());
    }
}
