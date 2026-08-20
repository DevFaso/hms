package com.example.hms.model;

import com.example.hms.enums.PhoneOtpPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A pending SMS one-time-password challenge issued via IKODDI. Holds the opaque
 * IKODDI verification key (returned when the code was sent) alongside the
 * candidate phone number, so the user-entered code can later be verified
 * against the same dispatch. One row per send; consumed or expired rows are
 * inert. {@code verified} marks a successful confirm, and
 * {@code usedForRegistration} makes a verified challenge single-use when a
 * patient record is created against it.
 */
@Entity
@Table(
    name = "phone_otp_challenges",
    schema = "security",
    indexes = {
        @Index(name = "idx_potp_phone_purpose", columnList = "phone_number, purpose"),
        @Index(name = "idx_potp_requested_by", columnList = "requested_by_user_id")
    }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PhoneOtpChallenge extends BaseEntity {

    @Column(name = "phone_number", nullable = false, length = 30)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 50)
    private PhoneOtpPurpose purpose;

    /** Opaque IKODDI {@code otpToken} (verification key); can be long, so stored as text. */
    @Column(name = "verification_key", nullable = false, columnDefinition = "TEXT")
    private String verificationKey;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "used_for_registration", nullable = false)
    private boolean usedForRegistration;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Staff member who requested the code (the patient may not have an account yet). */
    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "hospital_id")
    private UUID hospitalId;
}
