package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.utility.RoleValidator;

import java.util.UUID;

/**
 * QA D4140 (r81), PROVISIONAL: an imaging exam is prescribed by a physician.
 *
 * <p>A nurse or a midwife may ask for a study, but her request is not an order: it was placed as
 * « prescrit » at once, the technician checked the patient in and started the exam (a contrast CT
 * included), and no physician had seen it. The policy the technician's own screen states (« la
 * prescription de l'examen reste au médecin », decision 3 of the policy report) is now what the
 * server does, in one place:
 * <ul>
 *   <li><b>placed</b> by somebody who is not a signing physician of the order's hospital (a doctor,
 *       a physician, a surgeon there), the request is kept as a pending request
 *       ({@link ImagingOrderStatus#DRAFT}), with who asked for it;</li>
 *   <li><b>authorised</b> by a physician of that hospital, who signs it and becomes its ordering
 *       provider (the order then reads « prescrit »), or <b>declined</b> by one, with a reason;</li>
 *   <li><b>never performed before that</b>: scheduling, the patient's check-in, the start of the
 *       exam, a report and an image are refused on a request that still waits
 *       ({@link #requireAuthorised}).</li>
 * </ul>
 * Reading is unchanged: the nurse and the midwife read the request, the order and the report as
 * before. An order written before V465 has no flag: unsigned, with a nurse or a midwife as its
 * ordering provider, it waits all the same.
 *
 * <p>Stateless: each imaging service builds one over its own {@link RoleValidator}, as
 * {@link ImagingRoleScope}.
 */
@ProvisionalRule(ClinicalRuleSetKey.IMAGING_ORDER_PHYSICIAN_AUTHORISATION)
public final class ImagingOrderAuthorisation {

    /** The refusal of any step of the exam on a request no physician has authorised. */
    public static final String AWAITING_KEY = "imaging.order.awaitingPhysician";
    static final String FIELD_STATUS = "status";

    private final RoleValidator roleValidator;

    public ImagingOrderAuthorisation(RoleValidator roleValidator) {
        this.roleValidator = roleValidator;
    }

    /**
     * Whether a request placed by this user at this hospital waits for a physician: whoever places
     * it is not a signing physician there. With no resolved user nothing can be said (an order with
     * no recorded provider keeps the rule it always had).
     */
    public boolean needsPhysician(UUID requesterUserId, UUID hospitalId) {
        return requesterUserId != null && hospitalId != null
            && !roleValidator.isSigningPhysician(requesterUserId, hospitalId);
    }

    /**
     * A new request: who asked, and whether it waits for a physician.
     *
     * @return true when the request was placed by a nurse or a midwife: the caller keeps it pending
     *         ({@link ImagingOrderStatus#DRAFT}) whatever status the request named. The status itself is
     *         written by the order service, beside its pre-authorisation reconcile.
     */
    public boolean stampRequest(ImagingOrder order, UUID requesterUserId, String requesterName) {
        UUID hospitalId = hospitalIdOf(order);
        order.setRequestedByUserId(requesterUserId);
        order.setRequestedByName(requesterName);
        boolean pending = needsPhysician(requesterUserId, hospitalId);
        order.setPhysicianAuthorisationRequired(pending);
        return pending;
    }

    /** The request still waits for a physician: nobody signed it, and it was placed by a nurse or a midwife. */
    public boolean awaitsPhysician(ImagingOrder order) {
        if (order == null || order.getProviderSignedAt() != null) {
            return false;
        }
        if (Boolean.TRUE.equals(order.getPhysicianAuthorisationRequired())) {
            return true;
        }
        UUID provider = order.getOrderingProviderUserId();
        UUID hospitalId = hospitalIdOf(order);
        return provider != null && hospitalId != null && roleValidator.draftsForPhysicianSignature(provider, hospitalId);
    }

    /**
     * Refuses a step of the exam (schedule, check-in, start, report, image) on a request that waits
     * for a physician, naming what has to happen first.
     */
    public void requireAuthorised(ImagingOrder order) {
        if (awaitsPhysician(order)) {
            throw new FieldBusinessException(FIELD_STATUS, AWAITING_KEY);
        }
    }

    /**
     * A status write that makes the exam go ahead: any move other than keeping the request pending
     * or calling it off.
     */
    public void requireAuthorisedFor(ImagingOrder order, ImagingOrderStatus target) {
        if (target != ImagingOrderStatus.DRAFT && target != ImagingOrderStatus.CANCELLED) {
            requireAuthorised(order);
        }
    }

    /**
     * What a report, its signature and an image of the study all need (QA D4140, D4142): an order a
     * physician stands behind, and an exam that has started.
     */
    public void requireExamTookPlace(ImagingOrder order) {
        requireAuthorised(order);
        com.example.hms.service.support.ImagingOrderTransitions.requireExamStarted(
            order != null ? order.getStatus() : null);
    }

    /** A physician of the order's hospital declines a request that waits for one. */
    public boolean mayDecline(ImagingOrder order, UUID callerUserId) {
        UUID hospitalId = hospitalIdOf(order);
        return callerUserId != null && hospitalId != null && awaitsPhysician(order)
            && roleValidator.isSigningPhysician(callerUserId, hospitalId);
    }

    /** Whoever placed a request that still waits withdraws it herself. */
    public boolean mayWithdraw(ImagingOrder order, UUID callerUserId) {
        if (callerUserId == null || !awaitsPhysician(order)) {
            return false;
        }
        UUID requester = order.getRequestedByUserId() != null
            ? order.getRequestedByUserId() : order.getOrderingProviderUserId();
        return callerUserId.equals(requester);
    }

    private static UUID hospitalIdOf(ImagingOrder order) {
        return order != null && order.getHospital() != null ? order.getHospital().getId() : null;
    }
}
