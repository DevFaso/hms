package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /super-admin/providers/{id}/resubmit}: new evidence for a
 * provider whose last verification was rejected or revoked (provider plan
 * AC-2, "rejection, and re-submission after it, are allowed").
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "New evidence after a rejection or a revocation.")
public class ProviderResubmitRequestDTO {

    @Valid
    @NotNull
    private ProviderBusinessIdentityDTO business;

    @Valid
    @NotNull
    private ProviderProfessionalDTO professional;
}
