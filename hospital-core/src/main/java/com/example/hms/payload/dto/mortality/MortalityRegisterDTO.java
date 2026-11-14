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
    /** QA D1714: the death records that are not stillbirths (deaths after a live birth, maternal deaths...). */
    private int deathsAfterLiveBirth;
    /** WHO definition — LATE_MATERNAL deaths are excluded and counted separately. */
    private int maternalDeaths;
    private int lateMaternalDeaths;
    /**
     * QA D1714 (provisional, StillbirthClassification.Statistic.PERINATAL): the WHO stillbirths
     * ({@link #stillbirthsWho}) plus the early neonatal deaths on days 0-6 (death records, without a known gestation
     * under the ICD level). LATE_NEONATAL deaths are excluded and counted separately. A stillbirth with no gestation
     * is in {@link #stillbirthsClassificationIncomplete}, not here.
     */
    private int perinatalDeaths;
    /** Of {@link #perinatalDeaths}: early neonatal deaths counted on the clinician's classification, a fact missing. */
    private int perinatalEligibilityUnknown;
    /** Early neonatal deaths with a known gestation under the ICD level: not in {@link #perinatalDeaths}. */
    private int perinatalBelowGestation;
    /**
     * QA D1714: every stillbirth recorded in the period, whatever its gestation: the fetal-death events, plus the
     * stillbirth death records no live event holds (a death record declaring an event is counted once, through it).
     */
    private int stillbirths;
    /** Of {@link #stillbirths}: the WHO stillbirth indicator (StillbirthClassification.Statistic.WHO_STILLBIRTH). */
    private int stillbirthsWho;
    /** Of {@link #stillbirths}: the broader ICD level (StillbirthClassification.Statistic.ICD_FETAL_DEATH). */
    private int stillbirthsIcd;
    /** Of {@link #stillbirths}: no gestation recorded, so no statistic can classify it. Never dropped, never 0. */
    private int stillbirthsClassificationIncomplete;
    /** The thresholds the figures above use (provisional, StillbirthClassification). */
    private int whoStillbirthMinWeeks;
    private int icdFetalDeathMinWeeks;
    /** QA D1714: the stillbirths of the period, its own section, apart from the deaths after a live birth. */
    private List<FetalDeathEventDTO> stillbirthRows;
    private int earlyNeonatalDeaths;
    /** Days 7-27: past the WHO perinatal period, reported here and in no perinatal figure. */
    private int lateNeonatalDeaths;
    /** Deaths above under an open dispute: still counted until the outcome. For the coding authority's sign-off. */
    private int disputedDeaths;
    private List<DeathRecordResponseDTO> deaths;
    /** QA D1488: rows marked entered in error: kept and shown, in none of the counts above. */
    private List<DeathRecordResponseDTO> enteredInError;
}
