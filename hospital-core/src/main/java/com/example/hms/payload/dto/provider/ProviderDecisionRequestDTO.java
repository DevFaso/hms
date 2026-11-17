package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code .../reject} and {@code .../revoke}: why. Kept on the verification row, never in an audit description. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Reason for rejecting or revoking a provider verification.")
public class ProviderDecisionRequestDTO {

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
