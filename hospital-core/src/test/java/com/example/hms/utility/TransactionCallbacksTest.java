package com.example.hms.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deferral itself, which every caller's correctness rests on.
 *
 * <p>Worth its own test because no service test can cover it: unit tests bind
 * no transaction to the thread, so they all take the inline branch. Swapping
 * an {@code afterCommit(...)} wrapper for a direct call would leave the rest
 * of the suite green — this is the only place that would fail.
 */
@DisplayName("TransactionCallbacks")
class TransactionCallbacksTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("runs inline when no transaction is active")
    void runsInlineWithoutATransaction() {
        AtomicInteger ran = new AtomicInteger();

        TransactionCallbacks.afterCommit(ran::incrementAndGet);

        assertThat(ran).hasValue(1);
    }

    @Test
    @DisplayName("waits for the commit when a transaction is active")
    void defersUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger ran = new AtomicInteger();

        TransactionCallbacks.afterCommit(ran::incrementAndGet);

        // The whole point: nothing has happened yet.
        assertThat(ran).hasValue(0);

        commit();
        assertThat(ran).hasValue(1);
    }

    @Test
    @DisplayName("never runs when the transaction rolls back")
    void skipsTheActionOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger ran = new AtomicInteger();

        TransactionCallbacks.afterCommit(ran::incrementAndGet);
        // A rollback fires afterCompletion, never afterCommit. This is the
        // behaviour the callers depend on: no mail quoting a code the rollback
        // erased, no login lockout cleared for an activation that did not
        // stick.
        rollback();

        assertThat(ran).hasValue(0);
    }

    @Test
    @DisplayName("keeps registration order, so callbacks fire the way they were queued")
    void preservesRegistrationOrder() {
        TransactionSynchronizationManager.initSynchronization();
        StringBuilder order = new StringBuilder();

        TransactionCallbacks.afterCommit(() -> order.append('a'));
        TransactionCallbacks.afterCommit(() -> order.append('b'));
        commit();

        assertThat(order).hasToString("ab");
    }

    private static void commit() {
        for (TransactionSynchronization sync : registered()) {
            sync.afterCommit();
        }
    }

    private static void rollback() {
        for (TransactionSynchronization sync : registered()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private static List<TransactionSynchronization> registered() {
        return TransactionSynchronizationManager.getSynchronizations();
    }
}
