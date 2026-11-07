package com.example.hms.service.support;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.repository.OrderReviewHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The one rule for an order held for review (user ruling, round 36; NOT clinically approved): an order that existed
 * when a recorded death was disputed is not used until a prescriber has reviewed it. Sign, pharmacist verification,
 * dispense, fill release, hand-over, MAR, transfusion issue and start all ask here, with no exemption of their own,
 * and every ordinary safeguard runs as ever afterwards: reconfirming lifts this and nothing else.
 *
 * <p>Static and keyed on the order, as {@link DeceasedPatientGuard} is, so no service changes shape. A hold is about
 * the order and outlives the dispute: it is asked whatever the patient's state.
 * {@code OrderReviewHoldGuardTest} fails until a new entry point on an order is classified.
 */
@Component
@RequiredArgsConstructor
public class OrderReviewHoldGuard implements InitializingBean {

    public static final String PRESCRIPTION_KEY = "order.review.held.prescription";
    public static final String BLOOD_KEY = "order.review.held.bloodRequest";

    private static volatile BiPredicate<OrderReviewType, UUID> held;
    private static volatile java.util.function.BiConsumer<OrderReviewType, UUID> reviewAgain;

    private final OrderReviewHoldRepository holds;

    @Override
    public void afterPropertiesSet() {
        useHolds((type, id) -> holds.existsByOrderTypeAndOrderIdAndStatus(type, id,
            type == OrderReviewType.RUNNING_TRANSFUSION ? OrderReviewStatus.ASSESSMENT_DUE : OrderReviewStatus.HELD));
    }

    /** For the unit tests that walk the rule; Spring sets it at startup. */
    public static void useHolds(BiPredicate<OrderReviewType, UUID> lookup) {
        held = lookup;
    }

    /** Set at startup by the review service, which owns the row; none means a test with no store. */
    public static void useReviewAgain(java.util.function.BiConsumer<OrderReviewType, UUID> action) {
        reviewAgain = action;
    }

    /** A relevant change after a reconfirmation invalidates it. Called in the transaction of the change. */
    public static void reviewAgainAfterChange(UUID prescriptionId) {
        if (reviewAgain != null) {
            reviewAgain.accept(OrderReviewType.PRESCRIPTION, prescriptionId);
        }
    }

    /** As {@link #reviewAgainAfterChange}, for a blood request moved onto another blood group screen. */
    public static void reviewRequestAgainAfterChange(UUID requestId) {
        if (reviewAgain != null) {
            reviewAgain.accept(OrderReviewType.TRANSFUSION_REQUEST, requestId);
        }
    }

    /** THE rule. A running treatment's row answers whether its assessment is due: its flag, never a refusal. */
    public static boolean isHeld(OrderReviewType type, UUID orderId) {
        return orderId != null && held != null && held.test(type, orderId);
    }

    public static void refusePrescriptionIfHeld(UUID prescriptionId, String field) {
        if (isHeld(OrderReviewType.PRESCRIPTION, prescriptionId)) {
            throw new FieldBusinessException(field, PRESCRIPTION_KEY);
        }
    }

    public static void refuseBloodRequestIfHeld(UUID requestId, String field) {
        if (isHeld(OrderReviewType.TRANSFUSION_REQUEST, requestId)) {
            throw new FieldBusinessException(field, BLOOD_KEY);
        }
    }
}
