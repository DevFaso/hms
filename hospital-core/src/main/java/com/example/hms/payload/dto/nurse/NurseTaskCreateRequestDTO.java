package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request body for nurse task creation (MVP 13).
 */
@Data
public class NurseTaskCreateRequestDTO {

    @NotNull
    private UUID patientId;

    /** DRESSING_CHANGE | IV_CHECK | CATHETER_CARE | PAIN_REASSESSMENT | MOBILITY_ASSIST | INTAKE_OUTPUT | WOUND_CARE | OTHER */
    @NotBlank
    private String category;

    private String description;

    /** ROUTINE | URGENT | STAT */
    // QA D1373 class (round 31): one of ClinicalPriority, refused by name otherwise.
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.ClinicalPriority.CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE, message = "{validation.priority.invalid}")
    private String priority = "ROUTINE";

    private LocalDateTime dueAt;
}
