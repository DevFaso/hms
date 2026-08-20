package com.example.hms.service;

import com.example.hms.enums.PhoneOtpPurpose;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PhoneOtpChallenge;
import com.example.hms.repository.PhoneOtpChallengeRepository;
import com.example.hms.service.integration.IkoddiGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneVerificationServiceImpl")
class PhoneVerificationServiceImplTest {

    @Mock private PhoneOtpChallengeRepository challengeRepository;
    @Mock private IkoddiGateway ikoddiGateway;
    @Mock private AuditEventLogService auditService;

    private PhoneVerificationServiceImpl service;

    private UUID staffUserId;
    private UUID hospitalId;
    private UUID challengeId;

    @BeforeEach
    void setUp() {
        service = new PhoneVerificationServiceImpl(challengeRepository, ikoddiGateway, auditService, "226", 300);
        staffUserId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        challengeId = UUID.randomUUID();
    }

    private PhoneOtpChallenge challenge() {
        PhoneOtpChallenge c = PhoneOtpChallenge.builder()
            .phoneNumber("+22670707070")
            .purpose(PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION)
            .verificationKey("tok-1")
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .consumed(false)
            .verified(false)
            .usedForRegistration(false)
            .attempts(0)
            .requestedByUserId(staffUserId)
            .hospitalId(hospitalId)
            .build();
        c.setId(challengeId);
        return c;
    }

    @Test
    @DisplayName("request invalidates active challenges, dispatches via IKODDI, and persists the verification key")
    void requestDispatchesAndPersists() {
        PhoneOtpChallenge stale = challenge();
        // Outside the resend cooldown — created long ago
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(0L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(
            "+22670707070", PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION))
            .thenReturn(List.of(stale));
        when(ikoddiGateway.sendOtp("+22670707070", IkoddiGateway.OtpChannel.SMS))
            .thenReturn(new IkoddiGateway.OtpDispatch(0, "tok-2"));
        when(challengeRepository.save(any(PhoneOtpChallenge.class))).thenAnswer(inv -> {
            PhoneOtpChallenge c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });

        var view = service.requestRegistrationVerification("+226 70 70 70 70", staffUserId, hospitalId);

        assertThat(stale.isConsumed()).isTrue();
        assertThat(view.maskedPhone()).startsWith("+").endsWith("70").contains("•");
        ArgumentCaptor<PhoneOtpChallenge> captor = ArgumentCaptor.forClass(PhoneOtpChallenge.class);
        verify(challengeRepository).save(captor.capture());
        assertThat(captor.getValue().getVerificationKey()).isEqualTo("tok-2");
        assertThat(captor.getValue().getRequestedByUserId()).isEqualTo(staffUserId);
    }

    @Test
    @DisplayName("request surfaces a business error when IKODDI declines the dispatch")
    void requestFailsWhenProviderDeclines() {
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(0L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(anyString(), any()))
            .thenReturn(List.of());
        when(ikoddiGateway.sendOtp(anyString(), any()))
            .thenReturn(new IkoddiGateway.OtpDispatch(1, null));

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class);
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("the hourly per-staff send cap blocks further dispatches")
    void hourlySendCapBlocks() {
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(10L);

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("limit");
        verify(ikoddiGateway, never()).sendOtp(anyString(), any());
    }

    @Test
    @DisplayName("a resend inside the cooldown window is rejected without dispatching")
    void resendCooldownBlocks() {
        PhoneOtpChallenge justSent = challenge();
        justSent.setCreatedAt(LocalDateTime.now().minusSeconds(5));
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(1L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(
            "+22670707070", PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION))
            .thenReturn(List.of(justSent));

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("wait");
        verify(ikoddiGateway, never()).sendOtp(anyString(), any());
    }

    @Test
    @DisplayName("confirm marks the challenge consumed + verified on an IKODDI match")
    void confirmMarksVerified() {
        PhoneOtpChallenge c = challenge();
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));
        when(ikoddiGateway.verifyOtp("+22670707070", "123456", "tok-1"))
            .thenReturn(new IkoddiGateway.OtpVerification(0, "ok"));

        var view = service.confirmRegistrationVerification(challengeId, "123456", staffUserId);

        assertThat(view.verified()).isTrue();
        assertThat(c.isConsumed()).isTrue();
        assertThat(c.isVerified()).isTrue();
    }

    @Test
    @DisplayName("a wrong code increments attempts and throws")
    void wrongCodeIncrementsAttempts() {
        PhoneOtpChallenge c = challenge();
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));
        when(ikoddiGateway.verifyOtp(anyString(), anyString(), anyString()))
            .thenReturn(new IkoddiGateway.OtpVerification(1, "mismatch"));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "000000", staffUserId))
            .isInstanceOf(BusinessException.class);
        assertThat(c.getAttempts()).isEqualTo(1);
        assertThat(c.isVerified()).isFalse();
    }

    @Test
    @DisplayName("an expired challenge is rejected without calling IKODDI")
    void expiredChallengeRejected() {
        PhoneOtpChallenge c = challenge();
        c.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class);
        verify(ikoddiGateway, never()).verifyOtp(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the attempts cap consumes the challenge")
    void attemptsCapConsumes() {
        PhoneOtpChallenge c = challenge();
        c.setAttempts(5);
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class);
        assertThat(c.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("a foreign staff member cannot confirm someone else's challenge")
    void foreignRequesterGets404() {
        when(challengeRepository.findByIdAndRequestedByUserId(eq(challengeId), any()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("consumeVerifiedChallenge claims a verified challenge exactly once for the matching phone")
    void consumeVerifiedChallengeSingleUse() {
        PhoneOtpChallenge c = challenge();
        c.setConsumed(true);
        c.setVerified(true);
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(c));

        assertThat(service.consumeVerifiedChallenge(challengeId, "+226 70-70-70-70")).isTrue();
        assertThat(c.isUsedForRegistration()).isTrue();

        // Second claim on the same challenge must fail (single-use)
        assertThat(service.consumeVerifiedChallenge(challengeId, "+22670707070")).isFalse();
    }

    @Test
    @DisplayName("consumeVerifiedChallenge rejects a phone that differs from the verified one")
    void consumeVerifiedChallengeRejectsMismatch() {
        PhoneOtpChallenge c = challenge();
        c.setConsumed(true);
        c.setVerified(true);
        when(challengeRepository.findById(challengeId)).thenReturn(Optional.of(c));

        assertThat(service.consumeVerifiedChallenge(challengeId, "+22670000000")).isFalse();
        assertThat(c.isUsedForRegistration()).isFalse();
    }

    @Test
    @DisplayName("normalizePhone honours + and 00 prefixes and assumes the default country for local numbers")
    void normalizePhoneVariants() {
        assertThat(service.normalizePhone("+226 70 70 70 70")).isEqualTo("+22670707070");
        assertThat(service.normalizePhone("0022670707070")).isEqualTo("+22670707070");
        assertThat(service.normalizePhone("70 70 70 70")).isEqualTo("+22670707070");
        assertThat(service.normalizePhone("22670707070")).isEqualTo("+22670707070");
        assertThatThrownBy(() -> service.normalizePhone("707"))
            .isInstanceOf(BusinessException.class);
    }
}
