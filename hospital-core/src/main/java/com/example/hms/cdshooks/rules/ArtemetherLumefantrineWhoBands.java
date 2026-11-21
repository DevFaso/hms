package com.example.hms.cdshooks.rules;

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
final class ArtemetherLumefantrineWhoBands {

    /** The tablet the WHO bands count. */
    static final String BANDED_STRENGTH = "20/120";
    private static final BigDecimal ARTEMETHER_MG = new BigDecimal("20");
    private static final BigDecimal LUMEFANTRINE_MG = new BigDecimal("120");

    static final String SOURCE =
        "WHO Guidelines for the treatment of malaria, 3rd ed. 2015, section 4 (artemether + lumefantrine,"
            + " 20/120 mg tablets twice daily for 3 days) - provisoire";

    private static final Pattern STRENGTH = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:mg)?\\s*/\\s*(\\d+(?:[.,]\\d+)?)");

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
     * The strength stated on the item or in the written name ("20/120", "80 mg/480"), as "a/b" in the
     * digits it was written with; {@code null} when none is written.
     */
    static String statedStrength(String medicationName, MedicationCatalogItem item) {
        Matcher m = strengthMatch(medicationName, item);
        return m == null ? null : m.group(1) + "/" + m.group(2);
    }

    /** Whether the WHO bands count this product's tablets: 20/120 mg, or no strength written. */
    static boolean bandsCountThisProduct(String medicationName, MedicationCatalogItem item) {
        Matcher m = strengthMatch(medicationName, item);
        return m == null || (amount(m.group(1)).compareTo(ARTEMETHER_MG) == 0
            && amount(m.group(2)).compareTo(LUMEFANTRINE_MG) == 0);
    }

    private static Matcher strengthMatch(String medicationName, MedicationCatalogItem item) {
        String fromItem = item == null ? null : item.getStrength();
        for (String candidate : new String[] {fromItem, medicationName, item == null ? null : item.getNameFr()}) {
            if (candidate == null) {
                continue;
            }
            Matcher m = STRENGTH.matcher(candidate);
            if (m.find()) {
                return m;
            }
        }
        return null;
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
