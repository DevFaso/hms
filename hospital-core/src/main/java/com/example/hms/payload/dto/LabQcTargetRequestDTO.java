package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** r63 D2304: the lab director's target mean and SD for one test and control level. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabQcTargetRequestDTO {

    @jakarta.validation.constraints.NotNull(message = "{lab.qc.target.testRequired}")
    private UUID testDefinitionId;

    @jakarta.validation.constraints.NotBlank(message = "{lab.qc.levelRequired}")
    @jakarta.validation.constraints.Pattern(regexp = "(?i)\\s*(LOW_CONTROL|HIGH_CONTROL)\\s*",
        message = "{lab.qc.levelInvalid}")
    private String qcLevel;

    @jakarta.validation.constraints.NotNull(message = "{lab.qc.target.meanRequired}")
    private BigDecimal targetMean;

    @jakarta.validation.constraints.NotNull(message = "{lab.qc.target.sdRequired}")
    @jakarta.validation.constraints.Positive(message = "{lab.qc.target.sdPositive}")
    private BigDecimal targetSd;

    @jakarta.validation.constraints.Size(max = 100)
    private String lotNumber;
}
