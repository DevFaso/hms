package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NurseMedicationAdministrationRequestDTO {

    @NotBlank(message = "{nurseMedicationAdministration.status.required}")
    private String status;

    private String note;

    /**
     * When the bedside five-rights check fails but the nurse proceeds anyway,
     * a non-blank reason is required (P1 #8). The eMAR service rejects an
     * administration that proceeds past a failed right without one.
     */
    @Size(max = 1024)
    private String overrideReason;

    /**
     * QA D922 (decision 2026-10-28): the second clinician of the same hospital,
     * not the one giving it, who witnessed a controlled drug being given.
     * Required for a GIVEN dose of a controlled drug, ignored otherwise.
     */
    private java.util.UUID witnessUserId;

    /**
     * QA D1210 (round 23): why a scheduled dose is given before the minimum
     * interval since the last GIVEN dose of the same order has passed
     * ({@code MarDoseInterval}). Required only then; kept on the MAR row as its
     * reason and audited.
     */
    @Size(max = 1024)
    private String earlyDoseReason;
}
