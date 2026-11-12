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
import static org.mockito.Mockito.mock;
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
        recorder = new IntegrationMessageRecorder(repository);
        service = new SuperAdminIntegrationMessageServiceImpl(repository, recorder,
            new IntegrationMessageRetentionPolicy(true, 180, 365));
    }

    private IntegrationMessageEvent record(IntegrationMessageStatus status) {
        return recorder.recordMessage("cdx-simulator", UUID.randomUUID(),
            IntegrationMessageDirection.OUTBOUND, "CDX/Envelope", "{\"id\":\"cdx-fake-resource\"}",
            status, status == IntegrationMessageStatus.FAILED ? "cdx simulated timeout" : null);
    }

    @Test
    void recordedFailureReplaysWithTraceAndPayloadWhileOriginalRemainsUnchanged() {
        IntegrationMessageEvent original = record(IntegrationMessageStatus.FAILED);
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
        assertThat(original.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
        assertThat(original.getAttemptCount()).isEqualTo(1);
        assertThat(original.getErrorMessage()).isEqualTo("cdx simulated timeout");
        assertThat(service.getById(original.getId()).payload()).isEqualTo(result.payload());
    }

    @Test
    void replayedRowAndSuccessfulOriginalCannotBeReplayedAgain() {
        var replayed = service.replay(record(IntegrationMessageStatus.FAILED).getId());
        var sent = record(IntegrationMessageStatus.SENT);
        assertThatThrownBy(() -> service.replay(replayed.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.replay(sent.getId())).isInstanceOf(ConflictException.class);
        assertThat(rows).hasSize(3);
    }

    @Test
    void recorderSaveFailureSurfacesAsServerErrorWithoutInventingReplay() {
        IntegrationMessageEvent original = record(IntegrationMessageStatus.FAILED);
        when(repository.save(any(IntegrationMessageEvent.class))).thenThrow(new IllegalStateException("cdx stub unavailable"));
        assertThatThrownBy(() -> service.replay(original.getId()))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(500));
        assertThat(rows).hasSize(1);
        assertThat(original.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
    }
}
