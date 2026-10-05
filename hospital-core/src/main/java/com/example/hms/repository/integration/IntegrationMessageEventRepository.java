package com.example.hms.repository.integration;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP-c3 — repository for the Bridges-style message log. The search
 * surface is filterable on integration / status / time range; the
 * DLQ panel uses {@link #countByStatus} for the badge.
 */
@Repository
public interface IntegrationMessageEventRepository
    extends JpaRepository<IntegrationMessageEvent, UUID> {

    /**
     * Search query for the operator's message-trace UI. Every filter
     * is optional so the same query backs both "show me everything in
     * this window" and "show me failed FHIR claims for this org".
     */
    @Query("SELECT m FROM IntegrationMessageEvent m WHERE "
        + "(:integrationId IS NULL OR m.integrationId = :integrationId) AND "
        + "(:organizationId IS NULL OR m.organizationId = :organizationId) AND "
        + "(:status IS NULL OR m.status = :status) AND "
        + "(CAST(:fromDate AS LocalDateTime) IS NULL OR m.receivedAt >= :fromDate) AND "
        + "(CAST(:toDate AS LocalDateTime) IS NULL OR m.receivedAt <= :toDate) "
        + "ORDER BY m.receivedAt DESC")
    Page<IntegrationMessageEvent> search(
        @Param("integrationId") String integrationId,
        @Param("organizationId") UUID organizationId,
        @Param("status") IntegrationMessageStatus status,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable);

    /**
     * Raw status count — kept available for tests / per-status
     * dashboards. NOT what the DLQ badge uses; for the operator-
     * visible "still needs attention" count see
     * {@link #countUnresolvedDeadLetters()}.
     */
    long countByStatus(IntegrationMessageStatus status);

    /**
     * MVP-c3 follow-up — Bridges-style DLQ count. Returns the number
     * of {@code FAILED} rows that have no later attempt (same
     * {@code correlationId}, more recent {@code lastAttemptedAt}).
     * After an operator replays a failed message, the new replay row
     * supersedes the original so the DLQ badge ticks down even though
     * the original FAILED row is preserved for history.
     *
     * <p>Rows without a {@code correlationId} (legacy / pre-recorder)
     * are still counted as unresolved — the recorder always generates
     * one for new messages, so the only way to land here is via
     * direct DB inserts.
     */
    @Query("SELECT COUNT(m) FROM IntegrationMessageEvent m "
        + "WHERE m.status = com.example.hms.enums.integration.IntegrationMessageStatus.FAILED "
        + "AND NOT EXISTS ("
        + "  SELECT 1 FROM IntegrationMessageEvent later "
        + "  WHERE later.correlationId IS NOT NULL "
        + "  AND later.correlationId = m.correlationId "
        + "  AND later.lastAttemptedAt > m.lastAttemptedAt"
        + ")")
    long countUnresolvedDeadLetters();

    /**
     * The most recent row recorded under this correlation id within a
     * window, if there is one.
     *
     * <p>Used by {@code IntegrationMessageRecorder.recordRecurringFailure} to
     * fold a retry storm into the row it is a storm of, instead of inserting
     * a fresh row — and a fresh copy of the message — per attempt. The partial
     * index on {@code correlation_id} (V89) serves this.
     *
     * <p>The window is what keeps the fold from turning into amnesia. Without
     * one, a vendor whose January framing bug was diagnosed and cleared would
     * have a <em>different</em> June failure landing on the same reason
     * silently absorbed into the January row.
     *
     * <p>{@code status} is why this is not simply "the newest row":
     * {@code recordReplay} copies a row's correlation id onto the
     * {@code REPLAYED} row it writes, so a finder that ignored status would
     * let the vendor's next retry fold into the operator's replay — rewriting
     * an audit row with a different message's body and, because
     * {@code countUnresolvedDeadLetters} counts only {@code FAILED}, leaving
     * the badge at zero for a feed that is still failing. Callers pass
     * {@code FAILED}.
     */
    Optional<IntegrationMessageEvent>
        findFirstByCorrelationIdAndStatusAndReceivedAtAfterOrderByReceivedAtDesc(
            String correlationId, IntegrationMessageStatus status, LocalDateTime after);

    /**
     * Ids of rows whose message content is due for erasure, oldest first: a
     * body still held, never purged, and past {@code cutoff} by the rule for
     * its status.
     *
     * <ul>
     *   <li>Anything but {@code FAILED}: received before the cutoff.</li>
     *   <li>{@code FAILED}: only once <em>resolved</em>, and resolved before
     *   the cutoff. Resolved means what {@link #countUnresolvedDeadLetters}
     *   means - a later row shares its correlation id - so a dead letter the
     *   badge still counts keeps its body and stays replayable however old it
     *   is, and the clock starts at the first superseding attempt, not at
     *   receipt. A {@code FAILED} row with no correlation id is never resolved
     *   and is never purged.</li>
     * </ul>
     *
     * <p>The partial index on rows holding a body (V177) serves the scan;
     * the correlation index (V89) serves the {@code EXISTS}.
     */
    @Query("SELECT m.id FROM IntegrationMessageEvent m "
        + "WHERE m.payload IS NOT NULL AND m.payloadPurgedAt IS NULL "
        + "AND m.receivedAt < :cutoff "
        + "AND (m.status <> com.example.hms.enums.integration.IntegrationMessageStatus.FAILED "
        + "  OR EXISTS ("
        + "    SELECT 1 FROM IntegrationMessageEvent later "
        + "    WHERE later.correlationId IS NOT NULL "
        + "    AND later.correlationId = m.correlationId "
        + "    AND later.lastAttemptedAt > m.lastAttemptedAt "
        + "    AND later.lastAttemptedAt < :cutoff"
        + "  )"
        + ") "
        + "ORDER BY m.receivedAt ASC")
    List<UUID> findPayloadPurgeCandidateIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * Erase the content of the given rows and stamp when. Re-checks the whole
     * eligibility rule of {@link #findPayloadPurgeCandidateIds} rather than
     * trusting the ids, so a row that changed between the read and this write
     * is left alone, and a second instance or a rerun purging the same ids
     * updates nothing: the statement is idempotent. Returns the rows actually
     * purged.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IntegrationMessageEvent m "
        + "SET m.payload = NULL, m.payloadPurgedAt = :purgedAt "
        + "WHERE m.id IN :ids "
        + "AND m.payload IS NOT NULL AND m.payloadPurgedAt IS NULL "
        + "AND m.receivedAt < :cutoff "
        + "AND (m.status <> com.example.hms.enums.integration.IntegrationMessageStatus.FAILED "
        + "  OR EXISTS ("
        + "    SELECT 1 FROM IntegrationMessageEvent later "
        + "    WHERE later.correlationId IS NOT NULL "
        + "    AND later.correlationId = m.correlationId "
        + "    AND later.lastAttemptedAt > m.lastAttemptedAt "
        + "    AND later.lastAttemptedAt < :cutoff"
        + "  )"
        + ")")
    int purgePayloads(
        @Param("ids") Collection<UUID> ids,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("purgedAt") LocalDateTime purgedAt);
}
