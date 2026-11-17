package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The registered address of a provider business: secteur, section, lot, parcelle, city, region. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Registered address of the business.")
public class ProviderAddressDTO {

    @Size(max = 50)
    private String secteur;

    @Size(max = 50)
    private String section;

    @Size(max = 50)
    private String lot;

    @Size(max = 50)
    private String parcelle;

    @NotBlank
    @Size(max = 100)
    private String city;

    @NotBlank
    @Size(max = 100)
    private String region;
}
