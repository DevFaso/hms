package com.example.hms.security;

import com.example.hms.controller.AuthController;

import com.example.hms.service.AuthBootstrapService;
import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.MfaService;
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
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
@org.springframework.context.annotation.Import({AuthControllerProperties.class, IdleSessionRefreshIntegrationTest.LocalIdle.class})
@org.springframework.test.context.TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.frontend.base-url=https://localhost",
})
class IdleSessionRefreshIntegrationTest {

    @Autowired private MockMvc mockMvc;
    private static org.springframework.context.MessageSource previousMessageSource;

    @org.junit.jupiter.api.BeforeAll
    static void messageBundles() {
        previousMessageSource = (org.springframework.context.MessageSource)
            org.springframework.test.util.ReflectionTestUtils.getField(
                com.example.hms.utility.MessageUtil.class, "messageSource");
        var source = new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasenames(java.util.Arrays.stream(com.example.hms.config.MessageBundles.basenames())
            .map(b -> b.substring("classpath:".length())).toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        com.example.hms.utility.MessageUtil.setMessageSource(source);
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreMessageSource() {
        com.example.hms.utility.MessageUtil.setMessageSource(previousMessageSource);
    }

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private AuthNotificationFacade authNotificationFacade;
    @MockitoBean private UserService userService;
    @MockitoBean private UserCredentialLifecycleService userCredentialLifecycleService;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private com.example.hms.security.GlobalSessionRevocationService globalSessionRevocationService;
    @Autowired private IdleSessionGate idleSessionGate;
    @Autowired private java.util.concurrent.atomic.AtomicLong idleClock;
    @MockitoBean private com.example.hms.security.ImpersonationSessionTracker impersonationSessionTracker;
    @MockitoBean private LoginAttemptService loginAttemptService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerMfaGate;


    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LocalIdle {
        @org.springframework.context.annotation.Bean
        java.util.concurrent.atomic.AtomicLong idleClock() {
            return new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        }

        @org.springframework.context.annotation.Bean
        IdleSessionGate localGate(java.util.concurrent.atomic.AtomicLong now) {
            java.time.Clock clock = new MutableClock(now, java.time.ZoneOffset.UTC);
            return new IdleSessionGate(new InMemoryIdleSessionTracker(java.time.Duration.ofMinutes(15), clock), "");
        }
    }

    private static final class MutableClock extends java.time.Clock {
        private final java.util.concurrent.atomic.AtomicLong now;
        private final java.time.ZoneId zone;

        MutableClock(java.util.concurrent.atomic.AtomicLong now, java.time.ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        @Override public long millis() { return now.get(); }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis()); }
        @Override public java.time.ZoneId getZone() { return zone; }
        @Override public java.time.Clock withZone(java.time.ZoneId next) { return new MutableClock(now, next); }
    }

    private static final long WINDOW_MS = 15 * 60_000L;
    private com.example.hms.model.User user;

    @org.junit.jupiter.api.BeforeEach
    void stubLocalSessionOnly() {
        idleClock.set(1_000_000L);
        user = new com.example.hms.model.User();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("cdx-idle-session");
        user.setActive(true);
        var role = com.example.hms.model.Role.builder().code("ROLE_NURSE").build();
        var assignment = com.example.hms.model.UserRoleHospitalAssignment.builder().user(user).role(role).active(true).build();
        when(userRepository.findByUsername(user.getUsername())).thenReturn(java.util.Optional.of(user));
        when(assignmentRepository.findByUser(user)).thenReturn(java.util.Set.of(assignment));
        when(jwtTokenProvider.isRefreshToken("cdx-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("cdx-refresh")).thenReturn(user.getUsername());
        when(jwtTokenProvider.getJtiFromToken("cdx-refresh")).thenReturn("cdx-jti");
        when(jwtTokenProvider.getExpiration(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new java.util.Date(4_102_444_800_000L));
        when(tokenBlacklistService.claim(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn("cdx-new-access");
        when(jwtTokenProvider.generateRefreshToken(any(org.springframework.security.core.Authentication.class), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn("cdx-new-refresh");
        idleSessionGate.touchIfHuman(user.getId(), AuthorityUtils.createAuthorityList("ROLE_NURSE"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private org.springframework.test.web.servlet.ResultActions refresh() throws Exception {
        return mockMvc.perform(post("/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"cdx-refresh\"}"));
    }

    @Test
    void refreshJustBeforeExpiryRotatesAndExtendsTheRealIdleWindow() throws Exception {
        idleClock.addAndGet(WINDOW_MS - 1);
        refresh().andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("cdx-new-access"));
        idleClock.addAndGet(1);
        assertThat(idleSessionGate.shouldReject(user.getId(), AuthorityUtils.createAuthorityList("ROLE_NURSE"))).isFalse();
        idleClock.addAndGet(WINDOW_MS);
        assertThat(idleSessionGate.shouldReject(user.getId(), AuthorityUtils.createAuthorityList("ROLE_NURSE"))).isTrue();
        verify(tokenBlacklistService).claim(eq("cdx-jti"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void expiryRefusesRefreshAtTheBoundaryAndRetriesNeverReviveItOrMintTokens() throws Exception {
        idleClock.addAndGet(WINDOW_MS);
        for (int i = 0; i < 2; i++) {
            refresh().andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("WWW-Authenticate", IdleSessionGate.WWW_AUTHENTICATE_CHALLENGE))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
            idleClock.addAndGet(60_000);
        }
        verify(tokenBlacklistService, never()).claim(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(jwtTokenProvider, never()).generateAccessToken(any(com.example.hms.security.TokenUserDescriptor.class), org.mockito.ArgumentMatchers.any());
        verify(jwtTokenProvider, never()).generateRefreshToken(any(org.springframework.security.core.Authentication.class), org.mockito.ArgumentMatchers.any());
        verify(refreshTokenCookieService, never()).write(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        var events = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.TOKEN_REFRESH);
            assertThat(event.getStatus()).isEqualTo(com.example.hms.enums.AuditStatus.FAILURE);
            assertThat(event.getUserId()).isEqualTo(user.getId());
        });
    }
}
