package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Composition of actual service/recorder using a map-backed repository stub; no database or connector. */
class IntegrationMessageReplayContractIntegrationTest {

    private final Map<UUID, IntegrationMessageEvent> rows = new LinkedHashMap<>();
    private IntegrationMessageEventRepository repository;
    private IntegrationMessageRecorder recorder;
    private SuperAdminIntegrationMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(IntegrationMessageEventRepository.class);
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(invocation -> {
            IntegrationMessageEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(UUID.randomUUID());
            }
            rows.put(event.getId(), event);
            return event;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(repository.findByIdForUpdate(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        // The two "a later attempt took this row over" queries, answered from the rows held here.
        when(repository.existsLaterAttempt(any(), any())).thenAnswer(invocation -> rows.values().stream()
            .anyMatch(row -> invocation.getArgument(0).equals(row.getCorrelationId())
                && row.getLastAttemptedAt().isAfter(invocation.getArgument(1))));
        when(repository.findResolvedFailureIds(any())).thenAnswer(invocation -> {
            java.util.Collection<UUID> ids = invocation.getArgument(0);
            return rows.values().stream()
                .filter(row -> ids.contains(row.getId()) && row.getStatus() == IntegrationMessageStatus.FAILED)
                .filter(row -> rows.values().stream().anyMatch(later ->
                    row.getCorrelationId().equals(later.getCorrelationId())
                        && later.getLastAttemptedAt().isAfter(row.getLastAttemptedAt())))
                .map(IntegrationMessageEvent::getId).toList();
        });
        when(repository.findTop50ByCorrelationIdOrderByReceivedAtAsc(any())).thenAnswer(invocation ->
            rows.values().stream().filter(row -> invocation.getArgument(0).equals(row.getCorrelationId())).toList());
        recorder = new IntegrationMessageRecorder(repository);
        service = new SuperAdminIntegrationMessageServiceImpl(repository, recorder,
            new IntegrationMessageRetentionPolicy(true, 180, 365));
    }

    private IntegrationMessageEvent recordMessage(IntegrationMessageStatus status) {
        return recorder.recordMessage("cdx-simulator", UUID.randomUUID(),
            IntegrationMessageDirection.OUTBOUND, "CDX/Envelope", "{\"id\":\"cdx-fake-resource\"}",
            status, status == IntegrationMessageStatus.FAILED ? "cdx simulated timeout" : null);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("D4301: a successful replay clears the failure, and the same message cannot be replayed twice")
    void aFailureIsReplayedOnceAndThenReadsAsHandled() throws InterruptedException {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        try {
            IntegrationMessageEvent original = recordMessage(IntegrationMessageStatus.FAILED);
            assertThat(service.getById(original.getId()).resolved()).isFalse();
            Thread.sleep(5);

            var replay = service.replay(original.getId());

            // The failure now reads as handled, with both rows of its exchange.
            var handled = service.getById(original.getId());
            assertThat(handled.resolved()).isTrue();
            assertThat(handled.attempts()).extracting(a -> a.status())
                .containsExactly(IntegrationMessageStatus.FAILED, IntegrationMessageStatus.REPLAYED);
            assertThat(replay.resolved()).isFalse();

            // A second replay - a second click, a second operator - writes nothing.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.replay(original.getId()))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
            assertThat(rows).hasSize(2);
        } finally {
            com.example.hms.utility.MessageUtil.setMessageSource(null);
        }
    }

    @Test
    void recordedFailureReplaysWithTraceAndPayloadWhileOriginalRemainsUnchanged() {
        IntegrationMessageEvent original = recordMessage(IntegrationMessageStatus.FAILED);
        var originalSnapshot = service.getById(original.getId());
        assertThat(originalSnapshot.status()).isEqualTo(IntegrationMessageStatus.FAILED);
        assertThat(originalSnapshot.attemptCount()).isEqualTo(1);
        assertThat(originalSnapshot.errorMessage()).isEqualTo("cdx simulated timeout");
        var result = service.replay(original.getId());
        assertThat(rows).hasSize(2);
        assertThat(result.id()).isNotEqualTo(original.getId());
        assertThat(result.correlationId()).isEqualTo(original.getCorrelationId());
        assertThat(result.organizationId()).isEqualTo(original.getOrganizationId());
        assertThat(result.integrationId()).isEqualTo("cdx-simulator");
        assertThat(result.messageType()).isEqualTo("CDX/Envelope");
        assertThat(result.direction()).isEqualTo(IntegrationMessageDirection.OUTBOUND);
        assertThat(result.payload()).isEqualTo(original.getPayload());
        assertThat(result.status()).isEqualTo(IntegrationMessageStatus.REPLAYED);
        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(result.lastAttemptedAt()).isNotNull();
        // The stored row is untouched; it now reads as handled, with its exchange (QA D4301).
        assertThat(service.getById(original.getId())).usingRecursiveComparison()
            .ignoringFields("resolved", "attempts").isEqualTo(originalSnapshot);
        assertThat(service.getById(original.getId()).payload()).isEqualTo(result.payload());
    }

    @Test
    void replayedRowAndSuccessfulOriginalCannotBeReplayedAgain() {
        var replayed = service.replay(recordMessage(IntegrationMessageStatus.FAILED).getId());
        var sent = recordMessage(IntegrationMessageStatus.SENT);
        UUID replayedId = replayed.id();
        UUID sentId = sent.getId();
        assertThatThrownBy(() -> service.replay(replayedId)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.replay(sentId)).isInstanceOf(ConflictException.class);
        assertThat(rows).hasSize(3);
    }

    /** Was the known gap C9 (two replays, two history rows): closed by QA D4301. */
    @Test
    void replayingOriginalFailedIdTwiceWritesOneHistoryRow_gapC9Closed() throws InterruptedException {
        var original = recordMessage(IntegrationMessageStatus.FAILED);
        UUID originalId = original.getId();
        Thread.sleep(5);
        service.replay(originalId);
        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(rows).hasSize(2);
    }

    @Test
    void recorderSaveFailureSurfacesAsServerErrorWithoutInventingReplay() {
        IntegrationMessageEvent original = recordMessage(IntegrationMessageStatus.FAILED);
        UUID originalId = original.getId();
        var originalSnapshot = service.getById(originalId);
        clearInvocations(repository);
        doThrow(new IllegalStateException("cdx stub unavailable"))
            .when(repository).save(any(IntegrationMessageEvent.class));
        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(500));
        verify(repository).save(any(IntegrationMessageEvent.class));
        assertThat(rows).hasSize(1);
        // The stored row is untouched; it now reads as handled, with its exchange (QA D4301).
        assertThat(service.getById(original.getId())).usingRecursiveComparison()
            .ignoringFields("resolved", "attempts").isEqualTo(originalSnapshot);
    }
}
