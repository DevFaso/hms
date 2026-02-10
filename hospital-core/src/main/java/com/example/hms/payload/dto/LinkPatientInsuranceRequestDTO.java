package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

/**
 * Link an existing insurance to a patient, optionally scoping it to a hospital and
 * controlling the primary flag.
 *
 * <ul>
 *   <li><b>patientId</b> – required; the patient to attach to.</li>
 *   <li><b>hospitalId</b> – optional; only staff/admin may set this (enforced in service).</li>
 *   <li><b>primary</b> – true=set as primary (and unset others accordingly),
 *       false=explicitly unset, null=leave as-is.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkPatientInsuranceRequestDTO {

    @NotNull
    @Schema(description = "Patient to attach this insurance to", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @Schema(description = "Hospital context to scope this insurance (staff/admin only)")
    private UUID hospitalId;

    @Schema(description = "Set/unset/leave primary flag (true/false/null)")
    private Boolean primary;

    @Schema(description = "Payer code of the insurance (for natural key upsert)", example = "AETNA")
    private String payerCode;

    @Schema(description = "Policy number of the insurance (for natural key upsert)", example = "POL1234567")
    private String policyNumber;

}
