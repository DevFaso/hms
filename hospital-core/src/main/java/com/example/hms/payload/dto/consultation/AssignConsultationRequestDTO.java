package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AssignConsultationRequestDTO {

    @NotNull(message = "Consultant ID is required")
    private UUID consultantId;

    private String assignmentNote;
}
