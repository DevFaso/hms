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
        + "(:fromDate IS NULL OR m.receivedAt >= :fromDate) AND "
        + "(:toDate IS NULL OR m.receivedAt <= :toDate) "
        + "ORDER BY m.receivedAt DESC")
    Page<IntegrationMessageEvent> search(
        @Param("integrationId") String integrationId,
        @Param("organizationId") UUID organizationId,
        @Param("status") IntegrationMessageStatus status,
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable);

    /** DLQ badge — number of FAILED messages still awaiting attention. */
    long countByStatus(IntegrationMessageStatus status);
}
