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
public class LabQcEventResponseDTO {

    private UUID id;
    private UUID hospitalId;
    private String analyzerId;
    private UUID testDefinitionId;
    private String testDefinitionName;
    private String qcLevel;
    private BigDecimal measuredValue;
    private BigDecimal expectedValue;
    private boolean passed;
    private LocalDateTime recordedAt;
    private UUID recordedById;
    private String notes;
    private LocalDateTime createdAt;
    /** r63 D2304: the control lot, the operator's name and the target the run was judged against. */
    private String lotNumber;
    private String recordedByDisplay;
    private BigDecimal targetMean;
    private BigDecimal targetSd;
    /** WITHIN_2SD, OUTSIDE_2SD, OUTSIDE_3SD; null when no target was set. */
    private String sdFlag;
    /** (measured - target mean) / target SD, null when no target was set. */
    private Double zScore;
}
