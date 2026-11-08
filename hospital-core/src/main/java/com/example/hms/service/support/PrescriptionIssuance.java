package com.example.hms.service.support;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;

import java.util.List;

/**
 * QA D1537 (round 40): whether a prescription was ever issued, the one rule every reader that
 * presents a patient's medicines as taken asks.
 *
 * <p>A draft and an order still waiting for its signature were never given to the patient (QA D101,
 * D152). Neither was a draft the prescriber then cancelled or discontinued: the status alone says
 * CANCELLED or DISCONTINUED, the same words a signed order takes when it is stopped, so the status
 * cannot tell the two apart. The signature can: a withdrawn order was issued only when it carries
 * one ({@code signedAt}, the signer or the digest, all written by the signing ceremony). A signed
 * order that was then discontinued stays a past medicine.
 *
 * <p>Two readings:
 * <ul>
 *   <li>{@link #wasIssued} - the patient's own and the proxy's readers, the health summary, the
 *       prescriptions list, the patient apps (the same API) and the FHIR exports: only orders that
 *       were issued.</li>
 *   <li>{@link #isDiscardedProposal} - the clinician's medication list and timeline, which keep a
 *       live draft (shown on hold, waiting for its signature) but never present a draft that was
 *       thrown away as a course that was stopped.</li>
 * </ul>
 *
 * <p>Caveat: a signed order from before V118 (2026-08) has no signature on record; once withdrawn it
 * reads as never issued. Manufacturing the evidence is worse (V118's rule), and such rows predate
 * the patient portal's medication pages.
 */
public final class PrescriptionIssuance {

    private PrescriptionIssuance() {
    }

    /** Whether the order was ever issued to the patient: signed, and not only proposed. */
    public static boolean wasIssued(Prescription prescription) {
        if (prescription == null) {
            return false;
        }
        PrescriptionStatus status = prescription.getStatus();
        if (status == null) {
            // A row with no status predates the workflow; it was never a proposal.
            return true;
        }
        if (status.isProposal()) {
            return false;
        }
        return !status.isWithdrawn() || carriesSignature(prescription);
    }

    /** A proposal (draft or unsigned order) that was withdrawn before anyone signed it. */
    public static boolean isDiscardedProposal(Prescription prescription) {
        return prescription != null && prescription.getStatus() != null
            && prescription.getStatus().isWithdrawn() && !carriesSignature(prescription);
    }

    /** {@link #wasIssued} over a list, in its order. */
    public static List<Prescription> issuedOnly(List<Prescription> rows) {
        return rows.stream().filter(PrescriptionIssuance::wasIssued).toList();
    }

    /** The list less the discarded proposals ({@link #isDiscardedProposal}), in its order. */
    public static List<Prescription> withoutDiscardedProposals(List<Prescription> rows) {
        return rows.stream().filter(p -> !isDiscardedProposal(p)).toList();
    }

    private static boolean carriesSignature(Prescription prescription) {
        return prescription.getSignedAt() != null
            || prescription.getSignedBy() != null
            || prescription.getSignatureValue() != null;
    }
}
