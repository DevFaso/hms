package com.example.hms.payload.dto.transfusion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossmatchResponseDTO {

    private UUID id;
    private UUID requestId;
    private UUID bloodUnitId;
    private String unitNumber;
    private Boolean compatible;
    private String method;
    private String incompatibilityReason;
    private String performedByName;
    private LocalDateTime performedAt;
    private LocalDateTime expiresAt;
    /** Compatible AND unexpired — what the issue path actually requires. */
    private Boolean usable;

    /**
     * True for the one pairing the platelet protocol permits and nobody has
     * confirmed was meant: group O platelets to a group B recipient.
     *
     * <p><b>Advisory only — this does not affect {@code compatible} or
     * {@code usable}, and it must never gain the power to.</b> The sign-off
     * permits the pairing and the software does what the sign-off said. This
     * flag exists so an open clinical question is visible to a blood-bank
     * scientist who can raise it, instead of living only in a javadoc that
     * only developers read. Product-owner decision, 2026-08-26: keep the
     * question open and make it known.
     *
     * <p>Derived, not stored: no column, no migration. It disappears with the
     * question.
     */
    private boolean plateletPairingPendingConfirmation;
}
