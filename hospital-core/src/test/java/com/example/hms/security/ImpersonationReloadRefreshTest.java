package com.example.hms.security;

import com.example.hms.controller.AuthController;
import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.AuthBootstrapService;
import com.example.hms.service.MfaService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.SupportImpersonationService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A super admin who reloads the portal during an impersonation: the tab no
 * longer holds the impersonation token (memory only), so the portal's start-up
 * refresh ({@code X-Session-Restore: 1}) ends the tracked impersonation
 * (audited by the service) and the admin's own session continues. Any other
 * refresh while the impersonation is active is still refused.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import({AuthControllerProperties.class, IdleSessionRefreshIntegrationTest.LocalIdle.class})
@TestPropertySource(properties = {
    "app.mfa.required-roles=",
    "app.frontend.base-url=https://localhost",
})
class ImpersonationReloadRefreshTest {

    private static MessageSource previousMessageSource;

    @BeforeAll
    static void messageBundles() {
        previousMessageSource = (MessageSource)
            ReflectionTestUtils.getField(com.example.hms.utility.MessageUtil.class, "messageSource");
        var source = new ResourceBundleMessageSource();
        source.setBasenames(Arrays.stream(com.example.hms.config.MessageBundles.basenames())
            .map(b -> b.substring("classpath:".length())).toArray(String[]::new));
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        com.example.hms.utility.MessageUtil.setMessageSource(source);
    }

    @AfterAll
    static void restoreMessageSource() {
        com.example.hms.utility.MessageUtil.setMessageSource(previousMessageSource);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private IdleSessionGate idleSessionGate;
    @Autowired private AtomicLong idleClock;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private AuthenticationManager authenticationManager;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private AuthNotificationFacade authNotificationFacade;
    @MockitoBean private UserService userService;
    @MockitoBean private UserCredentialLifecycleService userCredentialLifecycleService;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private GlobalSessionRevocationService globalSessionRevocationService;
    @MockitoBean private ImpersonationSessionTracker impersonationSessionTracker;
    @MockitoBean private LoginAttemptService loginAttemptService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private PasswordHistoryService passwordHistoryService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private WsTicketService wsTicketService;
    @MockitoBean private RefreshTokenCookieService refreshTokenCookieService;
    @MockitoBean private AuthBootstrapService authBootstrapService;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerMfaGate;
    @MockitoBean private SupportImpersonationService supportImpersonationService;

    private User admin;

    @BeforeEach
    void superAdminWithATrackedImpersonation() {
        idleClock.set(1_000_000L);
        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("cdx-super-admin");
        admin.setActive(true);
        var role = Role.builder().code("ROLE_SUPER_ADMIN").build();
        var assignment = UserRoleHospitalAssignment.builder().user(admin).role(role).active(true).build();
        when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
        when(assignmentRepository.findByUser(admin)).thenReturn(Set.of(assignment));
        when(jwtTokenProvider.isRefreshToken("cdx-refresh")).thenReturn(true);
        when(jwtTokenProvider.carriedRoles("cdx-refresh")).thenReturn(Optional.of(List.of("ROLE_SUPER_ADMIN")));
        when(jwtTokenProvider.getUsernameFromJWT("cdx-refresh")).thenReturn(admin.getUsername());
        when(jwtTokenProvider.getJtiFromToken("cdx-refresh")).thenReturn("cdx-jti");
        when(jwtTokenProvider.getExpiration(anyString())).thenReturn(new Date(4_102_444_800_000L));
        when(tokenBlacklistService.claim(anyString(), anyLong())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class), isNull(), any()))
            .thenReturn("cdx-admin-access");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class), isNull(), any(),
                any(SessionLifetime.Live.class)))
            .thenReturn("cdx-admin-refresh");
        when(jwtTokenProvider.presentedSession("cdx-refresh")).thenAnswer(invocation -> Optional.of(
            SessionLifetimeTestSupport.presented(Instant.now(), Instant.now(), List.of("ROLE_SUPER_ADMIN"))));
        idleSessionGate.touchIfHuman(admin.getId(), AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"));
        when(impersonationSessionTracker.hasActive(admin.getId())).thenReturn(true);
    }

    private ResultActions refresh(boolean restore) throws Exception {
        var request = post("/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"cdx-refresh\"}");
        if (restore) request = request.header("X-Session-Restore", "1");
        return mockMvc.perform(request);
    }

    @Test
    void theReloadRefreshEndsTheImpersonationAndReturnsTheAdminsOwnSession() throws Exception {
        when(supportImpersonationService.endForImpersonator(eq(admin.getId()), any())).thenReturn(true);

        refresh(true).andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("cdx-admin-access"));

        verify(supportImpersonationService).endForImpersonator(admin.getId(), "the portal was reloaded");
    }

    @Test
    void anyOtherRefreshDuringTheImpersonationIsStillRefused() throws Exception {
        refresh(false).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(supportImpersonationService, never()).endForImpersonator(any(), any());
        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class), any(), any());
    }

    @Test
    void aReloadWhoseImpersonationCannotBeEndedIsRefused() throws Exception {
        when(supportImpersonationService.endForImpersonator(eq(admin.getId()), any())).thenReturn(false);

        refresh(true).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class), any(), any());
    }
}
