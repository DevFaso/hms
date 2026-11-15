package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Lightweight projection of lab test reference range values for lab result payloads. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultReferenceRangeDTO {
    private Double minValue;
    private Double maxValue;
    private String unit;
    private Integer ageMin;
    private Integer ageMax;
    private String gender;
    private String notes;
    private Double criticalLow;
    private Double criticalHigh;
    /** Round 18: YEARS (also when null), MONTHS or DAYS. */
    private String ageUnit;
    /** Round 18: where the limits come from. */
    private String source;
    /**
     * Whether the result's patient is inside this range's age band on the
     * result date: TRUE inside (or a range with no band), FALSE outside, null
     * when the patient's age is unknown or the range was not read against a
     * patient. The detail screen hides a band the patient is outside of when
     * it states no interval at all.
     */
    private Boolean patientInAgeBand;
}
