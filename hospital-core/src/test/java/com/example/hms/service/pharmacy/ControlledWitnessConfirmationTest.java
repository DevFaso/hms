package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.StockWitnessConfirmation;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.pharmacy.StockWitnessConfirmationRepository;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D2679: the witness of a controlled-drug movement confirms with their OWN factor. A name picked in a list
 * confirmed nothing; these pin that a confirmation exists only after the witness's password or authenticator
 * code was verified, and that every outcome is audited against the witness.
 */
@DisplayName("QA D2679: the witness's own confirmation")
class ControlledWitnessConfirmationTest {

    private final StockWitnessConfirmationRepository confirmations = mock(StockWitnessConfirmationRepository.class);
    private final MfaService mfaService = mock(MfaService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);

    private final ControlledWitnessConfirmation confirmation =
        new ControlledWitnessConfirmation(confirmations, mfaService, passwordEncoder, loginAttempts, audit);

    private final UUID hospitalId = UUID.randomUUID();
    private User witness;
    private User performer;

    @BeforeEach
    void people() {
        witness = new User();
        witness.setId(UUID.randomUUID());
        witness.setPasswordHash("{bcrypt}witness-hash");
        performer = new User();
        performer.setId(UUID.randomUUID());
        when(confirmations.save(any(StockWitnessConfirmation.class))).thenAnswer(call -> call.getArgument(0));
        // The bucket runs the comparison it is handed, as the real one does.
        when(loginAttempts.checkPassword(eq(witness.getId()), any(BooleanSupplier.class))).thenAnswer(call ->
            ((BooleanSupplier) call.getArgument(1)).getAsBoolean()
                ? LoginAttemptService.PasswordCheck.ACCEPTED : LoginAttemptService.PasswordCheck.REJECTED);
    }

    private AuditEventRequestDTO audited() {
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("the witness's right password writes a confirmation for this performer at this hospital")
    void rightPasswordConfirms() {
        when(passwordEncoder.matches("s3cret", "{bcrypt}witness-hash")).thenReturn(true);

        StockWitnessConfirmation saved = confirmation.confirm(witness, performer, hospitalId, "s3cret");

        assertThat(saved.getWitnessUserId()).isEqualTo(witness.getId());
        assertThat(saved.getPerformedByUserId()).isEqualTo(performer.getId());
        assertThat(saved.getHospitalId()).isEqualTo(hospitalId);
        assertThat(saved.getMethod()).isEqualTo(ControlledWitnessConfirmation.METHOD_PASSWORD);
        assertThat(saved.getExpiresAt()).isEqualTo(saved.getConfirmedAt().plus(ControlledWitnessConfirmation.VALIDITY));
        AuditEventRequestDTO event = audited();
        assertThat(event.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(event.getUserId()).isEqualTo(witness.getId());
        assertThat(event.getHospitalId()).isEqualTo(hospitalId);
        assertThat(event.getEntityType()).isEqualTo(ControlledWitnessConfirmation.AUDIT_ENTITY);
        // Never the secret, in any field of the row.
        assertThat(event.toString()).doesNotContain("s3cret");
    }

    @Test
    @DisplayName("a wrong password confirms nothing, is counted in the witness's bucket and is audited")
    void wrongPasswordRefused() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> confirmation.confirm(witness, performer, hospitalId, "guess"))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(ex -> assertThat(((FieldBusinessException) ex).getField())
                .isEqualTo(ControlledWitnessConfirmation.FIELD_SECRET));

        verify(loginAttempts).checkPassword(eq(witness.getId()), any(BooleanSupplier.class));
        verify(confirmations, never()).save(any());
        AuditEventRequestDTO event = audited();
        assertThat(event.getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(event.getEventType()).isEqualTo(AuditEventType.MFA_FAILURE);
        assertThat(event.getEventDescription()).contains("PASSWORD_INVALID");
    }

    @Test
    @DisplayName("a locked witness account confirms nothing")
    void lockedRefused() {
        when(loginAttempts.checkPassword(eq(witness.getId()), any(BooleanSupplier.class)))
            .thenReturn(LoginAttemptService.PasswordCheck.LOCKED);

        assertThatThrownBy(() -> confirmation.confirm(witness, performer, hospitalId, "s3cret"))
            .isInstanceOf(FieldBusinessException.class);
        verify(confirmations, never()).save(any());
    }

    @Test
    @DisplayName("a witness with an authenticator confirms with its code, never with a password")
    void authenticatorCodeConfirms() {
        when(mfaService.isMfaEnabled(witness.getId())).thenReturn(true);
        when(mfaService.requireStepUp(witness.getId(), "123456", ControlledWitnessConfirmation.ACTION, true))
            .thenReturn(true);

        StockWitnessConfirmation saved = confirmation.confirm(witness, performer, hospitalId, " 123456 ");

        assertThat(saved.getMethod()).isEqualTo(ControlledWitnessConfirmation.METHOD_TOTP);
        verify(loginAttempts, never()).checkPassword(any(), any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("a refused authenticator code confirms nothing and is audited")
    void authenticatorCodeRefused() {
        when(mfaService.isMfaEnabled(witness.getId())).thenReturn(true);
        doThrow(new MfaStepUpRefusedException(MfaStepUpRefusedException.Reason.INVALID))
            .when(mfaService).requireStepUp(any(), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> confirmation.confirm(witness, performer, hospitalId, "000000"))
            .isInstanceOf(FieldBusinessException.class);
        verify(confirmations, never()).save(any());
        assertThat(audited().getStatus()).isEqualTo(AuditStatus.FAILURE);
    }

    @Test
    @DisplayName("a blank secret is refused before any factor is asked")
    void blankRefused() {
        assertThatThrownBy(() -> confirmation.confirm(witness, performer, hospitalId, "  "))
            .isInstanceOf(FieldBusinessException.class);
        verify(loginAttempts, never()).checkPassword(any(), any());
        verify(confirmations, never()).save(any());
    }

    @Test
    @DisplayName("an account with neither a local password nor an authenticator cannot witness")
    void neitherFactorUnavailable() {
        witness.setPasswordHash(null);
        assertThat(confirmation.methodFor(witness)).isEqualTo(ControlledWitnessConfirmation.METHOD_UNAVAILABLE);
        witness.setPasswordHash("{bcrypt}witness-hash");
        witness.setAuthSource("keycloak");
        assertThat(confirmation.methodFor(witness)).isEqualTo(ControlledWitnessConfirmation.METHOD_UNAVAILABLE);

        assertThatThrownBy(() -> confirmation.confirm(witness, performer, hospitalId, "anything"))
            .isInstanceOf(FieldBusinessException.class);
        verify(confirmations, never()).save(any());
    }

    @Test
    @DisplayName("confirmed means: this witness, this performer, this hospital, not yet expired")
    void isConfirmedAsksForAllThree() {
        when(confirmations.existsByWitnessUserIdAndPerformedByUserIdAndHospitalIdAndExpiresAtAfter(
            eq(witness.getId()), eq(performer.getId()), eq(hospitalId), any(LocalDateTime.class))).thenReturn(true);

        assertThat(confirmation.isConfirmed(witness.getId(), performer.getId(), hospitalId)).isTrue();
        assertThat(confirmation.isConfirmed(witness.getId(), UUID.randomUUID(), hospitalId)).isFalse();
        assertThat(confirmation.isConfirmed(witness.getId(), performer.getId(), UUID.randomUUID())).isFalse();
        assertThat(confirmation.isConfirmed(witness.getId(), null, hospitalId)).isFalse();
    }
}
