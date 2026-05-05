package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.payload.dto.superadmin.IntegrationMessageEventDTO;
import com.example.hms.payload.dto.superadmin.IntegrationMessagePageDTO;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
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

    @InjectMocks
    private SuperAdminIntegrationMessageServiceImpl service;

    @Test
    void searchReturnsPagedDtosWithDeadLetterCount() {
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
        event.setId(UUID.randomUUID());

        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 25), 1L));
        when(repository.countByStatus(IntegrationMessageStatus.FAILED)).thenReturn(7L);

        IntegrationMessagePageDTO result = service.search(
            null, null, null, null, null, PageRequest.of(0, 25));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).integrationId()).isEqualTo("partner.nhis");
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.deadLetterCount()).isEqualTo(7L);
    }

    @Test
    void replayReturns404WhenOriginalDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(repository.existsById(missingId)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(missingId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not found");

        verify(recorder, never()).recordReplay(any(), any(), any());
    }

    @Test
    void replayInvokesRecorderAndReturnsTheReplayDto() {
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent replayed = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .correlationId("trace-1")
            .status(IntegrationMessageStatus.REPLAYED)
            .attemptCount(3)
            .build();
        replayed.setId(UUID.randomUUID());
        when(repository.existsById(originalId)).thenReturn(true);
        when(recorder.recordReplay(eq(originalId), eq(IntegrationMessageStatus.REPLAYED), isNull()))
            .thenReturn(replayed);

        IntegrationMessageEventDTO dto = service.replay(originalId);

        assertThat(dto.status()).isEqualTo(IntegrationMessageStatus.REPLAYED);
        assertThat(dto.correlationId()).isEqualTo("trace-1");
        assertThat(dto.attemptCount()).isEqualTo(3);
    }

    @Test
    void replayThrowsWhenRecorderReturnsNullOnPersistenceFailure() {
        UUID originalId = UUID.randomUUID();
        when(repository.existsById(originalId)).thenReturn(true);
        when(recorder.recordReplay(eq(originalId), any(), isNull())).thenReturn(null);

        assertThatThrownBy(() -> service.replay(originalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("could not be persisted");
    }
}
