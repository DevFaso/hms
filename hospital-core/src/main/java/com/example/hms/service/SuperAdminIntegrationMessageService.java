package com.example.hms.service;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MVP-c3 — Bridges-style search + replay surface for the per-message
 * log. All endpoints are super-admin-only; the underlying recorder
 * captures every partner-protocol payload that crossed the wire.
 */
public interface SuperAdminIntegrationMessageService {

    /**
     * Filterable search across the full message log. Every parameter
     * is optional — passing null on each yields an unfiltered descending
     * timeline. The dead-letter count is included on every response so
     * the UI badge stays consistent with the search result.
     *
     * <p><b>Important:</b> the {@code payload} field on each row is
     * elided from the search response so a deep page can't return
     * hundreds of MB of envelope data. Fetch the full row (including
     * payload) via {@link #getById(UUID)} when an operator drills in.
     */
    IntegrationMessagePageDTO search(
        String integrationId,
        UUID organizationId,
        IntegrationMessageStatus status,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable);

    /**
     * Fetch a single message including the full payload. Used by the
     * UI's row-detail drawer when the operator clicks into a row in
     * the search list.
     */
    IntegrationMessageEventDTO getById(UUID messageId);

    /**
     * Replay a previously-FAILED message. The replay row reuses the
     * same correlation id as the original and increments
     * {@code attemptCount}, so an operator following the search by
     * correlation id can trace the full retry history. Throws
     * {@link com.example.hms.exception.ResourceNotFoundException} if
     * the original is gone.
     */
    IntegrationMessageEventDTO replay(UUID originalMessageId);
}
