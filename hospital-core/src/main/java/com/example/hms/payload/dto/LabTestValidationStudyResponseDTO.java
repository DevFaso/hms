package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestValidationStudyResponseDTO {

    private UUID id;

    /** FK — the parent lab test definition. */
    private UUID labTestDefinitionId;
    private String testCode;
    private String testName;

    private String studyType;
    private LocalDate studyDate;

    private UUID performedByUserId;
    private String performedByDisplay;

    private String summary;

    /** JSON blob containing CLSI protocol metrics. */
    private String resultData;

    private boolean passed;
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
