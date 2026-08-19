package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * MVP-7: Singleton config row holding the platform-wide minimum-iat
 * timestamp the JWT filter compares against. Force-logout-all bumps this
 * value to {@code Instant.now()}; every token issued before the bump
 * fails the {@code iat >= globalMinTokenIat} check in
 * {@link com.example.hms.security.JwtAuthenticationFilter}.
 *
 * <p>Singleton-table pattern — one row, id=1, enforced by a DB CHECK
 * constraint (see V80 migration).
 */
@Entity
@Table(name = "security_revocations", schema = "security")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SecurityRevocation {

    public static final Integer SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "global_min_token_iat", nullable = false)
    private Instant globalMinTokenIat;

    @Column(name = "last_revoked_by_user_id")
    private UUID lastRevokedByUserId;

    @Column(name = "last_revoked_by_username", length = 255)
    private String lastRevokedByUsername;

    @Column(name = "last_revoked_reason", length = 1000)
    private String lastRevokedReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
