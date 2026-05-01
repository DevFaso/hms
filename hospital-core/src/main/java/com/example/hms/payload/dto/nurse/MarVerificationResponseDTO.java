package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result returned by the eMAR verify endpoint. The UI uses {@code outcomes}
 * to render the green/red checklist; {@code allPassed} is the single boolean
 * the nurse acts on (proceed straight to GIVEN, or be prompted for an
 * override reason). {@code failedChecks} + {@code failureReasons} carry the
 * detail needed for the override prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarVerificationResponseDTO {

    private UUID marId;

    /** Per-right pass/fail map keyed by {@code FiveRightsCheck} name. */
    private Map<String, Boolean> outcomes;

    /** Names of the rights that failed (subset of {@code FiveRightsCheck}). */
    private List<String> failedChecks;

    /** Human-readable reason per failed right, keyed by {@code FiveRightsCheck} name. */
    private Map<String, String> failureReasons;

    /** True only when every right passed. */
    private boolean allPassed;

    /** Server time when verification ran (also persisted on the MAR). */
    private LocalDateTime verifiedAt;
}
