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
import java.util.Optional;
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
     * How long one row serves for a repeating problem. Long enough that a
     * retry timer cannot multiply the copies, short enough that a problem
     * which comes back after being cleared brings its own row and its own
     * evidence.
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
     * Record a rejection that a sender will keep retrying, <b>folding the
     * retry into the row it is a retry of</b> rather than inserting a new one.
     *
     * <p>A stable correlation id alone keeps a retry storm to one
     * <em>counted</em> dead letter, because
     * {@code countUnresolvedDeadLetters} discounts a row once a later one
     * shares its id. It does nothing about what is stored: {@link
     * #recordMessage} inserts on every call, so a vendor retrying an
     * unparseable message every thirty seconds writes thousands of full
     * copies of it a day — PID and all — into a table with no retention,
     * while the badge reads 1. A bounded badge over unbounded PHI is worse
     * than the visible version, because it says the problem is handled.
     *
     * <p>Storing the body on the <em>first</em> occurrence and nothing after
     * was the first attempt at that and was worse than it looked: the count
     * surfaces the <em>newest</em> row per id, so the one dead letter an
     * operator is pointed at would be precisely the one with no payload, and
     * the row holding the message would be buried under every retry since.
     * The two mechanisms pulled in opposite directions.
     *
     * <p>So a repeat within {@link #BODY_DEDUPE_WINDOW} updates the existing
     * row in place: {@code attemptCount} goes up, {@code lastAttemptedAt} and
     * the reason are refreshed, and the payload is replaced by the latest
     * one. One row per problem per window, it is the row the badge counts, it
     * holds a body, and that body is current rather than a day stale. Rows,
     * stored bodies and counted dead letters are all bounded by the number of
     * distinct problems instead of by the sender's retry timer.
     *
     * <p>The window keeps the fold from becoming amnesia: a vendor whose
     * framing bug was diagnosed and cleared in January, shipping a different
     * failure in June that lands on the same reason, gets a new row rather
     * than a quiet increment on the old one.
     *
     * <p><b>Not transactional, deliberately</b>, unlike everything else here.
     * Its caller is the MLLP dispatcher, which has no transaction of its own,
     * so each repository call takes its own — which is what makes the lookup
     * safe to fail. Inside a {@code REQUIRES_NEW} of its own, a lookup that
     * threw would mark the transaction rollback-only and take the row and its
     * body down with it, on the one path where the body is the only evidence
     * there is. Do not call this from inside a transaction that must not see
     * these writes.
     *
     * <p>The read and the write are not atomic, so two retries arriving
     * together can both insert; bounded by the concurrency rather than by the
     * retry count, which is the point. A null {@code correlationId} means
     * there is nothing to fold into and the row is inserted as normal.
     */
    public IntegrationMessageEvent recordRecurringFailure(
        String integrationId,
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        String payload,
        String errorMessage,
        String correlationId
    ) {
        if (correlationId != null) {
            try {
                Optional<IntegrationMessageEvent> earlier = repository
                    .findFirstByCorrelationIdAndReceivedAtAfterOrderByReceivedAtDesc(
                        correlationId, LocalDateTime.now().minus(BODY_DEDUPE_WINDOW));
                if (earlier.isPresent()) {
                    return foldIntoExisting(earlier.get(), payload, errorMessage);
                }
            } catch (RuntimeException ex) {
                // Best-effort like the rest of this class, and the safe
                // direction is to write a fresh row: an extra copy beats
                // losing the only one.
                log.warn("[INTEGRATION-MESSAGE] Could not fold a recurrence of {}; "
                    + "recording it as a new row", correlationId, ex);
            }
        }
        return recordMessage(integrationId, organizationId, direction, messageType,
            payload, IntegrationMessageStatus.FAILED, errorMessage, correlationId);
    }

    /**
     * One more attempt at a problem already on the board.
     *
     * <p>No {@code @Transactional}, and not by omission: this is reached by
     * self-invocation from {@link #recordRecurringFailure}, where an
     * annotation would be silently inert anyway. The row was read in its own
     * transaction and is detached, so the save is a merge that takes a
     * transaction of its own — which is what keeps a failure here from
     * poisoning anything the caller is doing.
     *
     * <p>Never throws: a failure is logged and the caller gets null, exactly
     * as a failed insert does. Private, so nothing else can come to depend on
     * the propagation it does not have.
     */
    private IntegrationMessageEvent foldIntoExisting(
        IntegrationMessageEvent existing, String payload, String errorMessage) {
        try {
            existing.setAttemptCount(existing.getAttemptCount() + 1);
            existing.setLastAttemptedAt(LocalDateTime.now());
            existing.setErrorMessage(truncate(errorMessage, MAX_ERROR_CHARS));
            if (payload != null) {
                existing.setPayload(truncate(payload, MAX_PAYLOAD_CHARS));
            }
            return repository.save(existing);
        } catch (RuntimeException ex) {
            log.error("[INTEGRATION-MESSAGE] Failed to fold a recurrence into {}",
                existing.getId(), ex);
            return null;
        }
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
