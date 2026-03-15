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

    @NotNull(message = "New consultant ID is required")
    private UUID consultantId;

    @NotBlank(message = "Reassignment reason is required")
    @Size(max = 500, message = "Reassignment reason must not exceed 500 characters")
    private String reassignmentReason;
}
