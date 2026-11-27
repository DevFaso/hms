package com.example.hms.service.maternity;

import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scorecard K4: the first notice of an urgent alert is owed, not attempted. The row is written with the clinical
 * record, the delivery follows the commit, and a delivery that fails leaves the row to the sweep; the sweep reads
 * every open alert, with no cap on its steps.
 */
@DisplayName("An urgent alert's first notice is owed until it is delivered (K4)")
class ObstetricAlertEscalationServiceTest {

    private final ObstetricAlertEscalationRepository repository = mock(ObstetricAlertEscalationRepository.class);
    private final ObstetricAlertEscalationWorker worker = mock(ObstetricAlertEscalationWorker.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
    private final ObstetricAlertEscalationService service = new ObstetricAlertEscalationService(repository, worker, clock);

    @AfterEach
    void noSynchronizationLeftBehind() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static UrgentAlert alert(String sourceType) {
        return new UrgentAlert(UUID.randomUUID(), UUID.randomUUID(), "Awa", sourceType, UUID.randomUUID(),
            "TYPE", "label", "sentence", UUID.randomUUID(), "title");
    }

    @Test
    void theRowIsWrittenWithTheRecordAndTheNoticeWaitsForTheCommit() {
        UrgentAlert alert = alert(ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION);
        UUID rowId = UUID.randomUUID();
        when(worker.file(alert, false)).thenReturn(rowId);
        TransactionSynchronizationManager.initSynchronization();

        service.raiseAfterCommit(alert);

        // In the caller's transaction: the row, and nothing told yet.
        verify(worker).file(alert, false);
        verify(worker, never()).deliverFirstNotice(any(), any());

        // The commit: the first notice goes out.
        List<TransactionSynchronization> afterCommit = TransactionSynchronizationManager.getSynchronizations();
        assertThat(afterCommit).hasSize(1);
        afterCommit.forEach(TransactionSynchronization::afterCommit);
        InOrder order = inOrder(worker);
        order.verify(worker).file(alert, false);
        order.verify(worker).deliverFirstNotice(rowId, alert);
    }

    @Test
    void aDeliveryThatFailsNeverReachesTheCallerAndLeavesTheRowToTheSweep() {
        UrgentAlert alert = alert(ObstetricAlertEscalationService.SOURCE_NEWS2);
        UUID rowId = UUID.randomUUID();
        when(worker.file(alert, false)).thenReturn(rowId);
        when(worker.deliverFirstNotice(rowId, alert)).thenThrow(new IllegalStateException("bus down"));

        assertThatCode(() -> service.raiseAfterCommit(alert)).doesNotThrowAnyException();

        verify(worker).deliverFirstNotice(rowId, alert);
    }

    @Test
    void aRowThatCannotBeWrittenFailsTheClinicalWriteLoudly() {
        // The row joins the caller's transaction: a failure to write it has already doomed that transaction, and
        // hiding it would only move the error to the commit.
        UrgentAlert alert = alert(ObstetricAlertEscalationService.SOURCE_DELIVERY);
        when(worker.file(alert, false)).thenThrow(new IllegalStateException("constraint"));

        assertThatThrownBy(() -> service.raiseAfterCommit(alert)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAlertItsModuleAlreadyDeliveredIsFiledWithNothingOwed() {
        UrgentAlert alert = alert(ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN);
        when(worker.file(alert, true)).thenReturn(UUID.randomUUID());

        service.raiseAlreadyNotified(alert);

        verify(worker).file(alert, true);
        verify(worker, never()).deliverFirstNotice(any(), any());
    }

    @Test
    void aRepeatedOrUnknownAlertFilesNothing() {
        UrgentAlert repeated = alert(ObstetricAlertEscalationService.SOURCE_NEWS2);
        when(worker.file(repeated, false)).thenReturn(null);
        service.raiseAfterCommit(repeated);
        verify(worker, never()).deliverFirstNotice(any(), any());

        service.raiseAfterCommit(alert("SOMETHING_THE_TABLE_DOES_NOT_KNOW"));
        service.raiseAfterCommit(null);
        // Only the repeated one reached the worker at all.
        verify(worker, org.mockito.Mockito.times(1)).file(any(), anyBoolean());
    }

    @Test
    void theSweepReadsEveryOpenAlertAndOneFailureNeverStopsIt() {
        UUID owed = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID due = UUID.randomUUID();
        UUID notDue = UUID.randomUUID();
        // An alert still owed its first notice is the sweep's a minute after it was filed.
        when(repository.findOpenIds(LocalDateTime.of(2026, 11, 26, 9, 59))).thenReturn(List.of(owed, broken, due, notDue));
        when(worker.escalateOne(owed)).thenReturn(true);
        when(worker.escalateOne(broken)).thenThrow(new IllegalStateException("not delivered"));
        when(worker.escalateOne(due)).thenReturn(true);
        when(worker.escalateOne(notDue)).thenReturn(false);

        assertThat(service.escalateOverdue()).isEqualTo(2);

        verify(worker).escalateOne(notDue);
    }
}
