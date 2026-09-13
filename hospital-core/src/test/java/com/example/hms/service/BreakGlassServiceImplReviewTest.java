package com.example.hms.service;

import com.example.hms.enums.BreakGlassReviewOutcome;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.BreakGlassSession;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.BreakGlassReviewRequestDTO;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.repository.BreakGlassSessionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E8 #54 — the compliance sign-off on a break-the-glass session.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BreakGlassServiceImpl — review")
class BreakGlassServiceImplReviewTest {

    @Mock private BreakGlassSessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogService auditService;
    @InjectMocks private BreakGlassServiceImpl service;

    private User admin;
    private Hospital hospital;
    private BreakGlassSession session;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("admin.kone");
        hospital = Hospital.builder().name("CHU Yalgado").build();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        User declarer = new User();
        declarer.setId(UUID.randomUUID());
        declarer.setUsername("dr.alice");
        session = BreakGlassSession.builder()
            .user(declarer).patient(patient).hospital(hospital)
            .reason("Unconscious in the ED, no proxy reachable")
            .startedAt(LocalDateTime.now().minusHours(3))
            .expiresAt(LocalDateTime.now().minusHours(1))
            .build();
        session.setId(sessionId);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin.kone", "n/a"));
        when(userRepository.findByUsernameIgnoreCase("admin.kone")).thenReturn(Optional.of(admin));
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(BreakGlassSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void callerIsHospitalAdminHere(boolean yes) {
        when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(eq(admin.getId()), eq(hospitalId), anySet()))
            .thenReturn(yes);
        when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(admin.getId(), "ROLE_SUPER_ADMIN"))
            .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a hospital admin signs the session off: outcome, note, reviewer and time land on the row and in the audit")
    void adminReviews() {
        callerIsHospitalAdminHere(true);

        BreakGlassSessionResponseDTO dto = service.review(sessionId,
            BreakGlassReviewRequestDTO.builder().outcome(BreakGlassReviewOutcome.JUSTIFIED).note("Confirmed with the ED lead").build());

        assertThat(dto.isReviewed()).isTrue();
        assertThat(dto.getReviewOutcome()).isEqualTo(BreakGlassReviewOutcome.JUSTIFIED);
        assertThat(dto.getReviewNote()).isEqualTo("Confirmed with the ED lead");
        assertThat(dto.getReviewedByUserId()).isEqualTo(admin.getId());
        assertThat(dto.getReviewedByUserName()).isEqualTo("admin.kone");
        assertThat(session.getReviewedAt()).isNotNull();
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("a clinician at the hospital cannot review — the register is the administrator's")
    void nonAdminIsRefused() {
        callerIsHospitalAdminHere(false);

        BreakGlassReviewRequestDTO request =
            BreakGlassReviewRequestDTO.builder().outcome(BreakGlassReviewOutcome.NOT_JUSTIFIED).build();
        assertThatThrownBy(() -> service.review(sessionId, request))
            .isInstanceOf(UnauthorizedAccessException.class);
        assertThat(session.getReviewedAt()).isNull();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown session is a 404")
    void unknownSession() {
        UUID other = UUID.randomUUID();
        when(sessionRepository.findById(other)).thenReturn(Optional.empty());

        BreakGlassReviewRequestDTO request =
            BreakGlassReviewRequestDTO.builder().outcome(BreakGlassReviewOutcome.FOLLOW_UP).build();
        assertThatThrownBy(() -> service.review(other, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the register can be narrowed to the review queue")
    void queueFilter() {
        callerIsHospitalAdminHere(true);
        when(sessionRepository.findByHospitalIdAndReviewedAtIsNullOrderByStartedAtDesc(eq(hospitalId), any()))
            .thenReturn(new PageImpl<>(List.of(session)));

        var page = service.listForHospital(hospitalId, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).isReviewed()).isFalse();
        verify(sessionRepository, never()).findByHospitalIdOrderByStartedAtDesc(any(), any());
    }
}
