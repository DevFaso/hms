package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Aggregated QC statistics per test definition, used by the Lab Director QC Dashboard.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabQcSummaryDTO {

    private UUID testDefinitionId;
    private String testName;
    private long totalEvents;
    private long passedEvents;
    private long failedEvents;
    private double passRate;
    private LocalDateTime lastEventDate;
}
