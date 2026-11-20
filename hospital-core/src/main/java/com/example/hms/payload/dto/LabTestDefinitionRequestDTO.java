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

    /**
     * LOINC code identifying this test in FHIR {@code Observation.code}.
     * Format ({@code n{1,7}-d}) is intentionally enforced by the service
     * layer rather than {@code @Pattern} here so that the resulting 400
     * response carries the human-readable hint defined in
     * {@code TerminologyCodes#normalizeAndRequireValidLoinc} — both paths
     * land on {@code GlobalExceptionHandler} and produce 400 Bad Request,
     * but the service-layer message is more actionable.
     */
    @Size(max = 20)
    private String loincCode;

    @Size(max = 255)
    private String loincDisplay;

    @Size(max = 100)
    private String sampleType;

    @Size(max = 1000)
    private String preparationInstructions;

    /**
     * QA D834: the expected (normal) answer of a qualitative test, such as
     * "Négatif" for a malaria rapid test. A qualitative test has no numeric
     * range; this is what it is reviewed, graded and shown against.
     */
    @Size(max = 255)
    private String expectedResult;

    /**
     * QA D2730: the sensitive category every result of this test carries
     * (HIV, REPRODUCTIVE_HEALTH, ...). Left out, an update keeps the current
     * tag; null on a new test means none.
     */
    private com.example.hms.enums.SensitivityCategory sensitivityCategory;

    /**
     * QA D2730: true removes the test's sensitive category. A separate flag
     * because a body that merely leaves {@code sensitivityCategory} out (an
     * older client) must keep the tag, never clear it.
     */
    private Boolean clearSensitivityCategory;

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

    /**
     * r63 (NFS panels): the analytes of a panel test. Null leaves an existing
     * definition's components as they are; an empty list makes it a
     * single-value test.
     */
    @Valid
    @jakarta.validation.constraints.Size(max = 60)
    private List<LabTestComponentDTO> components;
}

