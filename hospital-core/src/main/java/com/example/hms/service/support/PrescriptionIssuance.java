package com.example.hms.service.support;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;

import java.util.List;

/**
 * QA D1537 (round 40): whether a prescription was ever issued, the one rule every reader that
 * presents a patient's medicines as taken asks.
 *
 * <p>A draft and an order still waiting for its signature were never given to the patient (QA D101,
 * D152). Neither was a draft the prescriber then cancelled or discontinued: its status says CANCELLED
 * or DISCONTINUED, the same words a signed order takes when it is stopped. The order records which it
 * was when it is withdrawn ({@code Prescription.withdrawnBeforeSigning}, set by
 * {@code Prescription.setStatus} from the status it held before; V283 backfilled it only where the
 * evidence was unambiguous). The signature columns are not read: an order signed before V118 carries
 * none, and stays a past medicine once stopped.
 *
 * <p>Two readings:
 * <ul>
 *   <li>{@link #wasIssued} - the patient's own and the proxy's readers, the health summary, the
 *       prescriptions list, the patient apps (the same API), the FHIR exports, and another hospital's
 *       reads ({@code NeverSentRule}): only orders that were issued.</li>
 *   <li>{@link #isDiscardedProposal} - the clinician's medication list and timeline, which keep a
 *       live draft (shown on hold, waiting for its signature) but never present a draft that was
 *       thrown away as a course that was stopped.</li>
 * </ul>
 */
public final class PrescriptionIssuance {

    /** A draft and an order waiting for its signature: proposals, never issued (QA D101, D152, D652). */
    public static final List<PrescriptionStatus> PROPOSALS =
        List.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE);

    private PrescriptionIssuance() {
    }

    /** Whether the order was ever issued to the patient: not a proposal, and not withdrawn as one. */
    public static boolean wasIssued(Prescription prescription) {
        if (prescription == null) {
            return false;
        }
        PrescriptionStatus status = prescription.getStatus();
        // A row with no status predates the workflow; it was never a proposal.
        return status == null || !(status.isProposal() || isDiscardedProposal(prescription));
    }

    /** A proposal (draft or unsigned order) that was withdrawn before anyone signed it. */
    public static boolean isDiscardedProposal(Prescription prescription) {
        return prescription != null && prescription.getStatus() != null
            && prescription.getStatus().isWithdrawn() && prescription.isWithdrawnBeforeSigning();
    }

    /** {@link #wasIssued} over a list, in its order. */
    public static List<Prescription> issuedOnly(List<Prescription> rows) {
        return rows.stream().filter(PrescriptionIssuance::wasIssued).toList();
    }

    /** The list less the discarded proposals ({@link #isDiscardedProposal}), in its order. */
    public static List<Prescription> withoutDiscardedProposals(List<Prescription> rows) {
        return rows.stream().filter(p -> !isDiscardedProposal(p)).toList();
    }
}
