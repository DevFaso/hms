package com.example.hms.service;

import com.example.hms.enums.PhoneOtpPurpose;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.NotificationTransportUnavailableException;
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
import org.springframework.context.support.StaticMessageSource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    /** Fixed instant: the OTP window is computed against the injected clock. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 10, 0);
    private static final Clock FIXED =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final String SALT = "00112233445566778899aabbccddeeff";

    private PhoneVerificationServiceImpl service;

    private UUID staffUserId;
    private UUID hospitalId;
    private UUID challengeId;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("sms.phoneVerification.code", Locale.FRENCH,
            "e-Keneya : votre code de vérification est {0}. Il expire dans {1} minutes.");
        service = new PhoneVerificationServiceImpl(challengeRepository, ikoddiGateway, auditService, messages,
            "226", 300, FIXED);
        staffUserId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        challengeId = UUID.randomUUID();
    }

    /** A challenge IKODDI issued before the switch: its key is IKODDI's opaque otpToken. */
    private PhoneOtpChallenge challenge() {
        PhoneOtpChallenge c = PhoneOtpChallenge.builder()
            .phoneNumber("+22670707070")
            .purpose(PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION)
            .verificationKey("tok-1")
            .expiresAt(NOW.plusMinutes(5))
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

    /** A locally issued challenge for {@code code}. */
    private PhoneOtpChallenge localChallenge(String code) {
        PhoneOtpChallenge c = challenge();
        c.setVerificationKey(PhoneVerificationServiceImpl.LOCAL_CODE_PREFIX + SALT + ":"
            + PhoneVerificationServiceImpl.hashCode(SALT, code));
        return c;
    }

    private void allowRequest() {
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(0L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(anyString(), any()))
            .thenReturn(List.of());
    }

    private record Sent(String body, PhoneOtpChallenge saved) {
    }

    /** Requests a code and captures the SMS body and the saved challenge. */
    private Sent requestAndCapture() {
        when(challengeRepository.save(any(PhoneOtpChallenge.class))).thenAnswer(inv -> {
            PhoneOtpChallenge c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        service.requestRegistrationVerification("+226 70 70 70 70", staffUserId, hospitalId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(ikoddiGateway).sendSms(to.capture(), body.capture(), eq(PhoneVerificationServiceImpl.OTP_SMS_CAMPAIGN));
        assertThat(to.getValue()).containsExactly("+22670707070");
        ArgumentCaptor<PhoneOtpChallenge> saved = ArgumentCaptor.forClass(PhoneOtpChallenge.class);
        verify(challengeRepository).save(saved.capture());
        return new Sent(body.getValue(), saved.getValue());
    }

    private static String codeIn(String body) {
        Matcher m = SIX_DIGITS.matcher(body);
        assertThat(m.find()).as("the SMS carries a six-digit code: " + body).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("request invalidates active challenges, sends a locally generated code as plain SMS, stores only its hash")
    void requestDispatchesAndPersists() {
        PhoneOtpChallenge stale = challenge();
        // Outside the resend cooldown — created long ago
        stale.setCreatedAt(NOW.minusMinutes(10));
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(0L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(
            "+22670707070", PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION))
            .thenReturn(List.of(stale));

        Sent sent = requestAndCapture();
        String code = codeIn(sent.body());

        assertThat(stale.isConsumed()).as("one active challenge per number and purpose").isTrue();
        assertThat(sent.body()).contains("expire dans 5 minutes");
        assertThat(sent.saved().getVerificationKey())
            .startsWith(PhoneVerificationServiceImpl.LOCAL_CODE_PREFIX)
            .doesNotContain(code)
            .matches("local-sha256:[0-9a-f]{32}:[0-9a-f]{64}");
        assertThat(sent.saved().getRequestedByUserId()).isEqualTo(staffUserId);
        assertThat(sent.saved().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        verify(ikoddiGateway, never()).sendOtp(anyString(), any());
    }

    @Test
    @DisplayName("the code that was sent verifies against the stored hash, without calling the provider")
    void sentCodeVerifiesLocally() {
        allowRequest();
        Sent sent = requestAndCapture();
        PhoneOtpChallenge saved = sent.saved();
        when(challengeRepository.findByIdAndRequestedByUserId(saved.getId(), staffUserId))
            .thenReturn(Optional.of(saved));

        var view = service.confirmRegistrationVerification(saved.getId(), " " + codeIn(sent.body()) + " ",
            staffUserId);

        assertThat(view.verified()).isTrue();
        assertThat(saved.isConsumed()).isTrue();
        assertThat(saved.isVerified()).isTrue();
        verify(ikoddiGateway, never()).verifyOtp(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the sent code verifies even when the server formats numbers with non-Latin digits")
    void sentCodeVerifiesWithNonLatinDefaultLocale() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
            allowRequest();
            Sent sent = requestAndCapture();
            PhoneOtpChallenge saved = sent.saved();
            when(challengeRepository.findByIdAndRequestedByUserId(saved.getId(), staffUserId))
                .thenReturn(Optional.of(saved));

            assertThat(service.confirmRegistrationVerification(saved.getId(), codeIn(sent.body()), staffUserId)
                .verified()).isTrue();
            assertThat(saved.isConsumed()).isTrue();
            assertThat(saved.isVerified()).isTrue();
            verify(ikoddiGateway, never()).verifyOtp(anyString(), anyString(), anyString());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    @DisplayName("the hash is salted per challenge and only an exact six-digit code matches")
    void saltIsPerChallenge() {
        String other = "ffeeddccbbaa99887766554433221100";
        assertThat(PhoneVerificationServiceImpl.hashCode(SALT, "123456"))
            .isNotEqualTo(PhoneVerificationServiceImpl.hashCode(other, "123456"));
        String stored = other + ":" + PhoneVerificationServiceImpl.hashCode(other, "123456");
        assertThat(PhoneVerificationServiceImpl.matchesLocalCode(stored, "123456")).isTrue();
        assertThat(PhoneVerificationServiceImpl.matchesLocalCode(stored, "12345")).isFalse();
        assertThat(PhoneVerificationServiceImpl.matchesLocalCode(stored, "abcdef")).isFalse();
        assertThat(PhoneVerificationServiceImpl.matchesLocalCode(stored, null)).isFalse();
    }

    @Test
    @DisplayName("request surfaces a business error when the provider refuses the SMS, and saves nothing")
    void requestFailsWhenProviderDeclines() {
        allowRequest();
        when(ikoddiGateway.sendSms(anyList(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("sms.provider.rejected"));

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.providerRefused");
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unconfigured transport still answers 503, as before")
    void unconfiguredTransportPropagates() {
        allowRequest();
        when(ikoddiGateway.sendSms(anyList(), anyString(), anyString()))
            .thenThrow(new NotificationTransportUnavailableException("unavailable"));

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(NotificationTransportUnavailableException.class);
        verify(challengeRepository, never()).save(any());
    }

    @Test
    @DisplayName("a number outside the supported countries, or a malformed Burkina number, is refused before any SMS")
    void unsupportedNumberRefused() {
        assertThatThrownBy(() -> service.requestRegistrationVerification("+33612345678", staffUserId, hospitalId))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.phone.countryNotSupported");
        assertThatThrownBy(() -> service.requestRegistrationVerification("+226707070701", staffUserId, hospitalId))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.phone.countryNotSupported");
        verify(ikoddiGateway, never()).sendSms(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("the hourly per-staff send cap blocks further dispatches")
    void hourlySendCapBlocks() {
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(10L);

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("limit");
        verify(ikoddiGateway, never()).sendSms(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("a resend inside the cooldown window is rejected without dispatching")
    void resendCooldownBlocks() {
        PhoneOtpChallenge justSent = challenge();
        justSent.setCreatedAt(NOW.minusSeconds(5));
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(eq(staffUserId), any()))
            .thenReturn(1L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(
            "+22670707070", PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION))
            .thenReturn(List.of(justSent));

        assertThatThrownBy(() -> service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.cooldown");
        verify(ikoddiGateway, never()).sendSms(anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("a legacy IKODDI challenge is still verified remotely during the migration window")
    void legacyChallengeVerifiedRemotely() {
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
    @DisplayName("a legacy challenge IKODDI can no longer verify is closed as expired, not an outage")
    void legacyChallengeWithoutIkoddiExpires() {
        PhoneOtpChallenge c = challenge();
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));
        when(ikoddiGateway.verifyOtp(anyString(), anyString(), anyString()))
            .thenThrow(new NotificationTransportUnavailableException("unavailable"));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.code.expired");
        assertThat(c.isConsumed()).isTrue();
        assertThat(c.isVerified()).isFalse();
    }

    @Test
    @DisplayName("a wrong local code increments attempts and throws")
    void wrongCodeIncrementsAttempts() {
        PhoneOtpChallenge c = localChallenge("123456");
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "000000", staffUserId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.code.invalid");
        assertThat(c.getAttempts()).isEqualTo(1);
        assertThat(c.isVerified()).isFalse();
        assertThat(c.isConsumed()).isFalse();
    }

    @Test
    @DisplayName("an expired challenge is rejected, even with the right code")
    void expiredChallengeRejected() {
        PhoneOtpChallenge c = localChallenge("123456");
        c.setExpiresAt(NOW.minusMinutes(1));
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");
        assertThat(c.isVerified()).isFalse();
        verify(ikoddiGateway, never()).verifyOtp(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("after five wrong codes the challenge is consumed and even the right code is refused")
    void attemptsCapConsumes() {
        PhoneOtpChallenge c = localChallenge("123456");
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "000000", staffUserId))
                .extracting("messageKey").isEqualTo("phoneVerification.code.invalid");
        }

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("tooManyAttempts");
        assertThat(c.isConsumed()).isTrue();
        assertThat(c.isVerified()).isFalse();
    }

    @Test
    @DisplayName("a used code cannot be confirmed twice")
    void usedCodeRefused() {
        PhoneOtpChallenge c = localChallenge("123456");
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(c));
        service.confirmRegistrationVerification(challengeId, "123456", staffUserId);

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("phoneVerification.code.used");
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
    @DisplayName("the code is still valid at exactly expiresAt and expired one nanosecond later")
    void expiryBoundaryUsesInjectedClock() {
        PhoneOtpChallenge atBoundary = localChallenge("123456");
        atBoundary.setExpiresAt(NOW);
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(atBoundary));

        assertThat(service.confirmRegistrationVerification(challengeId, "123456", staffUserId).verified()).isTrue();

        PhoneOtpChallenge justExpired = localChallenge("123456");
        justExpired.setExpiresAt(NOW.minusNanos(1));
        when(challengeRepository.findByIdAndRequestedByUserId(challengeId, staffUserId))
            .thenReturn(Optional.of(justExpired));

        assertThatThrownBy(() -> service.confirmRegistrationVerification(challengeId, "123456", staffUserId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("the TTL, the hourly-cap window and the resend cooldown are all measured from the injected clock")
    void requestWindowsUseInjectedClock() {
        PhoneOtpChallenge sentSixtySecondsAgo = challenge();
        // Exactly at the cooldown edge: isAfter is strict, so this one no longer blocks.
        sentSixtySecondsAgo.setCreatedAt(NOW.minusSeconds(60));
        when(challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(staffUserId, NOW.minusHours(1)))
            .thenReturn(0L);
        when(challengeRepository.findByPhoneNumberAndPurposeAndConsumedFalse(
            "+22670707070", PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION))
            .thenReturn(List.of(sentSixtySecondsAgo));
        when(challengeRepository.save(any(PhoneOtpChallenge.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.requestRegistrationVerification("+22670707070", staffUserId, hospitalId);

        assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(300));
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
