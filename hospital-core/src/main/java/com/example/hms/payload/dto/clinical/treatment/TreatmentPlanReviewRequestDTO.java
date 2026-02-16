package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanReviewRequestDTO {

    @NotNull
    private UUID reviewerStaffId;

    @NotNull
    private TreatmentPlanReviewAction action;

    @Size(max = 2000)
    private String comment;
}
