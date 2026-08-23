package com.example.hms.service.integration;

import com.example.hms.model.Hospital;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;

import java.util.List;

public interface MllpInboundLabService {

    /**
     * Persists an inbound HL7 v2 ORU^R01 as {@code LabResult} rows —
     * one per OBX segment, all in one transaction — scoped to the
     * receiving hospital resolved from the MLLP allowlist. Returns the
     * dispatcher-facing outcome that determines the ACK code.
     *
     * <p>All-or-nothing: either every observation of the message
     * persists or none does. That keeps the sender-scoped MSH-10
     * replay check sound — the presence of ANY row for the triple
     * implies the whole message already landed.
     *
     * <p>Resolution rule: OBR-2 (placer order number) is matched against
     * {@code LabSpecimen.accessionNumber} — that is the id we put on the
     * outbound {@code OML^O21} order, which the analyzer is expected to
     * echo back. The {@code LabSpecimen} carries the back-link to its
     * {@code LabOrder}. A multi-OBR message resolves each OBX against
     * its own OBR group's placer.
     *
     * <p>The persisted rows use {@code actorType=SYSTEM} and
     * {@code actorLabel="MLLP:{sendingApp}/{sendingFac}"}; assignment
     * stays {@code null} (V61 lifted the FK and the entity guard).
     */
    MllpInboundOutcome processOruR01(
        List<ParsedObservation> observations,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId,
        String rawMessageBody
    );
}
