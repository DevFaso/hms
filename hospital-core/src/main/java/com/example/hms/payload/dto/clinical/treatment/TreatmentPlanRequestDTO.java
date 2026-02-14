package com.example.hms.payload.dto.clinical.treatment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private UUID hospitalId;

    private UUID encounterId;

    @NotNull
    private UUID assignmentId;

    @NotNull
    private UUID authorStaffId;

    private UUID supervisingStaffId;

    private UUID signOffStaffId;

    @NotBlank
    @Size(max = 10_000)
    private String problemStatement;

    private List<String> therapeuticGoals;
    private List<String> medicationPlan;
    private List<String> lifestylePlan;
    private List<String> referralPlan;
    private List<String> responsibleParties;

    @Size(max = 4000)
    private String timelineSummary;

    @Size(max = 4000)
    private String followUpSummary;

    private LocalDate timelineStartDate;
    private LocalDate timelineReviewDate;

    private Boolean patientVisibility;

    @Valid
    private List<TreatmentPlanFollowUpRequestDTO> followUps;
}
