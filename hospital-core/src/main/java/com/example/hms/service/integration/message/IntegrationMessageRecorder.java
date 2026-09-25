package com.example.hms.service.integration.message;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-c3 — Bridges-style per-message recorder. Sibling of
 * {@link com.example.hms.service.integration.health.IntegrationHealthRecorder}:
 * the health recorder captures *probe* outcomes (one row per "is this
 * integration healthy right now?"); this one captures *messages*
 * (one row per actual partner-protocol payload that crossed the wire).
 *
 * <p>All public methods run in {@link Propagation#REQUIRES_NEW} so a
 * caller's transaction can roll back without losing the trace, and they
 * never throw — a bug in the recorder must not fail the partner-side
 * write that triggered it. PHI in the payload is truncated at
 * {@link #MAX_PAYLOAD_CHARS}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationMessageRecorder {

    /** Hard cap so a 10 MB FHIR Bundle can't blow up the audit table. */
    static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    /** {@code correlation_id} is {@code VARCHAR(120)} (V89). */
    static final int MAX_CORRELATION_ID_CHARS = 120;
    /**
     * {@code integration_id} is {@code VARCHAR(120) NOT NULL} (V89). Callers
     * that build it through {@code MllpRecordingContext} are already bounded,
     * but the guard belongs here too: this is the column whose overflow drops
     * a row silently, and a caller that has not been migrated to the helper
     * — there is still one — would otherwise hit exactly that.
     */
    static final int MAX_INTEGRATION_ID_CHARS = 120;
    /**
     * {@code message_type} is {@code VARCHAR(64)} (V89), and on the MLLP
     * paths it is MSH-9 as the sender wrote it — unvalidated, and HL7 v2
     * allows far more than 64 characters there. Truncate rather than let the
     * insert throw: this recorder swallows its own failures by design, so an
     * over-long value would silently drop the row instead of recording it.
     */
    static final int MAX_MESSAGE_TYPE_CHARS = 64;
    /**
     * How long one stored body serves for a repeating problem. Long enough
     * that a retry timer cannot multiply the copies, short enough that a
     * problem which comes back after being cleared brings its own evidence.
     */
    static final Duration BODY_DEDUPE_WINDOW = Duration.ofHours(24);
    private static final int MAX_ERROR_CHARS = 2_000;

    private final IntegrationMessageEventRepository repository;

    /**
     * Record an outbound or inbound message as it crossed the wire.
     * Returns the persisted entity so the caller (e.g. a synchronous
     * partner adapter) can capture the {@code correlationId} and pass
     * it through downstream logs. Returns {@code null} on persistence
     * failure — the caller does not need to special-case it.
     *
     * <p>Sonar review fix — renamed from {@code record} to
     * {@code recordMessage} so it does not collide with Java 21's
     * contextual {@code record} keyword.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordMessage(
        String integrationId,
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        String payload,
        IntegrationMessageStatus status,
        String errorMessage
    ) {
        return recordMessage(integrationId, organizationId, direction, messageType,
            payload, status, errorMessage, null);
    }

    /**
     * As above, but with the caller choosing the {@code correlationId}.
     *
     * <p>Added for a specific problem, and deliberately additive: the
     * seven-argument form above is unchanged and still mints a fresh random
     * id, so every existing caller behaves exactly as it did.
     *
     * <p>The reason a caller would want to choose one is
     * {@code IntegrationMessageEventRepository.countUnresolvedDeadLetters},
     * which counts a {@code FAILED} row only when no <em>later</em> row shares
     * its {@code correlationId}. A refusal that an HL7 sender retries on a
     * timer therefore accumulates one unresolved dead letter per retry when
     * each row gets a random id — thousands a day for a single misconfigured
     * feed, burying the refusals an operator has not seen yet. Passing a
     * <b>stable</b> id derived from what is actually wrong (the sender, the
     * message type, the reason — never anything per-message) makes each retry
     * supersede the last, so the badge shows one dead letter per real problem
     * and it clears when the problem stops recurring.
     *
     * <p>{@code correlationId} is not unique in the schema and replay flows
     * already reuse one across rows by design (V89), so this is the column
     * working as intended rather than a new contract. A null id keeps the
     * random-per-row behaviour.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordMessage(
        String integrationId,
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        String payload,
        IntegrationMessageStatus status,
        String errorMessage,
        String correlationId
    ) {
        try {
            String resolvedCorrelationId = correlationId == null
                ? UUID.randomUUID().toString()
                : truncate(correlationId, MAX_CORRELATION_ID_CHARS);
            IntegrationMessageEvent event = IntegrationMessageEvent.builder()
                .integrationId(truncate(integrationId, MAX_INTEGRATION_ID_CHARS))
                .organizationId(organizationId)
                .direction(direction)
                .messageType(truncate(messageType, MAX_MESSAGE_TYPE_CHARS))
                .correlationId(resolvedCorrelationId)
                .payload(truncate(payload, MAX_PAYLOAD_CHARS))
                .status(status)
                .errorMessage(truncate(errorMessage, MAX_ERROR_CHARS))
                .attemptCount(1)
                .lastAttemptedAt(LocalDateTime.now())
                .receivedAt(LocalDateTime.now())
                .build();
            return repository.save(event);
        } catch (RuntimeException ex) {
            // Best-effort — same posture as IntegrationHealthRecorder.
            log.error("[INTEGRATION-MESSAGE] Failed to record message for {}", integrationId, ex);
            return null;
        }
    }

    /**
     * Record a rejection that a sender will keep retrying, storing the
     * message body <b>only the first time</b> this correlation id is seen.
     *
     * <p>A stable correlation id keeps a retry storm to one <em>counted</em>
     * dead letter, because {@code countUnresolvedDeadLetters} discounts a row
     * once a later one shares its id. It does nothing about what is stored:
     * {@link #recordMessage} inserts on every call, so a vendor retrying an
     * unparseable message every thirty seconds would write thousands of full
     * copies of it a day — PID and all — into a table with no retention,
     * while the badge read 1. A bounded badge over unbounded PHI is worse
     * than the visible version, because it says the problem is handled.
     *
     * <p>So: first occurrence keeps the body, which is the evidence an
     * operator needs for a message nobody could parse; every later occurrence
     * records the same reason with no payload. The attempt trail stays, the
     * badge stays at one entry, and the stored bodies are bounded by the
     * number of distinct problems rather than by the sender's retry timer.
     *
     * <p>"First" means first within {@link #BODY_DEDUPE_WINDOW}, not first
     * ever. An all-history check would bound the storage and lose the
     * evidence: a vendor whose framing bug was diagnosed and cleared in
     * January, shipping a different failure in June that lands on the same
     * reason, would leave an operator a dead letter with nothing to look at.
     * One body per problem per day is bounded and still diagnosable.
     *
     * <p>Two honest limits. Rows are still one per attempt — small ones now,
     * but the table still grows, and retention remains an open question for
     * whoever owns this surface. And the check is a read followed by a write
     * with no lock, so two retries racing inside the same instant can both
     * store a body; bounded by the concurrency, not by the retry count, which
     * is the point.
     *
     * <p>A null {@code correlationId} means there is nothing to deduplicate
     * against and the payload is stored as normal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordRecurringFailure(
        String integrationId,
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        String payload,
        String errorMessage,
        String correlationId
    ) {
        String payloadToStore = payload;
        if (correlationId != null && payload != null) {
            try {
                if (repository.existsByCorrelationIdAndReceivedAtAfter(
                        correlationId, LocalDateTime.now().minus(BODY_DEDUPE_WINDOW))) {
                    payloadToStore = null;
                }
            } catch (RuntimeException ex) {
                // Best-effort like the rest of this class. Keeping the body on
                // a failed check is the safe direction: an extra copy beats
                // losing the only one.
                log.warn("[INTEGRATION-MESSAGE] Could not check for an earlier occurrence of {}",
                    correlationId, ex);
            }
        }
        return recordMessage(integrationId, organizationId, direction, messageType,
            payloadToStore, IntegrationMessageStatus.FAILED, errorMessage, correlationId);
    }

    /**
     * Mark a previously-FAILED message as replayed. The same
     * {@code correlationId} is preserved so an operator can read the
     * full retry history; {@code attemptCount} is incremented so a
     * UI counter reflects how many times we've tried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IntegrationMessageEvent recordReplay(
        UUID originalMessageId,
        IntegrationMessageStatus newStatus,
        String errorMessage
    ) {
        try {
            return repository.findById(originalMessageId)
                .map(original -> persistReplay(original, newStatus, errorMessage))
                .orElse(null);
        } catch (RuntimeException ex) {
            log.error("[INTEGRATION-MESSAGE] Failed to record replay for {}", originalMessageId, ex);
            return null;
        }
    }

    private IntegrationMessageEvent persistReplay(
        IntegrationMessageEvent original,
        IntegrationMessageStatus newStatus,
        String errorMessage
    ) {
        IntegrationMessageEvent replay = IntegrationMessageEvent.builder()
            .integrationId(original.getIntegrationId())
            .organizationId(original.getOrganizationId())
            .direction(original.getDirection())
            .messageType(original.getMessageType())
            .correlationId(original.getCorrelationId())
            .payload(original.getPayload())
            .status(newStatus)
            .errorMessage(truncate(errorMessage, MAX_ERROR_CHARS))
            .attemptCount(safeIncrement(original.getAttemptCount()))
            .lastAttemptedAt(LocalDateTime.now())
            .receivedAt(LocalDateTime.now())
            .build();
        return repository.save(replay);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int safeIncrement(Integer current) {
        return current == null ? 2 : current + 1;
    }
}
