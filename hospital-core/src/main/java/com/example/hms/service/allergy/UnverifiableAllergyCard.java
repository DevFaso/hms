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

    /** The English wording, when no bundle is loaded (a service built outside Spring). */
    private static final String ENGLISH_SUMMARY = "Allergy check impossible: \"{0}\" is not a recognised drug name,"
        + " and the patient has a documented allergy to {1}. Choose the drug from the catalogue, or confirm with a reason.";

    private static final String ENGLISH_DETAIL = "The allergy check matches a drug through its active ingredient."
        + " \"{0}\" is not in the catalogue and is not a known brand, so whether it belongs to the class of {1}"
        + " cannot be checked. Your reason is recorded on the order and audited.";

    private UnverifiableAllergyCard() {
    }

    private static String text(String key, String english, String name, String allergy) {
        if (MessageUtil.resolveOrNull(null, key) != null) {
            return MessageUtil.resolveOrRaw(key, name, allergy);
        }
        return java.text.MessageFormat.format(english, name, allergy);
    }

    /** The card for {@code medicationName} and the patient's class allergy {@code allergen}. */
    public static CdsCard of(String medicationName, String allergen) {
        String name = medicationName == null ? "" : medicationName.trim();
        String allergy = allergen == null ? "" : allergen.trim();
        return new CdsCard(
            text(SUMMARY_KEY, ENGLISH_SUMMARY, name, allergy),
            text(DETAIL_KEY, ENGLISH_DETAIL, name, allergy),
            CdsCard.Indicator.CRITICAL,
            new Source(CrossReactivityAdvisory.sourceLabel(), null, null),
            null, null, null, UUID.randomUUID().toString());
    }
}
