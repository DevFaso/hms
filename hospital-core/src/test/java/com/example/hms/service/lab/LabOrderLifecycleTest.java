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
    @DisplayName("a result on a COMPLETED order re-opens it to RESULTED; nothing else re-opens")
    void reopenForResultIsTheOneSanctionedStepBack() {
        LabOrder completed = orderAt(LabOrderStatus.COMPLETED);
        assertThat(LabOrderLifecycle.reopenForResult(completed)).isTrue();
        assertThat(completed.getStatus()).isEqualTo(LabOrderStatus.RESULTED);

        LabOrder cancelled = orderAt(LabOrderStatus.CANCELLED);
        assertThat(LabOrderLifecycle.reopenForResult(cancelled)).isFalse();
        assertThat(cancelled.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);

        LabOrder open = orderAt(LabOrderStatus.RECEIVED);
        assertThat(LabOrderLifecycle.reopenForResult(open)).isFalse();
        assertThat(open.getStatus()).isEqualTo(LabOrderStatus.RECEIVED);
        assertThat(LabOrderLifecycle.reopenForResult(null)).isFalse();
    }

    @Test
    @DisplayName("cancellation is a decision, never a side effect")
    void cancelledIsNeverATarget() {
        LabOrder order = orderAt(LabOrderStatus.ORDERED);

        assertThat(LabOrderLifecycle.advance(order, LabOrderStatus.CANCELLED)).isFalse();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
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
