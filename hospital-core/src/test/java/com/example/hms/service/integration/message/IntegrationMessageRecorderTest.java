package com.example.hms.service.integration.message;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationMessageRecorderTest {

    @Mock
    private IntegrationMessageEventRepository repository;

    @InjectMocks
    private IntegrationMessageRecorder recorder;

    @Test
    void recordSavesAnEventWithFreshCorrelationIdAndAttemptOne() {
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationMessageEvent saved = recorder.record(
            "partner.nhis", UUID.randomUUID(),
            IntegrationMessageDirection.OUTBOUND, "PROBE",
            "{\"stub\":true}",
            IntegrationMessageStatus.FAILED, "stub-mode probe");

        assertThat(saved).isNotNull();
        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getCorrelationId()).isNotBlank();
        assertThat(cap.getValue().getAttemptCount()).isEqualTo(1);
        assertThat(cap.getValue().getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
    }

    @Test
    void recordTruncatesPayloadAtMaxChars() {
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // 80 KB payload — must come back truncated to MAX_PAYLOAD_CHARS
        // so the audit table can never be a memory hazard.
        String huge = "x".repeat(80 * 1024);
        recorder.record("partner.nhis", null,
            IntegrationMessageDirection.INBOUND, "FHIR/Bundle",
            huge, IntegrationMessageStatus.RECEIVED, null);

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getPayload()).hasSize(IntegrationMessageRecorder.MAX_PAYLOAD_CHARS);
    }

    @Test
    void recordSwallowsRepositoryFailuresAndReturnsNull() {
        when(repository.save(any(IntegrationMessageEvent.class)))
            .thenThrow(new RuntimeException("DB down"));

        IntegrationMessageEvent result = recorder.record(
            "partner.nhis", null,
            IntegrationMessageDirection.OUTBOUND, "PROBE",
            "{}", IntegrationMessageStatus.FAILED, "stub");

        // Recorder is best-effort — the partner-side write that
        // triggered it must not fail because the audit table is sick.
        assertThat(result).isNull();
    }

    @Test
    void recordReplayPreservesCorrelationIdAndIncrementsAttempt() {
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent original = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .organizationId(UUID.randomUUID())
            .direction(IntegrationMessageDirection.OUTBOUND)
            .messageType("CLAIM")
            .correlationId("trace-001")
            .payload("{\"claim\":1}")
            .status(IntegrationMessageStatus.FAILED)
            .errorMessage("partner timeout")
            .attemptCount(2)
            .build();
        when(repository.findById(originalId)).thenReturn(Optional.of(original));
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationMessageEvent replay = recorder.recordReplay(
            originalId, IntegrationMessageStatus.REPLAYED, null);

        assertThat(replay).isNotNull();
        assertThat(replay.getCorrelationId()).isEqualTo("trace-001");
        assertThat(replay.getAttemptCount()).isEqualTo(3);
        assertThat(replay.getStatus()).isEqualTo(IntegrationMessageStatus.REPLAYED);
    }

    @Test
    void recordReplayReturnsNullWhenOriginalIsMissing() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        IntegrationMessageEvent replay = recorder.recordReplay(
            missingId, IntegrationMessageStatus.REPLAYED, null);

        assertThat(replay).isNull();
        verify(repository, never()).save(any(IntegrationMessageEvent.class));
    }

    @Test
    void recordReplaySwallowsPersistenceFailures() {
        UUID originalId = UUID.randomUUID();
        IntegrationMessageEvent original = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .status(IntegrationMessageStatus.FAILED)
            .attemptCount(1)
            .build();
        when(repository.findById(originalId)).thenReturn(Optional.of(original));
        when(repository.save(any(IntegrationMessageEvent.class)))
            .thenThrow(new RuntimeException("DB down"));

        IntegrationMessageEvent result = recorder.recordReplay(
            originalId, IntegrationMessageStatus.REPLAYED, null);

        assertThat(result).isNull();
    }
}
