package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationActiveResponseDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartRequestDTO;
import com.example.hms.payload.dto.superadmin.ImpersonationStartResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.ImpersonationSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.ImpersonationContext;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SupportImpersonationServiceImpl")
class SupportImpersonationServiceImplTest {

    private UserRepository userRepository;
    private TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;
    private JwtTokenProvider tokenProvider;
    private MfaService mfaService;
    private AuditEventLogService auditEventLogService;
    private TokenBlacklistService tokenBlacklistService;
    private ImpersonationSessionTracker sessionTracker;
    private SupportImpersonationServiceImpl service;

    private User actor;
    private User target;
    private UUID actorId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tenantRoleAssignmentAccessor = mock(TenantRoleAssignmentAccessor.class);
        tokenProvider = mock(JwtTokenProvider.class);
        mfaService = mock(MfaService.class);
        auditEventLogService = mock(AuditEventLogService.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        sessionTracker = new ImpersonationSessionTracker();
        service = new SupportImpersonationServiceImpl(
            userRepository, tenantRoleAssignmentAccessor, tokenProvider,
            mfaService, auditEventLogService, tokenBlacklistService, sessionTracker);
        ReflectionTestUtils.setField(service, "impersonationTtlMs", 1_800_000L);
        ReflectionTestUtils.setField(service, "requireMfaStrict", false);

        actorId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        actor = new User();
        actor.setId(actorId);
        actor.setUsername("super.admin");
        target = new User();
        target.setId(targetId);
        target.setUsername("nurse.alice");

        when(userRepository.findByUsername("super.admin")).thenReturn(Optional.of(actor));
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(tenantRoleAssignmentAccessor.findAssignmentsForUser(targetId))
            .thenReturn(List.of(assignment("NURSE", "Nurse", true)));
        when(tokenProvider.generateImpersonationAccessToken(any(TokenUserDescriptor.class),
            eq(actorId), eq("super.admin"), anyLong())).thenReturn("impersonation.jwt");
        when(tokenProvider.getJtiFromToken("impersonation.jwt")).thenReturn("imp-jti");
        when(tokenProvider.getJtiFromToken("original.super-admin.jwt")).thenReturn("orig-jti");
        when(tokenProvider.getExpiration("original.super-admin.jwt"))
            .thenReturn(new java.util.Date(System.currentTimeMillis() + 600_000L));

        authenticateSuperAdmin();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        ImpersonationContextHolder.clear();
    }

    @Test
    @DisplayName("happy path: mints a token and emits IMPERSONATION_STARTED")
    void startMintsTokenAndAudits() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(true);
        when(mfaService.verifyCode(actorId, "123456")).thenReturn(true);

