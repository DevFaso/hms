package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /super-admin/providers}: a new provider facility and its two
 * layers of evidence (provider plan AC-1). The facility is created SUSPENDED
 * and inactive; only a verification makes it ACTIVE.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create a provider facility (PHARMACY or LABORATORY) with its evidence.")
public class ProviderCreateRequestDTO {

    @NotNull
    @Schema(description = "PHARMACY or LABORATORY; HOSPITAL is refused.")
    private FacilityType facilityType;

    @NotBlank
    @Size(max = 100)
    private String code;

    @Email
    @Size(max = 255)
    private String email;

    @Valid
    @NotNull
    private ProviderBusinessIdentityDTO business;

    @Valid
    @NotNull
    private ProviderProfessionalDTO professional;
}
