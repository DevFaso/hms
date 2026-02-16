package com.example.hms.payload.dto;

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

    @DecimalMin(value = "0.0", inclusive = true, message = "lab.referenceRange.minValue.nonNegative")
    private Double minValue;

    @DecimalMin(value = "0.0", inclusive = true, message = "lab.referenceRange.maxValue.nonNegative")
    private Double maxValue;

    @Size(max = 50, message = "lab.referenceRange.unit.maxLength")
    private String unit;

    @Min(value = 0, message = "lab.referenceRange.ageMin.nonNegative")
    @Max(value = 150, message = "lab.referenceRange.ageMin.max")
    private Integer ageMin;

    @Min(value = 0, message = "lab.referenceRange.ageMax.nonNegative")
    @Max(value = 150, message = "lab.referenceRange.ageMax.max")
    private Integer ageMax;

    @Pattern(regexp = "ALL|MALE|FEMALE", message = "lab.referenceRange.gender.invalid")
    private String gender;

    @Size(max = 500, message = "lab.referenceRange.notes.maxLength")
    private String notes;
}
