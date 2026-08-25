package com.example.hms.payload.dto.mortality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What recording the death actually closed.
 *
 * <p>Returned to the caller rather than done silently: a clerk entering a death
 * is entitled to know that three appointments were cancelled and a recall
 * closed on their keystroke, and a silent cascade is one nobody can audit at
 * the moment it happens.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathClosureSummaryDTO {

    private int admissionsClosed;
    private int encountersClosed;
    private int appointmentsCancelled;
    private int recallsClosed;
}
