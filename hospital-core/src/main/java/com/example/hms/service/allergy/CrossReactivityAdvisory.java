package com.example.hms.service.allergy;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.PatientAllergy;
import com.example.hms.utility.MessageUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D793 / D799: the one WARNING card for a beta-lactam that may cross-react
 * with a documented beta-lactam allergy ({@link BetaLactamCrossReactivity}).
 * The prescriber saw it on create, update and sign, but the nurse giving the
 * dose did not: the eMAR row carried no advisory and the bedside verify said
 * allPassed=true with no word. Every surface builds the card here, so the
 * prescriber and the nurse read the same localised text. It never blocks.
 */
public final class CrossReactivityAdvisory {

    /** The card's source label, the same on every surface. */
    public static final String SOURCE_LABEL = "HMS allergy check";

    private CrossReactivityAdvisory() {
    }

    /**
     * The advisory card for this medication and these allergies, or empty when
     * no active allergy may cross-react with it (or one matches it directly,
     * which is the allergy check's block, not an advisory).
     */
    public static Optional<CdsCard> card(String medicationName, List<PatientAllergy> allergies) {
        return BetaLactamCrossReactivity.crossReactiveAllergen(medicationName, allergies)
            .map(allergen -> new CdsCard(
                MessageUtil.resolveOrRaw("cds.allergy.crossReactivity.summary", medicationName, allergen),
                MessageUtil.resolveOrRaw("cds.allergy.crossReactivity.detail", medicationName, allergen),
                CdsCard.Indicator.WARNING,
                new Source(SOURCE_LABEL, null, null),
                null, null, null, UUID.randomUUID().toString()));
    }
}
