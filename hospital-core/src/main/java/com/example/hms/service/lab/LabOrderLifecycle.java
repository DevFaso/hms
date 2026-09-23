package com.example.hms.service.lab;

import com.example.hms.enums.LabOrderStatus;

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
     * (see {@link #statusAfterForwardStep}), and ranking it low would let a
     * cancelled order be advanced.
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
     * <p>Read only by {@link #statusAfterNewResult}. There is deliberately no
     * method that re-opens an order by mutating it: the entry path writes the
     * status through a compare-and-set statement, and an entity-mutating
     * sibling is exactly how that write came to be swallowed by a stale
     * snapshot.
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
     *
     * <p>A {@code null} current status yields no move. The caller would write
     * it with {@code expected = null}, and {@code status = null} matches no
     * row in SQL, so the statement is a guaranteed no-op — a branch that could
     * only ever log that it had moved an order it had not. A lab order always
     * has a status ({@code @PrePersist} defaults it to ORDERED); a null here
     * means the row is gone, which is not this path's business to repair.
     */
    public static LabOrderStatus statusAfterNewResult(LabOrderStatus current) {
        if (current == null || current == LabOrderStatus.CANCELLED) {
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
     * What the release of the LAST outstanding result should move
     * {@code current} to, or {@code null} when it should not move.
     *
     * <p>Decided from the status committed in the database, read under the
     * order's write lock — not from an instance loaded before it. A
     * concurrent re-open (an amendment landing while this release runs) is
     * therefore visible: the order completes from RESULTED as it should,
     * rather than being advanced from a stale value and stranded with every
     * result released and nothing left to move it.
     */
    public static LabOrderStatus statusAfterAllResultsReleased(LabOrderStatus current) {
        if (current == null || TERMINAL.contains(current)) {
            return null;
        }
        Integer currentRank = RANK.get(current);
        Integer completedRank = RANK.get(LabOrderStatus.COMPLETED);
        return (currentRank != null && currentRank < completedRank) ? LabOrderStatus.COMPLETED : null;
    }

    /**
     * Whether {@code target} is a forward step from {@code current}, and so
     * the status to write — or {@code null} when the order should not move.
     *
     * <p>A verdict, like its siblings, because nothing writes a lab order's
     * status through the entity any more: every path issues the compare-and-set
     * statement instead. The mutating {@code advance(order, target)} this
     * replaces is what let a pre-lock snapshot decide, and then quietly
     * swallow or over-write the decision.
     *
     * @return {@code target} when the move is forward and legal; {@code null}
     *         when the order is already at or past it, is terminal, or
     *         {@code target} is CANCELLED (cancellation is a decision, never a
     *         side effect of a specimen or a result)
     */
    public static LabOrderStatus statusAfterForwardStep(LabOrderStatus current, LabOrderStatus target) {
        if (target == null || target == LabOrderStatus.CANCELLED) {
            return null;
        }
        Integer targetRank = RANK.get(target);
        if (targetRank == null || current == null) {
            // A null current yields no move, like statusAfterNewResult: the
            // caller writes the verdict with an absent expected value, and a
            // comparison against an absent status matches no row in SQL, so
            // the statement could only ever be a no-op that then logged a
            // concurrent move nobody made. A lab order always has a status
            // (@PrePersist defaults it); absent here means the row is gone.
            return null;
        }
        Integer currentRank = RANK.get(current);
        if (TERMINAL.contains(current) || (currentRank != null && currentRank >= targetRank)) {
            return null;
        }
        return target;
    }
}
