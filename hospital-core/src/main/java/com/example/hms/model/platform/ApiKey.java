package com.example.hms.model.platform;

import com.example.hms.enums.platform.ApiKeyStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One API key HMS issued to a third-party client (Tier 2 item 45).
 *
 * <p>The raw key is NEVER stored: {@code keyHash} is hex(SHA-256(raw)) —
 * the {@code PasswordResetToken} precedent — and {@code keyPrefix} keeps
 * the first characters so an admin can match a key in hand to a row
 * without the row being able to reproduce it. Revoked, never deleted.
 *
 * <p>{@code expiresOn} is nullable on purpose (the V145 lesson: an expiry
 * is the issuer's choice, never mandatory).
 */
@Entity
@Table(
    name = "api_keys",
    schema = "platform",
    indexes = @Index(name = "idx_api_keys_hospital", columnList = "hospital_id, status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"hospital", "keyHash"})
public class ApiKey extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_api_key_hospital"))
    private Hospital hospital;

    /** Who this key is for — "Mutuelle X claims system", not a person. */
    @Size(max = 120)
    @Column(name = "label", nullable = false, length = 120)
    private String label;

    /** The first characters of the raw key, for display-matching only. */
    @Size(max = 16)
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** hex(SHA-256(raw key)) — the only stored form of the credential. */
    @Size(max = 64)
    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    /** Optional — a key without one lives until revoked. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Best-effort, throttled — a liveness signal, not an access log. */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * Optimistic lock (the #549 lesson): a concurrent rotate/revoke pair
     * must not silently double-issue — the loser gets a retryable refusal.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
