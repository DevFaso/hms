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
}
