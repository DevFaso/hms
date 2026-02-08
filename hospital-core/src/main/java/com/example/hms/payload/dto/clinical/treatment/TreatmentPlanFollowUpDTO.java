package com.example.hms.payload.dto.clinical.treatment;

import com.example.hms.enums.TreatmentPlanTaskStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanFollowUpDTO {
    private UUID id;
    private String label;
    private String instructions;
    private LocalDate dueOn;
    private UUID assignedStaffId;
    private String assignedStaffName;
    private TreatmentPlanTaskStatus status;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
