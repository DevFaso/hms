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
    MllpInboundOutcome processAdt(
        ParsedAdtMessage parsed,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility
    );
}
