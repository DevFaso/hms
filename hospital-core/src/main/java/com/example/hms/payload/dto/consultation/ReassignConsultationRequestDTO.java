package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReassignConsultationRequestDTO {

    @NotNull(message = "{reassignConsultation.consultantId.required}")
    private UUID consultantId;

    @NotBlank(message = "{reassignConsultation.reassignmentReason.required}")
    @Size(max = 500, message = "{reassignConsultation.reassignmentReason.size}")
    private String reassignmentReason;
}
