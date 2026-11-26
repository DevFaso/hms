package com.example.hms.service.pharmacy;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

/** Classifies stock-row NOWAIT failures without hiding other persistence errors. */
final class StockRowLocks {
    private StockRowLocks() { }

    static boolean isContention(DataAccessException failure) {
        if (failure instanceof PessimisticLockingFailureException) return true;
        // Native-query lock failures may arrive through JpaSystemException.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
