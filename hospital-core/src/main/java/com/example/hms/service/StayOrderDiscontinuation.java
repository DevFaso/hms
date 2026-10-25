package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.QueueClaimExitActor;
import com.example.hms.enums.QueueClaimReleaseReason;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.pharmacy.PreparedFillVoider;
import com.example.hms.service.pharmacy.PrescriptionQueueClaimService;
import com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * QA D757: a ward order ends with its stay. After a discharge the stay's
 * orders stayed SIGNED: they sat on the pharmacy queue, were verified and
 * dispensed (IV ceftriaxone for a patient at home) and printed on the
 * after-visit summary as medicines to take home, while the MAR had already
 * dropped them. Every discharge path (direct, and the approval flow) calls
 * this before the stay's encounter is closed: each live or partner-status
 * order of the stay (the rule the MAR uses, {@link InpatientOrderEnd#orderOfStay})
 * that is not marked take-home is DISCONTINUED with a reason, exactly as a
 * prescriber's withdrawal: its prepared fill voided, its queue claim released
 * and its partner offers withdrawn; its open back order is closed too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StayOrderDiscontinuation {

    /** The statuses of an order that is still live: anything not yet withdrawn or fully handed over. */
    static final Set<PrescriptionStatus> LIVE_STATUSES = EnumSet.of(
        PrescriptionStatus.DRAFT,
        PrescriptionStatus.PENDING_SIGNATURE,
        PrescriptionStatus.SIGNED,
        PrescriptionStatus.TRANSMITTED,
        PrescriptionStatus.TRANSMISSION_FAILED,
        PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.PENDING_STOCK,
        PrescriptionStatus.PARTIALLY_FILLED);

    private final PrescriptionRepository prescriptionRepository;
    private final PreparedFillVoider preparedFills;
    private final PrescriptionQueueClaimService queueClaims;
    private final WithdrawnOrderPartnerHandler withdrawnOrders;
    private final MessageSource messageSource;
    private final EndedStayOrderGuard endedStayOrders;
    private final PrescriptionRoutingDecisionRepository routingDecisions;
    private final AuditEventLogService auditEventLogService;
    private final DispenseRepository dispenseRepository;

    /**
     * QA D792 (#953 review, round 2): where an order that ended with a stay can
     * still sit once the thing that held it is gone: a live status, or a
     * partner status (an offer sent before the stay ended, then refused, timed
     * out or not collected, would otherwise land back in the work queue).
     */
    static final Set<PrescriptionStatus> ENDABLE_STATUSES;

    static {
        Set<PrescriptionStatus> endable = EnumSet.copyOf(LIVE_STATUSES);
        endable.add(PrescriptionStatus.SENT_TO_PARTNER);
        endable.add(PrescriptionStatus.PARTNER_ACCEPTED);
        endable.add(PrescriptionStatus.PARTNER_REJECTED);
        ENDABLE_STATUSES = Collections.unmodifiableSet(endable);
    }

    /**
     * Discontinues the live (or partner-status, {@link #ENDABLE_STATUSES}),
     * not take-home orders of the stay. Returns the
     * orders it ended (for the caller's tests and audit); ids only are logged.
     */
    @Transactional
    public List<Prescription> discontinueStayOrders(Admission stay, UUID actorUserId) {
        if (stay == null || stay.getId() == null || stay.getPatient() == null || stay.getHospital() == null) {
            return List.of();
        }
        List<Prescription> candidates = prescriptionRepository.findByPatient_IdAndHospital_Id(
            stay.getPatient().getId(), stay.getHospital().getId());
        String reason = messageSource.getMessage("prescription.discontinuedAtDischarge", null,
            "Discontinued at discharge", LocaleContextHolder.getLocale());
        List<Prescription> ended = new ArrayList<>();
        for (Prescription rx : candidates) {
            if (!endsWithStay(rx, stay)) {
                continue;
            }
            rx.setStatus(PrescriptionStatus.DISCONTINUED);
            rx.setWithdrawalReason(reason);
            withdrawnOrders.withdrawPartnerOffers(rx);
            // #953 review, round 3: nothing is owed on a stopped order, so its
            // open back order closes with it, as in discontinueIfStayEnded.
            closeOpenBackOrders(rx);
            preparedFills.voidPreparedFill(rx, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
            queueClaims.releaseOnExit(rx, QueueClaimReleaseReason.WITHDRAWN, actorUserId, QueueClaimExitActor.OTHER);
            ended.add(rx);
        }
        if (!ended.isEmpty()) {
            prescriptionRepository.saveAll(ended);
            log.info("Discontinued {} ward order(s) of admission {} at discharge", ended.size(), stay.getId());
        }
        return ended;
    }

    /**
     * QA D792 (#953 review, round 2): an order that ended with a stay but was
     * left live because something held it (migration V233 leaves an order
     * with a prepared fill at the counter or an open partner offer; a partner
     * offer sent before the stay ended) is ended the moment that hold goes:
     * the pharmacist cancels the prepared fill, or the partner offer is
     * refused, times out or is not collected. Without this the order went back
     * to SIGNED, PARTIALLY_FILLED, PENDING_STOCK or PARTNER_REJECTED, stayed in
     * the work queue, and every action on it was refused (stayEnded), so
     * nothing ever cleared it.
     *
     * <p>Exactly what the discharge does to the stay's orders: DISCONTINUED
     * with the discharge reason, its partner offers withdrawn (the partner is
     * told after the commit), its open back order closed (nothing is owed on
     * a stopped order), its queue claim released; plus one
     * PRESCRIPTION_DISCONTINUED audit row (ids only), written after the commit.
     *
     * <p>A prepared fill still waiting at the counter is a hold of its own:
     * the order is left as it is and ends when the pharmacist cancels the
     * fill ({@code DispenseServiceImpl.cancelReady}). So this never voids a
     * fill, and needs no prescription row lock from a caller (the partner
     * reply and the timeout sweep take none).
     *
     * @param trigger what released the hold, for the audit row (no PHI)
     * @return whether the order was ended
     */
    @Transactional
    public boolean discontinueIfStayEnded(Prescription rx, UUID actorUserId, String trigger) {
        if (rx == null || rx.getId() == null || rx.getStatus() == null
            || !ENDABLE_STATUSES.contains(rx.getStatus()) || !endedStayOrders.endedWithAStay(rx)
            || dispenseRepository.existsByPrescription_IdAndStatus(rx.getId(), DispenseStatus.PENDING)) {
            return false;
        }
        PrescriptionStatus previous = rx.getStatus();
        rx.setStatus(PrescriptionStatus.DISCONTINUED);
        rx.setWithdrawalReason(messageSource.getMessage("prescription.discontinuedAtDischarge", null,
            "Discontinued at discharge", LocaleContextHolder.getLocale()));
        withdrawnOrders.withdrawPartnerOffers(rx);
        closeOpenBackOrders(rx);
        queueClaims.releaseOnExit(rx, QueueClaimReleaseReason.WITHDRAWN, actorUserId, QueueClaimExitActor.OTHER);
        prescriptionRepository.save(rx);
        audit(rx, actorUserId, previous, trigger);
        log.info("Discontinued prescription {} of an ended stay ({} -> DISCONTINUED)", rx.getId(), previous);
        return true;
    }

    private void closeOpenBackOrders(Prescription rx) {
        for (PrescriptionRoutingDecision decision : routingDecisions.findByPrescriptionIdOrderByDecidedAtDesc(rx.getId())) {
            if (decision.getRoutingType() == RoutingType.BACKORDER
                && (decision.getStatus() == RoutingDecisionStatus.PENDING
                    || decision.getStatus() == RoutingDecisionStatus.ACCEPTED)) {
                decision.setStatus(RoutingDecisionStatus.CANCELLED);
                routingDecisions.save(decision);
            }
        }
    }

    /**
     * Written once the discontinuation has committed (the audit service writes
     * in a transaction of its own, so a row written now would survive a
     * rollback of the order it describes). Built here from ids only: the
     * callback runs with no persistence context.
     */
    private void audit(Prescription rx, UUID actorUserId, PrescriptionStatus previous, String trigger) {
        UUID rxId = rx.getId();
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .userId(actorUserId)
            .hospitalId(rx.getHospital() != null ? rx.getHospital().getId() : null)
            .patientId(rx.getPatient() != null ? rx.getPatient().getId() : null)
            .eventType(AuditEventType.PRESCRIPTION_DISCONTINUED)
            .status(AuditStatus.SUCCESS)
            .eventDescription("Ward order of an ended stay discontinued (QA D792): " + trigger)
            .details("status " + previous + " -> DISCONTINUED; not take-home")
            .entityType("Prescription")
            .resourceId(rxId.toString())
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("Could not audit the discontinuation of prescription {}: {}", rxId, ex.getMessage());
            }
        });
    }

    /**
     * An order of this stay, not marked for home, that is live or sits on a
     * partner status (#953 review, round 3). A PARTNER_REJECTED order is back
     * in the in-house work queue with no offer left to end it, so a discharge
     * that skipped it left it there with every action refused; an order a
     * partner still holds ends too, its offer withdrawn and the partner told
     * ({@link WithdrawnOrderPartnerHandler#withdrawPartnerOffers}), exactly as
     * a prescriber's withdrawal.
     */
    static boolean endsWithStay(Prescription rx, Admission stay) {
        return rx != null && !rx.isTakeHome()
            && rx.getStatus() != null && ENDABLE_STATUSES.contains(rx.getStatus())
            && InpatientOrderEnd.orderOfStay(rx, stay);
    }
}
