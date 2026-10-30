package com.example.hms.service.support;

import com.example.hms.exception.BusinessException;

/**
 * QA D1066 (round 18): a cancelled order is closed. The author could send the
 * imaging order back to ORDERED or SCHEDULED through the status update; it came
 * back to the worklists still stamped as cancelled, and the chronology read
 * "Prescrit". As in real hospitals, a cancelled order is never revived: the
 * clinician places a new one. Every order service refuses any further change
 * to a cancelled order through this one check, and
 * {@code CancelledOrderLockGuardTest} fails on a status write that skips it.
 */
public final class CancelledOrders {

    private CancelledOrders() {
    }

    /** Refuses with {@code messageKey} when the order's current status is CANCELLED. */
    public static void requireNotCancelled(Enum<?> currentStatus, String messageKey) {
        if (currentStatus != null && "CANCELLED".equals(currentStatus.name())) {
            throw new BusinessException(messageKey);
        }
    }
}
