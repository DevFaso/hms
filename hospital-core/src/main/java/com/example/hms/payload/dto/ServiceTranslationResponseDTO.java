package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@Schema(description = "DTO for returning a service (treatment) translation.")
public class ServiceTranslationResponseDTO {

    @Schema(description = "Translation ID.", example = "74e6b221-89c3-4f43-bf75-d021f5445f03")
    private UUID id;

    @Schema(description = "Associated treatment ID.", example = "e79ffac1-3402-453e-8b91-84a0be8e2e6f")
    private UUID treatmentId;

    @Schema(description = "Treatment Name", example = "Physiotherapy")
    private String treatmentName;

    @Schema(description = "ISO language code (en, fr, es).", example = "fr")
    private String languageCode;

    @Schema(description = "Localized name for the treatment.", example = "Physiothérapie")
    private String name;

    @Schema(description = "Localized description for the treatment.", example = "Un traitement utilisant des méthodes physiques pour soulager la douleur.")
    private String description;
}

