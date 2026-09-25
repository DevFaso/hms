package com.example.hms.hl7.mllp;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.model.Hospital;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.message.MllpRecordingContext;
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
 *   <li>{@code ADT^A40} — patient merge (Tier 2 item 41). Parsed via
 *       {@link Hl7v2MessageBuilder#parseAdtA40} (PID-3 survives, MRG-1 is
 *       retired) and applied through {@link MllpInboundMergeService}, which
 *       enforces its own cross-tenant gate because the EMPI merge service's
 *       guards read the caller's hospital from a security context this
 *       thread does not have. Never auto-creates: both identifiers must
 *       already be known to EMPI.</li>
 *   <li>Anything else — AR (Application Reject).</li>
 * </ul>
 *
 * <p>The {@link MllpInboundOutcome} returned by the inbound services
 * maps to ACK codes: {@code ACCEPTED → AA},
 * {@code REJECTED_NOT_FOUND/INVALID → AE}. No inbound path answers AR
 * for a cross-tenant reference: an accession, an MRN or a merge pair
 * owned by another hospital answers exactly like an unknown one, so an
 * allowlisted sender cannot probe another tenant's identifier space.
 * AR is left for the transport-level refusals the dispatcher itself
 * makes — an unparseable MSH, a sender that is not allowlisted at all,
 * an unsupported message type — none of which depend on tenant data.
 */
