package com.example.hms.cdshooks.rules;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.PediatricWeightBand;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D2552 (round 65): the WHO weight bands of artemether-lumefantrine, used when the hospital's own
 * catalogue holds no paediatric limit for the drug. Before this, a 12 kg child's order for the adult
 * dose (4 tablets twice a day) signed with an advisory only.
 *
 * <p>Source: World Health Organization, <em>Guidelines for the treatment of malaria</em>, 3rd edition,
 * Geneva 2015 (ISBN 978 92 4 154912 7), section 4 "Treatment of uncomplicated Plasmodium falciparum
 * malaria", artemether + lumefantrine dosing table, p. 35-36; carried unchanged into the consolidated
 * <em>WHO Guidelines for malaria</em>. Dose of artemether + lumefantrine given twice daily for 3 days
 * (six doses): 5 to &lt; 15 kg 20 + 120 mg; 15 to &lt; 25 kg 40 + 240 mg; 25 to &lt; 35 kg 60 + 360 mg;
 * &ge; 35 kg 80 + 480 mg, that is 1, 2, 3 and 4 tablets of 20/120 mg. Under 5 kg the guideline asks for
 * the same mg/kg target as at 5 kg, which no band expresses: the existing "no weight band covers"
 * review applies.
 *
 * <p>PROVISIONAL (« provisoire »): a published default, never a local approval. It carries the status
 * {@link PediatricDoseLimitStatus#DEFAULT_PENDING_APPROVAL}, so, as for every limit not approved here
 * (CR-01), a dose under it is not cleared: the order is held for the pharmacist's or another
 * physician's review, or signed by the prescriber as an urgent exception with a reason; a dose above
 * the band is a critical soft stop that needs a reason. A hospital that records its own bands (or any
 * paediatric limit) on its catalogue item replaces these, and its approval clears in-band doses.
 */
@ProvisionalRule(ClinicalRuleSetKey.AL_WHO_WEIGHT_BANDS)
final class ArtemetherLumefantrineWhoBands {

    /** The tablet the WHO bands count. */
    static final String BANDED_STRENGTH = "20/120";
    private static final BigDecimal ARTEMETHER_MG = new BigDecimal("20");
    private static final BigDecimal LUMEFANTRINE_MG = new BigDecimal("120");

    static final String SOURCE =
        "WHO Guidelines for the treatment of malaria, 3rd ed. 2015, section 4 (artemether + lumefantrine,"
            + " 20/120 mg tablets twice daily for 3 days) - provisoire";

    /**
     * One amount written in a strength. Bounded and possessive (CodeQL java/polynomial-redos): a
     * prescriber's text is read in linear time. Whatever stands between two amounts ("/", "+", "-",
     * "mg", "Luméfantrine") is not read: the first two amounts of a text are its strength.
     */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,6}+(?:[.,]\\d{1,3}+)?+");
    /** Words that name a stronger tablet than 20/120 mg without its amounts ("Coartem forte", "DS"). */
    private static final Pattern STRONGER_TABLET_WORD = Pattern.compile("\\b(?:forte|ds|double)\\b");
    /** No drug name or strength is longer; a longer text is read up to here. */
    private static final int MAX_READ = 300;

    private ArtemetherLumefantrineWhoBands() {
    }

    /** Whether the order names artemether-lumefantrine, by its written name or its catalogue item. */
    static boolean names(String medicationName, MedicationCatalogItem item) {
        StringBuilder all = new StringBuilder(medicationName == null ? "" : medicationName);
        if (item != null) {
            all.append(' ').append(item.getNameFr()).append(' ').append(item.getGenericName())
                .append(' ').append(item.getBrandName());
        }
        String text = folded(all.toString());
        return (text.contains("artemether") && text.contains("lumefantrin")) || text.contains("coartem");
    }

    /**
     * The strength stated on the item or in the written name that the WHO bands cannot count (anything
     * but 20/120 mg: "80/480", "80 mg + 480 mg", "Artéméther 80 mg / Luméfantrine 480 mg", "80-480",
     * "forte"), as read; {@code null} when every stated strength is 20/120 mg, or none is stated.
     */
    static String statedStrength(String medicationName, MedicationCatalogItem item) {
        for (String candidate : sources(medicationName, item)) {
            String stated = strengthIn(candidate);
            if (stated != null && !isBandedStrength(stated)) {
                return stated;
            }
        }
        return null;
    }

    /**
     * Review of #1276 (QA D2552): whether the WHO bands count this product's tablets. Only a product
     * clearly of 20/120 mg: a strength of 20/120 mg stated on the item or in the name, and no other
     * strength stated anywhere; or, with no strength written at all, the Coartem brand, whose tablets
     * are all 20/120 mg. A generic name with no strength could be the 80/480 mg tablet: it is not
     * counted, and the order is held as "cannot assess" rather than judged in tablets four times too weak.
     */
    static boolean bandsCountThisProduct(String medicationName, MedicationCatalogItem item) {
        boolean banded = false;
        for (String candidate : sources(medicationName, item)) {
            String stated = strengthIn(candidate);
            if (stated != null) {
                if (!isBandedStrength(stated)) {
                    return false;
                }
                banded = true;
            }
        }
        return banded || namesCoartem(medicationName, item);
    }

    /** The texts a strength is read from: the item's strength, the written name, the item's names. */
    private static String[] sources(String medicationName, MedicationCatalogItem item) {
        return new String[] {item == null ? null : item.getStrength(), medicationName,
            item == null ? null : item.getNameFr(), item == null ? null : item.getBrandName()};
    }

    /**
     * The strength one text states: its first two amounts as "a/b", a lone amount as written, or a word
     * naming a stronger tablet; {@code null} when it states none.
     */
    private static String strengthIn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String read = folded(text.length() > MAX_READ ? text.substring(0, MAX_READ) : text);
        Matcher m = AMOUNT.matcher(read);
        if (m.find()) {
            String first = m.group();
            return m.find() ? first + "/" + m.group() : first;
        }
        Matcher word = STRONGER_TABLET_WORD.matcher(read);
        return word.find() ? word.group() : null;
    }

    /** 20/120 mg, in either order (an item may name lumefantrine first). */
    private static boolean isBandedStrength(String stated) {
        int slash = stated.indexOf('/');
        if (slash < 0) {
            return false;
        }
        BigDecimal a = amount(stated.substring(0, slash));
        BigDecimal b = amount(stated.substring(slash + 1));
        return (a.compareTo(ARTEMETHER_MG) == 0 && b.compareTo(LUMEFANTRINE_MG) == 0)
            || (a.compareTo(LUMEFANTRINE_MG) == 0 && b.compareTo(ARTEMETHER_MG) == 0);
    }

    /** The order names the Coartem brand, whose tablets are 20/120 mg (a stronger one is caught as a strength). */
    private static boolean namesCoartem(String medicationName, MedicationCatalogItem item) {
        StringBuilder all = new StringBuilder(medicationName == null ? "" : medicationName);
        if (item != null) {
            all.append(' ').append(item.getNameFr()).append(' ').append(item.getGenericName())
                .append(' ').append(item.getBrandName());
        }
        String text = folded(all.toString());
        return text.contains("coartem");
    }

    /**
     * The WHO default as a limit the paediatric check reads like a catalogue one: oral, the four bands,
     * its source, pending local approval. The item's own form and manual-review age are kept.
     */
    static MedicationCatalogItem asDefaultLimit(MedicationCatalogItem own) {
        return MedicationCatalogItem.builder()
            .nameFr(own == null ? null : own.getNameFr())
            .genericName(own == null ? null : own.getGenericName())
            .form(own == null ? null : own.getForm())
            .strength(BANDED_STRENGTH)
            .strengthUnit("mg")
            .route("PO")
            .pediatricManualReviewUnderMonths(own == null ? null : own.getPediatricManualReviewUnderMonths())
            .pediatricWeightBands(bands())
            .pediatricDoseSource(SOURCE)
            .pediatricDoseStatus(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL)
            .build();
    }

    /** The four WHO bands, in tablets of 20/120 mg, twice a day. */
    static List<PediatricWeightBand> bands() {
        List<PediatricWeightBand> bands = new ArrayList<>();
        bands.add(band("5", "15", "1"));
        bands.add(band("15", "25", "2"));
        bands.add(band("25", "35", "3"));
        bands.add(band("35", null, "4"));
        return bands;
    }

    private static PediatricWeightBand band(String min, String max, String tablets) {
        return PediatricWeightBand.builder()
            .minWeightKg(new BigDecimal(min))
            .maxWeightKg(max == null ? null : new BigDecimal(max))
            .dosePerAdministration(new BigDecimal(tablets))
            .doseUnit(null)
            .maxDosesPerDay(2)
            .build();
    }

    private static BigDecimal amount(String written) {
        return new BigDecimal(written.replace(',', '.'));
    }

    private static String folded(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
