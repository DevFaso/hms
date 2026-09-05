package com.example.hms.service.impl;

import com.example.hms.exception.UnauthorizedException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyActionResponseDTO;
import com.example.hms.payload.dto.superadmin.EmergencyBroadcastRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceLogoutRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyForceMfaRequestDTO;
import com.example.hms.payload.dto.superadmin.EmergencyKillFeatureRequestDTO;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.GlobalSessionRevocationService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.FeatureFlagService;
import com.example.hms.service.MfaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmergencyControlServiceImpl (MVP-7)")
class EmergencyControlServiceImplTest {

    private GlobalSessionRevocationService revocationService;
    private FeatureFlagService featureFlagService;
    private UserMfaEnrollmentRepository mfaEnrollmentRepository;
    private MfaBackupCodeRepository mfaBackupCodeRepository;
    private UserRepository userRepository;
    private AuditEventLogService auditEventLogService;
    private SimpMessagingTemplate messagingTemplate;
    private MfaService mfaService;
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    private EmergencyControlServiceImpl service;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        revocationService = mock(GlobalSessionRevocationService.class);
        featureFlagService = mock(FeatureFlagService.class);
        mfaEnrollmentRepository = mock(UserMfaEnrollmentRepository.class);
        mfaBackupCodeRepository = mock(MfaBackupCodeRepository.class);
        userRepository = mock(UserRepository.class);
        auditEventLogService = mock(AuditEventLogService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        mfaService = mock(MfaService.class);
        assignmentRepository = mock(UserRoleHospitalAssignmentRepository.class);

        service = new EmergencyControlServiceImpl(
            revocationService, featureFlagService, mfaEnrollmentRepository,
            mfaBackupCodeRepository, userRepository, auditEventLogService,
            messagingTemplate, mfaService, assignmentRepository);
        // Default: non-strict MFA mode (matches application.yml default).
        ReflectionTestUtils.setField(service, "requireMfaStrict", false);

        // Authenticate the SecurityContext with the actor user.
        actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("super.alice", "n/a", List.of()));

        User actor = new User();
        actor.setId(actorId);
        actor.setUsername("super.alice");
        when(userRepository.findByUsername("super.alice")).thenReturn(Optional.of(actor));

        // Default MFA stubs: enrolled, valid token. Individual tests override.
        when(mfaService.isMfaEnabled(actorId)).thenReturn(true);
        when(mfaService.verifyCode(eq(actorId), anyString())).thenReturn(true);

        when(revocationService.revokeAll(any(), any(), any())).thenReturn(Instant.now());
        when(featureFlagService.upsertOverride(anyString(), eq(false), anyString(),
            anyString(), anyString(), any())).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── forceLogoutAll ────────────────────────────────────────────────

