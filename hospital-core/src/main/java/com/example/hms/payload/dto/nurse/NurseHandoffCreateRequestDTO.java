package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Payload for recording a new SBAR handoff from the nurse station. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseHandoffCreateRequestDTO {

    /** The handoff types, as the portal's select sends them. */
    public static final java.util.Set<String> DIRECTIONS =
        java.util.Set.of("SHIFT_CHANGE", "WARD_TRANSFER", "PROCEDURE", "BREAK_COVER");

    /** {@link #DIRECTIONS} as the edge pattern (possessive: no backtracking). */
    static final String DIRECTION_PATTERN = "\\s*+(?:SHIFT_CHANGE|WARD_TRANSFER|PROCEDURE|BREAK_COVER)\\s*+";

    @NotNull
    private UUID patientId;

    /**
     * The handoff type, one of {@link #DIRECTIONS} (any case, spaces around
     * ignored; the service stores it upper case). QA D1371 (round 31): checked
     * here, at the edge, so an unknown type is named with the other refusals
     * instead of one request after them.
     */
    @NotBlank
    @Size(max = 200)
    @Pattern(regexp = DIRECTION_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE,
        message = "{nurse.handoff.direction.invalid}")
    private String direction;

    /** QA D1331 (round 29): required (QA D331), declared here so {} names it with the others. */
    @jakarta.validation.constraints.NotBlank(message = "{nurse.handoff.situation.required}")
    @Size(max = 4000)
    private String situation;

    @Size(max = 4000)
    private String background;

    @Size(max = 4000)
    private String assessment;

    @Size(max = 4000)
    private String recommendation;

    /** QA D776: the colleague (nurse, midwife or doctor at this hospital) the patient is handed to. */
    @NotNull(message = "{nurse.handoff.recipient.required}")
    private UUID recipientUserId;
}
