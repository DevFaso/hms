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
}
