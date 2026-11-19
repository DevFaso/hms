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

    // QA D1349 (round 30): the service's required values are declared here too,
    // so they are named at the edge with any malformed value of the body.
    @jakarta.validation.constraints.NotNull(message = "{lab.qc.hospital.required}")
    private UUID hospitalId;
    /** QA D1387 (round 32): within its column, refused by name (not a generic constraint 400). */
    @jakarta.validation.constraints.Size(max = 100)
    private String analyzerId;
    private UUID testDefinitionId;

    /** QC control level: LOW_CONTROL or HIGH_CONTROL. */
    @jakarta.validation.constraints.NotBlank(message = "{lab.qc.levelRequired}")
    private String qcLevel;

    @jakarta.validation.constraints.NotNull(message = "{lab.qc.measuredRequired}")
    private BigDecimal measuredValue;
    /**
     * The control's expected value. r63 D2304: optional when the director set a
     * target for this test and level (the target mean is used); required otherwise.
     */
    private BigDecimal expectedValue;

    /** r63 D2304: the control material's lot number. */
    @jakarta.validation.constraints.Size(max = 100)
    private String lotNumber;

    /**
     * r63 D2304: the operator's decision on the run. True accepts it, false
     * rejects it (results for the test are then held until a passing run or a
     * documented override). Null keeps the legacy +/-10% computation for API
     * callers that predate the decision.
     */
    private Boolean accepted;

    /** Optional timestamp; defaults to now if absent. */
    private LocalDateTime recordedAt;

    /** QA D1387 (round 32): within its column, refused by name (not a generic constraint 400). */
    @jakarta.validation.constraints.Size(max = 2048)
    private String notes;
}
