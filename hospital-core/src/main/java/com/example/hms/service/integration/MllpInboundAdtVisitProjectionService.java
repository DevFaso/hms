package com.example.hms.service.integration;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;

/**
 * Projects an inbound ADT^A01/A04/A08 message onto the existing
 * HMS {@link com.example.hms.model.Admission} / {@link com.example.hms.model.Encounter}
 * graph — the half of roadmap row 24 that goes beyond the demographic
 * upsert.
 *
 * <p><strong>Foundation pass.</strong> This iteration only performs
 * <em>reconciliation</em>: it looks up an existing Admission / Encounter
 * by the HL7 visit-number triplet ({@code MSH-3}, {@code MSH-4},
 * {@code PV1-19}, hospital) and stamps the most recent message control
 * id on it. It deliberately does NOT auto-create Admission or
 * Encounter rows from ADT, because both entities carry strict
 * invariants (staff + assignment must belong to the encounter hospital;
 * Admission requires {@code admittingProvider}, {@code admissionType},
 * {@code acuityLevel}, {@code chiefComplaint}) that the ADT segment
 * does not carry. Auto-provisioning will land in a follow-on PR once
 * the per-hospital "intake provider" config and conflict-resolution
 * defaults are in place — see
 * {@code docs/runbooks/hl7-adt-conflict-resolution.md}.
 *
 * <p>The whole projection is gated by
 * {@code app.hl7.adt.visit-sync.enabled} (default {@code false}). The
 * demographic upsert in {@link MllpInboundAdtService} is unchanged
 * whether the flag is on or off — projection runs <em>after</em> the
 * demographic write succeeds and never causes the parent transaction
 * to roll back.
 */
public interface MllpInboundAdtVisitProjectionService {

    /**
     * Possible outcomes of the projection step. Surfaced for tests and
     * for the audit / log line; the parent ADT flow always returns
     * {@link MllpInboundOutcome#ACCEPTED} regardless of which value we
     * return here, because the demographics write is the load-bearing
     * operation.
     */
    enum VisitProjectionResult {
        /** Sync is disabled or no PV1-19 was present — no-op by design. */
        SKIPPED,
        /** PV1-19 matched an existing Admission; its control id was stamped. */
        ADMISSION_RECONCILED,
        /** PV1-19 matched an existing Encounter; its control id was stamped. */
        ENCOUNTER_RECONCILED,
        /**
         * PV1-19 did not match any existing Admission or Encounter for the
         * sender+hospital. A WARN is emitted when
         * {@code app.hl7.adt.visit-sync.log-unmatched=true}.
         */
        NO_MATCH,
        /**
         * No-match resolved by writing a fresh Admission populated from
         * per-hospital intake-provider config (roadmap row 24 follow-on).
         * Fires only when ALL of (master flag, auto-create sub-flag,
         * per-hospital opt-in) are true AND the trigger event is A01 AND
         * the per-hospital intake config row exists AND the patient is
         * actively registered at the receiving hospital. Any other
         * configuration returns {@link #NO_MATCH}.
         */
        ADMISSION_AUTOCREATED,
        /**
         * No-match resolved by writing a fresh Encounter populated from
         * per-hospital intake-provider config (roadmap row 24, A04
         * follow-on). Fires under the same three-layer flag stack as
         * {@link #ADMISSION_AUTOCREATED}, on A04 (patient registration)
         * trigger, with the additional requirement that
         * {@code adt_intake_provider_configs.default_assignment_id} is
         * populated AND the resolved assignment + staff + department all
         * belong to the receiving hospital
         * ({@code Encounter#validate} invariants).
         */
        ENCOUNTER_AUTOCREATED
    }

    /**
     * Reconcile the parsed ADT message against the patient's existing
     * visit graph at the receiving hospital. The patient is the row
     * resolved by the demographic upsert step in
     * {@link MllpInboundAdtService} — the caller has already
     * verified the cross-tenant gate before invoking the projection.
     *
     * @param parsed              fully-parsed ADT (PID + PV1 segments)
     * @param patient             EMPI-resolved {@code Patient} row;
     *                            already known to be registered at {@code receivingHospital}
     * @param receivingHospital   the MLLP-allowlisted hospital the
     *                            sender was resolved to
     * @param sendingApplication  MSH-3 (sender app)
     * @param sendingFacility     MSH-4 (sender facility)
     * @param messageControlId    MSH-10 (control id of this specific
     *                            message — stamped on whatever row is
     *                            reconciled)
     * @return the structured outcome of the projection, primarily for
     *         observability and tests
     */
    VisitProjectionResult projectVisit(
        ParsedAdtMessage parsed,
        Patient patient,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId
    );
}
