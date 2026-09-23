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

    /**
     * How far through the workflow each state is.
     *
     * <p>Declared here rather than read from {@code ordinal()}: with ordinals,
     * inserting a value into the middle of {@link LabOrderStatus} silently
     * re-orders the workflow and reinstates the bug this class exists to fix,
     * with nothing failing. {@code everyStatusHasARank} in the test makes a new
     * enum value a compile-green but test-red change until someone decides
     * where it belongs.
     *
     * <p>CANCELLED is given the terminal rank: it is never a forward target
     * (see {@link #advance}), and ranking it low would let a cancelled order
     * be advanced.
     */
    private static final java.util.Map<LabOrderStatus, Integer> RANK =
        new java.util.EnumMap<>(java.util.Map.of(
            LabOrderStatus.ORDERED, 0,
            LabOrderStatus.PENDING, 1,
            LabOrderStatus.COLLECTED, 2,
            LabOrderStatus.RECEIVED, 3,
            LabOrderStatus.IN_PROGRESS, 4,
            LabOrderStatus.RESULTED, 5,
            LabOrderStatus.VERIFIED, 6,
            LabOrderStatus.COMPLETED, 7,
            LabOrderStatus.CANCELLED, 7));

    /** The workflow position of {@code status}; visible for the coverage test. */
    static Integer rankOf(LabOrderStatus status) {
        return RANK.get(status);
    }

    private LabOrderLifecycle() {
    }

    /**
     * States a NEW result re-opens: the order was finished with, and something
     * has come back anyway.
     *
     * <p>VERIFIED belongs here with COMPLETED. It is not merely a stage on the
     * way: {@code EncounterServiceImpl.LAB_TERMINAL} counts VERIFIED as done,
     * so an encounter can be closed over it. A result entered on a VERIFIED
     * order used to be neither re-opened (only COMPLETED was) nor advanced
     * (VERIFIED is already past RESULTED), which left the order parked in a
     * terminal state carrying an unreleased result nobody was going to be
     * shown. CANCELLED is excluded: a cancelled order is a decision somebody
     * made, and a stray result does not overturn it.
     */
    private static final Set<LabOrderStatus> REOPENABLE =
        EnumSet.of(LabOrderStatus.VERIFIED, LabOrderStatus.COMPLETED);

    /**
     * What a newly entered result should move {@code current} to, or
     * {@code null} when it should not move at all.
     *
     * <p>The decision, separated from the writing of it: the entry path writes
     * the status with a compare-and-set statement rather than through the
     * entity (see {@code LabOrderRepository.updateStatusFrom}), so it needs
     * the verdict before it has anything to mutate.
     */
    public static LabOrderStatus statusAfterNewResult(LabOrderStatus current) {
        if (current == null) {
            return LabOrderStatus.RESULTED;
        }
        if (current == LabOrderStatus.CANCELLED) {
            return null;
        }
        if (REOPENABLE.contains(current)) {
            return LabOrderStatus.RESULTED;
        }
        Integer currentRank = RANK.get(current);
        Integer resultedRank = RANK.get(LabOrderStatus.RESULTED);
        return (currentRank != null && currentRank < resultedRank) ? LabOrderStatus.RESULTED : null;
    }

    /**
     * The one sanctioned move backwards: a result arriving on an order that
     * was already finished (a correction, a late analyte) re-opens it to
     * RESULTED so the ordering doctor sees it as having something new to
     * review and the normal release → COMPLETED path runs again.
     *
     * <p>Only a result that is genuinely new may do this. The REST entry path
     * has no duplicate detection (the HL7 path dedups on sender + MSH-10), so
     * a retried {@code POST /lab-results} would otherwise un-complete a
     * finished order — and with auto-verification off by default nothing would
     * release the duplicate, stranding the order at RESULTED for good. The
     * caller decides what "new" means and passes the verdict in.
     *
     * @return true when the order was VERIFIED or COMPLETED and is now RESULTED
     */
    public static boolean reopenForResult(LabOrder order) {
        if (order == null || !REOPENABLE.contains(order.getStatus())) {
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
        Integer currentRank = RANK.get(current);
        Integer targetRank = RANK.get(target);
        if (targetRank == null) {
            return false;
        }
        if (current != null && (TERMINAL.contains(current)
                || (currentRank != null && currentRank >= targetRank))) {
            return false;
        }
        order.setStatus(target);
        return true;
    }
}
