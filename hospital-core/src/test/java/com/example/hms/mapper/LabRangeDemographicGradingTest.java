package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 18 (lab reference ranges by sex and age band): the grading, the
 * critical-value alert that reads it, and the "range applied" every screen
 * shows all pick the range that applies to the patient. Before, the first
 * range in the result's unit graded everyone: a man on the women's
 * haemoglobin range, a newborn on the adult glucose range.
 *
 * <p>Ranges here are the round-18 dev defaults (reference-values files of
 * 2026-10-29/30); the test pins the selection, not the clinical values.
 */
class LabRangeDemographicGradingTest {

    private static final LocalDate RESULT_DAY = LocalDate.of(2026, 10, 30);

    private final LabResultMapper mapper = new LabResultMapper();

    private static LabTestReferenceRange range(Double min, Double max, String unit, String gender,
                                               Integer ageMin, Integer ageMax, String ageUnit,
                                               Double criticalLow, Double criticalHigh) {
        return LabTestReferenceRange.builder()
            .minValue(min).maxValue(max).unit(unit).gender(gender)
            .ageMin(ageMin).ageMax(ageMax).ageUnit(ageUnit)
            .criticalLow(criticalLow).criticalHigh(criticalHigh)
            .build();
    }

    private static LabResult result(String value, String unit, List<LabTestReferenceRange> ranges,
                                    String gender, LocalDate dateOfBirth) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setUnit(unit);
        definition.setReferenceRanges(new java.util.ArrayList<>(ranges));
        Patient patient = new Patient();
        patient.setGender(gender);
        patient.setDateOfBirth(dateOfBirth);
        LabOrder order = new LabOrder();
        order.setLabTestDefinition(definition);
        order.setPatient(patient);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit(unit);
        result.setResultDate(RESULT_DAY.atTime(9, 0));
        return result;
    }

    /** NFS haemoglobin, women's range configured first (as on dev). */
    private static final List<LabTestReferenceRange> HB = List.of(
        range(12.0, 15.5, "g/dL", "FEMALE", 18, 150, "YEARS", 7.0, null),
        range(13.5, 17.5, "g/dL", "MALE", 18, 150, "YEARS", 7.0, null),
        range(13.5, 24.0, "g/dL", "ALL", 0, 7, "DAYS", 7.0, null),
        range(9.0, 14.0, "g/dL", "ALL", 1, 5, "MONTHS", 7.0, null),
        range(11.5, 13.5, "g/dL", "ALL", 2, 5, "YEARS", 7.0, null));

    @Test
    void aManIsGradedOnTheMensRangeNotTheFirstOne() {
        LabResult man = result("13.0", "g/dL", HB, "MALE", LocalDate.of(1980, 1, 1));

        assertThat(mapper.severityFlagOf(man)).isEqualTo("LOW");
        assertThat(mapper.gradedReferenceRange(man).getGender()).isEqualTo("MALE");
    }

    @Test
    void aWomanWithTheSameValueIsNormal() {
        LabResult woman = result("13.0", "g/dL", HB, "F", LocalDate.of(1980, 1, 1));

        assertThat(mapper.severityFlagOf(woman)).isEqualTo("NORMAL");
    }

    @Test
    void aChildIsGradedOnItsAgeBand() {
        LabResult child = result("11.0", "g/dL", HB, "MALE", RESULT_DAY.minusYears(3));

        assertThat(mapper.severityFlagOf(child)).isEqualTo("LOW");
        LabResultReferenceRangeDTO graded = mapper.gradedReferenceRange(child);
        assertThat(graded.getAgeMin()).isEqualTo(2);
        assertThat(graded.getAgeUnit()).isEqualTo("YEARS");
        assertThat(mapper.toResponseDTO(child).getGradedReferenceRange().getMaxValue()).isEqualTo(13.5);
    }

    @Test
    void aNewbornIsGradedOnTheDaysBandAndAnInfantOnTheMonthsBand() {
        LabResult newborn = result("20.0", "g/dL", HB, "FEMALE", RESULT_DAY.minusDays(3));
        LabResult infant = result("20.0", "g/dL", HB, "FEMALE", RESULT_DAY.minusMonths(2));

        assertThat(mapper.severityFlagOf(newborn)).isEqualTo("NORMAL");
        assertThat(mapper.gradedReferenceRange(newborn).getAgeUnit()).isEqualTo("DAYS");
        assertThat(mapper.severityFlagOf(infant)).isEqualTo("HIGH");
        assertThat(mapper.gradedReferenceRange(infant).getAgeUnit()).isEqualTo("MONTHS");
    }

    /** Glycémie in g/L: the neonatal critical low (0.40) differs from the adult one (0.45). */
    private static final List<LabTestReferenceRange> GLY = List.of(
        range(0.70, 0.99, "g/L", "ALL", 18, 150, "YEARS", 0.45, 5.00),
        range(null, null, "g/L", "ALL", 0, 27, "DAYS", 0.40, null));

    @Test
    void aCriticalValueOnTheApplicableBandRaisesTheCriticalAlert() {
        LabResult adult = result("0.42", "g/L", GLY, "MALE", LocalDate.of(1970, 5, 5));
        LabResult newborn = result("0.42", "g/L", GLY, "MALE", RESULT_DAY.minusDays(5));
        LabResult newbornCritical = result("0.38", "g/L", GLY, "MALE", RESULT_DAY.minusDays(5));

        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(adult))).isTrue();
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(newborn))).isFalse();
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(newbornCritical))).isTrue();
    }

    @Test
    void potassiumCriticalLimitsAreInclusiveOfTheBoundaryAsConfigured() {
        // Kaliémie: critical < 2.5 or >= 6.5. The grading is strict (value >
        // criticalHigh), so ">= 6.5" is configured as criticalHigh 6.49.
        List<LabTestReferenceRange> k = List.of(range(3.5, 5.0, "mmol/L", "ALL", null, null, null, 2.5, 6.49));

        assertThat(mapper.severityFlagOf(result("6.5", "mmol/L", k, "F", LocalDate.of(1990, 1, 1))))
            .isEqualTo(LabResultMapper.FLAG_CRITICAL);
        assertThat(mapper.severityFlagOf(result("6.4", "mmol/L", k, "F", LocalDate.of(1990, 1, 1))))
            .isEqualTo("HIGH");
        assertThat(mapper.severityFlagOf(result("2.4", "mmol/L", k, "F", LocalDate.of(1990, 1, 1))))
            .isEqualTo(LabResultMapper.FLAG_CRITICAL);
    }

    @Test
    void noApplicableRangeKeepsTheConfiguredOrderSoAValueIsStillGraded() {
        // Only adult ranges configured, a 4-year-old patient: graded as before
        // this change (first range in the unit) rather than left ungraded.
        List<LabTestReferenceRange> adultOnly = List.of(
            range(3.5, 5.0, "mmol/L", "ALL", 18, 150, "YEARS", 2.5, 6.49));
        LabResult child = result("7.0", "mmol/L", adultOnly, "MALE", RESULT_DAY.minusYears(4));

        assertThat(mapper.severityFlagOf(child)).isEqualTo(LabResultMapper.FLAG_CRITICAL);
    }

    @Test
    void unknownSexOrBirthDateNeverHidesAResult() {
        LabResult unknownSex = result("13.0", "g/dL", HB, null, LocalDate.of(1980, 1, 1));
        LabResult unknownAge = result("13.0", "g/dL", HB, "MALE", null);

        assertThat(mapper.severityFlagOf(unknownSex)).isNotEqualTo(LabResultMapper.FLAG_UNSPECIFIED);
        assertThat(mapper.severityFlagOf(unknownAge)).isEqualTo("LOW");
        assertThat(mapper.gradedReferenceRange(unknownAge).getGender()).isEqualTo("MALE");
    }

    @Test
    void theUnitRuleStillComesFirst() {
        List<LabTestReferenceRange> twoUnits = List.of(
            range(3.9, 5.5, "mmol/L", "ALL", 18, 150, "YEARS", 2.5, 27.8),
            range(0.70, 0.99, "g/L", "MALE", 18, 150, "YEARS", 0.45, 5.0));
        LabResult inMmol = result("2.0", "mmol/L", twoUnits, "MALE", LocalDate.of(1970, 1, 1));

        assertThat(mapper.gradedReferenceRange(inMmol).getUnit()).isEqualTo("mmol/L");
        assertThat(mapper.severityFlagOf(inMmol)).isEqualTo(LabResultMapper.FLAG_CRITICAL);
    }

    /**
     * Review of round 18: the patient's own range is in another unit; the only
     * range in the result's unit is the other sex's. The value is still graded
     * (and a critical one alerted), never "not graded: units differ".
     */
    @Test
    void aCriticalValueIsNeverHiddenByTheDemographicFilter() {
        List<LabTestReferenceRange> mixed = List.of(
            range(3.5, 5.0, "mEq/L", "FEMALE", 18, 150, "YEARS", 2.5, 6.49),
            range(3.5, 5.0, "mmol/L", "MALE", 18, 150, "YEARS", 2.5, 6.49));
        LabResult woman = result("7.2", "mmol/L", mixed, "F", LocalDate.of(1980, 1, 1));

        assertThat(mapper.isUngradedForUnitMismatch(woman)).isFalse();
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(woman))).isTrue();
    }

    @Test
    void ageIsCountedInCompletedUnitsInclusiveBounds() {
        LocalDate dob = LocalDate.of(2026, 1, 31);
        assertThat(LabRangeApplicability.ageIn("DAYS", dob, LocalDate.of(2026, 2, 7))).isEqualTo(7);
        assertThat(LabRangeApplicability.ageIn("MONTHS", dob, LocalDate.of(2026, 2, 28))).isZero();
        assertThat(LabRangeApplicability.ageIn("MONTHS", dob, LocalDate.of(2026, 3, 31))).isEqualTo(2);
        assertThat(LabRangeApplicability.ageIn(null, LocalDate.of(2008, 10, 31), RESULT_DAY)).isEqualTo(17);
        assertThat(LabRangeApplicability.ageIn("YEARS", null, RESULT_DAY)).isNull();
        assertThat(LabRangeApplicability.ageIn("DAYS", RESULT_DAY.plusDays(1), RESULT_DAY)).isNull();
    }

    @Test
    void storedGenderSpellingsAreRead() {
        assertThat(LabRangeApplicability.sexOf("Homme")).isEqualTo("MALE");
        assertThat(LabRangeApplicability.sexOf("FEMALE")).isEqualTo("FEMALE");
        assertThat(LabRangeApplicability.sexOf("ALL")).isNull();
        assertThat(LabRangeApplicability.sexOf(null)).isNull();
    }

    @Test
    void aResultWithoutDateIsGradedOnToday() {
        LabResult noDate = result("13.0", "g/dL", HB, "MALE", LocalDate.now().minusYears(30));
        noDate.setResultDate(null);
        assertThat(mapper.severityFlagOf(noDate)).isEqualTo("LOW");
        noDate.setResultDate(LocalDateTime.now());
        assertThat(mapper.severityFlagOf(noDate)).isEqualTo("LOW");
    }
}
