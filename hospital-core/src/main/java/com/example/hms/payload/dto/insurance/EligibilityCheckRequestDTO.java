package com.example.hms.payload.dto.insurance;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request body for {@code POST /eligibility/check} and {@code POST /eligibility/prior-auth}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Real-time eligibility / prior-auth submission against a public payer.")
public class EligibilityCheckRequestDTO {

    @NotNull
    @Schema(description = "Patient whose coverage will be verified.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotNull
    @Schema(description = "Hospital running the check (used for tenant scoping and audit).",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @Schema(description = "Optional link to the PatientInsurance row this check is associated with.")
    private UUID patientInsuranceId;

    @NotNull
    @Schema(description = "Public-payer scheme (NHIS, CNAMGS, mutuelle, …).",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private EligibilityScheme scheme;

    @NotNull
    @Schema(description = "Coverage / member-status check or prior-auth request.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private EligibilityCheckType checkType;

    @Size(max = 64)
    @Schema(description = "Member id printed on the payer card. Required for COVERAGE; required for PRIOR_AUTH "
                       + "unless the call is expected to short-circuit on a prior cached COVERAGE result.",
            example = "NHIS-1234567")
    private String memberId;

    @Size(max = 32)
    @Schema(description = "CPT-equivalent / local procedure code (only required for PRIOR_AUTH).",
            example = "CT-HEAD-WO-CONTRAST")
    private String serviceCode;
}
