package com.example.hms.service.support;

import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.utility.MessageUtil;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * The description and recommendation of a drug interaction, in the reader's
 * language (QA D887).
 *
 * <p>The knowledge base was seeded in English (V63, V120) and is edited by the
 * pharmacy team through the admin API. A French or Spanish pharmacist read the
 * seeded advice in English. The bundles now carry every seeded text under
 * {@code ddi.seed.<code>.<code>.<field>} (the two RxNorm codes in sorted order),
 * whose English line IS the seeded sentence. A stored text that still equals
 * it is shown in the reader's language; one that differs was written by a
 * person since, and is shown exactly as they typed it, so an edit is never
 * hidden behind a stale translation.
 */
public final class DrugInteractionText {

    private static final String PREFIX = "ddi.seed.";

    private DrugInteractionText() {
    }

    public static String description(DrugInteraction interaction) {
        return interaction == null ? null
            : localized(interaction.getDrug1Code(), interaction.getDrug2Code(), "description",
                interaction.getDescription());
    }

    public static String recommendation(DrugInteraction interaction) {
        return interaction == null ? null
            : localized(interaction.getDrug1Code(), interaction.getDrug2Code(), "recommendation",
                interaction.getRecommendation());
    }

    static String localized(String code1, String code2, String field, String stored) {
        if (stored == null || code1 == null || code2 == null) {
            return stored;
        }
        String a = code1.trim();
        String b = code2.trim();
        String key = PREFIX + (a.compareTo(b) <= 0 ? a + "." + b : b + "." + a) + "." + field;
        String seeded = MessageUtil.resolveOrNull(Locale.ENGLISH, key);
        if (seeded == null || !seeded.equals(stored.trim())) {
            return stored;
        }
        String translated = MessageUtil.resolveOrNull(LocaleContextHolder.getLocale(), key);
        return translated != null ? translated : stored;
    }
}
