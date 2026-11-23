package com.example.hms.service.support;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.ImagingOrder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The one pre-authorisation policy of an imaging order. Every write that
 * moves an imaging order's status goes through it ({@code
 * ImagingPreAuthorisationPolicyGuardTest}): the status endpoint, the order
 * signature and the report signature.
 *
 * <p><b>Provisional rule 1 (user decision 2026-11-15, pending the radiology
 * approver and the confirmation of the CNAMU rule;
 * docs/qa/regles-cliniques-provisoires.md).</b> Urgent imaging is never
 * blocked by a pre-authorisation. EMTALA forbids seeking a prior
 * authorisation before the emergency screening and stabilisation, and allows
 * it afterwards.
 * <ul>
 *   <li>A routine study whose pre-authorisation is required starts once its
 *       number is recorded (QA D1925, unchanged).</li>
 *   <li>An URGENT or STAT study starts without it. The order then shows
 *       « Préautorisation en attente » and the hospital's billing staff get a
 *       follow-up task to obtain the authorisation afterwards.</li>
 *   <li>A study that was done without its number (an urgent start, or a
 *       report signed on an order never started) is flagged the same way:
 *       a care step is never refused for want of a number.</li>
 *   <li>Recording the number (or the pre-authorisation no longer being
 *       required) clears the flag and closes the task.</li>
 * </ul>
 * Who may start a study is unchanged: that is the status endpoint's roles.
 */
@ProvisionalRule(ClinicalRuleSetKey.IMAGING_URGENT_PREAUTHORISATION)
public final class ImagingPreAuthorisationPolicy {

    /** The priorities that start without the pre-authorisation number. */
    public static final Set<ImagingOrderPriority> STARTS_WITHOUT_NUMBER =
        Collections.unmodifiableSet(EnumSet.of(ImagingOrderPriority.URGENT, ImagingOrderPriority.STAT));

    /** The statuses at which the study has been (or is being) done. */
    public static final Set<ImagingOrderStatus> STUDY_DONE_OR_RUNNING = Collections.unmodifiableSet(
        EnumSet.of(ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.COMPLETED, ImagingOrderStatus.RESULTS_AVAILABLE));

    /** What a reconcile did to the order's follow-up. */
    public enum FollowUp {
        /** The order became pending: file the follow-up task. */
        OPENED,
        /** The order is no longer pending: close the follow-up task. */
        CLOSED,
        /** Nothing changed. */
        UNCHANGED
    }

    private ImagingPreAuthorisationPolicy() {
    }

    /** Whether a priority starts without the pre-authorisation number. */
    public static boolean startsWithoutNumber(ImagingOrderPriority priority) {
        return priority != null && STARTS_WITHOUT_NUMBER.contains(priority);
    }

    /** A pre-authorisation required and not yet numbered. */
    public static boolean awaitsNumber(Boolean requiresAuthorization, String authorizationNumber) {
        return Boolean.TRUE.equals(requiresAuthorization)
            && (authorizationNumber == null || authorizationNumber.isBlank());
    }

    /**
     * Refuses to start a routine study whose pre-authorisation is required and
     * not numbered (on the order, or in the same status write). An urgent or
     * STAT study is never refused.
     */
    public static void requireStartAllowed(ImagingOrderStatus to, ImagingOrderPriority priority,
                                           Boolean requiresAuthorization, String authorizationNumber) {
        if (to == ImagingOrderStatus.IN_PROGRESS && awaitsNumber(requiresAuthorization, authorizationNumber)
                && !startsWithoutNumber(priority)) {
            throw new FieldBusinessException("authorizationNumber", "imaging.order.authorizationNumberRequired");
        }
    }

    /**
     * Whether the order is a study done without its pre-authorisation number.
     * A study cancelled after it was started stays pending: it was performed.
     */
    public static boolean isPending(ImagingOrder order) {
        if (order == null || !awaitsNumber(order.getRequiresAuthorization(), order.getAuthorizationNumber())) {
            return false;
        }
        ImagingOrderStatus status = order.getStatus();
        return STUDY_DONE_OR_RUNNING.contains(status)
            || (status == ImagingOrderStatus.CANCELLED && order.getAuthorizationPendingSince() != null);
    }

    /**
     * Brings the stored flag in line with the order after any write, and says
     * whether the follow-up task is to be filed or closed.
     */
    public static FollowUp reconcile(ImagingOrder order, LocalDateTime now) {
        if (order == null) {
            return FollowUp.UNCHANGED;
        }
        boolean pending = isPending(order);
        if (pending && order.getAuthorizationPendingSince() == null) {
            order.setAuthorizationPendingSince(now);
            return FollowUp.OPENED;
        }
        if (!pending && order.getAuthorizationPendingSince() != null) {
            order.setAuthorizationPendingSince(null);
            return FollowUp.CLOSED;
        }
        return FollowUp.UNCHANGED;
    }
}
