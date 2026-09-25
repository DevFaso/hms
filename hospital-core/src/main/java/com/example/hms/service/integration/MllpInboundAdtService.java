package com.example.hms.service.integration;

import com.example.hms.model.Hospital;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;

public interface MllpInboundAdtService {

    /**
     * Projects an inbound HL7 v2 ADT^A01/A04/A08 message onto the
     * existing {@code Patient} demographic record. The receiving
     * hospital is resolved from the MLLP allowlist.
     *
     * <p>Scope (P1 #2b): demographics upsert only — name (PID-5), DOB
     * (PID-7), sex (PID-8), and address (PID-11). Encounter creation
     * is intentionally <strong>not</strong> driven from ADT; clinical
     * encounters start when a clinician opens the chart through the
     * existing in-app workflow.
     *
     * <p>Patient resolution: PID-3 is treated as an MRN and looked up
     * via {@code EmpiService.findIdentityByAlias(MRN, ...)}. Unknown
     * MRNs are <strong>rejected, not auto-created</strong> — admitting
     * that an external system can register a brand-new patient via an
     * unsolicited ADT message is a much larger trust decision than
     * this PR is in a position to make. The patient must already be
     * registered through the existing intake flow.
     */
    default MllpInboundOutcome processAdt(
        ParsedAdtMessage parsed,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility
    ) {
        return processAdt(parsed, receivingHospital, sendingApplication, sendingFacility,
            null, null);
    }

    /**
     * Variant that also carries the inbound MSH-10 message control id.
     * Stamped onto the reconciled Admission / Encounter row by the
     * visit-sync projection (gated by
     * {@code app.hl7.adt.visit-sync.enabled}) so an operator can trace a
     * row back to the inbound {@code integration_messages} entry that
     * touched it.
     *
     * <p>This {@code default} implementation preserves backwards
     * compatibility with existing {@link MllpInboundAdtService}
     * implementations by delegating to the original 4-argument
     * {@link #processAdt(ParsedAdtMessage, Hospital, String, String)}
     * — implementers that haven't migrated yet keep working but ignore
     * {@code messageControlId}. Implementers that want the control id
     * threaded through the projection layer (e.g.
     * {@code MllpInboundAdtServiceImpl}) should <strong>override</strong>
     * this method and stop relying on the 4-arg path.
     */
    default MllpInboundOutcome processAdt(
        ParsedAdtMessage parsed,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId
    ) {
        return processAdt(parsed, receivingHospital, sendingApplication, sendingFacility,
            messageControlId, null);
    }

    /**
     * Variant that also carries the raw inbound message, so a rejection can
     * leave an {@code integration_message_event} row an operator can read and
     * replay.
     *
     * <p>That row is the only place a cross-tenant refusal is distinguishable
     * from an unknown MRN. The ACK deliberately is not: see
     * {@link MllpInboundOutcome}.
     *
     * <p>This is the interface's <b>abstract</b> method, and the narrower
     * overloads above are the defaults, rather than the other way round. An
     * implementation that satisfied a 4-argument contract and inherited a
     * default that threw the body away would compile, wire into the
     * dispatcher, and silently write no DLQ row for any rejection — and that
     * row is the whole compensating control for the indistinguishable ACK.
     */
    MllpInboundOutcome processAdt(
        ParsedAdtMessage parsed,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId,
        String rawMessageBody
    );
}
