package com.example.hms.hl7.mllp;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 *       (every OBX segment, grouped under its OBR) and persisted as one
 *       {@code LabResult} per observation via
 *       {@link MllpInboundLabService}. The placer order number (OBR-2)
 *       is matched against {@code LabSpecimen.accessionNumber}.</li>
 *   <li>{@code ADT^A01 / A02 / A03 / A04 / A08} — parsed via
 *       {@link Hl7v2MessageBuilder#parseAdtMessage} and applied to the
 *       existing {@code Patient} demographic record via
 *       {@link MllpInboundAdtService}. A02 (transfer) updates the
 *       reconciled Admission's department; A03 (discharge) closes the
 *       Admission with a {@code DISCHARGED} status. Both are
 *       reconcile-only — no Admission/Encounter auto-create on the
 *       lifecycle triggers.</li>
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

    private static final Set<String> ACCEPTED_ADT_EVENTS = Set.of("A01", "A02", "A03", "A04", "A08");

    private final Hl7v2MessageBuilder messageBuilder;
    private final MllpAllowedSenderService allowlist;
    private final MllpInboundLabService inboundLab;
    private final MllpInboundAdtService inboundAdt;
    private final IntegrationMessageRecorder messageRecorder;

    public Hl7MessageDispatcher(Hl7v2MessageBuilder messageBuilder,
                                MllpAllowedSenderService allowlist,
                                MllpInboundLabService inboundLab,
                                MllpInboundAdtService inboundAdt,
                                IntegrationMessageRecorder messageRecorder) {
        this.messageBuilder = messageBuilder;
        this.allowlist = allowlist;
        this.inboundLab = inboundLab;
        this.inboundAdt = inboundAdt;
        this.messageRecorder = messageRecorder;
    }

    public String dispatch(String hl7Body, String remoteAddress) {
        Hl7MessageHeader header;
        try {
            header = Hl7MessageInspector.parseHeader(hl7Body);
        } catch (MllpProtocolException ex) {
            log.warn("[MLLP {}] Rejecting message — invalid MSH: {}", remoteAddress, ex.getMessage());
            // No parsed header — record under a sentinel integration id
            // so the DLQ surface still shows the failure. The fallback
            // header is what we send back as the ACK envelope.
            recordReject("MLLP:?/?", null, "UNKNOWN", hl7Body,
                "Invalid MSH: " + ex.getMessage());
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
            recordReject(integrationIdFor(header), null,
                header.messageType(), hl7Body,
                "sender " + header.sendingApplication() + "/" + header.sendingFacility()
                    + " not allowlisted");
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
        recordReject(integrationIdFor(header), organizationIdOf(hospital.get()),
            header.messageType(), hl7Body,
            "unsupported message type " + header.messageType());
        return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
            "Unsupported message type " + header.messageType());
    }

    private String handleOru(Hl7MessageHeader header, String hl7Body,
                             String remoteAddress, Hospital hospital) {
        List<ParsedObservation> observations = messageBuilder.parseOruR01(hl7Body);
        if (observations == null || observations.isEmpty()) {
            log.warn("[MLLP {}] ORU^R01 from {}/{} unparseable or without OBX segments",
                remoteAddress, header.sendingApplication(), header.sendingFacility());
            // Service was never invoked, so record here. The successful
            // and post-service-reject paths are recorded inside the
            // service itself so the integration row carries the
            // domain-level error message.
            recordReject(integrationIdFor(header), organizationIdOf(hospital),
                "ORU^R01", hl7Body,
                "unparseable ORU^R01 or no OBX segments");
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable ORU^R01 or no OBX segments");
        }
        MllpInboundOutcome outcome = inboundLab.processOruR01(
            observations, hospital, header.sendingApplication(), header.sendingFacility(),
            header.messageControlId(), hl7Body);
        return ackForOutcome(header, outcome, "ORU^R01");
    }

    private String handleAdt(Hl7MessageHeader header, String hl7Body,
                             String remoteAddress, Hospital hospital) {
        ParsedAdtMessage parsed = messageBuilder.parseAdtMessage(hl7Body, header.triggerEvent());
        if (parsed == null) {
            log.warn("[MLLP {}] {} from {}/{} unparseable (missing PID-3 / segments)",
                remoteAddress, header.messageType(),
                header.sendingApplication(), header.sendingFacility());
            recordReject(integrationIdFor(header), organizationIdOf(hospital),
                header.messageType(), hl7Body,
                "unparseable " + header.messageType() + " — missing PID-3 or required segments");
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable " + header.messageType() + " — missing PID-3 or required segments");
        }
        MllpInboundOutcome outcome = inboundAdt.processAdt(
            parsed, hospital, header.sendingApplication(), header.sendingFacility(),
            header.messageControlId());
        return ackForOutcome(header, outcome, header.messageType());
    }

    /**
     * Best-effort FAILED record for a pre-service reject. The recorder
     * itself runs in REQUIRES_NEW and swallows its own exceptions; the
     * extra try-catch here is belt-and-braces so a recorder bean
     * failure can never poison the ACK we send back.
     */
    private void recordReject(String integrationId, UUID organizationId,
                              String messageType, String rawBody, String reason) {
        try {
            messageRecorder.recordMessage(
                integrationId, organizationId,
                IntegrationMessageDirection.INBOUND,
                messageType == null ? "UNKNOWN" : messageType,
                rawBody,
                IntegrationMessageStatus.FAILED,
                reason);
        } catch (RuntimeException ex) {
            log.warn("Dispatcher recorder threw for integration={} type={} reason={}",
                integrationId, messageType, reason, ex);
        }
    }

    /**
     * Recorder column {@code clinical.integration_message_event.integration_id}
     * is {@code VARCHAR(120)}. Truncate aggressively here so a sender with a
     * pathological MSH-3 / MSH-4 (HL7 v2.5 permits each up to 180 chars)
     * cannot push the synthesised id past the column limit and cause the
     * recorder insert to fail — which would silently drop the DLQ entry for
     * dispatcher-level rejects.
     */
    private static final int RECORDER_INTEGRATION_ID_MAX = 120;

    private static String integrationIdFor(Hl7MessageHeader header) {
        String app = blankOrNullToPlaceholder(header.sendingApplication());
        String fac = blankOrNullToPlaceholder(header.sendingFacility());
        String raw = "MLLP:" + app + "/" + fac;
        return raw.length() > RECORDER_INTEGRATION_ID_MAX
            ? raw.substring(0, RECORDER_INTEGRATION_ID_MAX)
            : raw;
    }

    private static String blankOrNullToPlaceholder(String value) {
        return (value == null || value.isBlank()) ? "?" : value.trim();
    }

    private static UUID organizationIdOf(Hospital hospital) {
        if (hospital == null || hospital.getOrganization() == null) {
            return null;
        }
        return hospital.getOrganization().getId();
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
