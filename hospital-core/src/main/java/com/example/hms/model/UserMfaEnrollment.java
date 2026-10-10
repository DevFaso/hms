package com.example.hms.model;

import com.example.hms.enums.MfaMethodType;
import com.example.hms.security.crypto.TotpSecretEncryptor;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_mfa_enrollments", schema = "\"security\"")
public class UserMfaEnrollment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 50)
    private MfaMethodType method;

    @Column(name = "channel", length = 120)
    private String channel;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "primary_factor", nullable = false)
    private boolean primaryFactor = false;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Convert(converter = TotpSecretEncryptor.class)
    @Column(name = "totp_secret", length = 512)
    private String totpSecret;

    /**
     * A replacement authenticator that has been started but not confirmed
     * (V183). {@link #totpSecret} stays the current one, and keeps verifying,
     * until verify-enrollment confirms a code from this one and swaps them.
     */
    @Convert(converter = TotpSecretEncryptor.class)
    @Column(name = "pending_totp_secret", length = 512)
    private String pendingTotpSecret;

    @Builder.Default
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Column(name = "metadata_json")
    private String metadataJson;
}
