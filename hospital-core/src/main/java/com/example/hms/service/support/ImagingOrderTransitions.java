package com.example.hms.service.support;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.FieldBusinessException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * QA D1925 (r53): the imaging order status writes the status endpoint
 * accepts. A status write jumped ORDERED straight to RESULTS_AVAILABLE with
 * no report, and completed a study nobody had started. (Starting a study
 * whose pre-authorisation has no number is {@link ImagingPreAuthorisationPolicy}'s.)
 *
 * <ul>
 *   <li>RESULTS_AVAILABLE is reached only by signing a report
 *       ({@code ImagingReportServiceImpl}), never by a status write.</li>
 *   <li>COMPLETED follows IN_PROGRESS: a study is started before it is done.</li>
 *   <li>A resulted or cancelled order is closed (QA D1066, D1098).</li>
 *   <li>A same-status write on an open order is kept: it is how a schedule,
 *       a location or a pre-authorisation number is recorded.</li>
 * </ul>
 *
 * Administrative, not clinical: which step follows which. The portal only
 * ever sends start, complete and cancel.
 */
public final class ImagingOrderTransitions {

    /** Every status a status write may move an order to, by its current status. */
    public static final Map<ImagingOrderStatus, Set<ImagingOrderStatus>> ALLOWED;

    static {
        Map<ImagingOrderStatus, Set<ImagingOrderStatus>> allowed = new EnumMap<>(ImagingOrderStatus.class);
        allowed.put(ImagingOrderStatus.DRAFT, EnumSet.of(ImagingOrderStatus.DRAFT, ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.CANCELLED));
        allowed.put(ImagingOrderStatus.ORDERED, EnumSet.of(ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.CANCELLED));
        allowed.put(ImagingOrderStatus.PENDING_AUTHORIZATION, EnumSet.of(ImagingOrderStatus.PENDING_AUTHORIZATION,
            ImagingOrderStatus.ORDERED, ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.CANCELLED));
        allowed.put(ImagingOrderStatus.SCHEDULED, EnumSet.of(ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.ORDERED,
            ImagingOrderStatus.PENDING_AUTHORIZATION, ImagingOrderStatus.IN_PROGRESS, ImagingOrderStatus.CANCELLED));
        // An interrupted study is booked again, completed, or called off.
        allowed.put(ImagingOrderStatus.IN_PROGRESS, EnumSet.of(ImagingOrderStatus.IN_PROGRESS,
            ImagingOrderStatus.SCHEDULED, ImagingOrderStatus.COMPLETED, ImagingOrderStatus.CANCELLED));
        allowed.put(ImagingOrderStatus.COMPLETED, EnumSet.noneOf(ImagingOrderStatus.class));
        allowed.put(ImagingOrderStatus.RESULTS_AVAILABLE, EnumSet.noneOf(ImagingOrderStatus.class));
        allowed.put(ImagingOrderStatus.CANCELLED, EnumSet.noneOf(ImagingOrderStatus.class));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    private ImagingOrderTransitions() {
    }

    public static boolean isAllowed(ImagingOrderStatus from, ImagingOrderStatus to) {
        if (to == null) {
            return false;
        }
        // An order written before statuses were enforced is read as placed.
        ImagingOrderStatus current = from != null ? from : ImagingOrderStatus.ORDERED;
        return ALLOWED.getOrDefault(current, Set.of()).contains(to);
    }

    /** Refuses a status write the table does not allow, with the reason the reader needs. */
    public static void require(ImagingOrderStatus from, ImagingOrderStatus to) {
        if (isAllowed(from, to)) {
            return;
        }
        if (to == ImagingOrderStatus.RESULTS_AVAILABLE) {
            throw new FieldBusinessException("status", "imaging.order.resultsOnlyBySigning");
        }
        if (to == ImagingOrderStatus.COMPLETED) {
            throw new FieldBusinessException("status", "imaging.order.completeBeforeStart");
        }
        throw new FieldBusinessException("status", "imaging.order.invalidTransition");
    }
}
