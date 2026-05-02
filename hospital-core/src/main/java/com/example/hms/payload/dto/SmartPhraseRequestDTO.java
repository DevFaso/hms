package com.example.hms.payload.dto;

import com.example.hms.enums.SmartPhraseScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Create / update payload for a SmartPhrase macro.
 *
 * <p>{@code hospitalId} is required when {@code scope=HOSPITAL} and may be set
 * for {@code scope=USER} (helps surface hospital-specialty defaults to staff
 * locked to one hospital). {@code ownerUserId} is required when
 * {@code scope=USER} and is ignored otherwise.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create or update payload for a SmartPhrase / dot-phrase macro.")
public class SmartPhraseRequestDTO {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "\\.[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}",
        message = "trigger must start with '.' and use alphanumerics, dash or underscore")
    @Schema(description = "Trigger token typed in a note section, e.g. '.normexam'.",
            example = ".normexam",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String trigger;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Short label shown in autocomplete.", example = "Normal exam — adult")
    private String title;

    @NotBlank
    @Schema(description = "Multi-line block inserted in place of the trigger.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String expansion;

    @NotNull
    @Schema(description = "Visibility tier — GLOBAL / HOSPITAL / USER.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private SmartPhraseScope scope;

    @Schema(description = "Hospital scope (required when scope=HOSPITAL).")
    private UUID hospitalId;

    @Schema(description = "Owner (required when scope=USER, ignored otherwise).")
    private UUID ownerUserId;

    @Size(max = 64)
    @Schema(description = "Optional specialty tag for filtering autocomplete (e.g. OBGYN).")
    private String specialty;
}
