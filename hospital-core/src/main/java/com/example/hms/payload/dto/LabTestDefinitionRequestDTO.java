package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LabTestDefinitionRequestDTO {

    private UUID id;

    @NotBlank
    @Size(max = 50)
    private String testCode;

    @JsonProperty("testName")
    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 100)
    private String category;

    @Size(max = 2048)
    private String description;

    @Size(max = 50)
    private String unit;

    @Size(max = 100)
    private String sampleType;

    @Size(max = 1000)
    private String preparationInstructions;

    @Min(1)
    @Max(10080)
    private Integer turnaroundTime;

    @JsonProperty("isActive")
    private Boolean active;

    @JsonProperty("assignmentId")
    private UUID assignmentId;

    @Valid
    @Builder.Default
    private List<LabTestReferenceRangeDTO> referenceRanges = new ArrayList<>();
}

