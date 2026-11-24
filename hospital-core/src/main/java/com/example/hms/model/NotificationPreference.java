package com.example.hms.model;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
/**
 * Per-user notification delivery preference.
 * One row per (user, notificationType, channel) combination.
 */
@Entity
@Table(name = "notification_preferences", schema = "security",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notif_pref_user_type_channel",
                columnNames = {"user_id", "notification_type", "channel"}),
        indexes = @Index(name = "idx_notif_pref_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@lombok.Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class NotificationPreference extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Who recorded the current choice; null only for legacy rows. */
    @Column(name = "recorded_by_user_id")
    private java.util.UUID recordedByUserId;

    @Column(name = "recorded_at")
    private java.time.LocalDateTime recordedAt;

    /** The choice and its provenance are one write, for desk and self-service alike. */
    public void recordChoice(boolean value, java.util.UUID actorId, java.time.LocalDateTime at) {
        this.enabled = value;
        this.recordedByUserId = java.util.Objects.requireNonNull(actorId, "recorder");
        this.recordedAt = java.util.Objects.requireNonNull(at, "recordedAt");
    }
}
