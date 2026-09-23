package com.example.hms.service.lab;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — the workflow advances an order forward and only forward.
 */
@DisplayName("LabOrderLifecycle")
class LabOrderLifecycleTest {

    private static LabOrder orderAt(LabOrderStatus status) {
        LabOrder order = new LabOrder();
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("a forward step is the status to write; skipped states are not a blocker")
    void forwardStepVerdicts() {
        // A verdict, not a mutation: nothing writes an order's status through
        // the entity any more, on any path, so the specimen events ask what to
        // write and issue the compare-and-set statement themselves.
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.ORDERED, LabOrderStatus.RESULTED))
            .isEqualTo(LabOrderStatus.RESULTED);
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.ORDERED, LabOrderStatus.COLLECTED))
            .isEqualTo(LabOrderStatus.COLLECTED);
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.COLLECTED, LabOrderStatus.RECEIVED))
            .isEqualTo(LabOrderStatus.RECEIVED);
        // an order whose status is somehow absent still takes the step
        assertThat(LabOrderLifecycle.statusAfterForwardStep(null, LabOrderStatus.COLLECTED))
            .isEqualTo(LabOrderStatus.COLLECTED);
    }

    @Test
    @DisplayName("a specimen logged after the result never moves the order back, and the same state is a no-op")
    void forwardStepNeverMovesBackwards() {
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.RESULTED, LabOrderStatus.COLLECTED))
            .isNull();
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.RECEIVED, LabOrderStatus.RECEIVED))
            .isNull();
    }

    @Test
    @DisplayName("COMPLETED and CANCELLED orders are left alone, and CANCELLED is never a target")
    void forwardStepLeavesTerminalOrdersAlone() {
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.COMPLETED, LabOrderStatus.RESULTED))
            .isNull();
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.CANCELLED, LabOrderStatus.RESULTED))
            .isNull();
        // cancellation is a decision, never a side effect of a specimen event
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.ORDERED, LabOrderStatus.CANCELLED))
            .isNull();
        assertThat(LabOrderLifecycle.statusAfterForwardStep(LabOrderStatus.ORDERED, null)).isNull();
    }

    @Test
    @DisplayName("a new result re-opens a finished order and advances an unfinished one")
    void statusAfterNewResultVerdicts() {
        // The verdict only — the caller writes it with a compare-and-set
        // statement, because writing it through the entity is how the
        // re-open came to be silently swallowed by a stale snapshot.
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.COMPLETED))
            .isEqualTo(LabOrderStatus.RESULTED);
        // VERIFIED is terminal for EncounterServiceImpl.LAB_TERMINAL, so a
        // result landing there would otherwise sit unreleased on an order
        // nobody looks at again.
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.VERIFIED))
            .isEqualTo(LabOrderStatus.RESULTED);
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.ORDERED))
            .isEqualTo(LabOrderStatus.RESULTED);
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.RECEIVED))
            .isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("a new result moves neither a cancelled order nor one already RESULTED")
    void statusAfterNewResultLeavesSomeOrdersAlone() {
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.CANCELLED)).isNull();
        assertThat(LabOrderLifecycle.statusAfterNewResult(LabOrderStatus.RESULTED)).isNull();
        // A null status would be written with expected = null, and
        // "status = null" matches no row in SQL: the statement could only ever
        // be a no-op that logged a move it had not made.
        assertThat(LabOrderLifecycle.statusAfterNewResult(null)).isNull();
    }

    @Test
    @DisplayName("releasing the last result completes an open order and moves no finished one")
    void statusAfterAllResultsReleasedVerdicts() {
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(LabOrderStatus.RESULTED))
            .isEqualTo(LabOrderStatus.COMPLETED);
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(LabOrderStatus.VERIFIED))
            .isEqualTo(LabOrderStatus.COMPLETED);
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(LabOrderStatus.ORDERED))
            .isEqualTo(LabOrderStatus.COMPLETED);
        // Terminal states, and a row that is not there, are left alone.
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(LabOrderStatus.COMPLETED)).isNull();
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(LabOrderStatus.CANCELLED)).isNull();
        assertThat(LabOrderLifecycle.statusAfterAllResultsReleased(null)).isNull();
    }

    @Test
    @DisplayName("every LabOrderStatus has a declared workflow rank")
    void everyStatusHasARank() {
        // Workflow order is declared, not inherited from enum ordinals: adding
        // a value to the middle of LabOrderStatus used to re-order the whole
        // lifecycle silently. A new value now fails here until somebody says
        // where it belongs.
        for (LabOrderStatus status : LabOrderStatus.values()) {
            assertThat(LabOrderLifecycle.rankOf(status))
                .as("LabOrderStatus.%s has no rank in LabOrderLifecycle.RANK", status)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("the workflow order is the declared one, not the enum's")
    void rankOrdersTheWorkflow() {
        assertThat(LabOrderLifecycle.rankOf(LabOrderStatus.ORDERED))
            .isLessThan(LabOrderLifecycle.rankOf(LabOrderStatus.COLLECTED));
        assertThat(LabOrderLifecycle.rankOf(LabOrderStatus.COLLECTED))
            .isLessThan(LabOrderLifecycle.rankOf(LabOrderStatus.RESULTED));
        assertThat(LabOrderLifecycle.rankOf(LabOrderStatus.RESULTED))
            .isLessThan(LabOrderLifecycle.rankOf(LabOrderStatus.COMPLETED));
        // CANCELLED ranks terminal so a cancelled order is never advanced.
        assertThat(LabOrderLifecycle.rankOf(LabOrderStatus.CANCELLED))
            .isEqualTo(LabOrderLifecycle.rankOf(LabOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("an order with no status still takes its first step")
    void nullsAreTolerated() {
        // Every other null case belongs to the verdict methods above; this is
        // the one a lab order can legitimately be in before @PrePersist
        // defaults it.
        assertThat(LabOrderLifecycle.statusAfterForwardStep(null, LabOrderStatus.COLLECTED))
            .isEqualTo(LabOrderStatus.COLLECTED);
    }
}
