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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerExchangeServiceTest {

    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PartnerNotificationChannel channel;
    @Mock private AuditEventLogService auditEventLogService;

    private final PartnerSmsReplyParser parser = new PartnerSmsReplyParser();

    private PartnerExchangeService service;

    private UUID decisionId;
    private PrescriptionRoutingDecision decision;
    private Prescription prescription;
    private Pharmacy partner;

    @BeforeEach
    void setUp() {
        service = new PartnerExchangeService(
                routingDecisionRepository, prescriptionRepository,
                channel, parser, auditEventLogService);

        decisionId = UUID.randomUUID();
        partner = Pharmacy.builder().name("Pharmacie Centrale").build();
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

    @Test
    @DisplayName("inbound accept reply updates statuses and notifies patient")
    void inboundAccept() {
        String token = decisionId.toString().substring(0, 8).toUpperCase();
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<PrescriptionRoutingDecision> updated =
                service.handleInboundReply("1 " + token);

        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
        verify(channel).notifyPatientAccepted(any(), eq(partner));
    }

    @Test
    @DisplayName("inbound reject reply does not notify patient")
    void inboundReject() {
        String token = decisionId.toString().substring(0, 8).toUpperCase();
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleInboundReply("2 " + token);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
        verify(channel, times(0)).notifyPatientAccepted(any(), any());
        verify(channel, times(0)).notifyPatientDispensed(any(), any());
    }

    @Test
    @DisplayName("inbound dispense confirmation notifies patient and completes decision")
    void inboundDispense() {
        String token = decisionId.toString().substring(0, 8).toUpperCase();
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleInboundReply("3 " + token);

        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
        verify(channel).notifyPatientDispensed(any(), eq(partner));
    }

    @Test
    @DisplayName("unparseable reply does nothing")
    void unparseableReply() {
        Optional<PrescriptionRoutingDecision> result = service.handleInboundReply("gibberish");
        assertThat(result).isEmpty();
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("reply with unknown token is ignored")
    void unknownTokenIgnored() {
        when(routingDecisionRepository.findByRoutingTypeAndStatus(
                RoutingType.PARTNER, RoutingDecisionStatus.PENDING))
                .thenReturn(List.of(decision));

        Optional<PrescriptionRoutingDecision> result =
                service.handleInboundReply("1 ZZZZZZZZ");

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
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PartnerExchangeService.TimeoutSweepResult r = service.sweepTimeouts();

        assertThat(r.autoRejected()).isEqualTo(1);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
        verify(channel).sendAutoRejected(decision, partner);
    }
}
