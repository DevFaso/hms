package com.example.hms.payload.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * What a generation run actually did.
 *
 * <p>{@code skippedExisting} is reported rather than hidden because generation
 * is idempotent: re-running for an overlapping window is the normal case, and a
 * caller needs to be able to tell "nothing to do" from "nothing happened".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlotGenerationResultDTO {

    private LocalDate from;
    private LocalDate to;
    private int templatesApplied;
    private int slotsCreated;
    private int skippedExisting;
}
