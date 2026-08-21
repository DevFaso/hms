package com.example.hms.service.integration;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.hl7.mllp.MllpOutboundSender;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.repository.InstrumentOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transmits queued HL7 messages to the instrument interface (P2 #17).
 *
 * <p>OML and ORU messages have been built and queued in
 * {@code lab.instrument_outbox} since the lab module shipped and never
 * transmitted, because no sender existed. Rows went in PENDING and stayed
 * there — every "sent to the analyser" claim upstream was really a claim about
 * a row in a table.
 *
 * <p>The inbound half was complete (MllpTcpServer, MllpFrameCodec, an
 * allowed-senders allowlist). This is the mirror.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentOutboxDispatchService {

    private final InstrumentOutboxRepository outboxRepository;
    private final MllpOutboundSender sender;
    private final MllpOutboundProperties properties;

    /**
     * Send one batch of queued messages.
     *
     * @return how many were acknowledged
     */
    @Transactional
    public int dispatchPending() {
        if (!properties.isEnabled()) {
            // Off by default. Saying so once per sweep at DEBUG rather than
            // silently returning keeps "nothing is being sent" diagnosable.
            log.debug("MLLP outbound transport is disabled; {} queued message(s) left untouched",
                outboxRepository.findByStatus(InstrumentOutboxStatus.PENDING).size());
            return 0;
        }

        LocalDateTime retryBefore =
            LocalDateTime.now().minusSeconds(properties.getRetryAfterSeconds());
        List<InstrumentOutbox> batch = outboxRepository.findDispatchable(
            InstrumentOutboxStatus.PENDING,
            properties.getMaxAttempts(),
            retryBefore,
            PageRequest.of(0, properties.getBatchSize()));

        int acknowledged = 0;
        for (InstrumentOutbox message : batch) {
            if (dispatchOne(message)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    private boolean dispatchOne(InstrumentOutbox message) {
        message.setAttempts(message.getAttempts() + 1);
        message.setLastAttemptAt(LocalDateTime.now());

        try {
            String ack = sender.send(message.getPayload());

            if (sender.isPositiveAck(ack)) {
                message.setStatus(InstrumentOutboxStatus.ACK);
                message.setSentAt(LocalDateTime.now());
                message.setLastError(null);
                outboxRepository.save(message);
                return true;
            }

            // The receiver answered and said no. Retrying an AE/AR verbatim will
            // fail identically every time — the message itself is the problem —
            // so this is terminal rather than backed off.
            message.setStatus(InstrumentOutboxStatus.ERROR);
            message.setLastError(truncate("Negative acknowledgement: " + ack));
            outboxRepository.save(message);
            log.warn("Instrument outbox {} rejected by receiver: {}", message.getId(), ack);
            return false;

        } catch (Exception ex) {
            // Transport failure — the receiver may simply be down, so this one
            // IS worth retrying until the attempt ceiling.
            message.setLastError(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            if (message.getAttempts() >= properties.getMaxAttempts()) {
                message.setStatus(InstrumentOutboxStatus.ERROR);
                log.error("Instrument outbox {} failed permanently after {} attempt(s): {}",
                    message.getId(), message.getAttempts(), ex.getMessage());
            } else {
                log.warn("Instrument outbox {} attempt {} failed, will retry: {}",
                    message.getId(), message.getAttempts(), ex.getMessage());
            }
            outboxRepository.save(message);
            return false;
        }
    }

    /** last_error is VARCHAR(2000); a stack-trace-length message must not fail the save. */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
