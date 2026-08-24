package com.example.hms.payload.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Top diagnoses recorded in one calendar month — the morbidity picture
 * behind the surveillance dashboard.
 *
 * <p><strong>Aggregate-only.</strong> Counts per ICD code, never patient
 * rows: the same stance as the scheduled reports, because this surface
 * is read by administrators who have no clinical relationship with the
 * patients being counted.
 *
 * <p>{@code byHospital} is populated for SUPER_ADMIN only. A hospital
 * admin gets their own facility's ranking in {@code overall} and an
 * EMPTY breakdown — never a partial one — so the response shape cannot
 * leak the existence of other tenants' data.
 */
@Schema(description = "Top diagnoses for one month, optionally split by hospital")
public record MorbidityDashboardDTO(

    @Schema(description = "The month these counts cover, as yyyy-MM", example = "2026-08")
    String month,

    @Schema(description = "NETWORK when the counts span every hospital (SUPER_ADMIN), "
        + "HOSPITAL when they are one facility's own",
        example = "HOSPITAL")
    Scope scope,

    @Schema(description = "Name of the single hospital in scope; null for NETWORK")
    String hospitalName,

    @Schema(description = "Ranked diagnoses for the scope above, highest count first")
    List<DiagnosisSlice> overall,

    @Schema(description = "Per-hospital top diagnoses. Empty unless the caller is SUPER_ADMIN.")
    List<HospitalBreakdown> byHospital
) {

    /** Whether the counts are one facility's or the whole network's. */
    public enum Scope { HOSPITAL, NETWORK }

    /** One ranked diagnosis and how many times it was recorded. */
    @Schema(description = "One diagnosis and its count")
    public record DiagnosisSlice(
        @Schema(description = "ICD code as recorded; null when the diagnosis was free-text",
            example = "B54")
        String code,
        @Schema(description = "Human-readable diagnosis", example = "Malaria, unspecified")
        String display,
        @Schema(description = "Times recorded in the month", example = "412")
        long count
    ) { }

    /** One hospital's own ranking within the network view. */
    @Schema(description = "One hospital's top diagnoses")
    public record HospitalBreakdown(
        UUID hospitalId,
        String hospitalName,
        @Schema(description = "This hospital's ranked diagnoses, highest first")
        List<DiagnosisSlice> top,
        @Schema(description = "All diagnoses recorded at this hospital in the month, "
            + "including those below the per-hospital cut-off", example = "310")
        long totalRecorded
    ) { }
}
