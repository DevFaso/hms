package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.support.UserAccountAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA r64 (lost phone): an administrator's reset and the user's own removal of two-step verification. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MfaResetServiceTest {

    private static final String REASON = "Telephone perdu, signale a l'accueil";

    @Mock private UserRepository userRepository;
    @Mock private UserMfaEnrollmentRepository enrollmentRepository;
    @Mock private MfaBackupCodeRepository backupCodeRepository;
    @Mock private UserAccountAccess accountAccess;
    @Mock private MfaService mfaService;
    @Mock private AuditEventLogService auditEventLogService;
    @InjectMocks private MfaResetService service;

    private final UUID adminId = UUID.randomUUID();
    private final User target = new User();
    private final UserMfaEnrollment enrolment = new UserMfaEnrollment();

    @BeforeEach
    void setUp() {
        target.setId(UUID.randomUUID());
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountAccess.currentUserId()).thenReturn(Optional.of(adminId));
        when(enrollmentRepository.findByUserId(target.getId())).thenReturn(List.of(enrolment));
        when(mfaService.requireStepUp(any(), any(), anyString(), anyBoolean())).thenReturn(true);
    }

    @Test
    void anAdministratorOfTheAccountResetsIt_afterTheirStepUp_andTheReasonIsAudited() {
        when(accountAccess.canAdminister(target)).thenReturn(true);

        service.resetByAdministrator(target.getId(), "  " + REASON + "  ", "654321");

        var order = inOrder(mfaService, enrollmentRepository, backupCodeRepository);
        order.verify(mfaService).requireStepUp(adminId, "654321", "MFA enrolment change", false);
        order.verify(enrollmentRepository).deleteAll(List.of(enrolment));
        order.verify(backupCodeRepository).deleteAllByUserId(target.getId());
        AuditEventRequestDTO row = audited();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.MFA_RESET);
        assertThat(row.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(row.getUserId()).isEqualTo(adminId);
        assertThat(row.getResourceId()).isEqualTo(target.getId().toString());
        assertThat(row.getEventDescription())
            .isEqualTo("Two-step verification reset by an administrator: " + REASON);
    }

    @Test
    void anAccountTheCallerDoesNotAdminister_isNeverReset_forExampleASuperAdminsFromAHospitalAdmin() {
        // UserAccountAccess#canAdminister: a hospital admin never administers an account holding an admin role.
        when(accountAccess.canAdminister(target)).thenReturn(false);
        when(accountAccess.canView(target)).thenReturn(false);

        assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), REASON, null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(enrollmentRepository, never()).deleteAll(any());
        verify(backupCodeRepository, never()).deleteAllByUserId(any());
        verify(mfaService, never()).requireStepUp(any(), any(), anyString(), anyBoolean());
        assertThat(audited().getStatus()).isEqualTo(AuditStatus.FAILURE);
    }

    @Test
    void anAccountTheCallerSeesButDoesNotAdminister_isRefusedWithTheReason() {
        when(accountAccess.canAdminister(target)).thenReturn(false);
        when(accountAccess.canView(target)).thenReturn(true);

        assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), REASON, null))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(backupCodeRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void aSupportSessionCannotResetAColleaguesSecondFactor() {
        when(accountAccess.canAdminister(target)).thenReturn(true);
        com.example.hms.security.context.ImpersonationContextHolder.set(
            com.example.hms.security.context.ImpersonationContext.of(UUID.randomUUID(), "support.agent"));
        try {
            assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), REASON, "654321"))
                .isInstanceOf(ClientSafeAccessDeniedException.class);
        } finally {
            com.example.hms.security.context.ImpersonationContextHolder.clear();
        }
        verifyNoInteractions(mfaService, enrollmentRepository, backupCodeRepository);
    }

    @Test
    void theReasonIsMandatory_andCheckedBeforeAnythingElse() {
        for (String reason : new String[] {null, "", "   ", "abc", "x".repeat(501)}) {
            assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), reason, "654321"))
                .isInstanceOf(FieldBusinessException.class);
        }
        verifyNoInteractions(mfaService, enrollmentRepository, backupCodeRepository, auditEventLogService);
    }

    @Test
    void theCallersOwnAccountGoesThroughTheProfile() {
        when(accountAccess.isSelf(target)).thenReturn(true);
        when(accountAccess.canAdminister(target)).thenReturn(true);

        assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), REASON, "654321"))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(mfaService, backupCodeRepository);
    }

    @Test
    void aDeletedAccountIsAnsweredAsMissing() {
        target.setDeleted(true);
        assertThatThrownBy(() -> service.resetByAdministrator(target.getId(), REASON, "654321"))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(mfaService, backupCodeRepository);
    }

    @Test
    void anAdministratorWithoutAnAuthenticator_inNonStrictMode_isRecordedAsABypass() {
        when(accountAccess.canAdminister(target)).thenReturn(true);
        when(mfaService.requireStepUp(any(), any(), anyString(), anyBoolean())).thenReturn(false);

        service.resetByAdministrator(target.getId(), REASON, null);

        ArgumentCaptor<AuditEventRequestDTO> rows = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(rows.capture());
        assertThat(rows.getAllValues()).extracting(AuditEventRequestDTO::getEventType)
            .containsExactly(AuditEventType.SECURITY_ALERT_TRIGGERED, AuditEventType.MFA_RESET);
    }

    @Test
    void theUserRemovesTheirOwn_afterTheGuardOnTheAuthenticator() {
        Authentication caller = mock(Authentication.class);
        when(mfaService.isMfaEnabled(target.getId())).thenReturn(true);

        service.removeOwn(target, caller, "123456");

        var order = inOrder(mfaService, enrollmentRepository, backupCodeRepository);
        order.verify(mfaService).requireStepUpToChangeAuthenticator(target.getId(), caller, "123456");
        order.verify(enrollmentRepository).deleteAll(List.of(enrolment));
        order.verify(backupCodeRepository).deleteAllByUserId(target.getId());
        assertThat(audited().getEventDescription()).isEqualTo("Two-step verification removed by the user");
    }

    @Test
    void aRefusedCodeRemovesNothing() {
        Authentication caller = mock(Authentication.class);
        when(mfaService.isMfaEnabled(target.getId())).thenReturn(true);
        doThrow(new MfaStepUpRefusedException(MfaStepUpRefusedException.Reason.INVALID))
            .when(mfaService).requireStepUpToChangeAuthenticator(eq(target.getId()), any(), any());

        assertThatThrownBy(() -> service.removeOwn(target, caller, "000000"))
            .isInstanceOf(MfaStepUpRefusedException.class);
        verify(enrollmentRepository, never()).deleteAll(any());
        verify(backupCodeRepository, never()).deleteAllByUserId(any());
    }

    @Test
    void nothingToRemove_isSaid() {
        when(mfaService.isMfaEnabled(target.getId())).thenReturn(false);
        Authentication caller = mock(Authentication.class);
        assertThatThrownBy(() -> service.removeOwn(target, caller, "123456"))
            .isInstanceOf(ConflictException.class);
        verify(backupCodeRepository, never()).deleteAllByUserId(any());
    }

    private AuditEventRequestDTO audited() {
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(row.capture());
        return row.getValue();
    }
}
