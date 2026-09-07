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
 * {@link ActivationDeliveryTracker} is still open when it fires.
 *
 * <p><strong>With no transaction active the action runs immediately</strong> —
 * inline, before the caller's next statement. That is not the deferral this
 * class is named for; it is the only thing it can do, since there is no commit
 * to wait for. It keeps unit tests working, but it also means a caller with no
 * transaction anywhere up its stack gets no protection here: {@code
 * AuthController} has no {@code @Transactional} at all, so routing one of its
 * side effects through this helper would read as deferred and behave as
 * inline.
 *
 * <p>The test is {@code isSynchronizationActive()}, which is thread-bound, not
 * method-bound — so a private or self-invoked method reached from a
 * transactional caller further up the stack still gets the deferred path, and
 * moving a callback into a helper method does not change which branch it
 * takes. What does change it is there being no transaction on the thread at
 * all: an entry point that is not transactional, or one whose
 * {@code @Transactional} never took effect because the call arrived through
 * self-invocation and bypassed the proxy.
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
