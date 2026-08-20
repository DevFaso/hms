package com.example.hms.service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * SMS one-time-password verification of a patient's phone number at the
 * registration desk. The OTP is generated and checked by IKODDI (OTP-as-a-
 * service); locally we persist only the challenge metadata and the opaque
 * verification key — codes never touch our database.
 */
public interface PhoneVerificationService {

    /** True when the IKODDI integration is enabled and fully configured. */
    boolean isAvailable();

    /**
     * Dispatch a verification code to {@code rawPhone}. Any still-active
     * challenge for the same number is invalidated first.
     */
    ChallengeView requestRegistrationVerification(String rawPhone, UUID requestedByUserId, UUID hospitalId);

    /** Verify the code the patient read back; marks the challenge verified. */
    ChallengeView confirmRegistrationVerification(UUID challengeId, String code, UUID requestedByUserId);

    /**
     * Single-use claim of a verified challenge when the patient record is
     * created. True only when the challenge is verified, unclaimed, and was
     * issued for {@code rawPhone}.
     */
    boolean consumeVerifiedChallenge(UUID challengeId, String rawPhone);

    record ChallengeView(UUID challengeId, String maskedPhone, LocalDateTime expiresAt, boolean verified) {}
}
