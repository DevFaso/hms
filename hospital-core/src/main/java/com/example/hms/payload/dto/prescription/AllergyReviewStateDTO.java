package com.example.hms.payload.dto.prescription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * QA D1584 / D1585: where an allergy conflict the gates hold stands, as every gate's card shows it.
 * Read by {@code CurrentAllergyRecheck} from the same token the gates check, so a card can never say
 * "awaiting review" while the gate lets the dose through, or the reverse.
 *
 * <p>{@code plan} and {@code rationale} are the prescriber's clinical narrative (encrypted at rest,
 * decrypted by the entity's converter). They are filled only for a clinical reader of the order at the
 * order's own hospital (CurrentAllergyRecheck#mayReadReviewPlan); otherwise they are null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllergyReviewStateDTO {

    /** The conflict's state at the gates. */
    public enum State {
        /** No review answers the conflict as it stands now (none recorded, or the allergy or the order changed). */
        AWAITING_REVIEW,
        /** The prescriber's CONTINUE review answers this very conflict: the gates pass under its plan. */
        CONTINUE,
        /** The prescriber decided to stop: every gate refuses. */
        STOP
    }

    private State state;
    /** The reviewing prescriber's name; null while awaiting review. */
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    /** The CONTINUE plan (monitoring, premedication); null unless the reader may see it. */
    private String plan;
    /** The prescriber's rationale; null unless the reader may see it. */
    private String rationale;

    /**
     * QA D3945 (r77, r81): a review is on record but no longer answers the conflict (the allergy record or the
     * order changed after it), so the gates hold again. The screen used to say only "awaiting review", as if no
     * prescriber had ever looked. These say that a review existed, whose, when, what it decided and why it no
     * longer counts. All null when no review was ever recorded, or while the review on record is current.
     */
    private State voidedDecision;
    /** The prescriber of the review that no longer counts. */
    private String voidedReviewedByName;
    private LocalDateTime voidedReviewedAt;
    /** Why it no longer counts: {@code ALLERGY_CHANGED} or {@code ALLERGY_OR_ORDER_CHANGED}. */
    private String voidReason;
    /** {@link #voidReason} as a sentence in the reader's language, for a screen that shows the server's text. */
    private String voidReasonText;
}
