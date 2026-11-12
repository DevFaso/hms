package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PhoneOtpPurpose;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PhoneOtpChallenge;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PhoneOtpChallengeRepository;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.service.integration.IkoddiGateway;
import com.example.hms.utility.SmsCountryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
@Transactional
public class PhoneVerificationServiceImpl implements PhoneVerificationService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int MIN_DIGITS = 8;
    /** A fresh code for the same number can only be requested after this delay. */
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    /** Per-staff hourly dispatch cap — SMS sends cost money and can harass. */
    private static final int MAX_SENDS_PER_USER_PER_HOUR = 10;
    /**
     * Codes are generated, hashed and checked here: Orange sends plain SMS and
     * has no OTP service. {@code verification_key} stores
     * {@code local-sha256:<salt>:<sha256(salt:code)>}, never the code. A key
     * without this prefix is an IKODDI {@code otpToken} issued before the
     * switch, still verified remotely until it expires (a few minutes).
     */
    static final String LOCAL_CODE_PREFIX = "local-sha256:";
    static final String OTP_SMS_CAMPAIGN = "phone-verification-code";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhoneOtpChallengeRepository challengeRepository;
    private final IkoddiGateway ikoddiGateway;
    private final AuditEventLogService auditService;
    private final MessageSource messageSource;
    private final String defaultCountryNumberCode;
    private final long otpTtlSeconds;
    /**
     * The OTP clock: it writes {@code expiresAt} and reads it back on confirm,
     * and bounds the resend cooldown and the hourly cap against
     * {@code createdAt}, which {@code BaseEntity}'s {@code @PrePersist} stamps
     * from the system default zone — the same zone {@code TimeConfig}'s bean uses.
     */
    private final Clock clock;

    public PhoneVerificationServiceImpl(
        PhoneOtpChallengeRepository challengeRepository,
        IkoddiGateway ikoddiGateway,
        AuditEventLogService auditService,
        MessageSource messageSource,
        @Value("${app.ikoddi.country-number-code:226}") String defaultCountryNumberCode,
        @Value("${app.ikoddi.otp-ttl-seconds:300}") long otpTtlSeconds,
        Clock clock
    ) {
        this.challengeRepository = challengeRepository;
        this.ikoddiGateway = ikoddiGateway;
        this.auditService = auditService;
        this.messageSource = messageSource;
        this.defaultCountryNumberCode = defaultCountryNumberCode;
        this.otpTtlSeconds = otpTtlSeconds;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable() {
        return ikoddiGateway.isConfigured();
    }

    @Override
    public ChallengeView requestRegistrationVerification(String rawPhone, UUID requestedByUserId, UUID hospitalId) {
        String phone = normalizePhone(rawPhone);
        if (!SmsCountryPolicy.allows(phone)) {
            // Every code is a paid SMS: only well-formed numbers in the countries we text.
            throw new FieldBusinessException("phoneNumber", "phoneVerification.phone.countryNotSupported");
        }

        // ── Send-abuse guards: SMS dispatches cost money and can harass a
        // victim's phone. Cooldown per number + hourly cap per staff account. ──
        if (challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(
                requestedByUserId, LocalDateTime.now(clock).minusHours(1)) >= MAX_SENDS_PER_USER_PER_HOUR) {
            throw new BusinessException("phoneVerification.limitReached");
        }
        List<PhoneOtpChallenge> active = challengeRepository
            .findByPhoneNumberAndPurposeAndConsumedFalse(phone, PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION);
        boolean withinCooldown = active.stream()
            .anyMatch(c -> c.getCreatedAt() != null
                && c.getCreatedAt().isAfter(LocalDateTime.now(clock).minusSeconds(RESEND_COOLDOWN_SECONDS)));
        if (withinCooldown) {
            throw new BusinessException("phoneVerification.cooldown");
        }

        // Invalidate any still-active challenge for the same number —
        // only the most recent code can ever succeed.
        if (!active.isEmpty()) {
            active.forEach(c -> c.setConsumed(true));
            challengeRepository.saveAll(active);
        }

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        try {
            // An unconfigured gateway throws NotificationTransportUnavailableException (503), untouched.
            ikoddiGateway.sendSms(List.of(phone), otpMessage(code), OTP_SMS_CAMPAIGN);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            log.warn("OTP SMS to {} not accepted: {}", maskPhone(phone), ex.getMessage());
            throw new BusinessException("phoneVerification.providerRefused");
        }

        PhoneOtpChallenge challenge = PhoneOtpChallenge.builder()
            .phoneNumber(phone)
            .purpose(PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION)
            .verificationKey(LOCAL_CODE_PREFIX + salt + ":" + hashCode(salt, code))
            .expiresAt(LocalDateTime.now(clock).plusSeconds(otpTtlSeconds))
            .consumed(false)
            .verified(false)
            .usedForRegistration(false)
            .attempts(0)
            .requestedByUserId(requestedByUserId)
            .hospitalId(hospitalId)
            .build();
        PhoneOtpChallenge saved = challengeRepository.save(challenge);
        emitOtpAudit("OTP dispatched to " + maskPhone(phone), requestedByUserId, saved.getId());
        return new ChallengeView(saved.getId(), maskPhone(phone), saved.getExpiresAt(), false);
    }

    @Override
    // noRollbackFor: the attempts counter and the at-cap consume MUST survive the
    // BusinessException we throw right after saving them — with the default
    // rollback rule the 5-attempt cap would be a no-op.
    @Transactional(noRollbackFor = BusinessException.class)
    public ChallengeView confirmRegistrationVerification(UUID challengeId, String code, UUID requestedByUserId) {
        PhoneOtpChallenge challenge = challengeRepository
            .findByIdAndRequestedByUserId(challengeId, requestedByUserId)
            .orElseThrow(() -> new ResourceNotFoundException("phoneVerification.challenge.notFound", challengeId));

        if (challenge.isConsumed()) {
            throw new BusinessException("phoneVerification.code.used");
        }
        if (challenge.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new BusinessException("phoneVerification.code.expired");
        }
        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            challenge.setConsumed(true);
            challengeRepository.save(challenge);
            throw new BusinessException("phoneVerification.code.tooManyAttempts");
        }

        if (!codeMatches(challenge, code)) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            challengeRepository.save(challenge);
            throw new BusinessException("phoneVerification.code.invalid");
        }

        challenge.setConsumed(true);
        challenge.setVerified(true);
        challengeRepository.save(challenge);
        emitOtpAudit("OTP confirmed for " + maskPhone(challenge.getPhoneNumber()),
            requestedByUserId, challenge.getId());
        return new ChallengeView(challenge.getId(), maskPhone(challenge.getPhoneNumber()),
            challenge.getExpiresAt(), true);
    }

    /**
     * A locally issued challenge is checked here in constant time. An IKODDI one
     * (issued before the switch) is verified remotely; when IKODDI can no longer
     * be reached for it, the challenge is closed as expired so the desk requests
     * a new code instead of seeing an outage.
     */
    private boolean codeMatches(PhoneOtpChallenge challenge, String code) {
        String key = challenge.getVerificationKey();
        if (key != null && key.startsWith(LOCAL_CODE_PREFIX)) {
            return matchesLocalCode(key.substring(LOCAL_CODE_PREFIX.length()), code);
        }
        try {
            return ikoddiGateway.verifyOtp(challenge.getPhoneNumber(), code, key).matched();
        } catch (NotificationTransportUnavailableException | IllegalStateException ex) {
            log.warn("Legacy IKODDI challenge {} cannot be verified any more: {}", challenge.getId(), ex.getMessage());
            challenge.setConsumed(true);
            challengeRepository.save(challenge);
            throw new BusinessException("phoneVerification.code.expired");
        }
    }

    /** The SMS body: the code, its lifetime and the never-share warning, in French (no patient record yet). */
    private String otpMessage(String code) {
        long minutes = Math.max(1, otpTtlSeconds / 60);
        return messageSource.getMessage("sms.phoneVerification.code", new Object[] {code, minutes}, Locale.FRENCH);
    }

    /** Constant-time check of a typed code (spaces ignored) against {@code salt:hash}. */
    static boolean matchesLocalCode(String saltAndHash, String typed) {
        int sep = saltAndHash.indexOf(':');
        if (sep <= 0 || typed == null) {
            return false;
        }
        String normalized = typed.replaceAll("\\s", "");
        if (!normalized.matches("\\d{6}")) {
            return false;
        }
        String salt = saltAndHash.substring(0, sep);
        String expected = saltAndHash.substring(sep + 1);
        return MessageDigest.isEqual(
            hashCode(salt, normalized).getBytes(StandardCharsets.US_ASCII),
            expected.getBytes(StandardCharsets.US_ASCII));
    }

    static String hashCode(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Attribution trail: which staff account sent/confirmed a code for which number. */
    private void emitOtpAudit(String description, UUID requestedByUserId, UUID challengeId) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.MFA_CHALLENGE)
                .status(AuditStatus.SUCCESS)
                .entityType("PhoneOtpChallenge")
                .resourceId(challengeId != null ? challengeId.toString() : null)
                .userId(requestedByUserId)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            // Audit failure must never break the verification flow
            log.warn("Failed to emit OTP audit event: {}", ex.getMessage());
        }
    }

    @Override
    public boolean consumeVerifiedChallenge(UUID challengeId, String rawPhone) {
        if (challengeId == null || rawPhone == null || rawPhone.isBlank()) {
            return false;
        }
        return challengeRepository.findById(challengeId)
            .filter(PhoneOtpChallenge::isVerified)
            .filter(c -> !c.isUsedForRegistration())
            .filter(c -> c.getPhoneNumber().equals(normalizePhone(rawPhone)))
            .map(c -> {
                c.setUsedForRegistration(true);
                challengeRepository.save(c);
                return true;
            })
            .orElse(false);
    }

    /**
     * Pragmatic normalisation to an E.164-style number: keep digits, honour an
     * existing country prefix ({@code +} or {@code 00}), otherwise assume the
     * configured default country. (No libphonenumber dependency — national
     * numbers in the default region are <= 10 digits.)
     */
    String normalizePhone(String rawPhone) {
        if (rawPhone == null) {
            throw new FieldBusinessException("phoneNumber", "phoneVerification.phone.required");
        }
        String trimmed = rawPhone.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
            hasPlus = true;
        }
        if (digits.length() < MIN_DIGITS) {
            throw new FieldBusinessException("phoneNumber", "phoneVerification.phone.tooShort");
        }
        if (hasPlus || digits.length() > 10) {
            return "+" + digits;
        }
        return "+" + defaultCountryNumberCode + digits;
    }

    /** Display-safe form: keep a leading '+' and the last two digits, mask the rest. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.length() <= 4) {
            return "••••";
        }
        boolean plus = trimmed.startsWith("+");
        String last = trimmed.substring(trimmed.length() - 2);
        int maskedCount = trimmed.length() - 2 - (plus ? 1 : 0);
        return (plus ? "+" : "") + "•".repeat(Math.max(0, maskedCount)) + last;
    }
}
