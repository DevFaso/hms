package com.example.hms.service.integration;

/**
 * Result of processing one inbound HL7 v2 message through the MLLP
 * dispatcher. The dispatcher maps each value to an HL7 v2 ACK code:
 *
 * <ul>
 *   <li>{@link #ACCEPTED}              → AA (application accept)</li>
 *   <li>{@link #REJECTED_NOT_FOUND}    → AE (application error,
 *       cannot resolve referenced entity — e.g. unknown placer order
 *       number, unknown patient MRN)</li>
 *   <li>{@link #REJECTED_CROSS_TENANT} → AR (application reject — the
 *       referenced entity exists but belongs to a different hospital
 *       than the allowlisted receiving hospital; treated as a hard
 *       reject so the analyzer doesn't keep retrying)</li>
 *   <li>{@link #REJECTED_INVALID}      → AE (parse failure, missing
 *       mandatory fields, etc.)</li>
 * </ul>
 *
 * <p>Modelled after the same intent as {@code Hl7AckBuilder.AckCode}
 * but kept on the service side so domain code never has to depend on
 * MLLP framing types.
 */
public enum MllpInboundOutcome {
    ACCEPTED,
    REJECTED_NOT_FOUND,
    REJECTED_CROSS_TENANT,
    REJECTED_INVALID
}
