package com.example.hms.cdshooks.rules;

import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.math.BigDecimal;

/**
 * QA D3306 (r70; D3151 before it): a child's paracetamol order at a hospital whose catalogue records no
 * paediatric limit for the drug got « évaluation impossible … aucune limite de paracétamol enregistrée …
 * source non précisée », although the child's weight was on the chart: 500 mg four times a day for a 12 kg
 * two-year-old (42 mg/kg per dose, 167 mg/kg per day) reached the reviewer with no figure and no limit.
 *
 * <p>The limits of the registered rule set {@link ClinicalRuleSetKey#PARACETAMOL_LIMITS} now stand in for a
 * catalogue with none, exactly as the WHO bands do for artemether-lumefantrine
 * ({@link ArtemetherLumefantrineWhoBands}): by mouth, from 1 month <b>15 mg/kg per dose and 60 mg/kg per day</b>,
 * under 1 month 10 mg/kg per dose and 40 mg/kg per day. Source, as registered: MSF, Essential drugs, oral
 * paracetamol; NHS. The check reads the latest weight on the chart, and says its date.
 *
 * <p>PROVISIONAL (« provisoire »): a published default, never a local approval. It carries the status
 * {@link PediatricDoseLimitStatus#DEFAULT_PENDING_APPROVAL}, so a dose under it is not cleared by itself (the
 * order is held for the second-person dose review, or signed by the prescriber as an urgent exception with a
 * reason), and a dose above it is a critical soft stop that needs a reason. It judges the oral route only: an
 * order by another route keeps « examen requis ». A hospital that records its own limits on its catalogue item
 * replaces these. A combination (paracetamol + codeine…) is not judged by them.
 */
@ProvisionalRule(ClinicalRuleSetKey.PARACETAMOL_LIMITS)
final class ParacetamolPaediatricDefault {

    static final String SOURCE =
        "MSF, Essential drugs, oral paracetamol (15 mg/kg per dose, 60 mg/kg per day from 1 month;"
            + " 10 and 40 under 1 month); NHS - provisoire";

    /** From 1 month. */
    static final BigDecimal MAX_DOSE_MG_PER_KG = new BigDecimal("15");
    static final BigDecimal MAX_DAILY_MG_PER_KG = new BigDecimal("60");
    /** Under 1 month. */
    static final BigDecimal NEONATE_MAX_DOSE_MG_PER_KG = new BigDecimal("10");
    static final BigDecimal NEONATE_MAX_DAILY_MG_PER_KG = new BigDecimal("40");

    private ParacetamolPaediatricDefault() {
    }

    /** The order is paracetamol alone: the default limits are for it, not for a combination. */
    static boolean appliesTo(ParacetamolFacts facts) {
        return facts != null && facts.applies() && !facts.proposedCombination();
    }

    /**
     * The default as a limit the paediatric check reads like a catalogue one: oral, mg/kg per dose and per day
     * for the child's age, its source, pending local approval. The item's own form, strength and manual-review
     * age are kept.
     */
    static MedicationCatalogItem asDefaultLimit(MedicationCatalogItem own, long ageMonths) {
        boolean neonate = ageMonths < 1;
        return MedicationCatalogItem.builder()
            .nameFr(own == null ? null : own.getNameFr())
            .genericName(own == null ? null : own.getGenericName())
            .form(own == null ? null : own.getForm())
            .strength(own == null ? null : own.getStrength())
            .strengthUnit(own == null ? null : own.getStrengthUnit())
            .route("PO")
            .pediatricManualReviewUnderMonths(own == null ? null : own.getPediatricManualReviewUnderMonths())
            .pediatricMaxDoseMgPerKg(neonate ? NEONATE_MAX_DOSE_MG_PER_KG : MAX_DOSE_MG_PER_KG)
            .pediatricMaxDailyMgPerKg(neonate ? NEONATE_MAX_DAILY_MG_PER_KG : MAX_DAILY_MG_PER_KG)
            .pediatricDoseSource(SOURCE)
            .pediatricDoseStatus(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL)
            .build();
    }
}
