package com.example.hms.payload.dto.mortality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * The mortality register for a period, with the two counts this product is
 * measured on broken out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MortalityRegisterDTO {

    private LocalDate from;
    private LocalDate to;
    private int totalDeaths;
    /** WHO definition — LATE_MATERNAL deaths are excluded and counted separately. */
    private int maternalDeaths;
    private int lateMaternalDeaths;
    /**
     * WHO definition (provisional): stillbirths and deaths on days 0-6, without a known gestation under 22 weeks.
     * LATE_NEONATAL deaths are excluded and counted separately.
     */
    private int perinatalDeaths;
    /** Of {@link #perinatalDeaths}: counted on the clinician's classification, with a fact it rests on missing. */
    private int perinatalEligibilityUnknown;
    /** Classified stillbirth or early neonatal with a known gestation under 22 weeks: not in {@link #perinatalDeaths}. */
    private int perinatalBelowGestation;
    private int stillbirths;
    private int earlyNeonatalDeaths;
    /** Days 7-27: past the WHO perinatal period, reported here and in no perinatal figure. */
    private int lateNeonatalDeaths;
    /** Deaths above under an open dispute: still counted until the outcome. For the coding authority's sign-off. */
    private int disputedDeaths;
    private List<DeathRecordResponseDTO> deaths;
    /** QA D1488: rows marked entered in error: kept and shown, in none of the counts above. */
    private List<DeathRecordResponseDTO> enteredInError;
}
