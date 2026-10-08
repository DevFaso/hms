package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * The professional layer of a provider (provider plan AC-1): the pharmacy's
 * operating licence or the laboratory's ministry authorisation, and the
 * responsible pharmacist or biologist with their Ordre number. Both dates are
 * optional; an expiry date is never required.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Licence or authorisation, and the responsible professional.")
public class ProviderProfessionalDTO {

    @NotBlank
    @Size(max = 100)
    private String licenceNumber;

    @NotBlank
    @Size(max = 200)
    private String licenceAuthority;

    private LocalDate licenceIssuedOn;

    @Schema(description = "Optional, never required.")
    private LocalDate licenceExpiresOn;

    @NotBlank
    @Size(max = 200)
    private String responsibleName;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "The responsible professional's Ordre number.")
    private String responsibleOrdreNumber;
}
