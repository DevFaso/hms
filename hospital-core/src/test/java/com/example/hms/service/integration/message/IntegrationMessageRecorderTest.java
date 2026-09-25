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
import static org.mockito.ArgumentMatchers.eq;
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

        IntegrationMessageEvent saved = recorder.recordMessage(
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
        recorder.recordMessage("partner.nhis", null,
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

        IntegrationMessageEvent result = recorder.recordMessage(
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
    void theCallerSuppliedCorrelationIdIsUsedVerbatim() {
        // What makes a retried MLLP refusal supersede its own dead letter
        // instead of stacking a new one: countUnresolvedDeadLetters discounts
        // a FAILED row only when a later row shares its correlationId.
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordMessage(
            "MLLP:REG/HOSP-B", UUID.randomUUID(),
            IntegrationMessageDirection.INBOUND, "ADT^A08", null,
            IntegrationMessageStatus.FAILED, "cross-tenant rejection",
            "11111111-2222-3333-4444-555555555555");

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getCorrelationId())
            .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void aNullCorrelationIdKeepsTheRandomPerRowBehaviour() {
        // The overload is purely additive: passing null must be
        // indistinguishable from calling the seven-argument form, which every
        // other caller on this codebase still uses.
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordMessage(
            "partner.nhis", UUID.randomUUID(),
            IntegrationMessageDirection.OUTBOUND, "PROBE", null,
            IntegrationMessageStatus.FAILED, "stub-mode probe", null);
        recorder.recordMessage(
            "partner.nhis", UUID.randomUUID(),
            IntegrationMessageDirection.OUTBOUND, "PROBE", null,
            IntegrationMessageStatus.FAILED, "stub-mode probe", null);

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getCorrelationId())
            .isNotNull()
            .isNotEqualTo(cap.getAllValues().get(1).getCorrelationId());
    }

    @Test
    void anOverLongCorrelationIdIsTruncatedToTheColumn() {
        // correlation_id is VARCHAR(120) (V89). A caller cannot make the
        // insert fail - the recorder swallows failures, so that would silently
        // drop the row it was asked to write.
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordMessage(
            "MLLP:REG/HOSP-B", UUID.randomUUID(),
            IntegrationMessageDirection.INBOUND, "ADT^A08", null,
            IntegrationMessageStatus.FAILED, "cross-tenant rejection",
            "c".repeat(400));

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getCorrelationId()).hasSize(120);
    }

    @Test
    void aRecurringFailureStoresItsBodyOnceAndItsReasonEveryTime() {
        // The point of the method: a stable correlation id bounds what the
        // DLQ badge counts, not what the table stores. Without this a vendor
        // retrying an unparseable message every thirty seconds writes
        // thousands of full copies of it - PID and all - while the badge
        // reads 1, which is worse than the visible version.
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.existsByCorrelationIdAndReceivedAtAfter(eq("corr-1"), any()))
            .thenReturn(false, true, true);

        for (int i = 0; i < 3; i++) {
            recorder.recordRecurringFailure(
                "MLLP:REG/HOSP-B", UUID.randomUUID(),
                IntegrationMessageDirection.INBOUND, "ADT^A01",
                "MSH|the whole message", "unparseable ADT^A01", "corr-1");
        }

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository, org.mockito.Mockito.times(3)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getPayload()).isEqualTo("MSH|the whole message");
        assertThat(cap.getAllValues().get(1).getPayload()).isNull();
        assertThat(cap.getAllValues().get(2).getPayload()).isNull();
        // Every occurrence still records the reason and is still FAILED, so
        // the attempt trail survives and the badge behaves as before.
        assertThat(cap.getAllValues()).allSatisfy(e -> {
            assertThat(e.getErrorMessage()).isEqualTo("unparseable ADT^A01");
            assertThat(e.getStatus()).isEqualTo(IntegrationMessageStatus.FAILED);
            assertThat(e.getCorrelationId()).isEqualTo("corr-1");
        });
    }

    @Test
    void aFailedLookupKeepsTheBodyRatherThanRiskingTheOnlyCopy() {
        // Best-effort like the rest of this class, and the safe direction is
        // an extra copy rather than no evidence at all.
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.existsByCorrelationIdAndReceivedAtAfter(eq("corr-2"), any()))
            .thenThrow(new RuntimeException("DB down"));

        recorder.recordRecurringFailure(
            "MLLP:REG/HOSP-B", UUID.randomUUID(),
            IntegrationMessageDirection.INBOUND, "ADT^A01",
            "MSH|the whole message", "unparseable ADT^A01", "corr-2");

        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getPayload()).isEqualTo("MSH|the whole message");
    }

    @Test
    void withNoCorrelationIdThereIsNothingToDeduplicateAgainst() {
        when(repository.save(any(IntegrationMessageEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.recordRecurringFailure(
            "MLLP:REG/HOSP-B", UUID.randomUUID(),
            IntegrationMessageDirection.INBOUND, "ADT^A01",
            "MSH|the whole message", "unparseable ADT^A01", null);

        verify(repository, org.mockito.Mockito.never())
            .existsByCorrelationIdAndReceivedAtAfter(any(), any());
        ArgumentCaptor<IntegrationMessageEvent> cap = ArgumentCaptor.forClass(IntegrationMessageEvent.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getPayload()).isEqualTo("MSH|the whole message");
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
