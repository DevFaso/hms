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
    public static final String FINISHED_KEY = "order.review.finishedUnderEvent";
    public static final String COPY_KEY = "order.review.copyHeld";

    private static volatile BiPredicate<OrderReviewType, UUID> held;
    private static volatile java.util.function.BiFunction<OrderReviewType, UUID, java.util.Set<UUID>> heldAtHospital;
    private static volatile com.example.hms.service.OrderReviewHoldService review;

    private final OrderReviewHoldRepository holds;

    @Override
    public void afterPropertiesSet() {
        useHolds((type, id) -> holds.existsByOrderTypeAndOrderIdAndStatus(type, id,
            type == OrderReviewType.RUNNING_TRANSFUSION ? OrderReviewStatus.ASSESSMENT_DUE : OrderReviewStatus.HELD));
        heldAtHospital = (type, hospital) -> {
            OrderReviewStatus standing = type == OrderReviewType.RUNNING_TRANSFUSION ? OrderReviewStatus.ASSESSMENT_DUE : OrderReviewStatus.HELD;
            return hospital != null ? holds.findStandingAtHospital(type, standing, hospital) : holds.findStanding(type, standing);
        };
    }

    /** For the unit tests that walk the rule; Spring sets it at startup. */
    public static void useHolds(BiPredicate<OrderReviewType, UUID> lookup) {
        held = lookup;
        heldAtHospital = null;
    }

    /** Set at startup by the review service, which owns the row; none means a test with no store. */
    public static void useReview(com.example.hms.service.OrderReviewHoldService service) {
        review = service;
    }

    /** A relevant change after a reconfirmation invalidates it. Called in the transaction of the change. */
    public static void reviewAgainAfterChange(UUID prescriptionId) {
        if (review != null) {
            review.reviewAgainAfterChange(OrderReviewType.PRESCRIPTION, prescriptionId);
        }
    }

    /** As {@link #reviewAgainAfterChange}, for a blood request moved onto another blood group screen. */
    public static void reviewRequestAgainAfterChange(UUID requestId) {
        if (review != null) {
            review.reviewAgainAfterChange(OrderReviewType.TRANSFUSION_REQUEST, requestId);
        }
    }

    /** A cancelled fill put an existing order back in use: it is held if a death event stands over it. */
    public static void holdIfEventStands(com.example.hms.model.Prescription order) {
        if (review != null) {
            review.holdIfEventStands(order);
        }
    }

    /**
     * A screen's rows, in one question: the standing rows are few, whatever the rows of the screen. {@code hospital}
     * null is a global view. A test with no store asks its own lookup, row by row.
     */
    public static java.util.Set<UUID> heldAmong(OrderReviewType type, UUID hospital, java.util.Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return java.util.Set.of();
        }
        if (heldAtHospital != null) {
            return heldAtHospital.apply(type, hospital);
        }
        java.util.Set<UUID> found = new java.util.HashSet<>();
        for (UUID id : orderIds) {
            if (isHeld(type, id)) {
                found.add(id);
            }
        }
        return found;
    }

    /**
     * Since when this order waits on a prescriber, or null when it does not: a standing hold, or a finished order an
     * event stands over, which nothing could hold when it was recorded and which must not be used as if it were spent.
     */
    public static java.time.LocalDateTime reviewPendingSince(com.example.hms.model.Prescription order) {
        if (order == null) {
            return null;
        }
        if (review != null) {
            return review.pendingSince(order);
        }
        return isHeld(OrderReviewType.PRESCRIPTION, order.getId()) ? java.time.LocalDateTime.MAX : null;
    }

    /** Which refusal applies while {@link #reviewPendingSince} answers. */
    public static String pendingKey(com.example.hms.model.Prescription order) {
        return isHeld(OrderReviewType.PRESCRIPTION, order.getId()) ? PRESCRIPTION_KEY : FINISHED_KEY;
    }

    /** A use of this order, the bedside check included, while a prescriber has not reviewed it. */
    public static void refuseIfReviewPending(com.example.hms.model.Prescription order, String field) {
        if (reviewPendingSince(order) != null) {
            throw new FieldBusinessException(field, pendingKey(order));
        }
    }

    /** A printed copy is something to take to a pharmacy: not while a prescriber has not reviewed the order. */
    public static void refuseCopyIfReviewPending(com.example.hms.model.Prescription order) {
        if (reviewPendingSince(order) != null) {
            throw new com.example.hms.exception.BusinessException(COPY_KEY);
        }
    }

    /** Charted, on the review list too, so the prescriber sees it. Called in the transaction of the dose. */
    public static void doseGivenBeforeHold(com.example.hms.model.Prescription order, java.time.LocalDateTime at, String why) {
        if (review != null) {
            review.doseGivenBeforeHold(order, at, why);
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
