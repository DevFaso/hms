package com.example.hms.service.support;

import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.ImagingOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provisional rule 1 (2026-11-15): the decision table of the one pre-authorisation policy. */
class ImagingPreAuthorisationPolicyTest {

    @Test
    void urgentAndStatAreTheOnlyPrioritiesThatStartWithoutTheNumber() {
        assertThat(ImagingPreAuthorisationPolicy.STARTS_WITHOUT_NUMBER)
            .containsExactlyInAnyOrder(ImagingOrderPriority.URGENT, ImagingOrderPriority.STAT);
        for (ImagingOrderPriority priority : ImagingOrderPriority.values()) {
            boolean urgent = priority != ImagingOrderPriority.ROUTINE;
            if (urgent) {
                assertThatCode(() -> ImagingPreAuthorisationPolicy.requireStartAllowed(
                    ImagingOrderStatus.IN_PROGRESS, priority, true, null)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> ImagingPreAuthorisationPolicy.requireStartAllowed(
                    ImagingOrderStatus.IN_PROGRESS, priority, true, " "))
                    .isInstanceOf(FieldBusinessException.class);
            }
        }
        // No priority recorded (an order written before priorities): the routine rule.
        assertThatThrownBy(() -> ImagingPreAuthorisationPolicy.requireStartAllowed(
            ImagingOrderStatus.IN_PROGRESS, null, true, null)).isInstanceOf(FieldBusinessException.class);
        // A number, or no pre-authorisation required, starts any study.
        assertThatCode(() -> ImagingPreAuthorisationPolicy.requireStartAllowed(
            ImagingOrderStatus.IN_PROGRESS, ImagingOrderPriority.ROUTINE, true, "PA-1")).doesNotThrowAnyException();
        assertThatCode(() -> ImagingPreAuthorisationPolicy.requireStartAllowed(
            ImagingOrderStatus.IN_PROGRESS, ImagingOrderPriority.ROUTINE, false, null)).doesNotThrowAnyException();
    }

    @Test
    void aStudyReportedWithoutItsNumberIsFlaggedNeverRefused() {
        // A report signed on a routine order never started: the care step stands, the order is flagged.
        ImagingOrder order = order(ImagingOrderPriority.ROUTINE, ImagingOrderStatus.RESULTS_AVAILABLE);
        LocalDateTime now = LocalDateTime.now();
        assertThat(ImagingPreAuthorisationPolicy.reconcile(order, now))
            .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.OPENED);
        assertThat(order.getAuthorizationPendingSince()).isEqualTo(now);
        // Idempotent: a second write files nothing more.
        assertThat(ImagingPreAuthorisationPolicy.reconcile(order, now.plusMinutes(5)))
            .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.UNCHANGED);
        assertThat(order.getAuthorizationPendingSince()).isEqualTo(now);
    }

    @Test
    void anOrderNotYetStartedIsNotPending() {
        for (ImagingOrderStatus status : new ImagingOrderStatus[] {ImagingOrderStatus.DRAFT, ImagingOrderStatus.ORDERED,
                ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.CANCELLED}) {
            ImagingOrder order = order(ImagingOrderPriority.STAT, status);
            assertThat(ImagingPreAuthorisationPolicy.reconcile(order, LocalDateTime.now())).as(status.name())
                .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.UNCHANGED);
            assertThat(order.getAuthorizationPendingSince()).as(status.name()).isNull();
        }
    }

    @Test
    void theNumberOrNoLongerRequiringItClearsTheFlag_butACancelAfterTheStudyDoesNot() {
        ImagingOrder numbered = pending();
        numbered.setAuthorizationNumber("PA-9");
        assertThat(ImagingPreAuthorisationPolicy.reconcile(numbered, LocalDateTime.now()))
            .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.CLOSED);
        assertThat(numbered.getAuthorizationPendingSince()).isNull();

        ImagingOrder notRequired = pending();
        notRequired.setRequiresAuthorization(false);
        assertThat(ImagingPreAuthorisationPolicy.reconcile(notRequired, LocalDateTime.now()))
            .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.CLOSED);

        ImagingOrder cancelled = pending();
        cancelled.setStatus(ImagingOrderStatus.CANCELLED);
        assertThat(ImagingPreAuthorisationPolicy.reconcile(cancelled, LocalDateTime.now()))
            .isEqualTo(ImagingPreAuthorisationPolicy.FollowUp.UNCHANGED);
        assertThat(cancelled.getAuthorizationPendingSince()).isNotNull();
    }

    private static ImagingOrder pending() {
        ImagingOrder order = order(ImagingOrderPriority.STAT, ImagingOrderStatus.IN_PROGRESS);
        order.setAuthorizationPendingSince(LocalDateTime.now().minusHours(1));
        return order;
    }

    private static ImagingOrder order(ImagingOrderPriority priority, ImagingOrderStatus status) {
        ImagingOrder order = new ImagingOrder();
        order.setPriority(priority);
        order.setStatus(status);
        order.setRequiresAuthorization(true);
        return order;
    }
}
