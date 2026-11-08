package com.example.hms.mapper;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1394: a value the system accepts but cannot grade never passes as if it
 * were fine. A comparator is graded from its bound, comma, case and spelling of
 * the unit make no difference, mEq/L is mmol/L only for a monovalent ion, and
 * what stays ungraded says so.
 *
 * <p>The limits are the dev catalogue's as the QA report used them (glucose as in
 * {@link LabUnitConversionGradingTest}): the table pins the rule, not the values.
 */
class LabResultComparatorGradingTest {

    private static final String CRITICAL = "CRITICAL";
    private static final String UNSPECIFIED = "UNSPECIFIED";

    private final LabResultMapper mapper = new LabResultMapper();

    private static List<LabTestReferenceRange> potassiumRange() {
        return List.of(LabTestReferenceRange.builder().minValue(3.5).maxValue(5.0).unit("mmol/L")
            .criticalLow(2.5).criticalHigh(6.5).gender("ALL").build());
    }

    private static List<LabTestReferenceRange> glucoseRanges() {
        return List.of(
            LabTestReferenceRange.builder().minValue(0.70).maxValue(0.99).unit("g/L")
                .criticalLow(0.45).criticalHigh(5.0).gender("ALL").build(),
            LabTestReferenceRange.builder().minValue(3.9).maxValue(5.5).unit("mmol/L").gender("ALL").build());
    }

