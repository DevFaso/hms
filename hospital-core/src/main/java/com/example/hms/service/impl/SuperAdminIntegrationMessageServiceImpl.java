package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.SuperAdminIntegrationMessageService;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuperAdminIntegrationMessageServiceImpl implements SuperAdminIntegrationMessageService {

    private final IntegrationMessageEventRepository repository;
    private final IntegrationMessageRecorder recorder;

    @Override
    public IntegrationMessagePageDTO search(
        String integrationId,
        UUID organizationId,
        IntegrationMessageStatus status,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable
    ) {
        Page<IntegrationMessageEvent> page = repository.search(
            integrationId, organizationId, status, fromDate, toDate, pageable);
        // Copilot review fix — the DLQ badge counts FAILED rows that
        // have NOT been superseded by a later attempt. The naive
        // countByStatus(FAILED) never decreased after a replay because
        // we preserve the original row for history; the new query
        // excludes correlationIds with a more recent attempt.
        long deadLetterCount = repository.countUnresolvedDeadLetters();
        // Copilot review fix — the search response strips the payload
        // so a 200-row page can't return a quarter-GB of envelope
        // data. Operators pull the full payload via the row-detail
        // endpoint (getById).
        List<IntegrationMessageEventDTO> content = page.getContent().stream()
            .map(SuperAdminIntegrationMessageServiceImpl::toSummaryDto)
            .toList();
        return IntegrationMessagePageDTO.builder()
            .content(content)
            .pageNumber(page.getNumber())
            .pageSize(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .deadLetterCount(deadLetterCount)
            .build();
    }

    @Override
    public IntegrationMessageEventDTO getById(UUID messageId) {
        IntegrationMessageEvent event = repository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Integration message not found: " + messageId));
        return toDto(event);
    }

    @Override
    @Transactional
    public IntegrationMessageEventDTO replay(UUID originalMessageId) {
        // Look the original up directly so we can both 404 on a missing
        // id and 409 on a replay-against-non-FAILED. Replaces the prior
        // existsById round-trip; the lookup is also needed for the
        // status precondition.
        IntegrationMessageEvent original = repository.findById(originalMessageId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Integration message not found: " + originalMessageId));

        // Copilot review fix — only FAILED rows are eligible for replay.
        // Replaying a SENT/RECEIVED row would emit a duplicate to the
        // partner (when real connectors land) and pollute the audit
        // trail; replaying an already-REPLAYED row is a no-op the
        // operator should know about.
        if (original.getStatus() != IntegrationMessageStatus.FAILED) {
            throw new ConflictException(
                "Integration message " + originalMessageId + " is not in FAILED state "
                    + "(current: " + original.getStatus() + "); only FAILED messages can be replayed.");
        }

        // Today the replay is symbolic — we record a REPLAYED row so
        // the operator-visible history reflects the retry attempt. The
        // actual partner re-emit lives in adapter-specific paths (the
        // partner-connector SPI hook can be added per-protocol once
        // sandbox credentials land); the operator's UI flow is in
        // place now so no test data is lost.
        IntegrationMessageEvent replay = recorder.recordReplay(
            originalMessageId, IntegrationMessageStatus.REPLAYED, null);
        if (replay == null) {
            // Copilot review fix — persistence failures inside the
            // recorder must surface as 500 instead of 404. The recorder
            // is best-effort and returns null when the underlying save
            // throws, so we translate the null back into an
            // INTERNAL_SERVER_ERROR for monitoring and operator UI.
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Replay row could not be persisted for message: " + originalMessageId);
        }
        return toDto(replay);
    }

    static IntegrationMessageEventDTO toDto(IntegrationMessageEvent event) {
        return mapBase(event).payload(event.getPayload()).build();
    }

    /**
     * Search-list variant: same shape as {@link #toDto} minus the
     * payload, which would otherwise let a 200-row page return up to
     * ~12 MB of envelope data per request. Operators fetch the full
     * payload via the row-detail endpoint.
     */
    static IntegrationMessageEventDTO toSummaryDto(IntegrationMessageEvent event) {
        return mapBase(event).payload(null).build();
    }

    private static IntegrationMessageEventDTO.IntegrationMessageEventDTOBuilder mapBase(
        IntegrationMessageEvent event
    ) {
        return IntegrationMessageEventDTO.builder()
            .id(event.getId())
            .integrationId(event.getIntegrationId())
            .organizationId(event.getOrganizationId())
            .direction(event.getDirection())
            .messageType(event.getMessageType())
            .correlationId(event.getCorrelationId())
            .status(event.getStatus())
            .errorMessage(event.getErrorMessage())
            .attemptCount(event.getAttemptCount())
            .lastAttemptedAt(event.getLastAttemptedAt())
            .receivedAt(event.getReceivedAt());
    }
}
