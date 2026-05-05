package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationMessageStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        long deadLetterCount = repository.countByStatus(IntegrationMessageStatus.FAILED);
        List<IntegrationMessageEventDTO> content = page.getContent().stream()
            .map(SuperAdminIntegrationMessageServiceImpl::toDto)
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
    @Transactional
    public IntegrationMessageEventDTO replay(UUID originalMessageId) {
        // Existence check up front — the recorder swallows
        // not-found / persistence errors as a "best effort" recorder
        // posture, so we need to distinguish "you asked for an id we
        // don't have" (404 to the operator) from "we found it but
        // couldn't persist the replay row" (500 / clear error).
        if (!repository.existsById(originalMessageId)) {
            throw new ResourceNotFoundException(
                "Integration message not found: " + originalMessageId);
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
            throw new ResourceNotFoundException(
                "Replay row could not be persisted for message: " + originalMessageId);
        }
        return toDto(replay);
    }

    static IntegrationMessageEventDTO toDto(IntegrationMessageEvent event) {
        return IntegrationMessageEventDTO.builder()
            .id(event.getId())
            .integrationId(event.getIntegrationId())
            .organizationId(event.getOrganizationId())
            .direction(event.getDirection())
            .messageType(event.getMessageType())
            .correlationId(event.getCorrelationId())
            .payload(event.getPayload())
            .status(event.getStatus())
            .errorMessage(event.getErrorMessage())
            .attemptCount(event.getAttemptCount())
            .lastAttemptedAt(event.getLastAttemptedAt())
            .receivedAt(event.getReceivedAt())
            .build();
    }
}
