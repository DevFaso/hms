package com.example.hms.service.impl;

import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.PatientChartAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The range a patient is shown beside a value is the range the value was
 * GRADED against, labelled only with a unit that came with it.
 *
 * <p>Runs the real {@link LabResultMapper}: the defect was two readers of the
 * same configuration choosing different rows, which a mocked mapper cannot see.
 */
@ExtendWith(MockitoExtension.class)
class PatientLabResultReferenceRangeTest {

    @Mock private LabResultRepository labResultRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RecordAccessPolicy recordAccessPolicy;
    @Mock private CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.repository.LabSpecimenRepository labSpecimenRepository;

    private PatientLabResultServiceImpl service;
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PatientLabResultServiceImpl(labResultRepository, patientChartAccess, hospitalRepository,
            new LabResultMapper(), recordAccessPolicy, reachRecorder, labSpecimenRepository);
    }

    private PatientLabResultResponseDTO readOnly(String value, String unit, LabTestReferenceRange... ranges) {
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Glucose");
        definition.setReferenceRanges(new ArrayList<>(List.of(ranges)));
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setLabTestDefinition(definition);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit(unit);
        result.setReleased(true);
        result.setResultDate(LocalDateTime.now());

        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        // The portal reads the patient's own rows, every hospital.
        when(labResultRepository.findAllPatientResults(eq(patientId), any(Pageable.class))).thenReturn(List.of(result));

        return service.getLabResultsForPatientPortal(patientId, hospitalId, 10).get(0);
    }

    /**
     * Critical limits far outside the range: these tests are about which
     * range grades a value, and QA D206 labels an above-range value on a
     * range with NO critical limits CRITICAL (that value is paged).
     */
    private static LabTestReferenceRange range(double min, double max, String unit) {
        double span = max - min;
        return LabTestReferenceRange.builder().minValue(min).maxValue(max).unit(unit)
            .criticalLow(min - 10 * span).criticalHigh(max + 10 * span).build();
    }

    @Test
    void twoUnitSpecificRanges_theDisplayedRangeIsTheOneTheValueWasGradedAgainst() {
        // mg/dL configured first: the old formatter always showed ranges[0].
        PatientLabResultResponseDTO row = readOnly("5.4", "mmol/L",
            range(70, 110, "mg/dL"), range(3.9, 6.1, "mmol/L"));

        assertThat(row.getReferenceRange()).isEqualTo("3.9 - 6.1 mmol/L");
        assertThat(row.getStatus()).as("graded against the SAME range").isEqualTo("NORMAL");
    }

    @Test
    void twoUnitSpecificRanges_theOtherUnitSelectsTheOtherRange() {
        PatientLabResultResponseDTO row = readOnly("150", "mg/dL",
            range(3.9, 6.1, "mmol/L"), range(70, 110, "mg/dL"));

        assertThat(row.getReferenceRange()).isEqualTo("70 - 110 mg/dL");
        assertThat(row.getStatus()).isEqualTo("ABNORMAL_HIGH");
    }

    @Test
    void aUnitlessRange_isNeverLabelledWithTheResultsUnit() {
        PatientLabResultResponseDTO row = readOnly("5.4", "mmol/L", range(3.9, 6.1, null));

        assertThat(row.getReferenceRange())
            .as("the limits are real; a unit nobody configured is not")
            .isEqualTo("3.9 - 6.1");
        assertThat(row.getUnit()).isEqualTo("mmol/L");
    }

    @Test
    void noRangeInTheResultsUnit_noRangeIsShownAndNothingGradedIt() {
        // Clinical decision 2026-10-04: limits in another unit grade nothing.
        // 5.4 against 70-110 used to reach the patient as ABNORMAL_LOW.
        PatientLabResultResponseDTO row = readOnly("5.4", "mmol/L", range(70, 110, "mg/dL"));

        assertThat(row.getReferenceRange())
            .as("5.4 mmol/L is never shown beside 70 - 110 mg/dL")
            .isNull();
        assertThat(row.getUnit()).isEqualTo("mmol/L");
        assertThat(row.isUnitMismatch()).as("readers label it not graded").isTrue();
        assertThat(row.getStatus())
            .as("no range-derived grade and no recorded flag: not interpreted (D31), never NORMAL")
            .isEqualTo("UNKNOWN");
    }

    @Test
    void aRangeInTheResultsUnit_isNotAUnitMismatch() {
        PatientLabResultResponseDTO row = readOnly("5.4", "mmol/L", range(3.9, 6.1, "mmol/L"));

        assertThat(row.isUnitMismatch()).isFalse();
    }

    /** The round-13 malaria rapid test: no range, expected answer "Négatif". */
    private PatientLabResultResponseDTO readQualitative(String value, String expectedResult) {
        Patient patient = new Patient();
        patient.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("TDR paludisme");
        definition.setTestCode("TDR-PALU");
        definition.setReferenceRanges(new ArrayList<>());
        definition.setExpectedResult(expectedResult);
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setLabTestDefinition(definition);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setReleased(true);
        result.setResultDate(LocalDateTime.now());

        when(patientChartAccess.requireOwnRecord(patientId)).thenReturn(patient);
        when(labResultRepository.findAllPatientResults(eq(patientId), any(Pageable.class))).thenReturn(List.of(result));

        return service.getLabResultsForPatientPortal(patientId, hospitalId, 10).get(0);
    }

    /**
     * QA D834: the patient read "Résultat: 1 (Normal : 0 - 0)" because a
     * qualitative test needed a fake 0-0 range. It reads its expected answer.
     */
    @Test
    void d834_aQualitativeResultShowsTheExpectedAnswerAndIsNormalWhenItMatches() {
        PatientLabResultResponseDTO row = readQualitative("négatif", "Négatif");

        assertThat(row.getReferenceRange()).isEqualTo("Négatif");
        assertThat(row.getStatus()).isEqualTo("NORMAL");
    }

    @Test
    void d834_aPositiveQualitativeResultIsAbnormalBesideItsExpectedAnswer() {
        PatientLabResultResponseDTO row = readQualitative("Positif", "Négatif");

        assertThat(row.getReferenceRange()).isEqualTo("Négatif");
        assertThat(row.getStatus()).isEqualTo("ABNORMAL");
    }
}
