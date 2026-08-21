package com.example.hms.service.integration;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.hl7.mllp.MllpOutboundSender;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.repository.InstrumentOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Instrument-outbox dispatch (P2 #17).
 *
 * <p>OML/ORU messages have been built and queued since the lab module shipped
 * and never transmitted, because no sender existed — rows went in PENDING and
 * stayed there. Every "sent to the analyser" claim upstream was a claim about a
 * row in a table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstrumentOutboxDispatchServiceTest {

    @Mock private InstrumentOutboxRepository outboxRepository;
    @Mock private MllpOutboundSender sender;

    private MllpOutboundProperties properties;
    private InstrumentOutboxDispatchService service;
    private InstrumentOutbox message;

    @BeforeEach
    void setUp() throws Exception {
        properties = new MllpOutboundProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(3);
        service = new InstrumentOutboxDispatchService(outboxRepository, sender, properties);

        message = new InstrumentOutbox();
        message.setId(UUID.randomUUID());
        message.setMessageType("OML^O21");
        message.setPayload("MSH|^~\\&|HMS|...");
        message.setStatus(InstrumentOutboxStatus.PENDING);

        when(outboxRepository.findDispatchable(any(), anyInt(), any(), any()))
            .thenReturn(List.of(message));
        when(outboxRepository.save(any(InstrumentOutbox.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aPositiveAckMarksTheMessageAcknowledged() throws Exception {
        when(sender.send(anyString())).thenReturn("MSH|^~\\&|LAB|...\rMSA|AA|MSGID");
        when(sender.isPositiveAck(anyString())).thenReturn(true);

        assertThat(service.dispatchPending()).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ACK);
        assertThat(message.getSentAt()).isNotNull();
        assertThat(message.getLastError()).isNull();
    }

    @Test
    void aNegativeAckIsTerminalRatherThanRetried() throws Exception {
        // The receiver answered and said no. Retrying an AE/AR verbatim fails
        // identically every time — the message itself is the problem.
        when(sender.send(anyString())).thenReturn("MSA|AR|MSGID|Unknown test code");
        when(sender.isPositiveAck(anyString())).thenReturn(false);

        assertThat(service.dispatchPending()).isZero();
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
        assertThat(message.getLastError()).contains("Negative acknowledgement");
    }

    @Test
    void aTransportFailureIsRetriedUntilTheCeiling() throws Exception {
        // The receiver may simply be down, so this one IS worth retrying.
        when(sender.send(anyString())).thenThrow(new IOException("Connection refused"));

        service.dispatchPending();

        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("Connection refused");
        assertThat(message.getLastAttemptAt()).isNotNull();
    }

    @Test
    void aTransportFailureAtTheCeilingIsParked() throws Exception {
        // An outbox that retries forever against a decommissioned analyser looks
        // identical to one that is working.
        message.setAttempts(2);
        when(sender.send(anyString())).thenThrow(new IOException("Connection refused"));

        service.dispatchPending();

        assertThat(message.getAttempts()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
    }

    @Test
    void nothingIsTransmittedWhileTheTransportIsDisabled() throws Exception {
        properties.setEnabled(false);
        when(outboxRepository.findByStatus(InstrumentOutboxStatus.PENDING)).thenReturn(List.of(message));

        assertThat(service.dispatchPending()).isZero();
        verify(sender, never()).send(anyString());
        assertThat(message.getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
    }

    @Test
    void everyAttemptIsRecordedEvenWhenItFails() throws Exception {
        // "status = ERROR" with no reason is a dead end for whoever has to fix
        // the interface at 3am.
        when(sender.send(anyString())).thenThrow(new IOException("Read timed out"));

        service.dispatchPending();

        verify(outboxRepository).save(message);
        assertThat(message.getLastError()).contains("IOException");
    }
}
