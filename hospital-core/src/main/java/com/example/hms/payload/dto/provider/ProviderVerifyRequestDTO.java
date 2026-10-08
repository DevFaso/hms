package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /super-admin/providers/{id}/verify} (provider plan AC-2). The
 * super-admin has checked the documents offline. Both confirmations must be
 * {@code true}: the IFU and the CNSS documents name the same business as the
 * RCCM extract. Either false or absent answers 400
 * {@code provider.identity.inconsistent} and nothing changes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Verify a provider: both consistency confirmations, optional corrections, a note.")
public class ProviderVerifyRequestDTO {

    @Schema(description = "The IFU document matches the RCCM extract.")
    private Boolean ifuMatchesRccm;

    @Schema(description = "The CNSS document matches the RCCM extract.")
    private Boolean cnssMatchesRccm;

    @Valid
    @Schema(description = "Corrections to the captured evidence, where the documents differ from it.")
    private Corrections corrections;

    @Size(max = 4000)
    private String evidenceNote;

    /** Replacement blocks; a block left out keeps what was captured. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Corrections {
        @Valid
        private ProviderBusinessIdentityDTO business;

        @Valid
        private ProviderProfessionalDTO professional;
    }
}
