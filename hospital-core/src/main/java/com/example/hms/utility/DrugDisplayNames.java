package com.example.hms.utility;

import com.example.hms.model.medication.MedicationCatalogItem;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * QA D1235 class (round 24): the one place a catalogue drug is named in text a
 * person reads - a refusal, a check reason. The wrong-drug refusal named the
 * lot's drug by its English INN to a Spanish pharmacist ("el lote
 * seleccionado es Amoxicillin, que no es el Paracétamol prescrito"), beside
 * the French name it uses for a French reader.
 *
 * <p>The catalogue keeps two names: the French name ({@code nameFr}, the one
 * every screen shows: stock, dispensing, the prescription picker) and the
 * generic (INN) name, written in English. So:
 * <ul>
 *   <li>a French reader reads the French name;</li>
 *   <li>an English reader reads the INN, which is the English name;</li>
 *   <li>any other reader (Spanish today) reads the catalogue's display name,
 *       the French one: the catalogue holds no Spanish name, inventing one is
 *       clinical fabrication, and this is the name the same reader sees on the
 *       lot and on the prescription, so the sentence names one drug one way.
 *       A Spanish name needs a catalogue column (tasklist standing debt).</li>
 * </ul>
 * A missing name falls back to the other names, then the brand.
 * {@code DrugDisplayNameGuardTest} fails on a refusal that picks a catalogue
 * name any other way.
 */
public final class DrugDisplayNames {

    private DrugDisplayNames() {
    }

    /** The drug's name for the reader of the current request, or null when it has none. */
    @Nullable
    public static String forReader(MedicationCatalogItem item) {
        return forReader(item, DisplayNumbers.requestLocale());
    }

    /** The drug's name for a reader of {@code locale}, or null when it has none. */
    @Nullable
    public static String forReader(@Nullable MedicationCatalogItem item, @Nullable Locale locale) {
        if (item == null) {
            return null;
        }
        String language = locale == null ? "" : locale.getLanguage();
        if ("en".equals(language)) {
            return firstNonBlank(item.getGenericName(), item.getNameFr(), item.getBrandName());
        }
        return firstNonBlank(item.getNameFr(), item.getGenericName(), item.getBrandName());
    }

    @Nullable
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }
}
