package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Save / update payload for an audit saved-search (MVP-c batch — MVP-8c).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Save / update an audit-search saved query.")
public class AuditSavedSearchRequestDTO {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name (unique per owner).", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank
    @Schema(description = "Opaque AuditSearchFilter JSON payload.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String filterJson;

    @Schema(description = "Mark the search visible to all super admins.")
    private boolean shared;
}
