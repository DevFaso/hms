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

    /** The second checker by staff record, or by user: one of the two is required (the service refuses neither). */
    private UUID verifiedByStaffId;

    /** QA CX-008: the portal's picker lists colleagues by user; nobody knows a staff id by heart. */
    private UUID verifiedByUserId;

    @Size(max = 60)
    private String verificationMethod;

    @Size(max = 1000)
    private String notes;
}
