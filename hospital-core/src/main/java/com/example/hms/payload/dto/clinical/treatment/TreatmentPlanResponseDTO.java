package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private String hospitalName;
    private UUID encounterId;
    private UUID assignmentId;
    private UUID authorStaffId;
    private String authorStaffName;
    private UUID supervisingStaffId;
    private String supervisingStaffName;
    private UUID signOffStaffId;
    private String signOffStaffName;
    private TreatmentPlanStatus status;
    private String problemStatement;
    private List<String> therapeuticGoals;
    private List<String> medicationPlan;
    private List<String> lifestylePlan;
    private List<String> referralPlan;
    private List<String> responsibleParties;
    private String timelineSummary;
    private String followUpSummary;
    private LocalDate timelineStartDate;
    private LocalDate timelineReviewDate;
    private Boolean patientVisibility;
    private LocalDateTime patientVisibilityAt;
    private LocalDateTime signOffAt;
    private Long version;
    private List<TreatmentPlanFollowUpDTO> followUps;
    private List<TreatmentPlanReviewDTO> reviews;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
