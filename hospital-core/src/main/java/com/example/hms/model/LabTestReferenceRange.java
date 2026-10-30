package com.example.hms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Value object persisted as JSON on {@link LabTestDefinition} to capture rich reference range metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestReferenceRange implements Serializable {
    private Double minValue;
    private Double maxValue;
    private String unit;
    private Integer ageMin;
    private Integer ageMax;
    private String gender;
    private String notes;
    /**
     * D107: critical (panic) limits. A value strictly below {@code criticalLow}
     * or strictly above {@code criticalHigh} grades CRITICAL and raises the
     * critical-value alert; one merely outside min/max grades LOW/HIGH.
     */
    private Double criticalLow;
    private Double criticalHigh;
    /**
     * Round 18: the unit {@code ageMin}/{@code ageMax} are counted in:
     * {@code YEARS} (also when null, every range stored before this field),
     * {@code MONTHS} or {@code DAYS}. Both bounds are inclusive and compare
     * against the patient's completed age in that unit on the result date, so
     * neonatal and infant bands (0-7 days, 1-5 months) can be stated.
     */
    private String ageUnit;
    /** Round 18: where the limits come from (a reference, "default pending local approval"). */
    private String source;
}
