package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * DTO capturing a discrete intervention or action recorded in a note.
 */
@Value
@Builder
public class NursingNoteInterventionDTO {

    @NotBlank
    @Size(max = 400)
    String description;

    UUID linkedOrderId;

    UUID linkedMedicationTaskId;

    @Size(max = 400)
    String followUpActions;
}
