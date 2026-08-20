package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PhoneOtpPurpose;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.PhoneOtpChallenge;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PhoneOtpChallengeRepository;
import com.example.hms.service.integration.IkoddiGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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

    private final PhoneOtpChallengeRepository challengeRepository;
    private final IkoddiGateway ikoddiGateway;
    private final AuditEventLogService auditService;
    private final String defaultCountryNumberCode;
    private final long otpTtlSeconds;

    public PhoneVerificationServiceImpl(
        PhoneOtpChallengeRepository challengeRepository,
        IkoddiGateway ikoddiGateway,
        AuditEventLogService auditService,
        @Value("${app.ikoddi.country-number-code:226}") String defaultCountryNumberCode,
        @Value("${app.ikoddi.otp-ttl-seconds:300}") long otpTtlSeconds
    ) {
        this.challengeRepository = challengeRepository;
        this.ikoddiGateway = ikoddiGateway;
        this.auditService = auditService;
        this.defaultCountryNumberCode = defaultCountryNumberCode;
        this.otpTtlSeconds = otpTtlSeconds;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable() {
        return ikoddiGateway.isConfigured();
    }

    @Override
    public ChallengeView requestRegistrationVerification(String rawPhone, UUID requestedByUserId, UUID hospitalId) {
        String phone = normalizePhone(rawPhone);

        // ── Send-abuse guards: SMS dispatches cost money and can harass a
        // victim's phone. Cooldown per number + hourly cap per staff account. ──
        if (challengeRepository.countByRequestedByUserIdAndCreatedAtAfter(
                requestedByUserId, LocalDateTime.now().minusHours(1)) >= MAX_SENDS_PER_USER_PER_HOUR) {
            throw new BusinessException("Verification-code limit reached — try again later.");
        }
        List<PhoneOtpChallenge> active = challengeRepository
            .findByPhoneNumberAndPurposeAndConsumedFalse(phone, PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION);
        boolean withinCooldown = active.stream()
            .anyMatch(c -> c.getCreatedAt() != null
                && c.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(RESEND_COOLDOWN_SECONDS)));
        if (withinCooldown) {
            throw new BusinessException("A code was just sent to this number — wait a moment before resending.");
        }

        // Invalidate any still-active challenge for the same number —
        // only the most recent code can ever succeed.
        if (!active.isEmpty()) {
            active.forEach(c -> c.setConsumed(true));
            challengeRepository.saveAll(active);
        }

        IkoddiGateway.OtpDispatch sent = ikoddiGateway.sendOtp(phone, IkoddiGateway.OtpChannel.SMS);
        if (!sent.accepted()) {
            throw new BusinessException("SMS provider did not accept the verification request.");
        }

        PhoneOtpChallenge challenge = PhoneOtpChallenge.builder()
            .phoneNumber(phone)
            .purpose(PhoneOtpPurpose.REGISTRATION_PHONE_VERIFICATION)
            .verificationKey(sent.otpToken())
            .expiresAt(LocalDateTime.now().plusSeconds(otpTtlSeconds))
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
            .orElseThrow(() -> new ResourceNotFoundException("Verification challenge not found: " + challengeId));

        if (challenge.isConsumed()) {
            throw new BusinessException("This verification code was already used — request a new one.");
        }
        if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("The verification code expired — request a new one.");
        }
        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            challenge.setConsumed(true);
            challengeRepository.save(challenge);
            throw new BusinessException("Too many attempts — request a new verification code.");
        }

        IkoddiGateway.OtpVerification verification =
            ikoddiGateway.verifyOtp(challenge.getPhoneNumber(), code, challenge.getVerificationKey());
        if (!verification.matched()) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            challengeRepository.save(challenge);
            throw new BusinessException("Invalid verification code.");
        }

        challenge.setConsumed(true);
        challenge.setVerified(true);
        challengeRepository.save(challenge);
        emitOtpAudit("OTP confirmed for " + maskPhone(challenge.getPhoneNumber()),
            requestedByUserId, challenge.getId());
        return new ChallengeView(challenge.getId(), maskPhone(challenge.getPhoneNumber()),
            challenge.getExpiresAt(), true);
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
            throw new BusinessException("Phone number is required.");
        }
        String trimmed = rawPhone.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
            hasPlus = true;
        }
        if (digits.length() < MIN_DIGITS) {
            throw new BusinessException("Phone number is too short to verify.");
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
