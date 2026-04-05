package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregated validation study statistics per test definition,
 * used by the Lab Director QC Dashboard.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabValidationSummaryDTO {

    private UUID testDefinitionId;
    private String testName;
    private String testCode;
    private long totalStudies;
    private long passedStudies;
    private long failedStudies;
    private double passRate;
    private LocalDate lastStudyDate;
}
