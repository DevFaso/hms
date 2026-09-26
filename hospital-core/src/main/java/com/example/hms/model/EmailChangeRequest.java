package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A user's pending change of their own email address, and this endpoint's
 * own wrong-password counter (V165). One row per user, reused across
 * requests.
 *
 * <p>{@code users.email} is only replaced once the code sent to
 * {@link #pendingEmail} comes back; until then the old address stays in
 * force. The code is kept as a password-encoder hash.
 *
 * <p>The password counter is deliberately not the login throttle: a stolen
 * session must not be able to lock the owner out of signing in by sending
 * wrong passwords here.
 */
@Entity
@Table(
    name = "email_change_requests",
    schema = "\"security\"",
    uniqueConstraints = @UniqueConstraint(name = "uq_email_change_user", columnNames = "user_id")
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class EmailChangeRequest extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The address waiting for its code; null when no change is pending. */
    @Column(name = "pending_email", length = 100)
    private String pendingEmail;

    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Column(name = "code_expires_at")
    private LocalDateTime codeExpiresAt;

    @Builder.Default
    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts = 0;

    @Builder.Default
    @Column(name = "password_failures", nullable = false)
    private int passwordFailures = 0;

    @Column(name = "password_window_started_at")
    private LocalDateTime passwordWindowStartedAt;

    @Column(name = "password_locked_until")
    private LocalDateTime passwordLockedUntil;

    /** Forget the pending change: the code is spent, expired or superseded. */
    public void clearPendingChange() {
        this.pendingEmail = null;
        this.codeHash = null;
        this.codeExpiresAt = null;
        this.codeAttempts = 0;
    }
}
