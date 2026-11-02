package com.example.hms.service.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * An {@link IsolatedReads} for unit tests that build services by hand: the
 * read runs inline under a transaction manager that holds no resource, so a
 * test sees the same outcome contract (a failed read is logged and reported,
 * never thrown) without a database.
 */
public final class IsolatedReadsTestSupport {

    private IsolatedReadsTestSupport() {
    }

    public static IsolatedReads inline() {
        return new IsolatedReads(new NoResourceTransactionManager());
    }

    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource to bind: the read runs inline.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to commit.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to roll back.
        }
    }
}
