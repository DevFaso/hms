package com.example.hms.hl7.mllp;

import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Routes an inbound HL7 v2 message to the right domain handler and produces
 * the ACK content.
 *
 * <p>Scope of P0.2 (HL7 v2 MLLP listener):
 * <ul>
 *   <li>{@code ORU^R01} — parsed via {@link Hl7v2MessageBuilder#parseOruR01(String)}
 *       and logged. Domain persistence (creating {@code LabResult} records) requires
 *       order-id resolution from OBR-3 + assignment context, which is part of P1.
 *       The message is acknowledged with AA so the analyzer does not retry.</li>
 *   <li>{@code ADT^A01 / A04 / A08} — logged with their MSH routing fields and
 *       acknowledged with AA. Encounter / patient projection is also a P1 item.</li>
 *   <li>Anything else — acknowledged with AR (Application Reject) so the sender
 *       knows the message type is not supported.</li>
 * </ul>
 *
 * <p>Returning AA without writing to the domain might look like silent data loss,
 * but the alternative — sending NAK and forcing the analyzer to retry forever —
 * is worse. P1 will turn the log lines into persisted rows.
 */
@Component
public class Hl7MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(Hl7MessageDispatcher.class);

    private static final Set<String> ACCEPTED_ADT_EVENTS = Set.of("A01", "A04", "A08");

    private final Hl7v2MessageBuilder messageBuilder;

    public Hl7MessageDispatcher(Hl7v2MessageBuilder messageBuilder) {
        this.messageBuilder = messageBuilder;
    }

    public String dispatch(String hl7Body, String remoteAddress) {
        Hl7MessageHeader header;
        try {
            header = Hl7MessageInspector.parseHeader(hl7Body);
        } catch (MllpProtocolException ex) {
            log.warn("[MLLP {}] Rejecting message — invalid MSH: {}", remoteAddress, ex.getMessage());
            // Build a synthetic header with sane defaults so we can still ACK back.
            Hl7MessageHeader fallback = new Hl7MessageHeader(
                "|", "^~\\&", "?", "?", "HMS", "HMS", "", "ACK", "?", "P", "2.5"
            );
            return Hl7AckBuilder.buildAck(fallback, Hl7AckBuilder.AckCode.AR, "Invalid MSH: " + ex.getMessage());
        }

        String code = header.messageCode();
        String trigger = header.triggerEvent();

        if ("ORU".equals(code) && "R01".equals(trigger)) {
            return handleOru(header, hl7Body, remoteAddress);
        }
        if ("ADT".equals(code) && trigger != null && ACCEPTED_ADT_EVENTS.contains(trigger)) {
            return handleAdt(header, remoteAddress);
        }

        log.warn("[MLLP {}] Unsupported message type {} from {}/{}",
            remoteAddress, header.messageType(), header.sendingApplication(), header.sendingFacility());
        return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
            "Unsupported message type " + header.messageType());
    }

    private String handleOru(Hl7MessageHeader header, String hl7Body, String remoteAddress) {
        ParsedObservation obs = messageBuilder.parseOruR01(hl7Body);
        if (obs == null) {
            log.warn("[MLLP {}] ORU^R01 from {}/{} unparseable",
                remoteAddress, header.sendingApplication(), header.sendingFacility());
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable ORU^R01 OBX segment");
        }
        log.info("[MLLP {}] ORU^R01 from={}/{} pid={} test={} value={} unit={} flag={}",
            remoteAddress, header.sendingApplication(), header.sendingFacility(),
            obs.patientId(), obs.testCode(), obs.resultValue(), obs.resultUnit(), obs.abnormalFlag());
        // Domain persistence is wired in P1 — P0.2 acks so the analyzer does not retry.
        return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AA, null);
    }

    private String handleAdt(Hl7MessageHeader header, String remoteAddress) {
        log.info("[MLLP {}] ADT {} from={}/{} ctrlId={}",
            remoteAddress, header.messageType(),
            header.sendingApplication(), header.sendingFacility(),
            header.messageControlId());
        // Encounter/patient projection wired in P1.
        return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AA, null);
    }
}
