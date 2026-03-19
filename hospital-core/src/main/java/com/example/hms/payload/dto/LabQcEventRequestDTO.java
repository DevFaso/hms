package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabQcEventRequestDTO {

    private UUID hospitalId;
    private String analyzerId;
    private UUID testDefinitionId;

    /** QC control level: LOW_CONTROL or HIGH_CONTROL. */
    private String qcLevel;

    private BigDecimal measuredValue;
    private BigDecimal expectedValue;

    /** Optional timestamp; defaults to now if absent. */
    private LocalDateTime recordedAt;

    private String notes;
}