        ImpersonationStartResponseDTO response = service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId)
                .reason("Patient cannot reach their refill queue, validating fix.")
                .build(),
            "123456",
            "original.super-admin.jwt");

        assertThat(response.getAccessToken()).isEqualTo("impersonation.jwt");
        assertThat(response.getImpersonatorUserId()).isEqualTo(actorId);
        assertThat(response.getTargetUsername()).isEqualTo("nurse.alice");
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(1)).logEvent(captor.capture());
        AuditEventRequestDTO event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.IMPERSONATION_STARTED);
        assertThat(event.getImpersonatorUserId()).isEqualTo(actorId);
        assertThat(event.getResourceName()).isEqualTo("nurse.alice");
        assertThat(event.getEventDescription()).contains("Patient cannot reach");

        // Closes Copilot review #2: original super-admin token blacklisted.
        verify(tokenBlacklistService, times(1)).blacklist(eq("orig-jti"), anyLong());
        // Closes Copilot review #4: tracker registered so refresh is now blocked.
        assertThat(sessionTracker.hasActive(actorId)).isTrue();
    }

    @Test
    @DisplayName("self-impersonation is rejected")
    void selfImpersonationBlocked() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(tenantRoleAssignmentAccessor.findAssignmentsForUser(actorId))
            .thenReturn(List.of(assignment("NURSE", "Nurse", true)));

        assertThatThrownBy(() -> service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(actorId)
                .reason("oops")
                .build(),
            "irrelevant",
            "original.super-admin.jwt"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot impersonate yourself");
        verify(tokenProvider, never()).generateImpersonationAccessToken(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("impersonating another super admin is rejected (anti-collusion)")
    void targetSuperAdminBlocked() {
        when(tenantRoleAssignmentAccessor.findAssignmentsForUser(targetId))
            .thenReturn(List.of(assignment("SUPER_ADMIN", "Super Admin", true)));

        assertThatThrownBy(() -> service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId)
                .reason("debug")
                .build(),
            "irrelevant",
            "original.super-admin.jwt"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cannot impersonate another super admin");
        verify(tokenProvider, never()).generateImpersonationAccessToken(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("nested impersonation (context already set) is rejected")
    void nestedImpersonationBlocked() {
        ImpersonationContextHolder.set(ImpersonationContext.of(
            UUID.randomUUID(), "another.admin"));

        assertThatThrownBy(() -> service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId)
                .reason("debug")
                .build(),
            "irrelevant",
            "original.super-admin.jwt"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already impersonating");
    }

    @Test
    @DisplayName("MFA required when enrolled — missing X-Mfa-Token rejects")
    void mfaRequiredWhenEnrolled() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(true);

        assertThatThrownBy(() -> service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId)
                .reason("debug")
                .build(),
            null,
            "original.super-admin.jwt"))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("mfa_required");
        verify(tokenProvider, never()).generateImpersonationAccessToken(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("non-strict mode: unenrolled actor passes through but MFA-bypass is audited")
    void unenrolledNonStrictAuditsBypass() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);

        service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId)
                .reason("emergency support")
                .build(),
            null,
            "original.super-admin.jwt");

        // Two audits: SECURITY_ALERT_TRIGGERED (bypass) + IMPERSONATION_STARTED (boundary)
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(AuditEventRequestDTO::getEventType)
            .containsExactlyInAnyOrder(
                AuditEventType.SECURITY_ALERT_TRIGGERED,
                AuditEventType.IMPERSONATION_STARTED);
    }

    @Test
    @DisplayName("stop() emits IMPERSONATION_ENDED and reports !impersonating")
    void stopEmitsBoundaryAudit() {
        ImpersonationContextHolder.set(ImpersonationContext.of(actorId, "super.admin"));
        // Active session: subject = target, so emulate that.
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("nurse.alice", "n/a"));
        when(userRepository.findByUsername("nurse.alice")).thenReturn(Optional.of(target));

        // Pre-condition: tracker has the actor's session registered (start
        // would have done this; we register manually for this isolated stop test).
        sessionTracker.register(actorId, targetId, "imp-jti",
            java.time.Instant.now().plusSeconds(60));
        when(tokenProvider.getExpiration("impersonation.jwt"))
            .thenReturn(new java.util.Date(System.currentTimeMillis() + 600_000L));

        ImpersonationActiveResponseDTO response = service.stop("impersonation.jwt");

        assertThat(response.isImpersonating()).isFalse();
        assertThat(response.getTargetUsername()).isEqualTo("nurse.alice");
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(1)).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.IMPERSONATION_ENDED);

        // Closes Copilot review #6: impersonation token blacklisted on stop.
        verify(tokenBlacklistService, times(1)).blacklist(eq("imp-jti"), anyLong());
        // Tracker entry cleared so the original super admin can refresh again.
        assertThat(sessionTracker.hasActive(actorId)).isFalse();
    }

    @Test
    @DisplayName("getActive() reflects the holder state")
    void getActiveReflectsHolder() {
        assertThat(service.getActive().isImpersonating()).isFalse();

        ImpersonationContextHolder.set(ImpersonationContext.of(actorId, "super.admin"));
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("nurse.alice", "n/a"));
        when(userRepository.findByUsername("nurse.alice")).thenReturn(Optional.of(target));

        ImpersonationActiveResponseDTO active = service.getActive();
        assertThat(active.isImpersonating()).isTrue();
        assertThat(active.getImpersonatorUsername()).isEqualTo("super.admin");
        assertThat(active.getTargetUsername()).isEqualTo("nurse.alice");
    }

    @Test
    @DisplayName("start() rejects when actor already has an active session in the cross-process tracker")
    void startRejectsWhenTrackerHasActive() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);
        // First start succeeds.
        service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId).reason("first session").build(),
            null,
            "original.super-admin.jwt");
        assertThat(sessionTracker.hasActive(actorId)).isTrue();

        // Second start (same actor, ImpersonationContextHolder still empty
        // because this is a fresh request) is refused by the tracker check.
        UUID secondTargetId = UUID.randomUUID();
        User secondTarget = new User();
        secondTarget.setId(secondTargetId);
        secondTarget.setUsername("nurse.bob");
        when(userRepository.findById(secondTargetId)).thenReturn(Optional.of(secondTarget));
        when(tenantRoleAssignmentAccessor.findAssignmentsForUser(secondTargetId))
            .thenReturn(List.of(assignment("NURSE", "Nurse", true)));

        assertThatThrownBy(() -> service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(secondTargetId).reason("second session attempt").build(),
            null,
            "original.super-admin.jwt"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already active");
    }

    @Test
    @DisplayName("start() with null bearer skips blacklist gracefully (test-call path)")
    void startSwallowsNullBearer() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);

        service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId).reason("test path with no bearer").build(),
            null,
            null);

        // No blacklist call attempted with a null bearer; tracker still
        // registers the session so refresh-blocking behaviour is unchanged.
        verify(tokenBlacklistService, never()).blacklist(any(), anyLong());
        assertThat(sessionTracker.hasActive(actorId)).isTrue();
    }

    @Test
    @DisplayName("start() blacklist failure is swallowed — boundary action still succeeds")
    void startBlacklistFailureSwallowed() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);
        when(tokenProvider.getJtiFromToken("original.super-admin.jwt"))
            .thenThrow(new RuntimeException("token corrupt"));

        ImpersonationStartResponseDTO response = service.start(
            ImpersonationStartRequestDTO.builder()
                .targetUserId(targetId).reason("blacklist fault tolerance").build(),
            null,
            "original.super-admin.jwt");

        // Boundary still succeeds + tracker still registers — a transient
        // blacklist issue must not block a legitimate impersonation.
        assertThat(response.getAccessToken()).isEqualTo("impersonation.jwt");
        assertThat(sessionTracker.hasActive(actorId)).isTrue();
    }

    @Test
    @DisplayName("stop() with null context throws BusinessException")
    void stopWithoutContextRejects() {
        // No ImpersonationContextHolder set — calling stop is a no-op error.
        assertThatThrownBy(() -> service.stop("any-bearer"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("No impersonation session");
    }

    private void authenticateSuperAdmin() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(
            "super.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private TenantRoleAssignment assignment(String code, String name, boolean active) {
        return new TenantRoleAssignment(null, null, code, name, active);
    }
}
