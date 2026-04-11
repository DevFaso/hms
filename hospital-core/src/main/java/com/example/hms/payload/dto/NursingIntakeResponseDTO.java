package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response after nursing intake submission.")
public class NursingIntakeResponseDTO {

    private UUID encounterId;

    /** Encounter status after intake (unchanged — intake does not transition status). */
    private String encounterStatus;

    /** Timestamp when nursing intake was completed. */
    private LocalDateTime intakeTimestamp;

    /** Number of allergy records created or updated. */
    private int allergyCount;

    /** Number of medication reconciliation entries recorded. */
    private int medicationCount;

    /** Whether a nursing assessment note was recorded on the encounter. */
    private boolean nursingNoteRecorded;
}
