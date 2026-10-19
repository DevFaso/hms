package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * DTO representing a single reference range entry for a lab test definition.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestReferenceRangeDTO implements Serializable {

    @DecimalMin(value = "0.0", inclusive = true, message = "{lab.referenceRange.minValue.nonNegative}")
    private Double minValue;

    @DecimalMin(value = "0.0", inclusive = true, message = "{lab.referenceRange.maxValue.nonNegative}")
    private Double maxValue;

    @Size(max = 50, message = "{lab.referenceRange.unit.maxLength}")
    private String unit;

    @Min(value = 0, message = "{lab.referenceRange.ageMin.nonNegative}")
    @Max(value = 150, message = "{lab.referenceRange.ageMin.max}")
    private Integer ageMin;

    @Min(value = 0, message = "{lab.referenceRange.ageMax.nonNegative}")
    @Max(value = 150, message = "{lab.referenceRange.ageMax.max}")
    private Integer ageMax;

    @Pattern(regexp = "ALL|MALE|FEMALE", message = "{lab.referenceRange.gender.invalid}")
    private String gender;

    @Size(max = 500, message = "{lab.referenceRange.notes.maxLength}")
    private String notes;

    /** D107: critical (panic) limits; a value beyond them grades CRITICAL. */
    private Double criticalLow;

    private Double criticalHigh;

    /** QA D287: an inverted range (min above max) was saved and graded every value wrongly. */
    @JsonIgnore
    @AssertTrue(message = "{lab.referenceRange.inverted}")
    public boolean isReferenceRangeOrdered() {
        return minValue == null || maxValue == null || minValue <= maxValue;
    }

    /** QA D287: critical limits must be ordered and lie outside the reference range. */
    @JsonIgnore
    @AssertTrue(message = "{lab.referenceRange.criticalInverted}")
    public boolean isCriticalRangeOrdered() {
        boolean ordered = criticalLow == null || criticalHigh == null || criticalLow <= criticalHigh;
        boolean lowOutside = criticalLow == null || minValue == null || criticalLow <= minValue;
        boolean highOutside = criticalHigh == null || maxValue == null || criticalHigh >= maxValue;
        return ordered && lowOutside && highOutside;
    }

    @JsonIgnore
    @AssertTrue(message = "{lab.referenceRange.ageInverted}")
    public boolean isAgeRangeOrdered() {
        return ageMin == null || ageMax == null || ageMin <= ageMax;
    }
}
