package com.example.hms.service.pharmacy.partner;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.pharmacy.PrescriberPharmacyNotifier;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * What happens to a partner-pharmacy offer once the prescriber has withdrawn
 * the order (CANCELLED / DISCONTINUED). One place for the rule, so the SMS
 * webhook, the timeout sweep and the pharmacist's endpoints answer alike:
 *
 * <ul>
 *   <li>the prescription keeps the status the prescriber gave it — nothing
 *       here moves it back into a queue or onto a partner status;</li>
 *   <li>the prescriber is never asked to re-route an order they withdrew;</li>
 *   <li>a partner that says it dispensed anyway is recorded and raised,
 *       because the patient may now hold a medication that was stopped.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WithdrawnOrderPartnerHandler {

    private static final String AUDIT_ENTITY = "PRESCRIPTION_ROUTING";

    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final PartnerNotificationChannel channel;
    private final AuditEventLogService auditEventLogService;
    private final PrescriberPharmacyNotifier prescriberNotifier;

    /** Whether the prescriber has withdrawn this order. */
    public static boolean isWithdrawn(Prescription prescription) {
        return prescription != null && prescription.getStatus() != null
                && prescription.getStatus().isWithdrawn();
    }

    /**
     * Called in the transaction that withdraws the order. Unanswered (PENDING)
     * partner offers are closed as CANCELLED, so the timeout sweep never
     * touches them and a late reply to them is ignored. An ACCEPTED offer is
     * left open on purpose: the partner may already have handed the
     * medication over, and its dispense confirmation must still match so it
     * is recorded and surfaced rather than dropped as a closed token.
     *
     * <p>Every partner holding one of those offers — PENDING or ACCEPTED — is
     * told the order was withdrawn and must not be dispensed, once the
     * withdrawal has committed: a rolled-back withdrawal must not stop a
     * pharmacy preparing an order that is still live.
     */
    public void withdrawPartnerOffers(Prescription prescription) {
        if (prescription == null || prescription.getId() == null) {
            return;
        }
        for (PrescriptionRoutingDecision decision
                : routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescription.getId())) {
            if (decision.getRoutingType() != RoutingType.PARTNER) {
                continue;
            }
            RoutingDecisionStatus status = decision.getStatus();
            if (status == RoutingDecisionStatus.PENDING) {
                decision.setStatus(RoutingDecisionStatus.CANCELLED);
                routingDecisionRepository.save(decision);
                tellPartnerAfterCommit(decision);
            } else if (status == RoutingDecisionStatus.ACCEPTED) {
                tellPartnerAfterCommit(decision);
            }
        }
    }

    /**
     * An accept, a refusal, a timeout or a no-show on an offer whose order is
     * withdrawn: the offer is closed (CANCELLED) and the event audited; the
     * prescription is not changed and nobody is notified.
     *
     * @param what who did what, for the audit row (no PHI)
     */
    public PrescriptionRoutingDecision closeOffer(PrescriptionRoutingDecision decision,
                                                  Prescription prescription, String what) {
        decision.setStatus(RoutingDecisionStatus.CANCELLED);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
        audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL, AuditStatus.SUCCESS,
                what + " on prescription " + prescription.getId() + ", which is " + prescription.getStatus()
                        + ": the prescription was not changed; offer closed",
                decision.getId());
        return saved;
    }

    /**
     * A partner dispense confirmed (by SMS or by a pharmacist) for an order the
     * prescriber had withdrawn. Recorded on the decision as COMPLETED — the
     * partner did hand it over — while the prescription keeps its withdrawn
     * status; raised as a SECURITY_ALERT_TRIGGERED / FAILURE row, the
     * "a person must action it" surface the partner exchange already uses;
     * and the prescriber is told. The patient is not sent the usual
     * "dispensed" SMS.
     *
     * @param source who confirmed it, for the audit row (no PHI)
     */
    public PrescriptionRoutingDecision recordDispenseOfWithdrawn(PrescriptionRoutingDecision decision,
                                                                 Prescription prescription, String source) {
        decision.setStatus(RoutingDecisionStatus.COMPLETED);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
        log.warn("{} confirmed a dispense of prescription {}, which is {}; left for staff",
                source, prescription.getId(), prescription.getStatus());
        audit(AuditEventType.SECURITY_ALERT_TRIGGERED, AuditStatus.FAILURE,
                source + " confirmed dispensing prescription " + prescription.getId() + ", which is "
                        + prescription.getStatus() + "; the prescription was not changed. A person must "
                        + "check with the patient and the partner.",
                decision.getId());
        try {
            prescriberNotifier.notifyPrescriberOfDispenseAfterWithdrawal(prescription);
        } catch (RuntimeException ex) {
            log.warn("Could not queue the prescriber notification for prescription {}: {}",
                    prescription.getId(), ex.getMessage());
        }
        return saved;
    }

    /**
     * The LAZY pharmacy is read here, inside the transaction; the callback
     * runs with no persistence context (see PrescriptionSmsDispatchServiceImpl
     * notifySuperseded, which this mirrors). A send failure is logged with the
     * decision id only and never reaches the withdrawal.
     */
    private void tellPartnerAfterCommit(PrescriptionRoutingDecision decision) {
        Pharmacy partner = decision.getTargetPharmacy();
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        UUID decisionId = decision.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                channel.sendWithdrawn(decision, partner);
            } catch (RuntimeException ex) {
                log.warn("Could not tell the partner pharmacy that decision {} was withdrawn: {}",
                        decisionId, ex.getMessage());
            }
        });
    }

    private void audit(AuditEventType type, AuditStatus status, String description, UUID decisionId) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .eventDescription(description)
                    .status(status)
                    .resourceId(decisionId != null ? decisionId.toString() : null)
                    .entityType(AUDIT_ENTITY)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log withdrawn-order partner event {}: {}", type, e.getMessage());
        }
    }
}
