package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "password_reset_tokens",
    schema = "\"security\"",
    uniqueConstraints = {@UniqueConstraint(name = "uq_prt_token_hash", columnNames = "token_hash")},
    indexes = {
        @Index(name = "idx_prt_user", columnList = "user_id"),
        @Index(name = "idx_prt_expiration", columnList = "expiration"),
        @Index(name = "idx_prt_consumed", columnList = "consumed_at")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = "user")
public class PasswordResetToken extends BaseEntity {

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash; // hex(SHA-256(rawToken))

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_prt_user"))
    private User user;

    @Column(nullable = false)
    private LocalDateTime expiration;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public boolean isExpired() { return LocalDateTime.now().isAfter(expiration); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isValid() { return !isExpired() && !isConsumed(); }

    @PrePersist
    private void defaultTtl() {
        if (expiration == null) expiration = LocalDateTime.now().plusHours(1);
    }
}
