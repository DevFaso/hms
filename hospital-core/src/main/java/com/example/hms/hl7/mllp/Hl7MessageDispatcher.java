package com.example.hms.hl7.mllp;

import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Routes an inbound HL7 v2 message to the right domain handler and
 * produces the ACK content.
 *
 * <p>P1 #2b — full persistence wiring:
 * <ul>
 *   <li>Sender allowlist gate first ({@link MllpAllowedSenderService}).
 *       Unknown {@code (MSH-3, MSH-4)} pairs are rejected with AR
 *       before any parsing or domain work.</li>
 *   <li>{@code ORU^R01} — parsed via {@link Hl7v2MessageBuilder#parseOruR01}
 *       and persisted as a {@code LabResult} via
 *       {@link MllpInboundLabService}. The placer order number (OBR-2)
 *       is matched against {@code LabSpecimen.accessionNumber}.</li>
 *   <li>{@code ADT^A01 / A04 / A08} — parsed via
 *       {@link Hl7v2MessageBuilder#parseAdtMessage} and applied to the
 *       existing {@code Patient} demographic record via
 *       {@link MllpInboundAdtService} (no Encounter creation).</li>
 *   <li>Anything else — AR (Application Reject).</li>
 * </ul>
 *
 * <p>The {@link MllpInboundOutcome} returned by the inbound services
 * maps to ACK codes: {@code ACCEPTED → AA},
 * {@code REJECTED_NOT_FOUND/INVALID → AE},
 * {@code REJECTED_CROSS_TENANT → AR}.
 */
@Component
public class Hl7MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(Hl7MessageDispatcher.class);

    private static final Set<String> ACCEPTED_ADT_EVENTS = Set.of("A01", "A04", "A08");

    private final Hl7v2MessageBuilder messageBuilder;
    private final MllpAllowedSenderService allowlist;
    private final MllpInboundLabService inboundLab;
    private final MllpInboundAdtService inboundAdt;

    public Hl7MessageDispatcher(Hl7v2MessageBuilder messageBuilder,
                                MllpAllowedSenderService allowlist,
                                MllpInboundLabService inboundLab,
                                MllpInboundAdtService inboundAdt) {
        this.messageBuilder = messageBuilder;
        this.allowlist = allowlist;
        this.inboundLab = inboundLab;
        this.inboundAdt = inboundAdt;
    }

    public String dispatch(String hl7Body, String remoteAddress) {
        Hl7MessageHeader header;
        try {
            header = Hl7MessageInspector.parseHeader(hl7Body);
        } catch (MllpProtocolException ex) {
            log.warn("[MLLP {}] Rejecting message — invalid MSH: {}", remoteAddress, ex.getMessage());
            Hl7MessageHeader fallback = new Hl7MessageHeader(
                "|", "^~\\&", "?", "?", "HMS", "HMS", "", "ACK", "?", "P", "2.5"
            );
            return Hl7AckBuilder.buildAck(fallback, Hl7AckBuilder.AckCode.AR, "Invalid MSH: " + ex.getMessage());
        }

        // Allowlist gate — runs before any domain work so unknown
        // senders never reach the persistence layer.
        Optional<Hospital> hospital = allowlist.resolveHospital(
            header.sendingApplication(), header.sendingFacility());
        if (hospital.isEmpty()) {
            log.warn("[MLLP {}] AR — sender {}/{} not allowlisted (msgType={})",
                remoteAddress, header.sendingApplication(), header.sendingFacility(),
                header.messageType());
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
                "Sender not authorised");
        }

        String code = header.messageCode();
        String trigger = header.triggerEvent();

        if ("ORU".equals(code) && "R01".equals(trigger)) {
            return handleOru(header, hl7Body, remoteAddress, hospital.get());
        }
        if ("ADT".equals(code) && trigger != null && ACCEPTED_ADT_EVENTS.contains(trigger)) {
            return handleAdt(header, hl7Body, remoteAddress, hospital.get());
        }

        log.warn("[MLLP {}] Unsupported message type {} from {}/{}",
            remoteAddress, header.messageType(),
            header.sendingApplication(), header.sendingFacility());
        return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
            "Unsupported message type " + header.messageType());
    }

    private String handleOru(Hl7MessageHeader header, String hl7Body,
                             String remoteAddress, Hospital hospital) {
        ParsedObservation obs = messageBuilder.parseOruR01(hl7Body);
        if (obs == null) {
            log.warn("[MLLP {}] ORU^R01 from {}/{} unparseable",
                remoteAddress, header.sendingApplication(), header.sendingFacility());
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable ORU^R01 OBX segment");
        }
        MllpInboundOutcome outcome = inboundLab.processOruR01(
            obs, hospital, header.sendingApplication(), header.sendingFacility());
        return ackForOutcome(header, outcome, "ORU^R01");
    }

    private String handleAdt(Hl7MessageHeader header, String hl7Body,
                             String remoteAddress, Hospital hospital) {
        ParsedAdtMessage parsed = messageBuilder.parseAdtMessage(hl7Body, header.triggerEvent());
        if (parsed == null) {
            log.warn("[MLLP {}] {} from {}/{} unparseable (missing PID-3 / segments)",
                remoteAddress, header.messageType(),
                header.sendingApplication(), header.sendingFacility());
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable " + header.messageType() + " — missing PID-3 or required segments");
        }
        MllpInboundOutcome outcome = inboundAdt.processAdt(
            parsed, hospital, header.sendingApplication(), header.sendingFacility());
        return ackForOutcome(header, outcome, header.messageType());
    }

    private String ackForOutcome(Hl7MessageHeader header, MllpInboundOutcome outcome, String label) {
        return switch (outcome) {
            case ACCEPTED ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AA, null);
            case REJECTED_NOT_FOUND ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                    label + " referenced entity not found");
            case REJECTED_CROSS_TENANT ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
                    label + " sender not authorised for this entity");
            case REJECTED_INVALID ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                    label + " invalid or missing required fields");
        };
    }
}
