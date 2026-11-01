package com.example.hms.service.allergy;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.utility.MessageUtil;

import java.util.UUID;

/**
 * QA D1183 / D1149 (round 23): THE SAFETY NET's card. The ordered drug's name
 * resolved to no active ingredient (no catalogue item, no cited brand, no INN
 * stem) while the patient carries a drug-class allergy, so the allergy check
 * could not be done. A CRITICAL card: the prescriber chooses the drug from the
 * catalogue, or acknowledges with a reason, which is recorded and audited like
 * an allergy override. Every surface (prescription create, edit and sign, the
 * CDS order-sign and medication-prescribe hooks) builds it here, so the
 * prescriber reads the same localised text everywhere.
 */
public final class UnverifiableAllergyCard {

    /** The message key of the card's summary. */
    public static final String SUMMARY_KEY = "prescription.allergy.unverifiable";

    /** The message key of the card's detail. */
    public static final String DETAIL_KEY = "prescription.allergy.unverifiable.detail";

    private UnverifiableAllergyCard() {
    }

    /** The card for {@code medicationName} and the patient's class allergy {@code allergen}. */
    public static CdsCard of(String medicationName, String allergen) {
        String name = medicationName == null ? "" : medicationName.trim();
        String allergy = allergen == null ? "" : allergen.trim();
        return new CdsCard(
            MessageUtil.resolveOrRaw(SUMMARY_KEY, name, allergy),
            MessageUtil.resolveOrRaw(DETAIL_KEY, name, allergy),
            CdsCard.Indicator.CRITICAL,
            new Source(CrossReactivityAdvisory.sourceLabel(), null, null),
            null, null, null, UUID.randomUUID().toString());
    }
}
