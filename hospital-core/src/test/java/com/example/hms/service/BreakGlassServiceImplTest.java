package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.BreakGlassSession;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.BreakGlassDeclareRequestDTO;
import com.example.hms.payload.dto.BreakGlassRevokeRequestDTO;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.repository.BreakGlassSessionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BreakGlassServiceImpl}.
 *
 * <p>The service does role-gating and audit emission, both of which are easy
 * to mis-wire. These tests pin the behaviour at the boundary the controller
 * relies on: a non-privileged user cannot declare; a declared session emits a
 * BREAK_GLASS_ACCESS audit; consumeIfLive increments the audit count and
 * emits an audit event; revoke is restricted to the owner / hospital admin.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BreakGlassServiceImpl")
class BreakGlassServiceImplTest {

    @Mock private BreakGlassSessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogService auditService;

    @InjectMocks private BreakGlassServiceImpl service;

    private User caller;
    private Hospital hospital;
    private Patient patient;
    private UUID hospitalId;
    private UUID patientId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        caller = new User();
        caller.setId(userId);
        caller.setUsername("dr.alice");

        hospital = Hospital.builder().name("City Clinic").build();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        // Authenticate as dr.alice
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("dr.alice", "n/a"));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------- declare

    @Nested
    @DisplayName("declare")
    class Declare {
        @Test
        @DisplayName("creates session, applies default 240 min TTL, emits audit")
        void declareSuccess() {
            stubAuthenticatedDoctor();

            when(sessionRepository.save(any(BreakGlassSession.class)))
                .thenAnswer(inv -> {
                    BreakGlassSession bg = inv.getArgument(0);
                    bg.setId(UUID.randomUUID());
                    return bg;
                });

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .reason("Unconscious trauma patient, no family reachable.")
                .build();

            BreakGlassSessionResponseDTO out = service.declare(req);

            ArgumentCaptor<BreakGlassSession> captor = ArgumentCaptor.forClass(BreakGlassSession.class);
            verify(sessionRepository).save(captor.capture());
            BreakGlassSession saved = captor.getValue();

            assertThat(saved.getReason()).startsWith("Unconscious");
            assertThat(saved.getUser().getId()).isEqualTo(userId);
            assertThat(saved.getPatient().getId()).isEqualTo(patientId);
            // Default TTL = 240 min
            assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(239));
            assertThat(saved.getExpiresAt()).isBefore(LocalDateTime.now().plusMinutes(241));
            assertThat(out.getReason()).startsWith("Unconscious");

            verify(auditService).logEvent(any());
        }

        @Test
        @DisplayName("clamps caller-supplied TTL above 240 min to the 240-min ceiling")
        void declareClampsTtl() {
            stubAuthenticatedDoctor();
            when(sessionRepository.save(any(BreakGlassSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .reason("Override needed for trauma chart.")
                .ttlMinutes(9999)
                .build();

            service.declare(req);

            ArgumentCaptor<BreakGlassSession> captor = ArgumentCaptor.forClass(BreakGlassSession.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getExpiresAt())
                .isBefore(LocalDateTime.now().plusMinutes(241));
        }

        @Test
        @DisplayName("rejects TTL below the 15-minute floor")
        void declareRejectsLowTtl() {
            stubAuthenticatedDoctor();

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .reason("Override needed for trauma chart.")
                .ttlMinutes(5)
                .build();

            assertThatThrownBy(() -> service.declare(req))
                .isInstanceOf(BusinessException.class);

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects callers without a privileged role at the hospital")
        void declareRejectsUnprivileged() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.empty());
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(eq(userId), eq(hospitalId), any()))
                .thenReturn(false);

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .reason("Trying to override.")
                .build();

            assertThatThrownBy(() -> service.declare(req))
                .isInstanceOf(UnauthorizedAccessException.class);

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("404s when hospital is unknown")
        void declareUnknownHospital() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .reason("Override needed for trauma chart.")
                .build();

            assertThatThrownBy(() -> service.declare(req))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // -------------------------------------------------------------------- revoke

    @Nested
    @DisplayName("revoke")
    class Revoke {
        @Test
        @DisplayName("declaring user can revoke their own session")
        void ownerRevokes() {
            BreakGlassSession session = liveSession();
            when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BreakGlassSessionResponseDTO out = service.revoke(session.getId(),
                BreakGlassRevokeRequestDTO.builder().reason("Patient regained consciousness.").build());

            assertThat(out.getRevokedAt()).isNotNull();
            verify(auditService).logEvent(any());
        }

        @Test
        @DisplayName("non-owner without admin role is rejected")
        void nonOwnerNonAdminRejected() {
            BreakGlassSession session = liveSession();
            // Different declaring user
            User other = new User();
            other.setId(UUID.randomUUID());
            other.setUsername("dr.bob");
            session.setUser(other);

            when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.empty());
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                eq(userId), eq(hospitalId), any())).thenReturn(false);

            assertThatThrownBy(() -> service.revoke(session.getId(), null))
                .isInstanceOf(UnauthorizedAccessException.class);
        }

        @Test
        @DisplayName("re-revoking an already-closed session is a no-op (idempotent)")
        void reRevokeIdempotent() {
            BreakGlassSession session = liveSession();
            session.setRevokedAt(LocalDateTime.now().minusMinutes(5));
            when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));

            service.revoke(session.getId(), null);

            verify(sessionRepository, never()).save(any());
        }
    }

    // -------------------------------------------------------------------- consumeIfLive

    @Nested
    @DisplayName("consumeIfLive")
    class Consume {
        @Test
        @DisplayName("uses an atomic UPDATE for the audit counter; emits audit on success")
        void consumesLiveSession() {
            BreakGlassSession session = liveSession();
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of(session));
            when(sessionRepository.incrementAuditCount(session.getId())).thenReturn(1);

            Optional<BreakGlassSessionResponseDTO> out =
                service.consumeIfLive(patientId, userId, "Reading allergies");

            assertThat(out).isPresent();
            // DTO reflects the atomic +1
            assertThat(out.get().getAuditCount()).isEqualTo(1);
            // Crucially, the service must NOT fall back to save-the-entity (race-prone)
            verify(sessionRepository, never()).save(any());
            verify(sessionRepository).incrementAuditCount(session.getId());
            verify(auditService, times(1)).logEvent(any());
        }

        @Test
        @DisplayName("returns empty without side effects when no live session exists")
        void noSession() {
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of());

            Optional<BreakGlassSessionResponseDTO> out =
                service.consumeIfLive(patientId, userId, "Reading allergies");

            assertThat(out).isEmpty();
            verify(sessionRepository, never()).incrementAuditCount(any());
            verify(auditService, never()).logEvent(any());
        }

        @Test
        @DisplayName("returns empty when patientId is null (defensive guard)")
        void nullPatientShortCircuits() {
            assertThat(service.consumeIfLive(null, userId, "x")).isEmpty();
            verify(sessionRepository, never()).findLiveForUserAndPatient(any(), any(), any());
        }

        @Test
        @DisplayName("falls back to current security context when sessionUserId is null")
        void nullUserIdResolvesFromSecurityContext() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of(liveSession()));
            when(sessionRepository.incrementAuditCount(any())).thenReturn(1);

            Optional<BreakGlassSessionResponseDTO> out =
                service.consumeIfLive(patientId, null, "fallback");

            assertThat(out).isPresent();
        }

        @Test
        @DisplayName("returns empty when sessionUserId is null AND no security context")
        void nullUserIdNoSecurityContext() {
            SecurityContextHolder.clearContext();
            assertThat(service.consumeIfLive(patientId, null, "x")).isEmpty();
            verify(sessionRepository, never()).findLiveForUserAndPatient(any(), any(), any());
        }

        @Test
        @DisplayName("uses fallback purpose label when caller passes null")
        void nullPurposeUsesFallback() {
            BreakGlassSession session = liveSession();
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of(session));
            when(sessionRepository.incrementAuditCount(any())).thenReturn(1);

            service.consumeIfLive(patientId, userId, null);

            verify(auditService).logEvent(any());
        }
    }

    // -------------------------------------------------------------------- list / find

    @Nested
    @DisplayName("listLiveForPatient / listForHospital / findLiveForCurrentUserAndPatient")
    class ListAndFind {
        @Test
        @DisplayName("listLiveForPatient returns only sessions whose hospital the caller works at")
        void listLiveForPatientFiltersByCallerHospital() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.empty());
            // Caller works at the session's hospital.
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                eq(userId), eq(hospitalId), eq(BreakGlassServiceImpl.DECLARE_ROLES))).thenReturn(true);
            // Build one session at the caller's hospital and one at a different hospital.
            BreakGlassSession s1 = liveSession();
            BreakGlassSession s2 = liveSession();
            Hospital otherHospital = Hospital.builder().name("Other").build();
            otherHospital.setId(UUID.randomUUID());
            s2.setHospital(otherHospital);
            when(sessionRepository.findLiveForPatient(eq(patientId), any()))
                .thenReturn(List.of(s1, s2));

            List<BreakGlassSessionResponseDTO> out = service.listLiveForPatient(patientId);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getHospitalId()).isEqualTo(hospitalId);
        }

        @Test
        @DisplayName("listLiveForPatient: SUPER_ADMIN sees every hospital's sessions")
        void listLiveForPatientSuperAdmin() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.of(new com.example.hms.model.UserRoleHospitalAssignment()));
            when(sessionRepository.findLiveForPatient(eq(patientId), any()))
                .thenReturn(List.of(liveSession(), liveSession()));

            assertThat(service.listLiveForPatient(patientId)).hasSize(2);
        }

        @Test
        @DisplayName("listForHospital rejects callers who are not admins of that hospital")
        void listForHospitalRejectsCrossHospitalAdmin() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.empty());
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                eq(userId), eq(hospitalId), eq(BreakGlassServiceImpl.ADMIN_REVOKE_ROLES))).thenReturn(false);

            assertThatThrownBy(() -> service.listForHospital(hospitalId,
                org.springframework.data.domain.PageRequest.of(0, 10)))
                .isInstanceOf(UnauthorizedAccessException.class);
            verify(sessionRepository, never())
                .findByHospitalIdOrderByStartedAtDesc(any(), any());
        }

        @Test
        @DisplayName("listForHospital paginates for an admin of the hospital")
        void listForHospital() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.empty());
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                eq(userId), eq(hospitalId), eq(BreakGlassServiceImpl.ADMIN_REVOKE_ROLES))).thenReturn(true);

            org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, 10);
            when(sessionRepository.findByHospitalIdOrderByStartedAtDesc(hospitalId, page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(liveSession())));

            org.springframework.data.domain.Page<BreakGlassSessionResponseDTO> out =
                service.listForHospital(hospitalId, page);

            assertThat(out.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("findLiveForCurrentUserAndPatient returns empty when no security context")
        void findLiveNoAuth() {
            SecurityContextHolder.clearContext();
            assertThat(service.findLiveForCurrentUserAndPatient(patientId)).isEmpty();
            verify(sessionRepository, never()).findLiveForUserAndPatient(any(), any(), any());
        }

        @Test
        @DisplayName("findLiveForCurrentUserAndPatient returns the live session for the calling user")
        void findLiveWithAuth() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of(liveSession()));

            Optional<BreakGlassSessionResponseDTO> out = service.findLiveForCurrentUserAndPatient(patientId);

            assertThat(out).isPresent();
            assertThat(out.get().isLive()).isTrue();
        }

        @Test
        @DisplayName("findLiveForCurrentUserAndPatient returns empty when the user has no live session")
        void findLiveNoActiveSession() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(sessionRepository.findLiveForUserAndPatient(eq(userId), eq(patientId), any()))
                .thenReturn(List.of());

            assertThat(service.findLiveForCurrentUserAndPatient(patientId)).isEmpty();
        }
    }

    // -------------------------------------------------------------------- super admin

    @Nested
    @DisplayName("super-admin shortcut + auth boundaries")
    class SuperAdminAndAuth {
        @Test
        @DisplayName("SUPER_ADMIN can declare even without a hospital-scoped role")
        void superAdminBypassesHospitalRoleCheck() {
            when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.of(new com.example.hms.model.UserRoleHospitalAssignment()));
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId).hospitalId(hospitalId)
                .reason("Super-admin override.").build();

            assertThat(service.declare(req)).isNotNull();
            // existsActiveByUserAndHospitalAndAnyRoleCode must NOT be consulted
            verify(assignmentRepository, never())
                .existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), any());
        }

        @Test
        @DisplayName("declare throws Unauthorized when no security context is present")
        void declareWithoutAuth() {
            SecurityContextHolder.clearContext();
            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId).hospitalId(hospitalId)
                .reason("Trying without auth.").build();
            assertThatThrownBy(() -> service.declare(req))
                .isInstanceOf(UnauthorizedAccessException.class);
        }

        @Test
        @DisplayName("audit emission failure does not bubble out of the service")
        void auditFailureSwallowed() {
            stubAuthenticatedDoctor();
            when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doThrow(new RuntimeException("audit DB down"))
                .when(auditService).logEvent(any());

            BreakGlassDeclareRequestDTO req = BreakGlassDeclareRequestDTO.builder()
                .patientId(patientId).hospitalId(hospitalId)
                .reason("Audit failure resilience test.").build();

            // Must not throw — declare should still succeed even if audit emission fails.
            assertThat(service.declare(req)).isNotNull();
        }
    }

    // -------------------------------------------------------------------- helpers

    private void stubAuthenticatedDoctor() {
        when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, "ROLE_SUPER_ADMIN"))
            .thenReturn(Optional.empty());
        // Pin the role-code set so a regression that drops the ROLE_ prefix
        // (or a typo'd value) fails this test instead of silently authorising.
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            eq(userId), eq(hospitalId), eq(BreakGlassServiceImpl.DECLARE_ROLES))).thenReturn(true);
    }

    private BreakGlassSession liveSession() {
        BreakGlassSession s = BreakGlassSession.builder()
            .user(caller)
            .patient(patient)
            .hospital(hospital)
            .reason("Trauma override")
            .startedAt(LocalDateTime.now().minusMinutes(10))
            .expiresAt(LocalDateTime.now().plusMinutes(230))
            .auditCount(0)
            .build();
        s.setId(UUID.randomUUID());
        return s;
    }
}
