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

    /** The shared refusal of a cancellation that states no reason (QA D1098). */
    public static final String CANCEL_REASON_REQUIRED = "order.lifecycle.cancelReasonRequired";

    /** The shared refusal of a cancel or a step back on an order that already has its result (QA D1098). */
    public static final String RESULTED_LOCKED = "order.lifecycle.resultedLocked";

    /**
     * QA D1098 (round 19): every cancellation states why. A resulted imaging
     * order was cancelled with {@code cancellationReason} null. Returns the
     * trimmed reason to store.
     */
    public static String requireCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(CANCEL_REASON_REQUIRED);
        }
        return reason.trim();
    }

    /**
     * QA D1098 (round 19): an order that has reached a resulted state is closed
     * to cancellation and to any step back. A resulted imaging order went to
     * CANCELLED, another back to ORDERED, both keeping their signed reports.
     * {@code resultedStates} lists the order's resulted states in lifecycle
     * order: staying put or moving forward among them is allowed, anything
     * else (CANCELLED, ORDERED, SCHEDULED…) is refused.
     */
    public static void requireResultStaysClosed(Enum<?> current, Enum<?> target,
                                                java.util.List<? extends Enum<?>> resultedStates) {
        int from = current == null ? -1 : resultedStates.indexOf(current);
        if (from < 0 || target == null) {
            return;
        }
        if (resultedStates.indexOf(target) < from) {
            throw new BusinessException(RESULTED_LOCKED);
        }
    }
}
