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
    @DisplayName("an entered result moves ORDERED straight to RESULTED — skipped states are not a blocker")
    void advancesForwardSkippingUnrecordedStates() {
        LabOrder order = orderAt(LabOrderStatus.ORDERED);

        assertThat(LabOrderLifecycle.advance(order, LabOrderStatus.RESULTED)).isTrue();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("a specimen logged after the result never moves the order back")
    void neverMovesBackwards() {
        LabOrder order = orderAt(LabOrderStatus.RESULTED);

        assertThat(LabOrderLifecycle.advance(order, LabOrderStatus.COLLECTED)).isFalse();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("the same state twice is a no-op, so callers do not re-save")
    void sameStateIsNoop() {
        LabOrder order = orderAt(LabOrderStatus.RECEIVED);

        assertThat(LabOrderLifecycle.advance(order, LabOrderStatus.RECEIVED)).isFalse();
    }

    @Test
    @DisplayName("COMPLETED and CANCELLED orders are left alone")
    void terminalStatesAreFrozen() {
        LabOrder completed = orderAt(LabOrderStatus.COMPLETED);
        LabOrder cancelled = orderAt(LabOrderStatus.CANCELLED);

        assertThat(LabOrderLifecycle.advance(completed, LabOrderStatus.CANCELLED)).isFalse();
        assertThat(LabOrderLifecycle.advance(cancelled, LabOrderStatus.RESULTED)).isFalse();
        assertThat(completed.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
        assertThat(cancelled.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);
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
    @DisplayName("cancellation is a decision, never a side effect")
    void cancelledIsNeverATarget() {
        LabOrder order = orderAt(LabOrderStatus.ORDERED);

        assertThat(LabOrderLifecycle.advance(order, LabOrderStatus.CANCELLED)).isFalse();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
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
    @DisplayName("a null order or target is ignored; an order with no status takes the target")
    void nullsAreTolerated() {
        assertThat(LabOrderLifecycle.advance(null, LabOrderStatus.RESULTED)).isFalse();
        assertThat(LabOrderLifecycle.advance(orderAt(LabOrderStatus.ORDERED), null)).isFalse();

        LabOrder blank = new LabOrder();
        assertThat(LabOrderLifecycle.advance(blank, LabOrderStatus.COLLECTED)).isTrue();
        assertThat(blank.getStatus()).isEqualTo(LabOrderStatus.COLLECTED);
    }
}
