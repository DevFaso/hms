package com.example.hms.repository.integration;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
     * Whether anything has been recorded under this correlation id
     * <em>recently</em>.
     *
     * <p>Used by {@code IntegrationMessageRecorder} to store a rejected
     * message's body once per problem per window instead of once per retry:
     * the first occurrence in the window carries the payload, the retries
     * behind it carry only the reason. The partial index on
     * {@code correlation_id} (V89) serves this.
     *
     * <p>The window is what keeps the bound from turning into amnesia. An
     * all-history check would mean that a vendor whose January framing bug
     * was diagnosed and cleared gets no body stored for a <em>different</em>
     * June failure that lands on the same reason — an operator left with a
     * dead letter and nothing to look at.
     */
    boolean existsByCorrelationIdAndReceivedAtAfter(String correlationId, LocalDateTime after);
}
