package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationUpdateDTO {

    @NotNull(message = "Consultant ID is required")
    private UUID consultantId;

    private LocalDateTime scheduledAt;

    private String consultantNote;

    private String recommendations;

    private Boolean followUpRequired;

    private String followUpInstructions;
}
