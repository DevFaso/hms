package com.example.hms.payload.dto.prescription;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;

import java.util.List;

/**
 * QA D1584: a gate response that shows the allergy re-check's cards. The cards and the review state are
 * set together, only by {@code CurrentAllergyRecheck.Reading#applyTo}, so no gate shows a stop card
 * without saying whether the prescriber reviewed it (AllergyReviewStateGuardTest).
 */
public interface AllergyReviewCarrier {

    /** The allergy cards (the stop first, then the cross-reactivity advisory). */
    void setAllergyCards(List<CdsCard> cards);

    /** The stop's review state; null when no card stops the drug. */
    void setAllergyReview(AllergyReviewStateDTO review);
}
