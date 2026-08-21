package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The clinician repeating a critical value back to the system.
 *
 * <p>The point is that {@code repeatedValue} is typed from what the clinician
 * heard or read, not copied from the screen — the check only catches a
 * transcription error if the two are independent. The API cannot enforce that,
 * but the UI prompt can, and the stored value makes a mismatch auditable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Read-back of a critical lab value by the receiving clinician")
public class CriticalValueReadBackRequestDTO {

    @NotBlank(message = "The repeated value is required.")
    @Size(max = 255)
    @Schema(description = "The result value as repeated back by the clinician", example = "6.8")
    private String repeatedValue;
}
