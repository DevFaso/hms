package com.example.hms.model;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only audit row capturing one state-machine transition on a
 * {@link GeneralReferral}. One row is emitted per call to
 * {@code submit()/acknowledge()/schedule()/start()/complete()/cancel()/reject()/expire()}.
 *
 * <p>Stores the parent by raw UUID (no JPA association) so the audit
 * trail survives if a referral is hard-deleted, mirroring
 * {@code EncounterNoteHistory}.
 */
@Entity
@Table(name = "referral_events", indexes = {
    @Index(name = "idx_referral_events_referral", columnList = "referral_id, recorded_at"),
    @Index(name = "idx_referral_events_type", columnList = "event_type")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ReferralEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "referral_id", nullable = false)
    private UUID referralId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private ReferralEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ReferralStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ReferralStatus toStatus;

    @Column(name = "actor_username", length = 255)
    private String actorUsername;

    /** {@code USER} for an authenticated principal, {@code SYSTEM:<source>} otherwise. */
    @Column(name = "actor_label", nullable = false, length = 100)
    private String actorLabel;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}
