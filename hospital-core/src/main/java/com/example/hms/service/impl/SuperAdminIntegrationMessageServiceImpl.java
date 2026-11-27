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
import com.example.hms.service.integration.message.IntegrationMessageRetentionPolicy;
import com.example.hms.utility.MessageUtil;
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

import com.example.hms.service.integration.health.IntegrationDiagnosticText;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuperAdminIntegrationMessageServiceImpl implements SuperAdminIntegrationMessageService {

    private final IntegrationMessageEventRepository repository;
    private final IntegrationMessageRecorder recorder;

    /**
     * The policy object the sweep itself runs on, so the page states the
     * policy in force - or that retention is off - and never windows a
     * disabled or refused sweep is not enforcing.
     */
    private final IntegrationMessageRetentionPolicy retentionPolicy;

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
        List<UUID> ids = page.getContent().stream().map(IntegrationMessageEvent::getId).toList();
        Set<UUID> resolved = ids.isEmpty() ? Set.of() : new HashSet<>(repository.findResolvedFailureIds(ids));
        Map<UUID, String> organizationNames = organizationNames(page.getContent());
        List<IntegrationMessageEventDTO> content = page.getContent().stream()
            .map(event -> mapBase(event)
                .payload(null)
                .resolved(resolved.contains(event.getId()))
                .organizationName(organizationNames.get(event.getOrganizationId()))
                .build())
            .toList();
        return IntegrationMessagePageDTO.builder()
            .content(content)
            .pageNumber(page.getNumber())
            .pageSize(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .deadLetterCount(deadLetterCount)
            .retentionActive(retentionPolicy.isActive())
            .payloadRetentionDays(retentionPolicy.payloadDays())
            .payloadUnresolvedMaxDays(retentionPolicy.unresolvedMaxDays())
            .build();
    }

    @Override
    public IntegrationMessageEventDTO getById(UUID messageId) {
        IntegrationMessageEvent event = repository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("integration.message.notFound", messageId));
        return toDetailDto(event);
    }

    /** QA D4302: the message with its content, its organisation and the rows of its exchange. */
    private IntegrationMessageEventDTO toDetailDto(IntegrationMessageEvent event) {
        List<IntegrationMessageEventDTO.Attempt> attempts = event.getCorrelationId() == null
            ? List.of()
            : repository.findTop50ByCorrelationIdOrderByReceivedAtAsc(event.getCorrelationId()).stream()
                .map(row -> new IntegrationMessageEventDTO.Attempt(
                    row.getId(), row.getStatus(), row.getAttemptCount(),
                    IntegrationDiagnosticText.localize(row.getErrorMessage(), LocaleContextHolder.getLocale()),
                    row.getLastAttemptedAt(), row.getReceivedAt()))
                .toList();
        Long attemptsTotal = event.getCorrelationId() == null
            ? Long.valueOf(attempts.size())
            : Long.valueOf(repository.countByCorrelationId(event.getCorrelationId()));
        boolean resolved = event.getStatus() == IntegrationMessageStatus.FAILED
            && !repository.findResolvedFailureIds(List.of(event.getId())).isEmpty();
        return mapBase(event)
            .payload(event.getPayload())
            .resolved(resolved)
            .organizationName(organizationNames(List.of(event)).get(event.getOrganizationId()))
            .attempts(attempts)
            .attemptsTotal(attemptsTotal)
            .build();
    }

    private Map<UUID, String> organizationNames(List<IntegrationMessageEvent> events) {
        Set<UUID> organizationIds = new HashSet<>();
        for (IntegrationMessageEvent event : events) {
            if (event.getOrganizationId() != null) {
                organizationIds.add(event.getOrganizationId());
            }
        }
        Map<UUID, String> names = new HashMap<>();
        if (organizationIds.isEmpty()) {
            return names;
        }
        for (Object[] row : repository.findOrganizationNames(organizationIds)) {
            names.put((UUID) row[0], (String) row[1]);
        }
        return names;
    }

    @Override
    @Transactional
    public IntegrationMessageEventDTO replay(UUID originalMessageId) {
        // Look the original up directly so we can both 404 on a missing
        // id and 409 on a replay-against-non-FAILED. Replaces the prior
        // existsById round-trip; the lookup is also needed for the
        // status precondition.
        IntegrationMessageEvent original = repository.findById(originalMessageId)
            .orElseThrow(() -> new ResourceNotFoundException("integration.message.notFound", originalMessageId));

        // Copilot review fix — only FAILED rows are eligible for replay.
        // Replaying a SENT/RECEIVED row would emit a duplicate to the
        // partner (when real connectors land) and pollute the audit
        // trail; replaying an already-REPLAYED row is a no-op the
        // operator should know about.
        if (original.getStatus() != IntegrationMessageStatus.FAILED) {
            // QA D4303: a refusal in the reader's language, with no raw status.
            throw new ConflictException(MessageUtil.resolve("integration.message.replay.notFailed"));
        }

        // The retention sweep erased the content (V177) - after resolution,
        // or past the unresolved ceiling - but the row still says FAILED, so
        // without this the replay would write a REPLAYED row with no body - a
        // "retry" of nothing. A clear 409 instead of that or a 500. The
        // message cites no number of days: which window applied depends on
        // the row, and the purge stamp is on the DTO.
        if (original.getPayloadPurgedAt() != null) {
            throw new ConflictException(MessageUtil.resolve("integration.message.contentPurged"));
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
                MessageUtil.resolve("integration.message.replay.notRecorded"));
        }
        return toDetailDto(replay);
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
            .payloadPurgedAt(event.getPayloadPurgedAt())
            .status(event.getStatus())
            .errorMessage(IntegrationDiagnosticText.localize(
                event.getErrorMessage(), LocaleContextHolder.getLocale()))
            .attemptCount(event.getAttemptCount())
            .lastAttemptedAt(event.getLastAttemptedAt())
            .receivedAt(event.getReceivedAt());
    }
}
