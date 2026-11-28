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
 *
 * <p>QA D4141: naming the verifier is not the check. The verifier is of the bedside team of the
 * hospital and confirms with their own factor ({@link #verifierSecret}).
 */
@com.example.hms.payload.dto.OneOf(fields = {"verifiedByStaffId", "verifiedByUserId"},
    message = "{transfusion.administration.verifierRequired}")
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

    /**
     * QA D4141: typed by the second checker themselves: the current code of their authenticator (or a backup
     * code) when they have one, else their password. Checked on this call and dropped: never stored, never
     * logged ({@code toString} leaves it out).
     */
    @lombok.ToString.Exclude
    @Size(max = 256)
    private String verifierSecret;

    @Size(max = 60)
    private String verificationMethod;

    @Size(max = 1000)
    private String notes;
}
