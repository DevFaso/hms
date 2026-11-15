package com.example.hms.controller;

import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.enums.AuditEventType;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.InMemoryLoginAttemptStore;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.WsTicketService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.AuthBootstrapService;
import com.example.hms.service.MfaService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2001: the lock screen ({@code POST /auth/verify-password}) counts wrong
 * passwords in the SAME bucket as {@code POST /auth/login}, through the real
 * {@link LoginAttemptService} and store: five wrong unlocks lock the account
 * and end the session (access and refresh tokens revoked), lock-screen and
 * sign-in failures add up, a lock made anywhere refuses everywhere with the
 * sign-in's localized 423, and a right password clears the count.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import({AuthControllerProperties.class, LoginAttemptService.class, InMemoryLoginAttemptStore.class,
    com.example.hms.config.LocaleConfig.class})
@TestPropertySource(properties = {"app.mfa.required-roles=", "app.frontend.base-url=https://localhost"})
@DisplayName("D2001: the lock screen counts in the sign-in's attempt bucket")
class LockScreenLockoutIntegrationTest {

    private static final String USERNAME = "d2001-nurse";
    private static final String RIGHT = "d2001-right-secret";
    private static final String WRONG = "d2001-wrong-secret";
    private static final String ACCESS = "d2001-access-jwt";
    private static final String REFRESH = "d2001-refresh-jwt";
    private static final int LIMIT = 5;

    private static MessageSource previousMessageSource;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MessageSource messageSource;
    @Autowired private LoginAttemptService loginAttemptService;

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
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerMfaGate;

    private UUID userId;

    @BeforeAll
    static void keepMessageSource() {
        previousMessageSource = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    }

    @AfterAll
    static void restoreMessageSource() {
        MessageUtil.setMessageSource(previousMessageSource);
    }

    @BeforeEach
    void account() {
        MessageUtil.setMessageSource(messageSource);
        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setUsername(USERNAME);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(userRepository.findIdsByUsernameIgnoreCase(USERNAME)).thenReturn(List.of(userId));
        when(authenticationManager.authenticate(any())).thenAnswer(inv -> {
            UsernamePasswordAuthenticationToken typed = inv.getArgument(0);
            if (RIGHT.equals(typed.getCredentials())) {
                return new UsernamePasswordAuthenticationToken(USERNAME, null,
                    AuthorityUtils.createAuthorityList("ROLE_NURSE"));
            }
            throw new BadCredentialsException("stub refusal");
        });
        // The session behind the lock screen: its bearer and its refresh cookie.
        when(jwtTokenProvider.getJtiFromToken(ACCESS)).thenReturn("jti-access");
        when(jwtTokenProvider.getJtiFromToken(REFRESH)).thenReturn("jti-refresh");
        when(jwtTokenProvider.getExpiration(anyString())).thenReturn(new Date(System.currentTimeMillis() + 600_000));
        when(jwtTokenProvider.getUsernameFromJWT(anyString())).thenReturn(USERNAME);
        when(jwtTokenProvider.validateToken(REFRESH)).thenReturn(true);
        when(refreshTokenCookieService.read(any())).thenReturn(REFRESH);
    }

