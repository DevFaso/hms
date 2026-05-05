package com.example.hms.model.integration;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
 * MVP-c3 — Bridges-style per-message log for partner-protocol traffic
 * (HL7 / FHIR / X12 / proprietary REST). Distinct from
 * {@link IntegrationHealthEvent}: that table records *probe* outcomes
 * (one row per "did the integration work right now?"); this one records
 * *messages* (one row per actual payload that crossed the wire).
 *
 * <p>The status column drives the DLQ surface — {@code FAILED} rows
 * are searchable + replayable by an operator. {@code attemptCount}
 * tracks how many times a message has been retried; the replay endpoint
 * increments it on each retry attempt.
 */
@Entity
@Table(name = "integration_message_event", schema = "clinical")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class IntegrationMessageEvent {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "integration_id", nullable = false, length = 120)
    private String integrationId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private IntegrationMessageDirection direction;

    /** Free-text message-type marker (e.g. "ADT^A04", "FHIR/Bundle", "X12/837"). */
    @Column(name = "message_type", length = 64)
    private String messageType;

    /**
     * Operator-facing tracer for grouping retries / replays. The
     * recorder fills this with a fresh UUID for every original
     * message; the replay endpoint reuses the same value on every
     * attempt so an operator can follow the lifecycle in the search
     * tab.
     */
    @Column(name = "correlation_id", length = 120)
    private String correlationId;

    /** Truncated to 64 KB at the recorder; stored as TEXT so PHI fits. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IntegrationMessageStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_attempted_at", nullable = false)
    private LocalDateTime lastAttemptedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    private void touch() {
        LocalDateTime now = LocalDateTime.now();
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (lastAttemptedAt == null) {
            lastAttemptedAt = now;
        }
        if (attemptCount == null) {
            attemptCount = 1;
        }
    }
}
