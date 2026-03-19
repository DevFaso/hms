package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for quick bedside care note capture (MVP 13).
 * <p>
 * Supports both DAR and SOAPIE templates; unused fields are ignored.
 */
@Data
public class NurseCareNoteRequestDTO {

    /** DAR | SOAPIE */
    @NotBlank
    private String template;

    /* ── DAR fields ── */
    private String dataPart;     // D — Data
    private String actionPart;   // A — Action
    private String responsePart; // R — Response

    /* ── SOAPIE fields ── */
    private String subjective;
    private String objective;
    private String assessment;
    private String plan;
    private String implementation;
    private String evaluation;

    /** Free-form narrative (both templates). */
    private String narrative;

    /** Optional: short title / summary shown in the note list. */
    private String title;
}
