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
    private String sampleType;
    private String preparationInstructions;
    private Integer turnaroundTime;

    @JsonProperty("isActive")
    private boolean active;

    @Default
    private List<LabTestReferenceRangeDTO> referenceRanges = Collections.emptyList();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

