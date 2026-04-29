package com.example.hms.service.integration;

import com.example.hms.model.Hospital;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;

public interface MllpInboundLabService {

    /**
     * Persists an inbound HL7 v2 ORU^R01 observation as a {@code LabResult}
     * row, scoped to the receiving hospital resolved from the MLLP
     * allowlist. Returns the dispatcher-facing outcome that determines
     * the ACK code.
     *
     * <p>Resolution rule: OBR-2 (placer order number) is matched against
     * {@code LabSpecimen.accessionNumber} — that is the id we put on the
     * outbound {@code OML^O21} order, which the analyzer is expected to
     * echo back. The {@code LabSpecimen} carries the back-link to its
     * {@code LabOrder}.
     *
     * <p>The persisted row uses {@code actorType=SYSTEM} and
     * {@code actorLabel="MLLP:{sendingApp}/{sendingFac}"}; assignment
     * stays {@code null} (V61 lifted the FK and the entity guard).
     */
    MllpInboundOutcome processOruR01(
        ParsedObservation observation,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility
    );
}
