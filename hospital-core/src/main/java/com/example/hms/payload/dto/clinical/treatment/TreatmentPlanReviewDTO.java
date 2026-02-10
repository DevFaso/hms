package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanReviewAction;
import lombok.*;

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
