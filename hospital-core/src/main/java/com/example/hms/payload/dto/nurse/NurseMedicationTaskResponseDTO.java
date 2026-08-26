package com.example.hms.payload.dto.nurse;

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
public class NurseMedicationTaskResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String medication;
    private String dose;
    private String route;
    private LocalDateTime dueTime;
    private String status;

    /* ── Pharmacist verification (Tier 2 item 33) ───────────────────────── */
    //
    // Both flags are on the task, not derived in the UI, so the nurse sees
    // WHY a dose is blocked before pressing the button rather than after.

    /** True when this medication cannot be given until a pharmacist verifies it. */
    private boolean requiresPharmacistVerification;

    /** True once verified. False plus the flag above means administration is refused. */
    private boolean pharmacistVerified;
}