    @AfterEach
    void clear() {
        loginAttemptService.resetAttempts(userId);
        SecurityContextHolder.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    private ResultActions unlock(String password, String language) throws Exception {
        // The controller resets the context after each check, as the filter chain would per request.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            USERNAME, "jwt", AuthorityUtils.createAuthorityList("ROLE_NURSE")));
        LoginRequest body = new LoginRequest(USERNAME, password, null);
        return mockMvc.perform(post("/auth/verify-password").header("Accept-Language", language)
            .header("Authorization", "Bearer " + ACCESS)
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions login(String password) throws Exception {
        SecurityContextHolder.clearContext();
        LoginRequest body = new LoginRequest(USERNAME, password, null);
        return mockMvc.perform(post("/auth/login").header("Accept-Language", "en")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private static String lockedMessage(String language) {
        // Independent of the bundle: a missing FR/ES line must not fall back silently.
        return switch (language) {
            case "fr" -> "Compte temporairement verrouillé. Réessayez dans 15 minutes.";
            case "es" -> "La cuenta está bloqueada temporalmente. Vuelva a intentarlo en 15 minutos.";
            default -> "Account is temporarily locked. Try again in 15 minutes.";
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "en", "es"})
    @DisplayName("five wrong unlocks: the fifth is the sign-in's localized 423 and ends the session")
    void fiveWrongUnlocksLockTheAccountAndRevokeTheSession(String language) throws Exception {
        for (int i = 0; i < LIMIT - 1; i++) {
            unlock(WRONG, language).andExpect(status().isUnauthorized());
        }
        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());

        unlock(WRONG, language).andExpect(status().isLocked())
            .andExpect(jsonPath("$.message").value(lockedMessage(language)));

        // The session behind the lock screen is over: both tokens revoked, the cookie cleared.
        verify(tokenBlacklistService).blacklist(eq("jti-access"), anyLong());
        verify(tokenBlacklistService).blacklist(eq("jti-refresh"), anyLong());
        verify(refreshTokenCookieService).clear(any());
        ArgumentCaptor<AuditEventRequestDTO> audits = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, Mockito.atLeastOnce()).logEvent(audits.capture());
        assertThat(audits.getAllValues()).filteredOn(a -> a.getEventType() == AuditEventType.LOGIN_FAILURE)
            .hasSize(LIMIT).allSatisfy(a -> assertThat(a.getUserId()).isEqualTo(userId));
        assertThat(audits.getAllValues()).filteredOn(a -> a.getEventType() == AuditEventType.ACCOUNT_LOCKED)
            .singleElement().satisfies(a -> assertThat(a.getUserId()).isEqualTo(userId));
        assertThat(audits.getAllValues()).filteredOn(a -> a.getEventType() == AuditEventType.TOKEN_REVOKED)
            .hasSize(2).allSatisfy(a -> assertThat(a.getEventDescription()).contains("lock-screen lockout"));

        // Locked everywhere: the sign-in refuses before checking, and so does the lock screen,
        // even with the right password.
        Mockito.clearInvocations(authenticationManager);
        login(RIGHT).andExpect(status().isLocked());
        unlock(RIGHT, language).andExpect(status().isLocked())
            .andExpect(jsonPath("$.message").value(lockedMessage(language)));
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("sign-in failures and lock-screen failures add up to one limit, in either order")
    void signInAndUnlockFailuresAddUp() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(WRONG).andExpect(status().isUnauthorized());
        }
        unlock(WRONG, "en").andExpect(status().isUnauthorized());
        unlock(WRONG, "en").andExpect(status().isLocked());
        verify(tokenBlacklistService).blacklist(eq("jti-access"), anyLong());

        loginAttemptService.resetAttempts(userId);
        Mockito.clearInvocations(tokenBlacklistService);

        for (int i = 0; i < LIMIT - 1; i++) {
            unlock(WRONG, "en").andExpect(status().isUnauthorized());
        }
        login(WRONG).andExpect(status().isUnauthorized());
        login(RIGHT).andExpect(status().isLocked());
        unlock(RIGHT, "en").andExpect(status().isLocked());
        // A lock made at the sign-in also ends the session behind the lock screen.
        verify(tokenBlacklistService).blacklist(eq("jti-access"), anyLong());
    }

    @Test
    @DisplayName("a right unlock clears the count, exactly as a sign-in does")
    void aRightUnlockResetsTheBucket() throws Exception {
        for (int i = 0; i < LIMIT - 1; i++) {
            unlock(WRONG, "en").andExpect(status().isUnauthorized());
        }
        unlock(RIGHT, "en").andExpect(status().isOk());

        for (int i = 0; i < LIMIT - 1; i++) {
            login(WRONG).andExpect(status().isUnauthorized());
        }
        assertThat(loginAttemptService.remainingLockMs(USERNAME)).isZero();
        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
        login(WRONG).andExpect(status().isUnauthorized());
        assertThat(loginAttemptService.remainingLockMs(USERNAME)).isPositive();
    }

    @Test
    @DisplayName("the change of password counts in the same bucket and answers 423 when locked")
    void otherOwnPasswordChecksCountToo() throws Exception {
        for (int i = 0; i < 2; i++) {
            login(WRONG).andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 2; i++) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USERNAME, "jwt", AuthorityUtils.createAuthorityList("ROLE_NURSE")));
            mockMvc.perform(post("/auth/me/change-password").header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"" + WRONG + "\",\"newPassword\":\"New-pass-2026\"}"))
                .andExpect(status().isUnauthorized());
        }
        unlock(WRONG, "en").andExpect(status().isLocked());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            USERNAME, "jwt", AuthorityUtils.createAuthorityList("ROLE_NURSE")));
        mockMvc.perform(post("/auth/me/change-password").header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + RIGHT + "\",\"newPassword\":\"New-pass-2026\"}"))
            .andExpect(status().isLocked())
            .andExpect(jsonPath("$.message").value(lockedMessage("en")));
        verify(userService, never()).changeOwnPassword(any(), any());
    }
}
