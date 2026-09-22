package com.example.hms.service.lab;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Forward-only advancement of a lab order by the workflow itself.
 *
 * <p>{@code POST /lab-orders/{id}/transition} is the hand-driven state
 * machine: one step at a time, role-checked per step, and nothing in the
 * portal calls it. So before this class nothing ever moved an order past
 * ORDERED — a released result sat on an order the ordering doctor's review
 * queue (which keys on COMPLETED) would never show.
 *
 * <p>The specimen and result services now advance the order as a side effect
 * of the clinical event that implies the state: a collected specimen means
 * COLLECTED, a received one RECEIVED, an entered result RESULTED, the last
 * released result COMPLETED. These are consequences, not requests, so they do
 * not go through the per-step transition table: an event may skip states the
 * lab never recorded explicitly (a result entered on an order whose specimen
 * was never logged still means the order is RESULTED), and it never moves an
 * order backwards or out of a terminal state.
 */
public final class LabOrderLifecycle {

    /** States the workflow never leaves on its own. */
    private static final Set<LabOrderStatus> TERMINAL =
        EnumSet.of(LabOrderStatus.COMPLETED, LabOrderStatus.CANCELLED);

    private LabOrderLifecycle() {
    }

    /**
     * The one sanctioned move backwards: a result arriving on a COMPLETED
     * order (a correction, a late analyte) re-opens it to RESULTED so the
     * ordering doctor sees it as having something new to review and the
     * normal release → COMPLETED path runs again. CANCELLED stays cancelled.
     *
     * @return true when the order was COMPLETED and is now RESULTED
     */
    public static boolean reopenForResult(LabOrder order) {
        if (order == null || order.getStatus() != LabOrderStatus.COMPLETED) {
            return false;
        }
        order.setStatus(LabOrderStatus.RESULTED);
        return true;
    }

    /**
     * Move {@code order} to {@code target} when that is a forward step.
     *
     * @return true when the status changed; false when the order is null,
     *         already at or past {@code target}, terminal, or {@code target}
     *         is CANCELLED (cancellation is a decision, never a side effect)
     */
    public static boolean advance(LabOrder order, LabOrderStatus target) {
        if (order == null || target == null || target == LabOrderStatus.CANCELLED) {
            return false;
        }
        LabOrderStatus current = order.getStatus();
        if (current != null && (TERMINAL.contains(current) || current.ordinal() >= target.ordinal())) {
            return false;
        }
        order.setStatus(target);
        return true;
    }
}
