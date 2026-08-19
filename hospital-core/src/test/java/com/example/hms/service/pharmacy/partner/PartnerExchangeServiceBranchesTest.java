package com.example.hms.service.pharmacy.partner;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link PartnerExchangeService}: the public
 * {@code sendNewOffer} guard, auto-reject with null prescription, audit
 * failure path, null and oversized raw SMS bodies.
 */
@ExtendWith(MockitoExtension.class)
class PartnerExchangeServiceBranchesTest {

    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PartnerNotificationChannel channel;
    @Mock private AuditEventLogService auditEventLogService;

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    private PartnerExchangeService service;

    private PrescriptionRoutingDecision decision;
    private Prescription prescription;
    private Pharmacy partner;
    private UUID decisionId;

    @BeforeEach
    void setUp() {
        service = new PartnerExchangeService(
                routingDecisionRepository, prescriptionRepository,
                channel, parser, auditEventLogService);

        decisionId = UUID.randomUUID();
        partner = Pharmacy.builder().name("Pharmacie Nord").build();
        partner.setId(UUID.randomUUID());

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setStatus(PrescriptionStatus.SENT_TO_PARTNER);

        decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partner)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now())
                .build();
        decision.setId(decisionId);
    }

    @Test
    @DisplayName("sendNewOffer forwards to channel when decision and partner non-null")
    void sendNewOfferForwards() {
        service.sendNewOffer(decision, prescription, partner);
        verify(channel).sendPrescriptionOffer(decision, prescription, partner);
    }

    @Test
    @DisplayName("sendNewOffer no-op when decision is null")
    void sendNewOfferNullDecision() {
        service.sendNewOffer(null, prescription, partner);
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("sendNewOffer no-op when partner is null")
    void sendNewOfferNullPartner() {
        service.sendNewOffer(decision, prescription, null);
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("handleInboundReply returns empty and does not throw on null body")
    void handleInboundReplyNull() {
        assertThat(service.handleInboundReply(null)).isEmpty();
        verifyNoInteractions(channel, routingDecisionRepository, prescriptionRepository);
    }

    @Test
    @DisplayName("handleInboundReply returns empty when no pending decisions found")
    void handleInboundReplyNoPending() {
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of());

        assertThat(service.handleInboundReply("1 ABCDEFGH")).isEmpty();
        verify(routingDecisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("handleInboundReply truncates long unparseable bodies safely")
    void handleInboundReplyLongUnparseable() {
        // 120 chars of non-action text — parser returns empty, safeTruncate branch exercised
        String longBody = "a".repeat(120);
        assertThat(service.handleInboundReply(longBody)).isEmpty();
    }

    @Test
    @DisplayName("sweepTimeouts with no stale decisions returns zeroes")
    void sweepTimeoutsEmpty() {
        when(routingDecisionRepository.findByRoutingTypeAndStatusAndDecidedAtBefore(
                eq(RoutingType.PARTNER), eq(RoutingDecisionStatus.PENDING), any()))
                .thenReturn(List.of());

        PartnerExchangeService.TimeoutSweepResult r = service.sweepTimeouts();

        assertThat(r.reminded()).isZero();
        assertThat(r.autoRejected()).isZero();
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("auto-reject handles decision with null prescription gracefully")
    void autoRejectNullPrescription() {
        decision.setPrescription(null);
        decision.setDecidedAt(LocalDateTime.now().minusHours(5));
        when(routingDecisionRepository.findByRoutingTypeAndStatusAndDecidedAtBefore(
                eq(RoutingType.PARTNER), eq(RoutingDecisionStatus.PENDING), any()))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PartnerExchangeService.TimeoutSweepResult r = service.sweepTimeouts();

        assertThat(r.autoRejected()).isEqualTo(1);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        verify(prescriptionRepository, never()).save(any());
        verify(channel).sendAutoRejected(decision, partner);
    }

    @Test
    @DisplayName("inbound accept still works when audit service throws")
    void inboundAcceptAuditFailureIsSwallowed() {
        String token = decisionId.toString().substring(0, 8).toUpperCase();
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("audit down"))
                .when(auditEventLogService).logEvent(any());

        Optional<PrescriptionRoutingDecision> updated = service.handleInboundReply("1 " + token);

        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("blank ref token in reply is ignored (no repo lookup)")
    void blankRefTokenIgnored() {
        // "1 a" — "a" is 1 char, below token pattern min of 3. Parser returns empty.
        assertThat(service.handleInboundReply("1 a")).isEmpty();
        verifyNoInteractions(routingDecisionRepository);
    }

    @Test
    @DisplayName("audit fired via PRESCRIPTION_SENT_TO_PARTNER type for accept")
    void acceptFiresSentAudit() {
        String token = decisionId.toString().substring(0, 8).toUpperCase();
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleInboundReply("1 " + token);

        verify(auditEventLogService).logEvent(org.mockito.ArgumentMatchers.argThat(
                req -> req != null && req.getEventType() == AuditEventType.PRESCRIPTION_SENT_TO_PARTNER));
    }
}
