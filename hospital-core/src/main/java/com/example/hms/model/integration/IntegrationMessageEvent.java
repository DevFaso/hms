package com.example.hms.model.integration;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 *
 * <p>So does the MLLP dispatcher, and it matters for reading a row. When a
 * sender retries something we refused,
 * {@code IntegrationMessageRecorder.recordRecurringFailure} folds the retry
 * into the existing row rather than inserting another: {@code attemptCount}
 * goes up and {@code receivedAt}, {@code lastAttemptedAt},
 * {@code messageType}, {@code errorMessage}, {@code organizationId} and
 * {@code payload} are all refreshed to the latest occurrence. Otherwise a
 * vendor retrying on a timer would write thousands of rows a day — each
 * holding a full copy of the message — into a table that keeps each body for months. Two
 * consequences worth knowing at the surface: on such a row
 * {@code attemptCount} mixes the sender's retries with any operator replays,
 * and {@code receivedAt} is when the problem was last seen rather than when
 * it first arrived. That is deliberate — the operator-facing search orders
 * and filters on {@code receivedAt}, and a row frozen at the first attempt
 * would drop out of "what is failing now" while still being counted.
 *
 * <p>The table now has a retention policy for content, not for rows: see
 * {@link #payloadPurgedAt}.
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

    /**
     * Truncated to 64 KB at the recorder; stored as TEXT, encrypted at rest.
     *
     * <p>It holds raw partner traffic: an unparseable HL7 message is recorded
     * whole, PID and all, because the body is the only diagnostic there is -
     * so the answer is encryption (and retention), not deletion. Nothing
     * compares this column's value in SQL or JPQL (the search, the dead-letter
     * count and the recurring-failure fold key on other columns; the retention
     * sweep only asks whether it is null), which is what makes a ciphertext
     * column safe here. Legacy plaintext rows are encrypted at startup by
     * {@code PhiTextEncryptionBackfill}.
     *
     * <p>Retention: {@code IntegrationMessageRetentionService} sets this to
     * null once the content is past {@code hms.integration.retention.payload-days}
     * and stamps {@link #payloadPurgedAt}; the row itself is kept.
     */
    @Column(name = "payload", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String payload;

    /**
     * When the retention sweep erased {@link #payload} (V177). Null while the
     * content is held, and on rows that never had any - an inbound service's
     * refusal records a reason, not a body - which is why a null payload
     * alone cannot tell the operator page "purged" from "never stored". A
     * purged row cannot be replayed.
     */
    @Column(name = "payload_purged_at")
    private LocalDateTime payloadPurgedAt;

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
