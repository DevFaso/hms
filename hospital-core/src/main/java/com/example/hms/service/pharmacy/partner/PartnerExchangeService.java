package com.example.hms.service.pharmacy.partner;

import com.example.hms.utility.ElapsedTime;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.pharmacy.PrescriberPharmacyNotifier;
import com.example.hms.utility.PhoneNumbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * T-53/54/55/59 — Orchestrates partner pharmacy exchange that happens outside a
 * logged-in user session: inbound SMS webhook replies (T-55) and the scheduled
 * timeout / escalation job (T-59).
 * <p>
 * All entry points are transactional and self-contained so they can be called
 * from a webhook controller (no SecurityContext) or a {@code @Scheduled}
 * method (system user).
 */
@Service
@Slf4j
public class PartnerExchangeService {

    /** T-59: remind a partner after this idle period. */
    static final Duration REMIND_AFTER = Duration.ofHours(2);
    /** T-59: auto-reject and move on after this idle period. */
    static final Duration AUTO_REJECT_AFTER = Duration.ofHours(4);

    /**
     * Decisions an SMS reply may still act on. ACCEPTED is open because the
     * partner confirms the dispense ({@code 3 <ref>}) after having accepted;
     * matching PENDING only made that third message unroutable. A decision
     * superseded by a re-dispatch (CANCELLED) or already answered is not open,
     * so a late reply to its token is ignored.
     */
    static final Set<RoutingDecisionStatus> OPEN_STATUSES =
            EnumSet.of(RoutingDecisionStatus.PENDING, RoutingDecisionStatus.ACCEPTED);

    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PartnerNotificationChannel channel;
    private final PartnerSmsReplyParser replyParser;
    private final AuditEventLogService auditEventLogService;
    private final PrescriberPharmacyNotifier prescriberNotifier;
    /** The gateway's country code, so a locally stored number matches the international reply. */
    private final String countryNumberCode;

    public PartnerExchangeService(PrescriptionRoutingDecisionRepository routingDecisionRepository,
                                  PrescriptionRepository prescriptionRepository,
                                  PartnerNotificationChannel channel,
                                  PartnerSmsReplyParser replyParser,
                                  AuditEventLogService auditEventLogService,
                                  PrescriberPharmacyNotifier prescriberNotifier,
                                  @Value("${app.ikoddi.country-number-code:226}") String countryNumberCode) {
        this.routingDecisionRepository = routingDecisionRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.channel = channel;
        this.replyParser = replyParser;
        this.auditEventLogService = auditEventLogService;
        this.prescriberNotifier = prescriberNotifier;
        this.countryNumberCode = countryNumberCode;
    }

    /** Convenience: tell the channel to notify the partner of a brand new offer. */
    public void sendNewOffer(PrescriptionRoutingDecision decision, Prescription prescription, Pharmacy partner) {
        if (decision == null || partner == null) {
            return;
        }
        channel.sendPrescriptionOffer(decision, prescription, partner);
    }

