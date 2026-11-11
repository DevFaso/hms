package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One closed dose of a MAR row, as the nurse reads it back (QA D1225, round
 * 24): what happened, when, by whom and WHY. The reasons the administer call
 * requires (hold, refusal, early dose, five-rights override) were stored on
 * the MAR row and shown on no screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarDoseHistoryDTO {

    private UUID id;
    /** GIVEN, HELD, REFUSED or MISSED. */
    private String status;
    private LocalDateTime scheduledTime;
    /** When the outcome was recorded (the dose given, held or refused). */
    private LocalDateTime recordedAt;
    /**
     * The reason the nurse gave: why the dose was held or refused, or, on a
     * GIVEN dose, why it was given before the minimum interval (QA D1210).
     */
    private String reason;
    /** True for a GIVEN dose recorded with an early-dose reason (QA D1210). */
    private boolean givenEarly;
    /** Why a failed five-rights check was overridden at the bedside. */
    private String overrideReason;
    private String administeredBy;
    /** QA D922: the clinician who witnessed a controlled dose. */
    private String witnessName;
}
