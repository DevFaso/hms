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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
@org.springframework.context.annotation.Import({AuthControllerProperties.class, LoginLockoutIntegrationTest.LocalThrottle.class})
@org.springframework.test.context.TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.frontend.base-url=https://localhost",
})
class LoginLockoutIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeAll
    static void messageBundles() {
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasenames(java.util.Arrays.stream(com.example.hms.config.MessageBundles.basenames())
            .map(b -> b.substring("classpath:".length())).toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        com.example.hms.utility.MessageUtil.setMessageSource(source);
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
    @Autowired private LoginAttemptService loginAttemptService;
    @Autowired private com.example.hms.security.InMemoryLoginAttemptStore attemptStore;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerMfaGate;


    /** Spring MVC + the real throttle/store. All persistence, tokens and transports are stubs. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LocalThrottle {
        @org.springframework.context.annotation.Bean
        com.example.hms.security.InMemoryLoginAttemptStore localAttempts() {
            return new com.example.hms.security.InMemoryLoginAttemptStore();
        }

        @org.springframework.context.annotation.Bean
        LoginAttemptService realThrottle(com.example.hms.security.InMemoryLoginAttemptStore store, UserRepository users) {
            return new LoginAttemptService(store, users);
        }
    }

    private static org.springframework.context.MessageSource expectedMessages() {
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasenames(java.util.Arrays.stream(com.example.hms.config.MessageBundles.basenames())
            .map(b -> b.substring("classpath:".length())).toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static final String USERNAME = "cdx-lockout";
    private static final String SECRET = "cdx-fake-login-secret";
    private static final int FAILURE_LIMIT = 5;
    private static final long LOCK_WINDOW_MS = 15 * 60_000L;
    private java.util.UUID userId;

    @org.junit.jupiter.api.BeforeEach
    void fakeAccount() {
        userId = java.util.UUID.randomUUID();
        when(userRepository.findIdsByUsernameIgnoreCase(USERNAME)).thenReturn(java.util.List.of(userId));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("stub password refusal"));
    }

    @AfterEach
    void clearRequestContext() {
        SecurityContextHolder.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String language) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(SECRET);
        return mockMvc.perform(post("/auth/login").header("Accept-Language", language)
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"fr", "en", "es"})
    void fifthFailureLocksAndSixthRequestIsRefusedBeforePasswordCheckWithLocalizedAudit(String language) throws Exception {
        for (int i = 0; i < FAILURE_LIMIT; i++) {
            login(USERNAME, language).andExpect(status().isUnauthorized());
        }
        assertThat(loginAttemptService.remainingLockMs(USERNAME)).isBetween(LOCK_WINDOW_MS - 60_000, LOCK_WINDOW_MS);
        String expected = expectedMessages()
            .getMessage("auth.login.locked", new Object[] {15}, java.util.Locale.forLanguageTag(language));
        login(USERNAME, language).andExpect(status().isLocked()).andExpect(jsonPath("$.message").value(expected));
        verify(authenticationManager, times(FAILURE_LIMIT)).authenticate(any());
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class));
        var audits = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService, times(FAILURE_LIMIT + 1)).logEvent(audits.capture());
        assertThat(audits.getAllValues().subList(0, FAILURE_LIMIT)).allSatisfy(audit -> {
            assertThat(audit.getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.LOGIN_FAILURE);
            assertThat(audit.getStatus()).isEqualTo(com.example.hms.enums.AuditStatus.FAILURE);
            assertThat(audit.getUserId()).isEqualTo(userId);
        });
        assertThat(audits.getAllValues().get(FAILURE_LIMIT).getEventType())
            .isEqualTo(com.example.hms.enums.AuditEventType.ACCOUNT_LOCKED);
    }

    @Test
    void unknownNamesHaveTheSameThresholdAndLockedResponseWithoutAnAccountLookupLeak() throws Exception {
        String unknown = "cdx-unknown-" + java.util.UUID.randomUUID();
        when(userRepository.findIdsByUsernameIgnoreCase(unknown)).thenReturn(java.util.List.of());
        for (int i = 0; i < FAILURE_LIMIT; i++) {
            login(USERNAME, "fr").andExpect(status().isUnauthorized());
            login(unknown, "fr").andExpect(status().isUnauthorized());
        }
        String knownBody = login(USERNAME, "fr").andExpect(status().isLocked()).andReturn().getResponse().getContentAsString();
        String unknownBody = login(unknown, "fr").andExpect(status().isLocked()).andReturn().getResponse().getContentAsString();
        assertThat(unknownBody).isEqualTo(knownBody).doesNotContain(USERNAME, unknown, userId.toString());
        verify(authenticationManager, times(FAILURE_LIMIT * 2)).authenticate(any());
    }

    @Test
    void successfulLoginClearsTheRealCounterAndStartsANewFailureBudget() throws Exception {
        for (int i = 0; i < FAILURE_LIMIT - 1; i++) login(USERNAME, "en").andExpect(status().isUnauthorized());
        org.mockito.Mockito.reset(authenticationManager);
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
            USERNAME, null, AuthorityUtils.createAuthorityList("ROLE_NURSE")));
        var user = new com.example.hms.model.User();
        user.setId(userId);
        user.setUsername(USERNAME);
        when(userRepository.findByUsername(USERNAME)).thenReturn(java.util.Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class))).thenReturn("cdx-access-stub");
        when(jwtTokenProvider.generateRefreshToken(any(com.example.hms.security.TokenUserDescriptor.class))).thenReturn("cdx-refresh-stub");
        login(USERNAME, "en").andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("cdx-access-stub"));
        verify(userCredentialLifecycleService).recordSuccessfulLogin(userId);
        org.mockito.Mockito.reset(authenticationManager);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("stub password refusal"));
        for (int i = 0; i < FAILURE_LIMIT; i++) login(USERNAME, "en").andExpect(status().isUnauthorized());
        login(USERNAME, "en").andExpect(status().isLocked());
        verify(authenticationManager, times(FAILURE_LIMIT)).authenticate(any());
    }

    @Test
    void anExpiredStoreLockIsPurgedAndAllowsAFreshFailureBudgetWithoutWaitingFifteenMinutes() throws Exception {
        // Seed a past deadline through the real store API, rather than sleeping or changing application policy.
        attemptStore.recordFailure("user:" + userId, 1, LOCK_WINDOW_MS, -1);
        assertThat(loginAttemptService.remainingLockMs(USERNAME)).isZero();
        for (int i = 0; i < FAILURE_LIMIT; i++) login(USERNAME, "en").andExpect(status().isUnauthorized());
        login(USERNAME, "en").andExpect(status().isLocked());
        verify(authenticationManager, times(FAILURE_LIMIT)).authenticate(any());
        loginAttemptService.resetAttempts(userId);
        login(USERNAME, "en").andExpect(status().isUnauthorized());
        verify(authenticationManager, times(FAILURE_LIMIT + 1)).authenticate(any());
    }
}
