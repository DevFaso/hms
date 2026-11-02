package com.example.hms.model.medication;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Round 18 (QA D935): one weight band of a drug dosed by weight band rather
 * than by mg/kg (artemether-lumefantrine 20/120: 5 to under 15 kg one tablet,
 * and so on). Stored as JSON on the catalogue item, like a lab test's
 * reference ranges.
 *
 * <p>{@code minWeightKg} is inclusive, {@code maxWeightKg} exclusive (null:
 * no upper bound). The dose is a count of {@code doseUnit} (tablets), given at
 * most {@code maxDosesPerDay} times a day.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PediatricWeightBand implements Serializable {
    private BigDecimal minWeightKg;
    private BigDecimal maxWeightKg;
    private BigDecimal dosePerAdministration;
    private String doseUnit;
    private Integer maxDosesPerDay;

    /** Whether a child of {@code weightKg} falls in this band. */
    public boolean contains(double weightKg) {
        boolean aboveMin = minWeightKg == null || weightKg >= minWeightKg.doubleValue();
        boolean belowMax = maxWeightKg == null || weightKg < maxWeightKg.doubleValue();
        return aboveMin && belowMax;
    }
}
