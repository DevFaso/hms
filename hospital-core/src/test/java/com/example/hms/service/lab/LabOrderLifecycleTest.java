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
    @DisplayName("a result on a COMPLETED or VERIFIED order re-opens it to RESULTED; nothing else re-opens")
    void reopenForResultIsTheOneSanctionedStepBack() {
        LabOrder completed = orderAt(LabOrderStatus.COMPLETED);
        assertThat(LabOrderLifecycle.reopenForResult(completed)).isTrue();
        assertThat(completed.getStatus()).isEqualTo(LabOrderStatus.RESULTED);

        // VERIFIED is terminal for EncounterServiceImpl.LAB_TERMINAL, so a
        // result landing there would otherwise sit unreleased on an order
        // nobody looks at again: advance() cannot move it (VERIFIED is past
        // RESULTED) and only COMPLETED used to re-open.
        LabOrder verified = orderAt(LabOrderStatus.VERIFIED);
        assertThat(LabOrderLifecycle.reopenForResult(verified)).isTrue();
        assertThat(verified.getStatus()).isEqualTo(LabOrderStatus.RESULTED);

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
