package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanReviewAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanReviewDTO {
    private UUID id;
    private UUID reviewerStaffId;
    private String reviewerName;
    private TreatmentPlanReviewAction action;
    private String comment;
    private LocalDateTime createdAt;
}