    private static LabResult result(String testName, String testCode, String testUnit,
                                    List<LabTestReferenceRange> ranges, String value, String unit) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName(testName);
        definition.setTestCode(testCode);
        definition.setUnit(testUnit);
        definition.setReferenceRanges(new ArrayList<>(ranges));
        Patient patient = new Patient();
        patient.setGender("MALE");
        patient.setDateOfBirth(LocalDate.of(1980, 1, 1));
        LabOrder order = new LabOrder();
        order.setLabTestDefinition(definition);
        order.setPatient(patient);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit(unit);
        result.setResultDate(LocalDate.of(2026, 11, 6).atTime(9, 0));
        return result;
    }

    private static LabResult potassium(String value, String unit) {
        return result("Potassium", "K", "mmol/L", potassiumRange(), value, unit);
    }

    private static LabResult glucose(String value, String unit) {
        return result("Glycémie", "GLY", "g/L", glucoseRanges(), value, unit);
    }

    /** Every value of the QA report (D1394), then the variants of comparator, comma and case. */
    @ParameterizedTest(name = "K {0} {1} is {2}")
    @CsvSource(delimiter = '|', value = {
        // The report.
        "> 6,5 | mmol/L | CRITICAL",
        "<1,5  | mmol/L | CRITICAL",
        "7     | mEq/L  | CRITICAL",
        // In every spelling: the comparator, the comma, the case.
        ">6,5  | mmol/L | CRITICAL",
        ">6.5  | mmol/L | CRITICAL",
        "> 6.5 | MMOL/L | CRITICAL",
        ">=7   | mmol/L | CRITICAL",
        "≥ 7,0 | mmol/l | CRITICAL",
        "<1.5  | MMOL/L | CRITICAL",
        "<=2   | mmol/L | CRITICAL",
        "≤ 2,5 | mmol/L | CRITICAL",
        "<2,5  | mEq/L  | CRITICAL",
        ">7    | MEQ/L  | CRITICAL",
        "7     | MEQ/L  | CRITICAL",
        "7     | meq/l  | CRITICAL",
        "7     | MMOL/L | CRITICAL",
        "7,0   | mmol/L | CRITICAL",
        // The bound settles abnormal, not critical.
        ">5,5  | mmol/L | HIGH",
        ">5    | mmol/L | HIGH",
        "<3    | mmol/L | LOW",
        "<3,5  | mEq/L  | LOW",
        // A plain value is graded as it always was.
        "4,2   | mmol/L | NORMAL",
        "4.2   | MMOL/L | NORMAL",
        "4.2   | mEq/L  | NORMAL",
        "5.5   | mEq/L  | HIGH",
        "6.5   | mmol/L | HIGH",
        "3     | mmol/L | LOW",
        // The bound does not settle it: never NORMAL.
        ">1    | mmol/L | UNSPECIFIED",
        ">4,9  | mmol/L | UNSPECIFIED",
        "<4    | mmol/L | UNSPECIFIED",
        "<7    | mmol/L | UNSPECIFIED",
        "<=4   | mEq/L  | UNSPECIFIED",
    })
    void aPotassiumIsGradedFromItsValueOrItsBound(String value, String unit, String expected) {
        LabResult result = potassium(value, unit);

        assertThat(mapper.severityFlagOf(result)).isEqualTo(expected);
        assertThat(mapper.toResponseDTO(result).getSeverityFlag()).isEqualTo(expected);
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(result))).isEqualTo(CRITICAL.equals(expected));
        assertThat(mapper.isUngradedForUnitMismatch(result)).isFalse();
        assertThat(mapper.isGradeUnknown(result)).isEqualTo(UNSPECIFIED.equals(expected));
        assertThat(mapper.toResponseDTO(result).isGradeUnknown()).isEqualTo(UNSPECIFIED.equals(expected));
    }

    @ParameterizedTest(name = "glucose {0} {1} is {2}")
    @CsvSource(delimiter = '|', value = {
        // The report.
        ">500  | mg/dL | CRITICAL",
        "600   | MG/DL | CRITICAL",
        "30    | MG/DL | CRITICAL",
        // Every other spelling grades the same: 30 mg/dl and 7 MMOL/L already did.
        "30    | mg/dl | CRITICAL",
        "30    | Mg/dL | CRITICAL",
        "<30   | MG/DL | CRITICAL",
        ">500  | MG/DL | CRITICAL",
        "> 5,0 | g/L   | CRITICAL",
        "> 5,0 | g/l   | CRITICAL",
        "85    | MG/DL | NORMAL",
        "60    | MG/DL | LOW",
        "499   | MG/DL | HIGH",
        "4400  | UMOL/L | NORMAL",
        ">200  | mg/dL | HIGH",
        "<50   | mg/dL | LOW",
        ">80   | mg/dL | UNSPECIFIED",
    })
    void aGlucoseIsGradedInEverySpellingOfItsUnit(String value, String unit, String expected) {
        LabResult result = glucose(value, unit);

        assertThat(mapper.severityFlagOf(result)).isEqualTo(expected);
        assertThat(mapper.isUngradedForUnitMismatch(result)).isFalse();
    }

    /** mEq/L is mmol/L for a monovalent ion, whichever the name or the code says it is. */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', value = {
        "Potassium | K-001", "Kaliémie | KAL", "Potasio | X1", "Dosage du potassium sérique | X2", "Autre | K", "Autre | k+",
        "Sodium | NA-1", "Natrémie | X3", "Sodio | X4", "Autre | Na",
        "Chloride | X5", "Chlorémie | X6", "Chlorures | X7", "Cloro | X8", "Autre | CL",
        "Bicarbonates | X9", "Bicarbonato | X10", "HCO3- | X11",
    })
    void aMonovalentIonConvertsOneToOne(String name, String code) {
        LabResult result = result(name, code, "mmol/L", potassiumRange(), "7", "mEq/L");

        assertThat(mapper.severityFlagOf(result)).isEqualTo(CRITICAL);
        assertThat(mapper.gradedReferenceRange(result).getUnit()).isEqualTo("mEq/L");
        assertThat(mapper.gradedReferenceRange(result).getCriticalHigh()).isEqualTo(6.5);
    }

    /** Divalent (2 mEq = 1 mmol), or an analyte nobody knows the valence of: flagged, not guessed. */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', value = {
        "Calcium | CA", "Calcémie | CA-1", "Calcio | X1",
        "Magnésium | MG", "Magnésémie | X2", "Magnesio | X3",
        "Chlorure de calcium | X4",
        "Vitamine K | VIT-K", "Clairance de la créatinine | CL-CREAT", "Glycémie | GLY", "Inconnu | ",
    })
    void nothingElseConverts(String name, String code) {
        LabResult result = result(name, code, "mmol/L", potassiumRange(), "7", "mEq/L");

        assertThat(mapper.isUngradedForUnitMismatch(result)).isTrue();
        assertThat(mapper.severityFlagOf(result)).isEqualTo(UNSPECIFIED);
        assertThat(mapper.toResponseDTO(result).isUnitMismatch()).isTrue();
    }

    /** A range stated in mEq/L grades a value in mmol/L, the other way round. */
    @Test
    void aRangeInMilliEquivalentsGradesAMolarValueOfAMonovalentIon() {
        List<LabTestReferenceRange> range = List.of(LabTestReferenceRange.builder().minValue(3.5).maxValue(5.0)
            .unit("mEq/L").criticalHigh(6.5).build());

        assertThat(mapper.severityFlagOf(result("Potassium", "K", "mEq/L", range, "7", "mmol/L"))).isEqualTo(CRITICAL);
        assertThat(mapper.severityFlagOf(result("Calcium", "CA", "mEq/L", range, "7", "mmol/L"))).isEqualTo(UNSPECIFIED);
        assertThat(mapper.severityFlagOf(result("Calcium", "CA", "mEq/L", range, "7", "MEQ/L")))
            .as("the unit the range is in needs no conversion, whatever the ion").isEqualTo(CRITICAL);
    }

    /** G/L on its own is a count (giga per litre), not grams: case folding must not make it one. */
    @Test
    void gigaPerLitreIsNeverAMass() {
        List<LabTestReferenceRange> massOnly = List.of(LabTestReferenceRange.builder().minValue(70.0).maxValue(99.0)
            .unit("mg/dL").criticalHigh(500.0).build());
        LabResult count = result("Glycémie", "GLY", "mg/dL", massOnly, "6", "G/L");

        assertThat(mapper.isUngradedForUnitMismatch(count)).isTrue();
        assertThat(LabUnitConversion.gradingFactor("G/L", "mg/dL", false)).isEmpty();
        assertThat(LabUnitConversion.gradingFactor("g/L", "MG/DL", false)).hasValueSatisfying(
            f -> assertThat(f).isEqualByComparingTo(new BigDecimal("100")));
    }

    /** The bound settles NORMAL when the value cannot leave the range: these must not nag anyone. */
    @ParameterizedTest(name = "{0} on [{1}, {2}] is {3}")
    @CsvSource(delimiter = '|', value = {
        "<5    | 0  | 5  | NORMAL",
        "<0,5  | 0  | 5  | NORMAL",
        "<5    |    | 5  | NORMAL",
        ">60   | 60 |    | NORMAL",
        ">90   | 60 |    | NORMAL",
        ">30   | 60 |    | UNSPECIFIED",
        "<10   | 0  | 5  | UNSPECIFIED",
        "<1    | -2 | 2  | UNSPECIFIED",
        ">1    | -2 | 2  | UNSPECIFIED",
        ">3    | -2 | 2  | HIGH",
        "<-3   | -2 | 2  | LOW",
    })
    void aBoundInsideAnOpenRangeIsNormal(String value, Double min, Double max, String expected) {
        List<LabTestReferenceRange> range = List.of(LabTestReferenceRange.builder().minValue(min).maxValue(max)
            .unit("mg/L").build());
        LabResult result = result("CRP", "CRP", "mg/L", range, value, "mg/L");

        assertThat(mapper.severityFlagOf(result)).isEqualTo(expected);
        assertThat(mapper.isGradeUnknown(result)).isEqualTo(UNSPECIFIED.equals(expected));
    }

    @Test
    void anAnalysersNormalDoesNotSettleAComparatorItsRangesCannot() {
        LabResult result = potassium(">1", "mmol/L");
        result.setAbnormalFlag(AbnormalFlag.NORMAL);

        assertThat(mapper.isGradeUnknown(result)).isTrue();
    }

    @Test
    void anAnalysersAbnormalFlagStillGradesIt() {
        LabResult result = potassium(">1", "mmol/L");
        result.setAbnormalFlag(AbnormalFlag.ABNORMAL_HIGH);

        assertThat(mapper.severityFlagOf(result)).isEqualTo(LabResultMapper.FLAG_ABNORMAL);
        assertThat(mapper.isGradeUnknown(result)).isFalse();
    }

    @Test
    void textOnANumericTestIsMarkedAndAPositiveAnswerStaysAbnormal() {
        assertThat(mapper.isGradeUnknown(potassium("Hémolysé", "mmol/L"))).isTrue();
        assertThat(mapper.severityFlagOf(potassium("Positif", "mmol/L"))).isEqualTo(LabResultMapper.FLAG_ABNORMAL);
        assertThat(mapper.isGradeUnknown(potassium("Positif", "mmol/L"))).isFalse();
    }

    /** A test with no range at all is not this: nothing was there to grade it, as before. */
    @Test
    void aTestWithNoRangeIsNotGradeUnknown() {
        LabResult result = result("Observation", "OBS", null, List.of(), ">1", null);

        assertThat(mapper.severityFlagOf(result)).isEqualTo(UNSPECIFIED);
        assertThat(mapper.isGradeUnknown(result)).isFalse();
        assertThat(mapper.isUngraded(result)).isFalse();
    }

    /** NaN used to pass every comparison and read NORMAL. */
    @Test
    void notANumberIsNeverNormal() {
        assertThat(mapper.severityFlagOf(potassium("NaN", "mmol/L"))).isEqualTo(UNSPECIFIED);
        assertThat(mapper.severityFlagOf(potassium("Infinity", "mmol/L"))).isEqualTo(UNSPECIFIED);
    }
}
