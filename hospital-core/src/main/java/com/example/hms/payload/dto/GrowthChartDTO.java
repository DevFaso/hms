package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Anthropometric series for one patient — the data behind the growth chart.
 * Points are oldest-first; {@code ageDays} is computed against the patient's
 * date of birth at each measurement so the client never re-derives age.
 *
 * <p>Deliberately carries NO percentile or z-score values: WHO reference
 * curves are clinical reference data that must be imported from a verified
 * source and signed off (the V120 drug-KB precedent), not reproduced from
 * memory. Until then the chart plots the patient's own trajectory only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthChartDTO {

    private UUID patientId;
    private LocalDate dateOfBirth;
    /** Raw patients.gender value (free text, mixed casing in legacy rows). */
    private String gender;
    private List<GrowthPoint> points;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GrowthPoint {
        private LocalDateTime recordedAt;
        private long ageDays;
        private Double weightKg;
        private Double heightCm;
        private Double headCircumferenceCm;
        /** Vitals row source (TRIAGE, NURSE_STATION, …) or DELIVERY for the birth-weight seed. */
        private String source;
    }
}
