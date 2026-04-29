package com.example.hms.cdshooks.rules;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.List;
import java.util.UUID;

/**
 * Immutable input handed to every {@link CdsRule}. Carries the proposed
 * medication order and enough patient context for the rules to decide
 * without re-querying the database.
 *
 * <p>Constructed by {@link CdsRuleEngine}; never built directly by a
 * rule. Records are public so Mockito can spy / verify in tests without
 * accessor reflection probes.
 *
 * @param patient                  the patient receiving the proposed order
 *                                 (loaded unscoped — multi-hospital safe)
 * @param hospitalId               hospital scope of the order, used by rules
 *                                 that need to reach back to the catalog
 * @param proposedMedicationName   free-text name from the prescription DTO
 * @param proposedMedicationCode   internal formulary code (may be null)
 * @param proposedRxnormCode       resolved RxNorm code if the medication
 *                                 was matched against the catalog (may be
 *                                 null when the prescription is for a
 *                                 freetext medication)
 * @param proposedDoseMg           parsed numeric dose in mg (may be null
 *                                 when the dose string is non-numeric or
 *                                 uses a different unit)
 * @param proposedCatalogItem      catalog row matched for the proposed
 *                                 medication, used by the pediatric rule
 *                                 to read pediatric_max_dose_mg_per_kg
 *                                 (may be null)
 * @param patientWeightKg          most recent recorded weight in kg, when
 *                                 available (may be null — pediatric rule
 *                                 then degrades to advisory)
 * @param activePrescriptions      the patient's currently-active
 *                                 prescriptions, hospital-scoped, used by
 *                                 the duplicate-order and DDI rules
 * @param activePrescriptionRxnorms RxNorm codes already resolved for the
 *                                 active prescriptions (parallel to
 *                                 activePrescriptions order, with nulls
 *                                 where a prescription could not be
 *                                 mapped to a catalog item)
 */
public record CdsRuleContext(
    Patient patient,
    UUID hospitalId,
    String proposedMedicationName,
    String proposedMedicationCode,
    String proposedRxnormCode,
    Double proposedDoseMg,
    MedicationCatalogItem proposedCatalogItem,
    Double patientWeightKg,
    List<Prescription> activePrescriptions,
    List<String> activePrescriptionRxnorms
) {

    /** Defensive copy + null guards to keep the record fully immutable. */
    public CdsRuleContext {
        activePrescriptions = activePrescriptions == null
            ? List.of() : List.copyOf(activePrescriptions);
        activePrescriptionRxnorms = activePrescriptionRxnorms == null
            ? List.of() : java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(activePrescriptionRxnorms));
    }

    /** Convenience: lower-cased medication name for substring comparisons. */
    public String proposedMedicationNameLower() {
        return proposedMedicationName == null
            ? "" : proposedMedicationName.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
