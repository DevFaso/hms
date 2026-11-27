package com.example.hms.model.medication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Go-live D2 (V385): the dose limits of a medication catalogue entry as one version carries them, stored as JSON
 * on {@link MedicationCatalogVersion}. Everything the dose check reads from the entry: the usual maxima per dose
 * and per day in mg/kg, the absolute maxima in mg, the age under which a dose is reviewed by hand, the weight
 * bands and the published source. They reach the catalogue row only through
 * {@link MedicationCatalogApprovedWrite}.
 */
public record DoseLimitSnapshot(
    BigDecimal maxDoseMgPerKg,
    BigDecimal maxDailyMgPerKg,
    BigDecimal maxSingleDoseMg,
    BigDecimal maxDailyMg,
    Integer manualReviewUnderMonths,
    List<PediatricWeightBand> weightBands,
    String source) {

    public DoseLimitSnapshot {
        weightBands = weightBands == null ? List.of() : List.copyOf(weightBands);
        source = FormularySnapshot.text(source);
    }

    /** The limits as the catalogue holds them now. */
    public static DoseLimitSnapshot of(MedicationCatalogItem item) {
        return new DoseLimitSnapshot(item.getPediatricMaxDoseMgPerKg(), item.getPediatricMaxDailyMgPerKg(),
            item.getPediatricMaxSingleDoseMg(), item.getPediatricMaxDailyMg(),
            item.getPediatricManualReviewUnderMonths(), item.getPediatricWeightBands(),
            item.getPediatricDoseSource());
    }

    /** Whether any limit is recorded at all. */
    public boolean hasAnyLimit() {
        return maxDoseMgPerKg != null || maxDailyMgPerKg != null || maxSingleDoseMg != null || maxDailyMg != null
            || manualReviewUnderMonths != null || !weightBands.isEmpty();
    }

    /** The limits that differ from {@code other}, by the name of their field; 15 and 15.000 are the same limit. */
    public List<String> differences(DoseLimitSnapshot other) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "maxDoseMgPerKg", maxDoseMgPerKg, other.maxDoseMgPerKg);
        addIfChanged(changed, "maxDailyMgPerKg", maxDailyMgPerKg, other.maxDailyMgPerKg);
        addIfChanged(changed, "maxSingleDoseMg", maxSingleDoseMg, other.maxSingleDoseMg);
        addIfChanged(changed, "maxDailyMg", maxDailyMg, other.maxDailyMg);
        if (!Objects.equals(manualReviewUnderMonths, other.manualReviewUnderMonths)) {
            changed.add("manualReviewUnderMonths");
        }
        if (!sameBands(weightBands, other.weightBands)) {
            changed.add("weightBands");
        }
        if (!Objects.equals(source, other.source)) {
            changed.add("source");
        }
        return changed;
    }

    private static void addIfChanged(List<String> changed, String field, BigDecimal mine, BigDecimal theirs) {
        if (!sameNumber(mine, theirs)) {
            changed.add(field);
        }
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }

    private static boolean sameBands(List<PediatricWeightBand> mine, List<PediatricWeightBand> theirs) {
        if (mine.size() != theirs.size()) {
            return false;
        }
        for (int i = 0; i < mine.size(); i++) {
            PediatricWeightBand a = mine.get(i);
            PediatricWeightBand b = theirs.get(i);
            boolean same = sameNumber(a.getMinWeightKg(), b.getMinWeightKg())
                && sameNumber(a.getMaxWeightKg(), b.getMaxWeightKg())
                && sameNumber(a.getDosePerAdministration(), b.getDosePerAdministration())
                && Objects.equals(FormularySnapshot.text(a.getDoseUnit()), FormularySnapshot.text(b.getDoseUnit()))
                && Objects.equals(a.getMaxDosesPerDay(), b.getMaxDosesPerDay());
            if (!same) {
                return false;
            }
        }
        return true;
    }
}
