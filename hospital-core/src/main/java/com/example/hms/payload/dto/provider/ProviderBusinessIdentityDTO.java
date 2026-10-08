package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * The legal identity of a provider business, as its registration documents
 * state it (provider plan AC-1). The RCCM extract is authoritative; the IFU
 * and CNSS documents must agree with it before the facility is verified.
 * Business identifiers, not PHI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Legal identity of the business (RCCM authoritative; IFU and CNSS must agree).")
public class ProviderBusinessIdentityDTO {

    @NotBlank
    @Size(max = 255)
    private String legalName;

    @Size(max = 255)
    private String tradeName;

    @NotBlank
    @Size(max = 50)
    @Schema(example = "SARL")
    private String legalStructure;

    @NotBlank
    @Size(max = 50)
    private String rccmNumber;

    @NotBlank
    @Size(max = 30)
    private String ifuNumber;

    @NotBlank
    @Size(max = 30)
    private String cnssNumber;

    @Valid
    @NotNull
    private ProviderAddressDTO address;

    @NotBlank
    @Size(max = 30)
    private String companyPhone;

    @NotBlank
    @Size(max = 200)
    private String managerName;

    @NotBlank
    @Size(max = 100)
    private String managerTitle;

    @NotNull
    @PastOrPresent
    private LocalDate startedOn;
}
