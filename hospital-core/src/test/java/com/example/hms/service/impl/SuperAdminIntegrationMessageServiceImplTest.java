package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminIntegrationMessageServiceImplTest {

    @Mock
    private IntegrationMessageEventRepository repository;

    @Mock
    private IntegrationMessageRecorder recorder;

    @Mock
    private IntegrationMessageRetentionPolicy retentionPolicy;

    @InjectMocks
    private SuperAdminIntegrationMessageServiceImpl service;

    private IntegrationMessageEvent failedRow(UUID id) {
        IntegrationMessageEvent event = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .organizationId(UUID.randomUUID())
            .direction(IntegrationMessageDirection.OUTBOUND)
            .messageType("CLAIM")
            .correlationId("trace-1")
            .payload("{\"claim\":1}")
            .status(IntegrationMessageStatus.FAILED)
            .errorMessage("partner timeout")
            .attemptCount(2)
            .build();
        event.setId(id);
        return event;
    }

    @Test
    void searchReturnsPagedDtosWithUnresolvedDeadLetterCount() {
        IntegrationMessageEvent event = failedRow(UUID.randomUUID());

        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 25), 1L));
        // Copilot review fix — DLQ count is "FAILED rows whose
        // correlationId has no later attempt", not raw countByStatus.
        when(repository.countUnresolvedDeadLetters()).thenReturn(7L);

        IntegrationMessagePageDTO result = service.search(
            null, null, null, null, null, PageRequest.of(0, 25));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).integrationId()).isEqualTo("partner.nhis");
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.deadLetterCount()).isEqualTo(7L);
    }

    @Test
    void searchStripsPayloadFromListResponse() {
        // Copilot review fix — search() must elide the payload so a
        // 200-row page can't return ~12 MB of envelope data. Operators
        // pull the payload via getById() when drilling in.
        IntegrationMessageEvent event = failedRow(UUID.randomUUID());

        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 25), 1L));
        when(repository.countUnresolvedDeadLetters()).thenReturn(0L);

        IntegrationMessagePageDTO result = service.search(
            null, null, null, null, null, PageRequest.of(0, 25));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).payload()).isNull();
        // Every other field still arrives so the row renders fine.
        assertThat(result.content().get(0).errorMessage()).isEqualTo("partner timeout");
        assertThat(result.content().get(0).correlationId()).isEqualTo("trace-1");
    }

    @Test
    void getByIdReturnsTheFullRowIncludingPayload() {
        UUID id = UUID.randomUUID();
        IntegrationMessageEvent event = failedRow(id);
        when(repository.findById(id)).thenReturn(Optional.of(event));

        IntegrationMessageEventDTO dto = service.getById(id);

        assertThat(dto.payload()).isEqualTo("{\"claim\":1}");
        assertThat(dto.id()).isEqualTo(id);
    }

    @Test
    void getByIdThrowsResourceNotFoundForUnknownId() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(missingId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "integration.message.notFound");
    }

    @Test
    void replayReturns404WhenOriginalDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(missingId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "integration.message.notFound");

        verify(recorder, never()).recordReplay(any(), any(), any());
    }

    @Test
    void replayReturns409WhenOriginalIsNotInFailedState() {
        // Copilot review fix — only FAILED rows are eligible for
        // replay. Replaying a SENT or REPLAYED row would emit a
        // duplicate to the partner and pollute the audit trail.
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent sentRow = failedRow(originalId);
        sentRow.setStatus(IntegrationMessageStatus.SENT);
        when(repository.findById(originalId)).thenReturn(Optional.of(sentRow));

        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("not in FAILED state");

        verify(recorder, never()).recordReplay(any(), any(), any());
    }

    @Test
    void replayInvokesRecorderAndReturnsTheReplayDto() {
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent original = failedRow(originalId);
        IntegrationMessageEvent replayed = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .correlationId("trace-1")
            .status(IntegrationMessageStatus.REPLAYED)
            .attemptCount(3)
            .build();
        replayed.setId(UUID.randomUUID());
        when(repository.findById(originalId)).thenReturn(Optional.of(original));
        when(recorder.recordReplay(eq(originalId), eq(IntegrationMessageStatus.REPLAYED), isNull()))
            .thenReturn(replayed);

        IntegrationMessageEventDTO dto = service.replay(originalId);

        assertThat(dto.status()).isEqualTo(IntegrationMessageStatus.REPLAYED);
        assertThat(dto.correlationId()).isEqualTo("trace-1");
        assertThat(dto.attemptCount()).isEqualTo(3);
    }

    @Test
    void replayThrowsResponseStatus500WhenRecorderReturnsNullOnPersistenceFailure() {
        // Copilot review fix — persistence failures inside the recorder
        // must surface as 500, not 404. Distinguishes "id not found"
        // from "DB sick" for monitoring and the operator UI.
        UUID originalId = UUID.randomUUID();
        when(repository.findById(originalId)).thenReturn(Optional.of(failedRow(originalId)));
        when(recorder.recordReplay(eq(originalId), any(), isNull())).thenReturn(null);

        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("could not be persisted")
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void replayOfAPurgedRowIsRefusedWith409AndRecordsNothing() {
        // Retention (V177) erased the content of a resolved dead letter.
        // The row still says FAILED; replaying it would write a REPLAYED row
        // with no body. A clear 409 instead.
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent purged = failedRow(originalId);
        purged.setPayload(null);
        purged.setPayloadPurgedAt(LocalDateTime.now().minusDays(1));
        when(repository.findById(originalId)).thenReturn(Optional.of(purged));

        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("integration.message.contentPurged");

        verify(recorder, never()).recordReplay(any(), any(), any());
    }

    @Test
    void thePurgeStampAndTheRetentionWindowReachThePage() {
        when(retentionPolicy.isActive()).thenReturn(true);
        when(retentionPolicy.payloadDays()).thenReturn(90);
        when(retentionPolicy.unresolvedMaxDays()).thenReturn(400);
        LocalDateTime purgedAt = LocalDateTime.of(2026, 9, 30, 3, 30);
        IntegrationMessageEvent event = failedRow(UUID.randomUUID());
        event.setPayload(null);
        event.setPayloadPurgedAt(purgedAt);
        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 25), 1L));
        when(repository.countUnresolvedDeadLetters()).thenReturn(0L);

        IntegrationMessagePageDTO result = service.search(
            null, null, null, null, null, PageRequest.of(0, 25));

        assertThat(result.payloadRetentionDays()).isEqualTo(90);
        assertThat(result.payloadUnresolvedMaxDays()).isEqualTo(400);
        assertThat(result.retentionActive()).isTrue();
        assertThat(result.content().get(0).payloadPurgedAt()).isEqualTo(purgedAt);
    }

    @Test
    void getByIdCarriesThePurgeStamp() {
        UUID id = UUID.randomUUID();
        LocalDateTime purgedAt = LocalDateTime.of(2026, 9, 30, 3, 30);
        IntegrationMessageEvent event = failedRow(id);
        event.setPayload(null);
        event.setPayloadPurgedAt(purgedAt);
        when(repository.findById(id)).thenReturn(Optional.of(event));

        IntegrationMessageEventDTO dto = service.getById(id);

        assertThat(dto.payload()).isNull();
        assertThat(dto.payloadPurgedAt()).isEqualTo(purgedAt);
    }

    @Test
    void thePageSaysRetentionIsOffWhenThePolicyIsNotInForce() {
        // Disabled, or a configuration the sweep refuses: the page must not
        // quote windows nobody is enforcing.
        when(retentionPolicy.isActive()).thenReturn(false);
        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0L));
        when(repository.countUnresolvedDeadLetters()).thenReturn(0L);

        IntegrationMessagePageDTO result = service.search(
            null, null, null, null, null, PageRequest.of(0, 25));

        assertThat(result.retentionActive()).isFalse();
    }
}
