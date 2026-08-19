package com.example.hms.service.pharmacy.partner;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
@RequiredArgsConstructor
@Slf4j
public class PartnerExchangeService {

    /** T-59: remind a partner after this idle period. */
    static final Duration REMIND_AFTER = Duration.ofHours(2);
    /** T-59: auto-reject and move on after this idle period. */
    static final Duration AUTO_REJECT_AFTER = Duration.ofHours(4);

    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PartnerNotificationChannel channel;
    private final PartnerSmsReplyParser replyParser;
    private final AuditEventLogService auditEventLogService;

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
     */
    @Transactional
    public Optional<PrescriptionRoutingDecision> handleInboundReply(String rawBody) {
        Optional<PartnerSmsReplyParser.ParsedReply> parsed = replyParser.parse(rawBody);
        if (parsed.isEmpty()) {
            log.info("Partner SMS reply unparseable: {}", safeTruncate(rawBody));
            return Optional.empty();
        }
        PartnerSmsReplyParser.ParsedReply reply = parsed.get();

        PrescriptionRoutingDecision decision = findPendingByRef(reply.refToken())
                .orElse(null);
        if (decision == null) {
            log.info("Partner SMS reply referenced unknown/non-pending token {}", reply.refToken());
            return Optional.empty();
        }

        return Optional.of(applyReply(decision, reply.action()));
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
            Duration idle = Duration.between(d.getDecidedAt(), now);
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

    // ---------- internals ----------

    private Optional<PrescriptionRoutingDecision> findPendingByRef(String refToken) {
        if (refToken == null || refToken.isBlank()) {
            return Optional.empty();
        }
        String needle = refToken.toUpperCase(java.util.Locale.ROOT);
        return routingDecisionRepository
                .findByRoutingTypeAndStatus(RoutingType.PARTNER, RoutingDecisionStatus.PENDING)
                .stream()
                .filter(d -> d.getId() != null
                        && d.getId().toString().toUpperCase(java.util.Locale.ROOT).startsWith(needle))
                .findFirst();
    }

    private PrescriptionRoutingDecision applyReply(PrescriptionRoutingDecision decision,
                                                   PartnerSmsReplyParser.Action action) {
        Prescription rx = decision.getPrescription();
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
        return routingDecisionRepository.save(decision);
    }

    private void autoReject(PrescriptionRoutingDecision d) {
        d.setStatus(RoutingDecisionStatus.REJECTED);
        Prescription rx = d.getPrescription();
        if (rx != null) {
            rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
            prescriptionRepository.save(rx);
        }
        routingDecisionRepository.save(d);
        channel.sendAutoRejected(d, d.getTargetPharmacy());
        audit(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL,
                "Partner auto-rejected after timeout for prescription "
                        + (rx != null ? rx.getId() : d.getId()),
                d.getId().toString());
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

    private static String safeTruncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** Result of {@link #sweepTimeouts()}. */
    public record TimeoutSweepResult(int reminded, int autoRejected) { }
}
