package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.LoginAttemptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 18: the prescriber's step-up re-authentication at a controlled-drug
 * signature. One call, one signature: the TOTP code (with the shared
 * lockout and replay protection) when an authenticator is enrolled, else the
 * password (with its own lockout); refused for an account whose password HMS
 * does not hold; both outcomes audited, never the secret.
 */
class PrescriberReauthenticationServiceTest {

    private final MfaService mfaService = mock(MfaService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptStore attemptStore = mock(LoginAttemptStore.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);

    private final PrescriberReauthenticationService service = new PrescriberReauthenticationService(
        mfaService, userRepository, passwordEncoder, attemptStore, audit);

    private final UUID userId = UUID.randomUUID();
    private final UUID rxId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(userId);
        user.setPasswordHash("{bcrypt}hash");
        user.setAuthSource("internal");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(attemptStore.remainingLockMs(any())).thenReturn(0L);
        when(attemptStore.recordFailure(any(), anyInt(), anyLong(), anyLong())).thenReturn(1);
    }

    private AuditEventRequestDTO lastAudit() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).logEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void anEnrolledPrescriberSignsWithACurrentCode() {
        when(mfaService.isMfaEnabled(userId)).thenReturn(true);
        when(mfaService.requireStepUp(eq(userId), eq("123456"), any(), eq(true))).thenReturn(true);

        PrescriberReauthenticationService.Verified verified =
            service.requireForControlledSigning(userId, rxId, "ignored-password", "123456");

        assertThat(verified.method()).isEqualTo("TOTP");
        verify(passwordEncoder, never()).matches(any(), any());
        AuditEventRequestDTO row = lastAudit();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.PRESCRIPTION_SIGN_REAUTHENTICATION);
        assertThat(row.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(row.getResourceId()).isEqualTo(rxId.toString());
        assertThat(row.getEventDescription()).doesNotContain("123456");
    }

    @Test
    void aWrongCodeIsRefusedAndAudited() {
        when(mfaService.isMfaEnabled(userId)).thenReturn(true);
        when(mfaService.requireStepUp(eq(userId), any(), any(), eq(true)))
            .thenThrow(new MfaStepUpRefusedException(MfaStepUpRefusedException.Reason.INVALID));

        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, null, "000000"))
            .isInstanceOf(MfaStepUpRefusedException.class);
        assertThat(lastAudit().getStatus()).isEqualTo(AuditStatus.FAILURE);
    }

    @Test
    void anEnrolledPrescriberCannotFallBackToThePassword() {
        when(mfaService.isMfaEnabled(userId)).thenReturn(true);
        when(mfaService.requireStepUp(eq(userId), eq(null), any(), eq(true)))
            .thenThrow(new MfaStepUpRefusedException(MfaStepUpRefusedException.Reason.REQUIRED));

        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "right-password", null))
            .isInstanceOf(MfaStepUpRefusedException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void withoutAnAuthenticatorThePasswordIsChecked() {
        when(passwordEncoder.matches("right-password", "{bcrypt}hash")).thenReturn(true);

        PrescriberReauthenticationService.Verified verified =
            service.requireForControlledSigning(userId, rxId, "right-password", null);

        assertThat(verified.method()).isEqualTo("PASSWORD");
        verify(attemptStore).clear("rx-sign-password:" + userId);
        AuditEventRequestDTO row = lastAudit();
        assertThat(row.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(row.getEventDescription()).doesNotContain("right-password");
    }

    @Test
    void aWrongPasswordIsRefusedCountedAndAudited() {
        when(passwordEncoder.matches("wrong", "{bcrypt}hash")).thenReturn(false);

        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "wrong", null))
            .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(MfaStepUpRefusedException.Reason.PASSWORD_INVALID));
        verify(attemptStore).recordFailure(eq("rx-sign-password:" + userId), eq(5), anyLong(), anyLong());
        verify(attemptStore, never()).clear(any());
        assertThat(lastAudit().getStatus()).isEqualTo(AuditStatus.FAILURE);
    }

    @Test
    void tooManyWrongPasswordsLockTheSignature() {
        when(attemptStore.recordFailure(any(), anyInt(), anyLong(), anyLong())).thenReturn(6);

        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "right-password", null))
            .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(MfaStepUpRefusedException.Reason.PASSWORD_LOCKED));
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void aBlankPasswordIsRequired() {
        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "  ", null))
            .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(MfaStepUpRefusedException.Reason.PASSWORD_REQUIRED));
    }

    @Test
    void anOidcAccountWithoutAnAuthenticatorMustEnrolOne() {
        user.setAuthSource("keycloak");

        assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "any", null))
            .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(MfaStepUpRefusedException.Reason.SIGNING_NOT_ENROLLED));
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void aSupportSessionNeverSignsForThePrescriber() {
        try (org.mockito.MockedStatic<com.example.hms.security.context.ImpersonationContextHolder> holder =
                 org.mockito.Mockito.mockStatic(com.example.hms.security.context.ImpersonationContextHolder.class)) {
            holder.when(com.example.hms.security.context.ImpersonationContextHolder::isImpersonating).thenReturn(true);

            assertThatThrownBy(() -> service.requireForControlledSigning(userId, rxId, "right-password", null))
                .isInstanceOfSatisfying(MfaStepUpRefusedException.class, ex -> assertThat(ex.getReason())
                    .isEqualTo(MfaStepUpRefusedException.Reason.SIGNING_IMPERSONATION));
        }
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void anUnknownSignerIsRefused() {
        assertThatThrownBy(() -> service.requireForControlledSigning(null, rxId, "any", null))
            .isInstanceOfSatisfying(MfaStepUpRefusedException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(MfaStepUpRefusedException.Reason.UNIDENTIFIED));
    }

    @Test
    void eachSignatureAsksAgain() {
        when(passwordEncoder.matches("right-password", "{bcrypt}hash")).thenReturn(true);
        service.requireForControlledSigning(userId, rxId, "right-password", null);

        UUID otherRx = UUID.randomUUID();
        assertThatThrownBy(() -> service.requireForControlledSigning(userId, otherRx, null, null))
            .isInstanceOf(MfaStepUpRefusedException.class);
    }
}
