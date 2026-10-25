package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestDefinitionResponseDTO {

    private UUID id;

    private String testCode;

    @JsonProperty("testName")
    private String name;

    private String description;
    private String category;
    private String unit;
    private String loincCode;
    private String loincDisplay;
    private String sampleType;
    private String preparationInstructions;
    private Integer turnaroundTime;

    @JsonProperty("isActive")
    private boolean active;

    private String approvalStatus;
    private UUID approvedById;
    private LocalDateTime approvedAt;
    /** QA D534: who submitted it, so the queue does not offer the QA pass to them. */
    private UUID submittedById;

    private UUID reviewedById;

    /**
     * QA D722: set only for the caller who did the QA review of a definition
     * pending approval - true when the server will accept their approval too
     * (the sole-director exception). Null otherwise.
     */
    private Boolean reviewerMayApprove;
    private LocalDateTime reviewedAt;
    private String rejectionReason;

    @Default
    private List<LabTestReferenceRangeDTO> referenceRanges = Collections.emptyList();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

