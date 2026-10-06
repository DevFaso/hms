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
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.pharmacy.PrescriberPharmacyNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawnOrderPartnerHandler")
class WithdrawnOrderPartnerHandlerTest {

    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private PartnerNotificationChannel channel;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;

    private WithdrawnOrderPartnerHandler handler;
    private Prescription prescription;
    private Pharmacy partner;

    @BeforeEach
    void setUp() {
        handler = new WithdrawnOrderPartnerHandler(
                routingDecisionRepository, channel, auditEventLogService, prescriberNotifier);
        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setStatus(PrescriptionStatus.CANCELLED);
        partner = Pharmacy.builder().name("Pharmacie Centrale").phoneNumber("+22670000000").build();
        partner.setId(UUID.randomUUID());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void rollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private PrescriptionRoutingDecision decision(RoutingType type, RoutingDecisionStatus status) {
        PrescriptionRoutingDecision d = PrescriptionRoutingDecision.builder()
                .prescription(prescription)
                .targetPharmacy(partner)
                .routingType(type)
                .status(status)
                .build();
        d.setId(UUID.randomUUID());
        return d;
    }

    private void onFile(PrescriptionRoutingDecision... decisions) {
        when(routingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc(prescription.getId()))
                .thenReturn(List.of(decisions));
    }

    @Test
    @DisplayName("isWithdrawn: CANCELLED and DISCONTINUED only")
    void isWithdrawn() {
        assertThat(WithdrawnOrderPartnerHandler.isWithdrawn(prescription)).isTrue();
        prescription.setStatus(PrescriptionStatus.DISCONTINUED);
        assertThat(WithdrawnOrderPartnerHandler.isWithdrawn(prescription)).isTrue();
        prescription.setStatus(PrescriptionStatus.PARTNER_ACCEPTED);
        assertThat(WithdrawnOrderPartnerHandler.isWithdrawn(prescription)).isFalse();
        prescription.setStatus(null);
        assertThat(WithdrawnOrderPartnerHandler.isWithdrawn(prescription)).isFalse();
        assertThat(WithdrawnOrderPartnerHandler.isWithdrawn(null)).isFalse();
    }

    @Test
    @DisplayName("withdrawal with a PENDING offer: the offer closes and the partner gets one notice, after commit")
    void pendingOfferClosedAndPartnerTold() {
        PrescriptionRoutingDecision pending = decision(RoutingType.PARTNER, RoutingDecisionStatus.PENDING);
        onFile(pending);

        handler.withdrawPartnerOffers(prescription);

        assertThat(pending.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        verify(routingDecisionRepository).save(pending);
        verifyNoInteractions(channel);
        commit();
        verify(channel).sendWithdrawn(pending, partner);
    }

    @Test
    @DisplayName("withdrawal with an ACCEPTED offer: the partner gets one notice and the offer stays ACCEPTED")
    void acceptedOfferStaysOpenAndPartnerTold() {
        PrescriptionRoutingDecision accepted = decision(RoutingType.PARTNER, RoutingDecisionStatus.ACCEPTED);
        onFile(accepted);

        handler.withdrawPartnerOffers(prescription);
        commit();

        assertThat(accepted.getStatus()).isEqualTo(RoutingDecisionStatus.ACCEPTED);
        verify(routingDecisionRepository, never()).save(any());
        verify(channel).sendWithdrawn(accepted, partner);
    }

    @Test
    @DisplayName("closed partner offers and non-partner decisions are left alone and nobody is told")
    void otherDecisionsUntouched() {
        PrescriptionRoutingDecision backorder = decision(RoutingType.BACKORDER, RoutingDecisionStatus.PENDING);
        PrescriptionRoutingDecision refused = decision(RoutingType.PARTNER, RoutingDecisionStatus.REJECTED);
        PrescriptionRoutingDecision superseded = decision(RoutingType.PARTNER, RoutingDecisionStatus.CANCELLED);
        onFile(backorder, refused, superseded);

        handler.withdrawPartnerOffers(prescription);
        commit();

        assertThat(backorder.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        assertThat(refused.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        verify(routingDecisionRepository, never()).save(any());
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("a rolled-back withdrawal tells no partner anything")
    void rollbackTellsNobody() {
        onFile(decision(RoutingType.PARTNER, RoutingDecisionStatus.PENDING));

        handler.withdrawPartnerOffers(prescription);
        rollback();

        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("a send failure never reaches the withdrawal")
    void sendFailureIsSwallowed() {
        PrescriptionRoutingDecision pending = decision(RoutingType.PARTNER, RoutingDecisionStatus.PENDING);
        onFile(pending);
        doThrow(new IllegalStateException("gateway down")).when(channel).sendWithdrawn(any(), any());

        handler.withdrawPartnerOffers(prescription);

        assertThatCode(WithdrawnOrderPartnerHandlerTest::commit).doesNotThrowAnyException();
        assertThat(pending.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
    }

    @Test
    @DisplayName("a partner without a phone is not queued a notice")
    void noPhoneNoNotice() {
        partner.setPhoneNumber(" ");
        onFile(decision(RoutingType.PARTNER, RoutingDecisionStatus.PENDING));

        handler.withdrawPartnerOffers(prescription);
        commit();

        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("closeOffer cancels the decision and audits it without touching the prescription")
    void closeOffer() {
        PrescriptionRoutingDecision accepted = decision(RoutingType.PARTNER, RoutingDecisionStatus.ACCEPTED);
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionRoutingDecision saved = handler.closeOffer(accepted, prescription, "Partner no-show");

        assertThat(saved.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.CANCELLED);
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.PRESCRIPTION_ROUTED_EXTERNAL);
        assertThat(captor.getValue().getResourceId()).isEqualTo(accepted.getId().toString());
        verifyNoInteractions(prescriberNotifier, channel);
    }

    @Test
    @DisplayName("a dispense of a withdrawn order is recorded COMPLETED, raised for staff and told to the prescriber")
    void recordDispenseOfWithdrawn() {
        PrescriptionRoutingDecision accepted = decision(RoutingType.PARTNER, RoutingDecisionStatus.ACCEPTED);
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionRoutingDecision saved = handler.recordDispenseOfWithdrawn(accepted, prescription, "Partner SMS");

        assertThat(saved.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
        assertThat(prescription.getStatus()).isEqualTo(PrescriptionStatus.CANCELLED);
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.SECURITY_ALERT_TRIGGERED);
        assertThat(captor.getValue().getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(captor.getValue().getEventDescription())
                .contains(prescription.getId().toString()).contains("CANCELLED");
        verify(prescriberNotifier).notifyPrescriberOfDispenseAfterWithdrawal(prescription);
        verifyNoInteractions(channel);
    }

    @Test
    @DisplayName("a notifier failure does not undo the recorded dispense")
    void recordDispenseSurvivesNotifierFailure() {
        PrescriptionRoutingDecision accepted = decision(RoutingType.PARTNER, RoutingDecisionStatus.ACCEPTED);
        when(routingDecisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("down"))
                .when(prescriberNotifier).notifyPrescriberOfDispenseAfterWithdrawal(any());

        assertThatCode(() -> handler.recordDispenseOfWithdrawn(accepted, prescription, "Partner SMS"))
                .doesNotThrowAnyException();
        assertThat(accepted.getStatus()).isEqualTo(RoutingDecisionStatus.COMPLETED);
    }
}
