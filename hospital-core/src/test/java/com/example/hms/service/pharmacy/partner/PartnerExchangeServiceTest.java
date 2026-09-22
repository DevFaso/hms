package com.example.hms.service.pharmacy.partner;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerExchangeServiceTest {

    private static final String PARTNER_PHONE = "+22670000000";

    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PartnerNotificationChannel channel;
    @Mock private AuditEventLogService auditEventLogService;

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    private PartnerExchangeService service;

    private UUID decisionId;
    private String token;
    private PrescriptionRoutingDecision decision;
    private Prescription prescription;
    private Pharmacy partner;

    @BeforeEach
    void setUp() {
        service = new PartnerExchangeService(
                routingDecisionRepository, prescriptionRepository,
                channel, parser, auditEventLogService, "226");

        decisionId = UUID.randomUUID();
        token = decisionId.toString().substring(0, 8).toUpperCase();
        partner = Pharmacy.builder().name("Pharmacie Centrale").phoneNumber(PARTNER_PHONE).build();
        partner.setId(UUID.randomUUID());

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setStatus(PrescriptionStatus.SENT_TO_PARTNER);

        decision = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partner)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now().minusMinutes(30))
                .build();
        decision.setId(decisionId);
    }

    /** The repository is asked for the token prefix (lower-cased) over the OPEN statuses only. */
    private void stubPrefixLookup(PrescriptionRoutingDecision... found) {
        when(routingDecisionRepository.findOpenByIdPrefix(
                RoutingType.PARTNER, PartnerExchangeService.OPEN_STATUSES, token.toLowerCase()))
                .thenReturn(List.of(found));
    }

    private void stubSaves() {
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("inbound accept reply updates statuses and notifies patient")
    void inboundAccept() {
        stubPrefixLookup(decision);
        stubSaves();

        Optional<PrescriptionRoutingDecision> updated =
                service.handleInboundReply(PARTNER_PHONE, "1 " + token);

        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
        verify(channel).notifyPatientAccepted(any(), eq(partner));
    }

    @Test
    @DisplayName("inbound reject reply does not notify patient")
    void inboundReject() {
        stubPrefixLookup(decision);
        stubSaves();

        service.handleInboundReply(PARTNER_PHONE, "2 " + token);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
        verify(channel, times(0)).notifyPatientAccepted(any(), any());
        verify(channel, times(0)).notifyPatientDispensed(any(), any());
    }

    @Test
    @DisplayName("inbound dispense confirmation notifies patient and completes decision")
    void inboundDispense() {
        stubPrefixLookup(decision);
        stubSaves();

        service.handleInboundReply(PARTNER_PHONE, "3 " + token);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
        verify(channel).notifyPatientDispensed(any(), eq(partner));
    }

    @Test
    @DisplayName("an ACCEPTED decision still takes the dispense confirmation (1 then 3)")
    void inboundDispenseAfterAccept() {
        decision.setStatus(RoutingDecisionStatus.ACCEPTED);
        prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
        stubPrefixLookup(decision);
        stubSaves();

        Optional<PrescriptionRoutingDecision> updated =
                service.handleInboundReply(PARTNER_PHONE, "3 " + token);

        assertThat(updated).isPresent();
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);
    }

    @Test
    @DisplayName("an ACCEPTED decision ignores a second accept or a late reject")
    void acceptedDecisionIgnoresAcceptAndReject() {
        decision.setStatus(RoutingDecisionStatus.ACCEPTED);
        prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
        stubPrefixLookup(decision);

        assertThat(service.handleInboundReply(PARTNER_PHONE, "1 " + token)).isEmpty();
        assertThat(service.handleInboundReply(PARTNER_PHONE, "2 " + token)).isEmpty();

        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
        verify(routingDecisionRepository, never()).save(any());
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("G8: the sender must be the target pharmacy's number — same token, other phone is ignored")
    void replyFromUnknownPhoneIgnored() {
        stubPrefixLookup(decision);

        Optional<PrescriptionRoutingDecision> result =
                service.handleInboundReply("+22699999999", "1 " + token);

        assertThat(result).isEmpty();
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        verify(routingDecisionRepository, never()).save(any());
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("G8: sender formatting differences do not break the match")
    void senderPhoneIsNormalised() {
        partner.setPhoneNumber("+226 70 00 00 00");
        stubPrefixLookup(decision);
        stubSaves();

        assertThat(service.handleInboundReply("0022670000000", "1 " + token)).isPresent();
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("round 1: a pharmacy stored in local format matches the international number its reply comes from")
    void locallyStoredNumberMatchesInternationalReply() {
        partner.setPhoneNumber("70 00 00 00");
        stubPrefixLookup(decision);
        stubSaves();

        assertThat(service.handleInboundReply("+22670000000", "1 " + token)).isPresent();
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("G8: a reply without a sender number is ignored")
    void replyWithoutSenderIgnored() {
        assertThat(service.handleInboundReply(null, "1 " + token)).isEmpty();
        assertThat(service.handleInboundReply("  ", "1 " + token)).isEmpty();
        verifyNoInteractions(routingDecisionRepository, channel);
    }

    @Test
    @DisplayName("G8: a token prefix that matches two open decisions for the same phone is ignored")
    void ambiguousPrefixIgnored() {
        PrescriptionRoutingDecision twin = PrescriptionRoutingDecision.builder()
                .prescription(new Prescription())
                .targetPharmacy(partner)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .decidedAt(LocalDateTime.now())
                .build();
        twin.setId(UUID.randomUUID());
        stubPrefixLookup(decision, twin);

        assertThat(service.handleInboundReply(PARTNER_PHONE, "1 " + token)).isEmpty();
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        verify(routingDecisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("canonicalPhone is the gateway's wire form: local, international and 00-prefixed all agree")
    void canonicalPhone() {
        assertThat(service.canonicalPhone("70 11 12 22")).isEqualTo("22670111222");
        assertThat(service.canonicalPhone("+226 70 11 12 22")).isEqualTo("22670111222");
        assertThat(service.canonicalPhone("0022670111222")).isEqualTo("22670111222");
        assertThat(service.canonicalPhone("22670111222")).isEqualTo("22670111222");
        assertThat(service.canonicalPhone(null)).isEmpty();
        assertThat(service.canonicalPhone("abc")).isEmpty();
    }

    @Test
    @DisplayName("unparseable reply does nothing")
    void unparseableReply() {
        Optional<PrescriptionRoutingDecision> result = service.handleInboundReply(PARTNER_PHONE, "gibberish");
        assertThat(result).isEmpty();
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("reply with unknown token is ignored")
    void unknownTokenIgnored() {
        when(routingDecisionRepository.findOpenByIdPrefix(
                RoutingType.PARTNER, PartnerExchangeService.OPEN_STATUSES, "zzzzzzzz"))
                .thenReturn(List.of());

        Optional<PrescriptionRoutingDecision> result =
                service.handleInboundReply(PARTNER_PHONE, "1 ZZZZZZZZ");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sweep sends reminder when idle >= 2h and < 4h")
    void sweepReminds() {
        decision.setDecidedAt(LocalDateTime.now().minusHours(2).minusMinutes(30));
        when(routingDecisionRepository.findByRoutingTypeAndStatusAndDecidedAtBefore(
                eq(RoutingType.PARTNER), eq(RoutingDecisionStatus.PENDING), any()))
                .thenReturn(List.of(decision));

        PartnerExchangeService.TimeoutSweepResult r = service.sweepTimeouts();

        assertThat(r.reminded()).isEqualTo(1);
        assertThat(r.autoRejected()).isZero();
        verify(channel).sendReminder(decision, partner);
    }

    @Test
    @DisplayName("sweep auto-rejects when idle >= 4h")
    void sweepAutoRejects() {
        decision.setDecidedAt(LocalDateTime.now().minusHours(5));
        when(routingDecisionRepository.findByRoutingTypeAndStatusAndDecidedAtBefore(
                eq(RoutingType.PARTNER), eq(RoutingDecisionStatus.PENDING), any()))
                .thenReturn(List.of(decision));
        stubSaves();

        PartnerExchangeService.TimeoutSweepResult r = service.sweepTimeouts();

        assertThat(r.autoRejected()).isEqualTo(1);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
        verify(channel).sendAutoRejected(decision, partner);
    }
}
