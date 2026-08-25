package com.example.hms.payload.dto.transfusion;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Hanging a unit at the bedside.
 *
 * <p>The administering staff member is the authenticated caller. The VERIFIER
 * is named explicitly because a transfusion requires an independent second
 * bedside check, and the service refuses the two being the same person.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransfusionAdministrationRequestDTO {

    @NotNull
    private UUID requestId;

    @NotNull
    private UUID bloodUnitId;

    @NotNull
    private UUID verifiedByStaffId;

    @Size(max = 60)
    private String verificationMethod;

    @Size(max = 1000)
    private String notes;
}
