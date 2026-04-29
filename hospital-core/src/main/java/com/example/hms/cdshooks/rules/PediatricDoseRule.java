package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

/**
 * Pediatric dose advisory. Behaviour by data availability:
 *
 * <ol>
 *   <li><strong>Hard ceiling:</strong> when the catalog row carries
 *       {@code pediatric_max_dose_mg_per_kg}, the proposed dose has a
 *       parsed mg value, and the patient weight is known, we compute
 *       mg/kg and emit {@code critical} when it exceeds the ceiling.</li>
 *   <li><strong>Soft advisory:</strong> when the patient is &lt; 18
 *       and no ceiling is on file (or weight / parsed dose is missing)
 *       we emit a {@code warning} card prompting the clinician to
 *       verify weight-based dosing.</li>
 *   <li><strong>Silent:</strong> patient is an adult — no card.</li>
 * </ol>
 *
 * <p>Why the soft advisory exists: most West-African catalog rows do
 * not yet carry pediatric ceilings, but the safety prompt is still
 * worth surfacing for paediatric patients. As the catalog grows the
 * hard ceiling fires and the soft advisory naturally fades.
 */
@Component
public class PediatricDoseRule implements CdsRule {

    private static final String ID = "pediatric-dose";
    private static final String SOURCE_LABEL = "HMS Pediatric Dose Check";
    private static final int PEDIATRIC_AGE_THRESHOLD_YEARS = 18;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<CdsCard> evaluate(CdsRuleContext context) {
        if (context.patient() == null) return List.of();
        Integer ageYears = ageInYears(context.patient().getDateOfBirth());
        if (ageYears == null || ageYears >= PEDIATRIC_AGE_THRESHOLD_YEARS) return List.of();

        BigDecimal ceiling = ceilingFromCatalog(context.proposedCatalogItem());
        Double doseMg = context.proposedDoseMg();
        Double weightKg = context.patientWeightKg();

        if (ceiling != null && doseMg != null && weightKg != null && weightKg > 0d) {
            double mgPerKg = doseMg / weightKg;
            if (mgPerKg > ceiling.doubleValue()) {
                return List.of(buildExceedsCeilingCard(context, ageYears, mgPerKg, ceiling));
            }
            // dose within the ceiling — suppress the soft advisory: data is sufficient
            return List.of();
        }

        return List.of(buildSoftAdvisoryCard(context, ageYears));
    }

    private static Integer ageInYears(LocalDate dob) {
        if (dob == null) return null;
        return Period.between(dob, LocalDate.now()).getYears();
    }

    private static BigDecimal ceilingFromCatalog(MedicationCatalogItem item) {
        return item == null ? null : item.getPediatricMaxDoseMgPerKg();
    }

    private CdsCard buildExceedsCeilingCard(CdsRuleContext context, int ageYears,
                                            double mgPerKg, BigDecimal ceiling) {
        String summary = String.format(
            java.util.Locale.ROOT,
            "Pediatric dose exceeds ceiling: %.2f mg/kg > %s mg/kg max for %s",
            mgPerKg, ceiling.toPlainString(), context.proposedMedicationName());
        String detail = "Patient is " + ageYears + " years old. Proposed dose ("
            + format(context.proposedDoseMg()) + " mg at "
            + format(context.patientWeightKg())
            + " kg) exceeds the catalog pediatric ceiling. Reduce the dose or"
            + " set forceOverride after weighing risk/benefit.";
        return new CdsCard(
            summary, detail, CdsCard.Indicator.CRITICAL,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, UUID.randomUUID().toString()
        );
    }

    private CdsCard buildSoftAdvisoryCard(CdsRuleContext context, int ageYears) {
        String summary = "Pediatric patient: verify weight-based dosing for "
            + context.proposedMedicationName();
        String detail = "Patient is " + ageYears + " years old"
            + (context.patientWeightKg() == null
                ? " (weight not on file). "
                : " (weight " + format(context.patientWeightKg()) + " kg). ")
            + "Confirm the prescribed dose against a pediatric reference"
            + " before signing.";
        return new CdsCard(
            summary, detail, CdsCard.Indicator.WARNING,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, UUID.randomUUID().toString()
        );
    }

    private static String format(Double v) {
        return v == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
