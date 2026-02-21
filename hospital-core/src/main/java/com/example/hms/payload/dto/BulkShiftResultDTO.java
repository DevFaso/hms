package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response returned by the bulk-scheduling endpoint.
 *
 * <p>Even when some dates are skipped, the HTTP status remains 200 and the
 * caller can inspect {@link #skipped} to understand which dates were omitted
 * and why. A completely empty {@link #scheduled} list with a non-empty
 * {@link #skipped} list means no shifts could be created.
 */
public record BulkShiftResultDTO(

    @Schema(description = "Shifts that were successfully created")
    List<StaffShiftResponseDTO> scheduled,

    @Schema(description = "Dates that were skipped and the reason for each skip")
    List<BulkShiftSkipDTO> skipped,

    @Schema(description = "Total number of shifts created", example = "5")
    int totalScheduled,

    @Schema(description = "Total number of dates skipped", example = "1")
    int totalSkipped
) { }
