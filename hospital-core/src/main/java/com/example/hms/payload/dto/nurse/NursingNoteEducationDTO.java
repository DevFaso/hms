package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

/**
 * DTO describing a patient education entry documented within a nursing note.
 */
@Value
@Builder
public class NursingNoteEducationDTO {

    @NotBlank
    @Size(max = 150)
    String topic;

    @Size(max = 120)
    String teachingMethod;

    @NotBlank
    @Size(max = 400)
    String patientUnderstanding;

    @Size(max = 400)
    String reinforcementActions;

    String educationSummary;
}