    /**
     * T-55 — Process an inbound SMS reply from a partner pharmacy.
     * Returns the updated decision when the reply was matched and applied.
     *
     * <p>G8: the reply is bound to the pharmacy that was offered the
     * prescription. The short token is only the first 8 hex characters of the
     * decision id, and the webhook secret is shared by every gateway, so the
     * token alone must never select a decision: the sender's number has to be
     * the target pharmacy's number, and exactly one open decision has to match
     * both. Anything else is ignored (and logged) rather than guessed.
     */
    @Transactional
    public Optional<PrescriptionRoutingDecision> handleInboundReply(String senderPhone, String rawBody) {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = replyParser.parse(rawBody);
        if (parsed.isEmpty()) {
            // Not guessed at. The parser understands the instructed reply and
            // nothing else, so anything a pharmacy phrased its own way reaches
            // a person instead of being interpreted into a clinical decision.
            reportUnreadableReply(senderPhone, rawBody);
            return Optional.empty();
        }
        PartnerSmsReplyParser.ParsedReply reply = parsed.get();

        PrescriptionRoutingDecision decision = findOpenByRef(reply.refToken(), senderPhone)
                .orElse(null);
        if (decision == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(applyReply(decision, reply.action()));
    }

    /**
     * T-59 — Scheduled reminder + auto-reject of stale pending partner decisions.
     * Returns a summary count useful for tests and logging.
     */
    @Transactional
    public TimeoutSweepResult sweepTimeouts() {
        LocalDateTime now = LocalDateTime.now();

        List<PrescriptionRoutingDecision> stale = routingDecisionRepository
                .findByRoutingTypeAndStatusAndDecidedAtBefore(
                        RoutingType.PARTNER,
                        RoutingDecisionStatus.PENDING,
                        now.minus(REMIND_AFTER));

        int reminded = 0;
        int autoRejected = 0;
        for (PrescriptionRoutingDecision d : stale) {
            Duration idle = ElapsedTime.between(d.getDecidedAt(), now);
            if (idle.compareTo(AUTO_REJECT_AFTER) >= 0) {
                autoReject(d);
                autoRejected++;
            } else {
                channel.sendReminder(d, d.getTargetPharmacy());
                reminded++;
            }
        }
        if (reminded > 0 || autoRejected > 0) {
            log.info("Partner timeout sweep: reminded={}, autoRejected={}", reminded, autoRejected);
        }
        return new TimeoutSweepResult(reminded, autoRejected);
    }

    /**
     * The wire form the gateway sends to, so a pharmacy stored as
     * {@code "70 11 12 22"} matches the {@code +22670111222} its reply comes
     * from — the same canonicalisation IkoddiGatewayImpl applies on the way
     * out. Empty when nothing usable remains.
     */
    String canonicalPhone(String raw) {
        return PhoneNumbers.toInternationalDigits(raw, countryNumberCode);
    }

    /**
     * Whether the number on file for the pharmacy and the number that replied
     * are the same subscriber. A stored field holding two numbers, or a number
     * with an extension, is still that pharmacy — refusing those replies (and
     * raising a security alert for each) was a bug in the comparison, not an
     * intruder.
     */
    boolean isSameSubscriber(String storedField, String senderPhone) {
        return PhoneNumbers.isSameSubscriber(storedField, senderPhone, countryNumberCode);
    }

    // ---------- internals ----------

    private Optional<PrescriptionRoutingDecision> findOpenByRef(String refToken, String senderPhone) {
        return findOpenByRef(refToken, senderPhone, true);
    }

    private Optional<PrescriptionRoutingDecision> findOpenByRef(String refToken, String senderPhone,
                                                                boolean reportMismatch) {
        if (refToken == null || refToken.isBlank()) {
            return Optional.empty();
        }
        if (canonicalPhone(senderPhone).isEmpty()) {
            log.info("Partner SMS reply for token {} carried no sender number; ignored", refToken);
            return Optional.empty();
        }
        String prefix = refToken.toLowerCase(Locale.ROOT);
        List<PrescriptionRoutingDecision> byToken = routingDecisionRepository
                .findOpenByIdPrefix(RoutingType.PARTNER, OPEN_STATUSES, prefix);
        List<PrescriptionRoutingDecision> matches = byToken.stream()
                .filter(d -> isSameSubscriber(targetPhone(d), senderPhone))
                .toList();
        if (matches.isEmpty()) {
            if (byToken.isEmpty()) {
                log.info("Partner SMS reply referenced unknown/closed token {}; ignored", refToken);
            } else if (reportMismatch) {
                // The token is live but the handset is not the one we offered
                // it to. Staying fail-closed is right — one shared webhook
                // secret is not authorisation to answer for a pharmacy — but
                // an offer that auto-rejects four hours later because the
                // pharmacist replied from a different phone must not do so in
                // silence, so this is surfaced where staff review the exchange.
                reportUnmatchedSender(byToken, refToken, senderPhone);
            }
            return Optional.empty();
        }
        if (matches.size() > 1) {
            log.warn("Partner SMS token {} from sender {} matches {} open decisions; ignored",
                    refToken, SmsPartnerNotificationChannel.maskPhone(senderPhone), matches.size());
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    /**
     * A reply that is not the instructed form. Surfaced exactly like an
     * unmatched sender: a person has to read the message in the gateway and
     * act, because nothing here will. The message body is deliberately absent
     * from the audit row — a quoted-back offer carries the medication and the
     * patient's initials.
     */
    private void reportUnreadableReply(String senderPhone, String rawBody) {
        String masked = SmsPartnerNotificationChannel.maskPhone(senderPhone);
        Optional<PrescriptionRoutingDecision> about = replyParser.candidateReferences(rawBody).stream()
                .map(ref -> findOpenByRef(ref, senderPhone, false))
                .flatMap(Optional::stream)
                .findFirst();
        String prescription = about
                .map(d -> d.getPrescription() != null ? String.valueOf(d.getPrescription().getId()) : "unknown")
                .orElse("not identified");
        log.warn("Partner SMS reply from {} is not the instructed reply; ignored and left for staff. "
                + "Prescription: {}", masked, prescription);
        auditForStaff("Partner SMS reply from " + masked + " could not be read as an accept/refuse/dispense "
                + "reply and was not applied; a person must action it. Prescription: " + prescription,
                about.map(d -> d.getId().toString()).orElse(null));
    }

    /**
     * WARN + an audit row naming the prescription(s) the reply could have been
     * for and the (masked) handset it came from, so a dropped reply is visible
     * without reading application logs. The number is masked because an
     * unmatched sender is, by definition, not a number we know belongs to a
     * pharmacy.
     */
    private void reportUnmatchedSender(List<PrescriptionRoutingDecision> byToken,
                                       String refToken, String senderPhone) {
        String masked = SmsPartnerNotificationChannel.maskPhone(senderPhone);
        String prescriptions = byToken.stream()
                .map(d -> d.getPrescription() != null ? String.valueOf(d.getPrescription().getId()) : "unknown")
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("Partner SMS reply for live token {} came from {}, which is not the pharmacy it was "
                        + "offered to; ignored. Prescription(s): {}", refToken, masked, prescriptions);
        auditForStaff("Partner SMS reply for reference " + refToken + " from unrecognised sender "
                + masked + " was ignored; offer still open for prescription(s) " + prescriptions,
                byToken.get(0).getId().toString());
    }

    private void auditForStaff(String description, String resourceId) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                    .eventDescription(description)
                    .status(AuditStatus.FAILURE)
                    .resourceId(resourceId)
                    .entityType("PRESCRIPTION_ROUTING")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log a partner-SMS event for staff: {}", e.getMessage());
        }
    }

    private static String targetPhone(PrescriptionRoutingDecision d) {
        Pharmacy target = d.getTargetPharmacy();
        return target != null ? target.getPhoneNumber() : null;
    }

    /**
     * Applies the reply, or returns {@code null} when the decision is not in
     * the state that action moves from — exactly the transitions the staff
     * endpoints in StockOutRoutingServiceImpl allow: ACCEPT / REJECT answer a
     * PENDING offer; CONFIRM_DISPENSE requires ACCEPTED (partnerRespond then
     * confirmPartnerDispense). A {@code 3} on a PENDING offer is not an
     * implicit accept: it is ignored and logged, so the patient is never told
     * "délivré" for an offer nobody accepted.
     */
    private PrescriptionRoutingDecision applyReply(PrescriptionRoutingDecision decision,
                                                   PartnerSmsReplyParser.Action action) {
        RoutingDecisionStatus required = action == PartnerSmsReplyParser.Action.CONFIRM_DISPENSE
                ? RoutingDecisionStatus.ACCEPTED
                : RoutingDecisionStatus.PENDING;
        if (decision.getStatus() != required) {
            log.info("Partner SMS {} ignored: decision {} is {}, needs {}",
                    action, decision.getId(), decision.getStatus(), required);
            return null;
        }
        Prescription rx = decision.getPrescription();
        if (rx.getStatus() != null && rx.getStatus().isWithdrawn()) {
            return applyReplyToWithdrawn(decision, rx, action);
        }
        switch (action) {
            case ACCEPT -> {
                decision.setStatus(RoutingDecisionStatus.ACCEPTED);
                rx.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
                channel.notifyPatientAccepted(decision.getDecidedForPatient(), decision.getTargetPharmacy());
                audit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                        "Partner SMS reply accepted prescription " + rx.getId(),
                        decision.getId().toString());
            }
            case REJECT -> {
                decision.setStatus(RoutingDecisionStatus.REJECTED);
                rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
                // As the staff reject (partnerRespond): the refusing partner is
                // no longer this order's pharmacy, so the work queue groups the
                // row under the in-house dispensary; the decision keeps who refused.
                rx.clearPharmacy();
                audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                        "Partner SMS reply rejected prescription " + rx.getId(),
                        decision.getId().toString());
            }
            case CONFIRM_DISPENSE -> {
                decision.setStatus(RoutingDecisionStatus.COMPLETED);
                rx.setStatus(PrescriptionStatus.PARTNER_DISPENSED);
                channel.notifyPatientDispensed(decision.getDecidedForPatient(), decision.getTargetPharmacy());
                audit(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER,
                        "Partner SMS confirmed dispense for prescription " + rx.getId(),
                        decision.getId().toString());
            }
        }
        prescriptionRepository.save(rx);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
        // The prescriber learns the outcome exactly as from the staff
        // endpoints; the notifier defers the write until this commits.
        PrescriptionStatus outcome = rx.getStatus();
        tellPrescriber(rx, () -> prescriberNotifier.notifyPrescriber(rx, outcome));
        return saved;
    }

    /**
     * A reply about an order the prescriber has since withdrawn (an offer
     * still open from before withdrawal closed it). The prescription is not
     * touched and the prescriber is not asked to re-route anything; the
     * decision is closed. An accept or a refusal changes nothing clinically,
     * so it is only audited. A dispense confirmation is different: the
     * patient may now hold a medication the prescriber stopped, so it is
     * recorded on the decision (COMPLETED — the partner did hand it over),
     * raised for staff like any reply a person must action, and the
     * prescriber is told.
     */
    private PrescriptionRoutingDecision applyReplyToWithdrawn(PrescriptionRoutingDecision decision,
                                                             Prescription rx,
                                                             PartnerSmsReplyParser.Action action) {
        PrescriptionStatus withdrawn = rx.getStatus();
        if (action == PartnerSmsReplyParser.Action.CONFIRM_DISPENSE) {
            decision.setStatus(RoutingDecisionStatus.COMPLETED);
            PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
            log.warn("Partner SMS confirmed a dispense of prescription {}, which is {}; left for staff",
                    rx.getId(), withdrawn);
            auditForStaff("Partner SMS confirmed dispensing prescription " + rx.getId() + ", which is "
                    + withdrawn + "; the prescription was not changed. A person must check with the "
                    + "patient and the partner.", decision.getId().toString());
            tellPrescriber(rx, () -> prescriberNotifier.notifyPrescriberOfDispenseAfterWithdrawal(rx));
            return saved;
        }
        decision.setStatus(RoutingDecisionStatus.CANCELLED);
        PrescriptionRoutingDecision saved = routingDecisionRepository.save(decision);
        audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                "Partner SMS " + action + " for prescription " + rx.getId() + " not applied: the "
                        + "prescription is " + withdrawn + "; offer closed",
                decision.getId().toString());
        return saved;
    }

    private void autoReject(PrescriptionRoutingDecision d) {
        Prescription rx = d.getPrescription();
        if (rx != null && rx.getStatus() != null && rx.getStatus().isWithdrawn()) {
            // Withdrawn while the offer was open: close the offer, leave the
            // order as the prescriber left it, and ask nobody to re-route it.
            d.setStatus(RoutingDecisionStatus.CANCELLED);
            routingDecisionRepository.save(d);
            channel.sendAutoRejected(d, d.getTargetPharmacy());
            audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                    "Partner offer closed after timeout; prescription " + rx.getId() + " is "
                            + rx.getStatus() + " and was not changed",
                    d.getId().toString());
            return;
        }
        d.setStatus(RoutingDecisionStatus.REJECTED);
        if (rx != null) {
            rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
            // Same state as a refusal, so the same detachment: a timed-out
            // offer must reach the in-house queue, not stay under the partner.
            rx.clearPharmacy();
            prescriptionRepository.save(rx);
            tellPrescriber(rx, () -> prescriberNotifier.notifyPrescriberOfPartnerTimeout(rx));
        }
        routingDecisionRepository.save(d);
        channel.sendAutoRejected(d, d.getTargetPharmacy());
        audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                "Partner auto-rejected after timeout for prescription "
                        + (rx != null ? rx.getId() : d.getId()),
                d.getId().toString());
    }

    /**
     * The notifier already defers its write past the commit and swallows a
     * failure there; this guards the queueing itself, so nothing about telling
     * the prescriber can fail the webhook reply or the timeout sweep, or roll
     * back the partner's answer.
     */
    private void tellPrescriber(Prescription rx, Runnable notify) {
        try {
            notify.run();
        } catch (RuntimeException ex) {
            log.warn("Could not queue the prescriber notification for prescription {}: {}",
                    rx.getId(), ex.getMessage());
        }
    }

    private void audit(AuditEventType type, String description, String resourceId) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType("PRESCRIPTION_ROUTING")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log partner-exchange audit event {}: {}", type, e.getMessage());
        }
    }

    /** Result of {@link #sweepTimeouts()}. */
    public record TimeoutSweepResult(int reminded, int autoRejected) { }
}
