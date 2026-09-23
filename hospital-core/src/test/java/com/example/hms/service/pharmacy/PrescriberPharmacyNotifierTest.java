package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * G6 — the notification is written after the commit and never on a
 * rollback, and the writer receives an id, not the entity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriberPharmacyNotifier")
class PrescriberPharmacyNotifierTest {

    @Mock private PrescriberPharmacyNotificationWriter writer;

    private PrescriberPharmacyNotifier notifier() {
        return new PrescriberPharmacyNotifier(writer);
    }

    private static Prescription prescription() {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        return p;
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.beforeCommit(false);
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void rollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    @Test
    @DisplayName("writes only once the transaction has committed, passing the prescription id")
    void writesAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        Prescription p = prescription();

        notifier().notifyPrescriber(p, PrescriptionStatus.DISPENSED);

        verifyNoInteractions(writer);
        commit();
        verify(writer).write(p.getId(), PrescriptionStatus.DISPENSED);
    }

    @Test
    @DisplayName("never writes when the transaction rolls back")
    void skipsOnRollback() {
        TransactionSynchronizationManager.initSynchronization();

        notifier().notifyPrescriber(prescription(), PrescriptionStatus.PENDING_STOCK);
        rollback();

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a writer failure after commit is swallowed, never thrown into the caller")
    void swallowsWriterFailure() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new IllegalStateException("no session")).when(writer).write(any(), any());

        notifier().notifyPrescriber(prescription(), PrescriptionStatus.PARTNER_REJECTED);

        assertThatCode(PrescriberPharmacyNotifierTest::commit).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ignores events the prescriber is not told about, and prescriptions without an id")
    void ignoresIrrelevantEvents() {
        notifier().notifyPrescriber(prescription(), PrescriptionStatus.SIGNED);
        notifier().notifyPrescriber(new Prescription(), PrescriptionStatus.DISPENSED);
        notifier().notifyPrescriber(null, PrescriptionStatus.DISPENSED);

        verify(writer, never()).write(any(), any());
    }
}
