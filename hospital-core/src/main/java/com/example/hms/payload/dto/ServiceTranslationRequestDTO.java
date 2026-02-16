package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "DTO for creating or updating a service (treatment) translation.")
public class ServiceTranslationRequestDTO {

    @NotNull
    @Schema(description = "Treatment ID this translation belongs to.", example = "e79ffac1-3402-453e-8b91-84a0be8e2e6f", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID treatmentId;

    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;

    @NotBlank
    @Schema(description = "ISO language code (en, fr, es).", example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    private String languageCode;

    @NotBlank
    @Schema(description = "Translated treatment name.", example = "Physiotherapy")
    private String name;

    @Schema(description = "Translated treatment description.", example = "A treatment that uses physical methods to relieve pain.")
    private String description;
}
