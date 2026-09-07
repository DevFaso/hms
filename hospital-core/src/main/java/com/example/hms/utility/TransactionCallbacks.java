package com.example.hms.utility;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers work until the current transaction commits.
 *
 * <p>For side effects that must not happen if the transaction rolls back —
 * sending mail that quotes a code the rollback would erase, or clearing a
 * login lockout for an activation that did not stick. Both are one-way: there
 * is no compensating action once the message is delivered or the counter is
 * gone.
 *
 * <p>The callback runs on the calling thread, so request-scoped state such as
 * {@link ActivationDeliveryTracker} is still open when it fires. With no
 * transaction active — unit tests, or a caller outside one — the action runs
 * immediately, which is the same ordering guarantee.
 */
public final class TransactionCallbacks {

    private TransactionCallbacks() {
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
