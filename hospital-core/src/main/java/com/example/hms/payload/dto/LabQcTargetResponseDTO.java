package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** r63 D2304: a QC target as the dashboard shows it, with who approved it and when. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabQcTargetResponseDTO {
    private UUID id;
    private UUID hospitalId;
    private UUID testDefinitionId;
    private String testDefinitionName;
    private String qcLevel;
    private BigDecimal targetMean;
    private BigDecimal targetSd;
    private String lotNumber;
    private String approvedByDisplay;
    private LocalDateTime approvedAt;
}