@Component
public class Hl7MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(Hl7MessageDispatcher.class);

    private static final Set<String> ACCEPTED_ADT_EVENTS = Set.of("A01", "A02", "A03", "A04", "A08");

    /**
     * A40 is deliberately NOT in {@link #ACCEPTED_ADT_EVENTS}. It carries an
     * MRG segment the demographic parser does not read and it changes
     * identity rather than attributes, so it routes to its own handler
     * (Tier 2 item 41).
     */
    private static final String MERGE_EVENT = "A40";

    /**
     * Stands in for the message type in the dispatcher's correlation keys.
     * MSH-9 is sender-controlled, so it cannot be part of the key; the
     * {@code reasonKey} already separates the problems worth separating.
     */
    private static final String DISPATCHER_CORRELATION_TYPE = "MLLP-DISPATCH";

    private final Hl7v2MessageBuilder messageBuilder;
    private final MllpAllowedSenderService allowlist;
    private final MllpInboundLabService inboundLab;
    private final MllpInboundAdtService inboundAdt;
    private final MllpInboundMergeService inboundMerge;
    private final IntegrationMessageRecorder messageRecorder;

    public Hl7MessageDispatcher(Hl7v2MessageBuilder messageBuilder,
                                MllpAllowedSenderService allowlist,
                                MllpInboundLabService inboundLab,
                                MllpInboundAdtService inboundAdt,
                                MllpInboundMergeService inboundMerge,
                                IntegrationMessageRecorder messageRecorder) {
        this.messageBuilder = messageBuilder;
        this.allowlist = allowlist;
        this.inboundLab = inboundLab;
        this.inboundAdt = inboundAdt;
        this.inboundMerge = inboundMerge;
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
            // No parsed header, so no sender: the helper's own placeholder
            // form, not a hand-written copy of it.
            recordReject(MllpRecordingContext.integrationId(null, null),
                null, "UNKNOWN", hl7Body,
                "Invalid MSH: " + ex.getMessage(), "invalid MSH",
                // No scope, so no dedupe: a random id per row and every
                // occurrence keeps its own counted entry and its own body.
                //
                // A placeholder scope was tried here and is wrong twice over.
                // It is shared across senders, so anyone reaching the port
                // could supersede a real partner's outstanding entry - the
                // thing the not-allowlisted path was fixed for. And because
                // recordRecurringFailure stores the body on the first
                // occurrence of an id, one shared id means that after the
                // first malformed frame ever recorded, no unparseable-MSH
                // message body is ever stored again, for anyone - which
                // destroys exactly the evidence the line above argues we must
                // keep. There is no sender to bound by when the header is the
                // thing that would not parse.
                null);
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
                    + " not allowlisted",
                "sender not allowlisted",
                // The claimed sender, normalised. This bounds the honest
                // case: a de-allowlisted production partner retrying on a
                // timer stays one entry rather than thousands, and junk
                // arriving under other names lands elsewhere.
                //
                // It is not a guarantee against a chosen victim. MSH-3/MSH-4
                // are unverified here, so an attacker who knows a partner's
                // pair can claim it, share its correlation id, and both
                // supersede its counted row and suppress its stored body for
                // the window. Fixing that needs an identity this path does
                // not have; what is ruled out is the accidental version,
                // where every unrecognised sender collided by construction.
                integrationIdFor(header));
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AR,
                "Sender not authorised");
        }

        String code = header.messageCode();
        String trigger = header.triggerEvent();

        if ("ORU".equals(code) && "R01".equals(trigger)) {
            return handleOru(header, hl7Body, remoteAddress, hospital.get());
        }
        if ("ADT".equals(code) && MERGE_EVENT.equals(trigger)) {
            return handleMerge(header, hl7Body, remoteAddress, hospital.get());
        }
        if ("ADT".equals(code) && trigger != null && ACCEPTED_ADT_EVENTS.contains(trigger)) {
            return handleAdt(header, hl7Body, remoteAddress, hospital.get());
        }

        log.warn("[MLLP {}] Unsupported message type {} from {}/{}",
            remoteAddress, header.messageType(),
            header.sendingApplication(), header.sendingFacility());
        recordReject(integrationIdFor(header), organizationIdOf(hospital.get()),
            header.messageType(), hl7Body,
            "unsupported message type " + header.messageType(),
            "unsupported message type", integrationIdFor(header));
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
                "unparseable ORU^R01 or no OBX segments",
                "unparseable ORU^R01", integrationIdFor(header));
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
                "unparseable " + header.messageType() + " — missing PID-3 or required segments",
                // The trigger belongs in the key: it is one of the five in
                // ACCEPTED_ADT_EVENTS, checked before we got here, so it
                // cannot be used to mint entries - and without it a malformed
                // A01 and a structurally different malformed A08 from one
                // sender share a key, so the second is recorded with no body
                // and the operator has a dead letter and nothing to read.
                "unparseable ADT^" + header.triggerEvent(), integrationIdFor(header));
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable " + header.messageType() + " — missing PID-3 or required segments");
        }
        MllpInboundOutcome outcome = inboundAdt.processAdt(
            parsed, hospital, header.sendingApplication(), header.sendingFacility(),
            header.messageControlId());
        return ackForOutcome(header, outcome, header.messageType());
    }

    /**
     * {@code ADT^A40} — patient merge (Tier 2 item 41).
     *
     * <p>Its own handler rather than another entry in
     * {@link #ACCEPTED_ADT_EVENTS}: A40 carries an MRG segment the
     * demographic parser never looks at, and a merge changes who a record IS
     * rather than what it says. Routing it through {@code handleAdt} would
     * have parsed the PID, found no MRG, and quietly applied a demographic
     * update instead of a merge — a silent wrong-thing rather than a reject.
     */
    private String handleMerge(Hl7MessageHeader header, String hl7Body,
                               String remoteAddress, Hospital hospital) {
        Hl7v2MessageBuilder.ParsedMergeMessage parsed = messageBuilder.parseAdtA40(hl7Body);
        if (parsed == null) {
            log.warn("[MLLP {}] ADT^A40 from {}/{} unparseable (missing PID-3 or MRG-1)",
                remoteAddress, header.sendingApplication(), header.sendingFacility());
            recordReject(integrationIdFor(header), organizationIdOf(hospital),
                "ADT^A40", hl7Body,
                "unparseable ADT^A40 — missing PID-3 or MRG-1",
                "unparseable ADT^A40", integrationIdFor(header));
            return Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                "Unparseable ADT^A40 — missing PID-3 or MRG-1");
        }
        MllpInboundOutcome outcome = inboundMerge.processMerge(
            parsed, hospital, header.sendingApplication(), header.sendingFacility(),
            header.messageControlId());
        return ackForOutcome(header, outcome, "ADT^A40");
    }

    /**
     * Best-effort FAILED record for a pre-service reject.
     *
     * <p><b>The raw body stays.</b> Unlike the inbound services' refusals,
     * which know exactly what was wrong and record a reason instead, these
     * rows are for messages we could not read — an unparseable MSH, an ADT
     * with no PID-3, an A40 with no MRG. The body <em>is</em> the evidence,
     * and an operator diagnosing a vendor's framing has nothing else to look
     * at. Do not "tidy" it away.
     *
     * <p>Three of these paths answer AE, which HL7 senders treat as
     * transient and retry on a timer. A stable {@code correlationScope} +
     * {@code reasonKey} keeps that storm to <b>one counted dead letter</b>
     * per (sender, problem), because
     * {@code countUnresolvedDeadLetters} discounts a {@code FAILED} row once
     * a later row shares its correlation id.
     *
     * <p><b>The body is stored once per problem, not once per retry.</b>
     * That is {@code recordRecurringFailure}, not the correlation id on its
     * own: a stable id only stops the retries being <em>counted</em>, and
     * every one of them still inserts a row, so keying alone would have left
     * a vendor writing thousands of full copies of a message — PID and all —
     * into a table with no retention while the badge read 1. A bounded badge
     * over unbounded PHI is worse than the visible version, because it claims
     * the problem is handled. So the first occurrence carries the body, which
     * is the evidence an operator needs for a message nobody could parse, and
     * later occurrences carry the reason alone.
     *
     * <p>What is bounded: counted dead letters (one per sender and problem)
     * and stored bodies (one per sender and problem). What is not: the row
     * count, still one small row per attempt, on a table with no retention
     * policy. That last one is reported, not solved here.
     *
     * <p>{@code reasonKey} is separate from {@code reason} on purpose, and
     * {@code correlationScope} is separate from {@code integrationId} for the
     * same reason: <b>nothing a sender controls may enter the correlation
     * key.</b> The human-readable reason embeds the exception message and the
     * concrete message type; MSH-9 is whatever the sender wrote; and on the
     * not-allowlisted path MSH-3 and MSH-4 have not been checked against
     * anything at all. Key on any of those and an adversary varies it per
     * message — a different {@code ZZZ^Znn} each time — to mint a fresh id
     * per row, which is a fresh counted dead letter. So the scope is the
     * <b>resolved</b> allowlisted sender's id, and only ever that.
     *
     * <p>Where no sender has been resolved — an unreadable MSH, a pair that
     * is not on the allowlist — {@code correlationScope} is null and the
     * recorder mints a random id per row, as it always did. A shared constant
     * was tried and reverted: it made every unrecognised sender's refusal
     * supersede every other one, so anyone who could reach the port could
     * silence a real partner's dead letter by sending junk after it. Between
     * a stranger being able to flood the badge and a stranger being able to
     * empty it, flooding is the one that does not lose information, and
     * per-sender bounding is meaningless when the sender is exactly what has
     * not been established.
     *
     * <p>The recorder itself runs in REQUIRES_NEW and swallows its own
     * exceptions; the extra try-catch here is belt-and-braces so a recorder
     * bean failure can never poison the ACK we send back.
     */
    private void recordReject(String integrationId, UUID organizationId,
                              String messageType, String rawBody, String reason,
                              String reasonKey, String correlationScope) {
        String resolvedType = messageType == null ? "UNKNOWN" : messageType;
        try {
            messageRecorder.recordRecurringFailure(
                integrationId, organizationId,
                IntegrationMessageDirection.INBOUND,
                resolvedType,
                rawBody,
                reason,
                MllpRecordingContext.rejectionCorrelationId(
                    correlationScope, DISPATCHER_CORRELATION_TYPE, reasonKey));
        } catch (RuntimeException ex) {
            log.warn("Dispatcher recorder threw for integration={} type={} reason={}",
                integrationId, messageType, reason, ex);
        }
    }

    /**
     * One spelling of the sender's id for the whole MLLP surface. The
     * dispatcher's pre-service rejects and the inbound services' own rejects
     * have to land under the same {@code integration_id}, or an operator
     * reading the DLQ for a misconfigured sender sees half its messages. The
     * truncation that used to live here lives in {@link MllpRecordingContext}
     * with the reason it exists.
     */
    private static String integrationIdFor(Hl7MessageHeader header) {
        return MllpRecordingContext.integrationId(
            header.sendingApplication(), header.sendingFacility());
    }

    private static UUID organizationIdOf(Hospital hospital) {
        return MllpRecordingContext.organizationId(hospital);
    }

    private String ackForOutcome(Hl7MessageHeader header, MllpInboundOutcome outcome, String label) {
        return switch (outcome) {
            case ACCEPTED ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AA, null);
            // One answer for "no such entity" and for "an entity you may
            // not see": same code, same text. See MllpInboundOutcome —
            // the AR that used to distinguish them was an enumeration
            // oracle over every identifier space HL7 reaches.
            case REJECTED_NOT_FOUND ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                    label + " referenced entity not found");
            case REJECTED_INVALID ->
                Hl7AckBuilder.buildAck(header, Hl7AckBuilder.AckCode.AE,
                    label + " invalid or missing required fields");
        };
    }
}