    @Test
    @DisplayName("forceLogoutAll bumps revocation + audits + returns the timestamp")
    void forceLogoutAllHappyPath() {
        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder()
            .reason("incident X").build();

        EmergencyActionResponseDTO out = service.forceLogoutAll(req, "123456");

        verify(revocationService).revokeAll(actorId, "super.alice", "incident X");
        assertThat(out.getAction()).isEqualTo("FORCE_LOGOUT_ALL");
        assertThat(out.getActorUsername()).isEqualTo("super.alice");
        assertThat(out.getMessage()).contains("All sessions revoked");
        ArgumentCaptor<AuditEventRequestDTO> auditCaptor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventDescription()).contains("EMERGENCY_FORCE_LOGOUT_ALL");
    }

    @Test
    @DisplayName("forceLogoutAll rejects when actor is enrolled but token is missing")
    void forceLogoutAllRejectsMissingToken() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(true);

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.forceLogoutAll(req, null))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("mfa_required");
        verify(revocationService, never()).revokeAll(any(), any(), any());
    }

    @Test
    @DisplayName("forceLogoutAll rejects when actor is enrolled but token is invalid")
    void forceLogoutAllRejectsBadToken() {
        when(mfaService.verifyCode(actorId, "bad")).thenReturn(false);

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.forceLogoutAll(req, "bad"))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("forceLogoutAll passes through with audit when actor not enrolled (non-strict mode)")
    void forceLogoutAllUnenrolledNonStrict() {
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        service.forceLogoutAll(req, null);

        // Bypass + action = 2 audit events.
        verify(auditEventLogService, times(2)).logEvent(any());
        verify(revocationService).revokeAll(any(), any(), any());
    }

    @Test
    @DisplayName("forceLogoutAll rejects unenrolled actor in strict mode")
    void forceLogoutAllUnenrolledStrict() {
        ReflectionTestUtils.setField(service, "requireMfaStrict", true);
        when(mfaService.isMfaEnabled(actorId)).thenReturn(false);

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.forceLogoutAll(req, null))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("forceLogoutAll wraps an MFA-lookup exception as UnauthorizedException")
    void forceLogoutAllMfaLookupBlowsUp() {
        when(mfaService.isMfaEnabled(actorId)).thenThrow(new RuntimeException("db down"));

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.forceLogoutAll(req, "123"))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("enrollment lookup unavailable");
    }

    @Test
    @DisplayName("forceLogoutAll rejects when no super-admin actor can be resolved")
    void forceLogoutAllNoActor() {
        SecurityContextHolder.clearContext();

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.forceLogoutAll(req, "123"))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("missing super-admin actor");
    }

    @Test
    @DisplayName("audit emit failure is swallowed (boundary still completes)")
    void auditFailureSwallowed() {
        doThrow(new RuntimeException("audit write boom"))
            .when(auditEventLogService).logEvent(any(AuditEventRequestDTO.class));

        EmergencyForceLogoutRequestDTO req = EmergencyForceLogoutRequestDTO.builder().reason("x").build();

        // Should not propagate.
        EmergencyActionResponseDTO out = service.forceLogoutAll(req, "123");

        assertThat(out.getAction()).isEqualTo("FORCE_LOGOUT_ALL");
    }

    // ── killFeature ───────────────────────────────────────────────────

    @Test
    @DisplayName("killFeature sets enabled=false on the override and audits")
    void killFeatureHappyPath() {
        EmergencyKillFeatureRequestDTO req = EmergencyKillFeatureRequestDTO.builder()
            .flagKey("billing.fastlane")
            .reason("payments outage")
            .build();

        EmergencyActionResponseDTO out = service.killFeature(req, "123");

        assertThat(out.getAction()).isEqualTo("KILL_FEATURE");
        verify(featureFlagService).upsertOverride(eq("billing.fastlane"), eq(false),
            anyString(), eq("super.alice"), anyString(), any());
        verify(auditEventLogService).logEvent(any());
    }

    // ── forceMfaReenrol ───────────────────────────────────────────────

    @Test
    @DisplayName("forceMfaReenrol with explicit user ids deletes per-user enrolments + backup codes")
    void forceMfaReenrolExplicitTargets() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UserMfaEnrollment e1 = UserMfaEnrollment.builder().build();
        e1.setId(UUID.randomUUID());
        UserMfaEnrollment e2 = UserMfaEnrollment.builder().build();
        e2.setId(UUID.randomUUID());
        when(mfaEnrollmentRepository.findByUserId(t1)).thenReturn(List.of(e1));
        when(mfaEnrollmentRepository.findByUserId(t2)).thenReturn(List.of(e2));

        EmergencyForceMfaRequestDTO req = EmergencyForceMfaRequestDTO.builder()
            .userIds(List.of(t1, t2)).reason("breach response").build();

        EmergencyActionResponseDTO out = service.forceMfaReenrol(req, "123");

        // Two delete calls (one per user); identity is by id (Lombok @EqualsAndHashCode
        // on BaseEntity), so giving each enrolment a unique id makes the two
        // deleteAll(...) invocations distinguishable to Mockito.
        verify(mfaEnrollmentRepository, times(2)).deleteAll(any());
        verify(mfaBackupCodeRepository).deleteAllByUserId(t1);
        verify(mfaBackupCodeRepository).deleteAllByUserId(t2);
        assertThat(out.getAffectedRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("forceMfaReenrol refuses an empty user list unless resetAll=true — nothing is deleted")
    void forceMfaReenrolRefusesSilentResetAll() {
        EmergencyForceMfaRequestDTO req = EmergencyForceMfaRequestDTO.builder()
            .userIds(List.of()).reason("oops, blank field").build();

        assertThatThrownBy(() -> service.forceMfaReenrol(req, "123456"))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("resetAll");
        verify(mfaEnrollmentRepository, never()).findAll();
        verify(mfaEnrollmentRepository, never()).deleteAll(any());
        verify(mfaBackupCodeRepository, never()).deleteAllByUserId(any());
    }

    @Test
    @DisplayName("forceMfaReenrol with empty userIds discovers every enrolled user from the repo")
    void forceMfaReenrolFallbackToAll() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        User u1 = new User(); u1.setId(t1);
        User u2 = new User(); u2.setId(t2);
        UserMfaEnrollment e1 = UserMfaEnrollment.builder().user(u1).build();
        UserMfaEnrollment e2 = UserMfaEnrollment.builder().user(u2).build();

        when(mfaEnrollmentRepository.findAll()).thenReturn(List.of(e1, e2));
        when(mfaEnrollmentRepository.findByUserId(t1)).thenReturn(List.of(e1));
        when(mfaEnrollmentRepository.findByUserId(t2)).thenReturn(List.of(e2));

        EmergencyForceMfaRequestDTO req = EmergencyForceMfaRequestDTO.builder()
            .userIds(null).resetAll(true).reason("global rotate").build();

        EmergencyActionResponseDTO out = service.forceMfaReenrol(req, "123");

        assertThat(out.getAffectedRows()).isEqualTo(2);
        verify(mfaBackupCodeRepository).deleteAllByUserId(t1);
        verify(mfaBackupCodeRepository).deleteAllByUserId(t2);
    }

    @Test
    @DisplayName("forceMfaReenrol with target having no enrolment row only clears backup codes")
    void forceMfaReenrolTargetWithoutEnrolmentRows() {
        UUID t = UUID.randomUUID();
        when(mfaEnrollmentRepository.findByUserId(t)).thenReturn(List.of());

        EmergencyForceMfaRequestDTO req = EmergencyForceMfaRequestDTO.builder()
            .userIds(List.of(t)).reason("recovery").build();

        EmergencyActionResponseDTO out = service.forceMfaReenrol(req, "123");

        verify(mfaEnrollmentRepository, never()).deleteAll(any());
        verify(mfaBackupCodeRepository).deleteAllByUserId(t);
        assertThat(out.getAffectedRows()).isZero();
    }

    // ── broadcast ─────────────────────────────────────────────────────

    @Test
    @DisplayName("broadcast publishes a STOMP frame and audits")
    void broadcastHappyPath() {
        EmergencyBroadcastRequestDTO req = EmergencyBroadcastRequestDTO.builder()
            .message("System maintenance in 5 minutes")
            .severity("WARN")
            .build();

        EmergencyActionResponseDTO out = service.broadcast(req, "123");

        verify(messagingTemplate).convertAndSend(eq("/topic/emergency-broadcast"), any(Object.class));
        verify(auditEventLogService).logEvent(any());
        assertThat(out.getMessage()).contains("/topic/emergency-broadcast");
    }

    @Test
    @DisplayName("broadcast defaults severity to INFO when null")
    void broadcastDefaultsSeverity() {
        EmergencyBroadcastRequestDTO req = EmergencyBroadcastRequestDTO.builder()
            .message("hello").severity(null).build();

        service.broadcast(req, "123");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/emergency-broadcast"), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
            .containsEntry("severity", "INFO")
            .containsEntry("type", "EMERGENCY_BROADCAST");
    }

    @Test
    @DisplayName("broadcast swallows broker failures and still records the audit")
    void broadcastBrokerFailureSwallowed() {
        doThrow(new RuntimeException("broker down"))
            .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        EmergencyBroadcastRequestDTO req = EmergencyBroadcastRequestDTO.builder()
            .message("hi").severity("INFO").build();

        EmergencyActionResponseDTO out = service.broadcast(req, "123");

        assertThat(out.getAction()).isEqualTo("BROADCAST");
        verify(auditEventLogService).logEvent(any());
    }

    // ── currentUserId edge case ───────────────────────────────────────

    @Test
    @DisplayName("currentUserId returns null actor → emergency op rejected as unauthorized")
    void rejectsWhenSecurityContextHasNoUser() {
        // Authenticate but with a username the repo doesn't know about.
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("ghost", "n/a", List.of()));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        EmergencyKillFeatureRequestDTO req = EmergencyKillFeatureRequestDTO.builder()
            .flagKey("a.b").reason("test").build();

        assertThatThrownBy(() -> service.killFeature(req, "123"))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageContaining("missing super-admin actor");
    }
}
