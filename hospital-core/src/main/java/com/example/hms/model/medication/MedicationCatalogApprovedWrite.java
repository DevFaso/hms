package com.example.hms.model.medication;

import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.Hospital;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Go-live D2 (V385): the ONE place that writes the approved content of a medication catalogue entry.
 *
 * <p>No service, mapper or importer sets a field marked {@link ApprovedContent} on a
 * {@link MedicationCatalogItem}: this class does, and it is called by the approval step alone
 * ({@code MedicationCatalogVersionService#approve}), with a version a second person has just approved.
 * {@code MedicationCatalogApprovedWriteGuardTest} reads the compiled classes and holds both halves.
 */
public final class MedicationCatalogApprovedWrite {

    private MedicationCatalogApprovedWrite() {
    }

    /** A new entry, from the approved version that proposed it. It is version {@code versionNumber} from birth. */
    public static MedicationCatalogItem newEntry(FormularySnapshot approved, Hospital hospital, int versionNumber) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setHospital(hospital);
        item.setCategory(approved.category());
        item.setEssentialList(approved.essentialList());
        item.setDescription(approved.description());
        item.setSensitivityCategory(approved.sensitivityCategory());
        formulary(item, approved, versionNumber);
        return item;
    }

    /** The approved formulary content of an entry already in the catalogue. */
    public static void formulary(MedicationCatalogItem item, FormularySnapshot approved, int versionNumber) {
        item.setNameFr(approved.nameFr());
        item.setGenericName(approved.genericName());
        item.setBrandName(approved.brandName());
        item.setAtcCode(approved.atcCode());
        item.setForm(approved.form());
        item.setStrength(approved.strength());
        item.setStrengthUnit(approved.strengthUnit());
        item.setRxnormCode(approved.rxnormCode());
        item.setTallManName(approved.tallManName());
        item.setRoute(approved.route());
        item.setControlled(approved.controlled());
        item.setActive(approved.active());
        item.setFormularyApprovedVersion(versionNumber);
    }

    /** The approved dose limits of an entry, with who approved them and when. */
    public static void doseLimits(MedicationCatalogItem item, DoseLimitSnapshot approved, int versionNumber,
                                  UUID approvedBy, LocalDateTime approvedAt) {
        item.setPediatricMaxDoseMgPerKg(approved.maxDoseMgPerKg());
        item.setPediatricMaxDailyMgPerKg(approved.maxDailyMgPerKg());
        item.setPediatricMaxSingleDoseMg(approved.maxSingleDoseMg());
        item.setPediatricMaxDailyMg(approved.maxDailyMg());
        item.setPediatricManualReviewUnderMonths(approved.manualReviewUnderMonths());
        item.setPediatricWeightBands(new ArrayList<>(approved.weightBands()));
        item.setPediatricDoseSource(approved.source());
        item.setPediatricDoseStatus(PediatricDoseLimitStatus.APPROVED);
        item.setPediatricDoseReviewedBy(approvedBy);
        item.setPediatricDoseReviewedAt(approvedAt);
        item.setDoseLimitApprovedVersion(versionNumber);
    }
}
