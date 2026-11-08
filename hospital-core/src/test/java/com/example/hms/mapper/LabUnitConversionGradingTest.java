package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1092: a glucose entered in mg/dL on a test whose ranges are configured
 * in g/L and mmol/L was released "not graded: units differ", so 510 and 30
 * mg/dL (beyond the critical limits 0.45 / 5.0 g/L) paged nobody.
 *
 * <p>Grading now converts between units of the same dimension (a mass per
 * volume, a molar concentration) by a power of ten, which needs no knowledge
 * of the analyte. Nothing that needs a molar mass or a valence is converted.
 * The limits below are the round-19 dev glucose range (0.70-0.99 g/L, critical
 * 0.45 / 5.0 g/L; 3.9-5.5 mmol/L): the test pins the conversion, not the
 * clinical values.
 */
class LabUnitConversionGradingTest {

    private final LabResultMapper mapper = new LabResultMapper();

    private static final List<LabTestReferenceRange> GLUCOSE = List.of(
        LabTestReferenceRange.builder().minValue(0.70).maxValue(0.99).unit("g/L")
            .criticalLow(0.45).criticalHigh(5.0).gender("ALL").build(),
        LabTestReferenceRange.builder().minValue(3.9).maxValue(5.5).unit("mmol/L").gender("ALL").build());

    private static LabResult glucose(String value, String unit) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setUnit("g/L");
        definition.setReferenceRanges(new ArrayList<>(GLUCOSE));
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
        result.setResultDate(LocalDate.of(2026, 10, 31).atTime(9, 0));
        return result;
    }

    /** The values of the QA report, in every spelling of mg/dL a technician types. */
    @ParameterizedTest
    @CsvSource({
        "510, mg/dL, CRITICAL",
        "30, mg/dL, CRITICAL",
        "501, mg/dl, CRITICAL",
        "499, mg/dL, HIGH",
        "85, mg/dL, NORMAL",
        "60, mg/dL, LOW",
        "5.1, g/L, CRITICAL",
        "0.85, g/L, NORMAL",
        "510, mg / dL, CRITICAL",
        "4.4, mmol/L, NORMAL",
        "4400, µmol/L, NORMAL",
        "4400, umol/L, NORMAL",
    })
    void everyUnitTheTestAcceptsIsGraded(String value, String unit, String expected) {
        LabResult result = glucose(value, unit);

        assertThat(mapper.severityFlagOf(result)).isEqualTo(expected);
        assertThat(mapper.isUngradedForUnitMismatch(result)).isFalse();
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(result)))
            .isEqualTo("CRITICAL".equals(expected));
    }

    @Test
    void theRangeShownBesideAConvertedValueIsInTheValuesUnit() {
        var range = mapper.gradedReferenceRange(glucose("85", "mg/dL"));

        assertThat(range.getUnit()).isEqualTo("mg/dL");
        assertThat(range.getMinValue()).isEqualTo(70.0);
        assertThat(range.getMaxValue()).isEqualTo(99.0);
        assertThat(range.getCriticalLow()).isEqualTo(45.0);
        assertThat(range.getCriticalHigh()).isEqualTo(500.0);
        assertThat(range.getGender()).as("the other fields of the range are kept").isEqualTo("ALL");
    }

    @Test
    void theConfiguredRangeIsNotChangedByTheConversion() {
        mapper.severityFlagOf(glucose("510", "mg/dL"));

        assertThat(GLUCOSE.get(0).getUnit()).isEqualTo("g/L");
        assertThat(GLUCOSE.get(0).getCriticalHigh()).isEqualTo(5.0);
    }

    /** A unit that needs the analyte (molar mass, valence) or is not a concentration stays ungraded and flagged. */
    @ParameterizedTest
    @ValueSource(strings = {"mEq/L", "mg", "%", "10^9/L", "UI/L"})
    void aUnitThatCannotBeConvertedWithoutTheAnalyteIsFlaggedNotGuessed(String unit) {
        LabResult result = glucose("510", unit);

        assertThat(mapper.isUngradedForUnitMismatch(result)).isTrue();
        assertThat(mapper.severityFlagOf(result)).isEqualTo(LabResultMapper.FLAG_UNSPECIFIED);
    }

    @ParameterizedTest
    @CsvSource({
        "mg/dL, g/L, 0.01",
        "g/L, mg/dL, 100",
        "g/dL, g/L, 10",
        "mg/L, g/L, 0.001",
        "µg/L, mg/L, 0.001",
        "ng/mL, µg/L, 1",
        "mmol/L, µmol/L, 1000",
        "umol/L, mmol/L, 0.001",
        "nmol/L, pmol/L, 1000",
        "mg/dl, MG/DL, 1",
    })
    void massAndMolarConcentrationsConvertByAPowerOfTen(String from, String to, String factor) {
        if ("MG/DL".equals(to)) {
            // Upper-case G is giga, not gram: not a mass unit.
            assertThat(LabUnitConversion.factor(from, to)).isEmpty();
            return;
        }
        assertThat(LabUnitConversion.factor(from, to)).hasValueSatisfying(
            f -> assertThat(f).isEqualByComparingTo(new BigDecimal(factor)));
    }

    @Test
    void massNeverConvertsToMolar() {
        assertThat(LabUnitConversion.factor("mg/dL", "mmol/L")).isEmpty();
        assertThat(LabUnitConversion.factor("mmol/L", "g/L")).isEmpty();
    }

    @Test
    void scalingIsExact() {
        assertThat(LabUnitConversion.scale(0.99, new BigDecimal("100"))).isEqualTo(99.0);
        assertThat(LabUnitConversion.scale(0.7, new BigDecimal("100"))).isEqualTo(70.0);
        assertThat(LabUnitConversion.scale(null, BigDecimal.TEN)).isNull();
    }

    /** QA D1095: a result with no unit of its own is shown in its test's unit. */
    @Test
    void aBlankUnitIsTheTestsUnit() {
        assertThat(LabResultMapper.unitOf(glucose("0.40", ""))).isEqualTo("g/L");
        assertThat(LabResultMapper.unitOf(glucose("0.40", null))).isEqualTo("g/L");
        assertThat(LabResultMapper.unitOf(glucose("40", " mg/dL "))).isEqualTo("mg/dL");
        assertThat(mapper.toResponseDTO(glucose("0.40", null)).getResultUnit()).isEqualTo("g/L");
    }
}
