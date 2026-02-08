package com.example.hms.payload.dto.reference;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SchedulePublishRequestDTO {

    @Schema(description = "Timestamp when the catalog should be published", example = "2025-10-06T15:30:00")
    @NotNull(message = "publishAt is required")
    @FutureOrPresent(message = "publishAt must be in the future or present")
    private LocalDateTime publishAt;

    @Schema(description = "Optional notes for the publish action")
    @Size(max = 2000, message = "Notes must be 2000 characters or less")
    private String notes;
}
