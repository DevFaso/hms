package com.example.hms.service.integration;

import com.example.hms.model.Hospital;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;

/**
 * Applies an inbound {@code ADT^A40} patient merge (Tier 2 item 41).
 *
 * <p>The merge machinery itself already existed — {@code EmpiService
 * .mergePatients} shipped in #439/#449 with alias reassignment and a
 * PATIENT_MERGE audit event, and A40 was explicitly deferred. This is the
 * inbound trigger for it.
 *
 * <p><b>It is not a thin adapter, and the reason is a security one.</b> Every
 * tenant guard inside {@code EmpiServiceImpl} is written against the CALLER's
 * active hospital, resolved from the security context — and
 * {@code isVisibleToCaller} treats a null active hospital as "unscoped, allow".
 * There is no security context on an MLLP worker thread, so on this path those
 * guards pass unconditionally. Wiring A40 straight through to
 * {@code mergePatients} would hand an allowlisted sender the ability to merge
 * <em>any two patients in the system</em>: the allowlist decides which sender
 * may connect, and nothing would decide which patients they may merge.
 *
 * <p>So this service enforces the boundary itself, before delegating —
 * <b>both</b> patients must already be registered at the receiving hospital.
 * That is the same gate {@code MllpInboundAdtServiceImpl} applies to
 * demographic updates, and for the same reason: a sender at hospital B has no
 * business reshaping identity for a patient known only to hospital A.
 *
 * <p>Merges are never auto-created from unknown identifiers. Both sides must
 * already exist in EMPI; an unrecognised MRN is rejected, not provisioned.
 */
public interface MllpInboundMergeService {

    /**
     * @param parsed             PID-3 survivor and MRG-1 retiree — see
     *                           {@link ParsedMergeMessage} for why the
     *                           direction is named rather than positional
     * @param receivingHospital  the allowlisted sender's hospital
     * @param messageControlId   MSH-10, recorded on the merge so an operator
     *                           can trace it back to the inbound message
     */
    MllpInboundOutcome processMerge(
        ParsedMergeMessage parsed,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId
    );
}
