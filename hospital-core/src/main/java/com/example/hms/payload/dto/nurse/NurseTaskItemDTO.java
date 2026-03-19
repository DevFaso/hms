package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row on the nursing task board (MVP 13).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseTaskItemDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String mrn;
    private String category;
    private String description;
    private String priority;
    private String status;
    private LocalDateTime dueAt;
    private boolean overdue;
    private LocalDateTime completedAt;
    private String completedByName;
    private String completionNote;
    private String createdByName;
}
