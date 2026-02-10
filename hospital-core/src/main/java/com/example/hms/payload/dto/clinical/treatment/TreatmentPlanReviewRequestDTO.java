package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

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
