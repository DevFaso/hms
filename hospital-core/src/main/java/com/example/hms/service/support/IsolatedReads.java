package com.example.hms.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way to read something "best effort" from inside a transaction.
 *
 * <p>QA D1212 (round 24): the proxy health summary caught the exception of a
 * nested {@code @Transactional} read and carried on with an empty card. The
 * nested read had already marked the SHARED transaction rollback-only on its
 * way out, so the outer method's commit threw
 * {@code UnexpectedRollbackException} and the whole page answered 500. A
 * try/catch around a call that joins the caller's transaction cannot make
 * that call optional: the failure belongs to the caller's transaction, not to
 * the call. (On PostgreSQL a failed statement also aborts the transaction, so
 * every later read on it fails too.)
 *
 * <p>Here the read runs in its own read-only transaction
 * ({@code REQUIRES_NEW}): when it fails, only that transaction rolls back,
 * the failure is logged, and the caller's transaction is untouched. The cost
 * is one extra pooled connection for the duration of the read, while the
 * caller's is suspended.
 *
 * <p>Typed by design (no generic helper, user rule 2026-09-12): the caller
 * hands a {@link Runnable} that stores what it read; this class only owns the
 * transaction boundary and the outcome. The architecture guard
 * {@code SwallowedNestedTransactionGuardTest} fails when a catch that does not
 * rethrow wraps a transactional call anywhere else.
 */
@Slf4j
@Component
public class IsolatedReads {

    private final TransactionTemplate ownReadTransaction;

    public IsolatedReads(PlatformTransactionManager transactionManager) {
        this.ownReadTransaction = new TransactionTemplate(transactionManager);
        this.ownReadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownReadTransaction.setReadOnly(true);
    }

    /**
     * Run {@code read} in its own read-only transaction.
     *
     * @param what  a short, PHI-free label for the log line (e.g. "lab results")
     * @param read  the read; it stores its result where the caller wants it
     * @return {@code true} when the read completed, {@code false} when it
     *         failed (logged at WARN with the exception class only)
     */
    public boolean attempt(String what, Runnable read) {
        try {
            ownReadTransaction.executeWithoutResult(status -> read.run());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Best-effort read '{}' unavailable: {}", what, ex.getClass().getSimpleName());
            return false;
        }
    }
}
