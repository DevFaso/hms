package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.lab.LabResultTrendDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class PatientLabResultServiceImplTest {

    @Mock private LabResultRepository labResultRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private LabResultMapper labResultMapper;

    @InjectMocks private PatientLabResultServiceImpl service;

    private UUID patientId, hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = new Hospital(); hospital.setId(hospitalId);
    }

    private LabResult buildLabResult(String value, String unit, boolean released, boolean acknowledged) {
        LabResult lr = new LabResult();
        lr.setId(UUID.randomUUID());
        lr.setResultValue(value);
        lr.setResultUnit(unit);
        lr.setReleased(released);
        lr.setAcknowledged(acknowledged);
        lr.setResultDate(LocalDateTime.now());
        return lr;
    }

    @Test void getLabResults_success_empty() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of());
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 10)).isEmpty();
    }

    @Test void getLabResults_patientNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLabResultsForPatient(patientId, hospitalId, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getLabResults_hospitalNotFound() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLabResultsForPatient(patientId, hospitalId, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getLabResults_defaultLimit() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of());
        // passing 0 should use default limit of 25
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 0)).isEmpty();
    }

    @Test void getLabResults_exceedsMaxLimit() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of());
        // passing 999 should be clamped to 100
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 999)).isEmpty();
    }

    @Test void getLabResults_withResults_pendingStatus() {
        LabResult lr = buildLabResult("5.0", "mg/dL", false, false);
        LabTestDefinition testDef = new LabTestDefinition();
        testDef.setName("Glucose"); testDef.setTestCode("GLU");
        LabOrder order = new LabOrder();
        order.setLabTestDefinition(testDef);
        order.setOrderDatetime(LocalDateTime.now());
        lr.setLabOrder(order);
        when(labResultMapper.toResponseDTO(lr)).thenReturn(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(results.get(0).getTestName()).isEqualTo("Glucose");
        assertThat(results.get(0).getTestCode()).isEqualTo("GLU");
    }

    @Test void getLabResults_released_normalStatus() {
        LabResult lr = buildLabResult("5.0", "mg/dL", true, false);
        LabTestDefinition testDef = new LabTestDefinition();
        testDef.setName("Glucose"); testDef.setTestCode("GLU");
        LabOrder order = new LabOrder();
        order.setLabTestDefinition(testDef);
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag(null);
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getStatus()).isEqualTo("NORMAL");
    }

    @Test void getLabResults_released_criticalSeverity() {
        LabResult lr = buildLabResult("200", "mg/dL", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("CRITICAL");
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getStatus()).isEqualTo("CRITICAL");
    }

    @Test void getLabResults_released_highSeverity_notAcknowledged() {
        LabResult lr = buildLabResult("200", "mg/dL", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("HIGH");
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getStatus()).isEqualTo("CRITICAL");
    }

    @Test void getLabResults_released_highSeverity_acknowledged() {
        LabResult lr = buildLabResult("200", "mg/dL", true, true);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("HIGH");
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getStatus()).isEqualTo("ABNORMAL_HIGH");
    }

    @Test void getLabResults_released_lowSeverity() {
        LabResult lr = buildLabResult("1.0", "mg/dL", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("LOW");
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getStatus()).isEqualTo("ABNORMAL_LOW");
    }

    @Test void getLabResults_staffNameResolution() {
        LabResult lr = buildLabResult("5.0", "mg/dL", true, false);
        Staff staff = new Staff(); staff.setName("Dr. Smith");
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        order.setOrderingStaff(staff);
        lr.setLabOrder(order);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getOrderedBy()).isEqualTo("Dr. Smith");
    }

    @Test void getLabResults_referenceRange_minAndMax() {
        LabResult lr = buildLabResult("5.0", "mg/dL", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        LabResultReferenceRangeDTO range = new LabResultReferenceRangeDTO();
        range.setMinValue(3.0); range.setMaxValue(6.0); range.setUnit("mg/dL");
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setReferenceRanges(List.of(range));
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getReferenceRange()).isEqualTo("3 - 6 mg/dL");
    }

    @Test void getLabResults_noLabOrder_fallbackTestName() {
        LabResult lr = buildLabResult("5.0", null, true, false);
        lr.setLabOrder(null);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getTestName()).isEqualTo("Lab Result");
    }

    @Test void getLabResults_assignmentUserResolution() {
        LabResult lr = buildLabResult("5.0", "mg/dL", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
        lr.setLabOrder(order);
        User user = new User(); user.setFirstName("Jane"); user.setLastName("Doe");
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setUser(user);
        lr.setAssignment(assignment);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getPerformedBy()).isEqualTo("Jane Doe");
    }

    // ── Lab Result Trends ──────────────────────────────────────────────────

    @Test void getLabResultTrends_emptyResults() {
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(Collections.emptyList());
        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends).isEmpty();
    }

    @Test void getLabResultTrends_groupsByTestDefinition() {
        LabTestDefinition defA = new LabTestDefinition();
        defA.setId(UUID.randomUUID()); defA.setName("Glucose"); defA.setTestCode("GLU"); defA.setCategory("Chemistry");
        LabTestDefinition defB = new LabTestDefinition();
        defB.setId(UUID.randomUUID()); defB.setName("Hemoglobin"); defB.setTestCode("HGB"); defB.setCategory("Hematology");

        LabResult r1 = buildLabResult("5.0", "mg/dL", true, false);
        LabOrder o1 = new LabOrder(); o1.setLabTestDefinition(defA); o1.setOrderDatetime(LocalDateTime.now().minusDays(2));
        r1.setLabOrder(o1); r1.setResultDate(LocalDateTime.now().minusDays(1));

        LabResult r2 = buildLabResult("14.0", "g/dL", true, false);
        LabOrder o2 = new LabOrder(); o2.setLabTestDefinition(defB); o2.setOrderDatetime(LocalDateTime.now().minusDays(3));
        r2.setLabOrder(o2); r2.setResultDate(LocalDateTime.now().minusDays(2));

        LabResult r3 = buildLabResult("5.5", "mg/dL", true, false);
        LabOrder o3 = new LabOrder(); o3.setLabTestDefinition(defA); o3.setOrderDatetime(LocalDateTime.now().minusDays(5));
        r3.setLabOrder(o3); r3.setResultDate(LocalDateTime.now().minusDays(4));

        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(r1, r2, r3));
        when(labResultMapper.toResponseDTO(any())).thenReturn(new LabResultResponseDTO());

        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends).hasSize(2);
        // Alphabetical: Glucose before Hemoglobin
        assertThat(trends.get(0).getTestName()).isEqualTo("Glucose");
        assertThat(trends.get(0).getDataPoints()).hasSize(2);
        assertThat(trends.get(1).getTestName()).isEqualTo("Hemoglobin");
        assertThat(trends.get(1).getDataPoints()).hasSize(1);
    }

    @Test void getLabResultTrends_cappedAt12Points() {
        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID()); def.setName("WBC"); def.setTestCode("WBC");

        List<LabResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            LabResult lr = buildLabResult(String.valueOf(4.0 + i * 0.1), "K/uL", true, false);
            LabOrder order = new LabOrder(); order.setLabTestDefinition(def); order.setOrderDatetime(LocalDateTime.now().minusDays(i));
            lr.setLabOrder(order); lr.setResultDate(LocalDateTime.now().minusDays(i));
            results.add(lr);
        }

        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(results);
        when(labResultMapper.toResponseDTO(any())).thenReturn(new LabResultResponseDTO());

        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends).hasSize(1);
        assertThat(trends.get(0).getDataPoints()).hasSize(12);
    }

    @Test void getLabResultTrends_sortedByDateDescending() {
        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID()); def.setName("Creatinine"); def.setTestCode("CRE");

        LabResult older = buildLabResult("1.0", "mg/dL", true, false);
        LabOrder o1 = new LabOrder(); o1.setLabTestDefinition(def); o1.setOrderDatetime(LocalDateTime.now().minusDays(10));
        older.setLabOrder(o1); older.setResultDate(LocalDateTime.now().minusDays(10));

        LabResult newer = buildLabResult("1.2", "mg/dL", true, false);
        LabOrder o2 = new LabOrder(); o2.setLabTestDefinition(def); o2.setOrderDatetime(LocalDateTime.now().minusDays(1));
        newer.setLabOrder(o2); newer.setResultDate(LocalDateTime.now().minusDays(1));

        // Pass in non-chronological order
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(older, newer));
        when(labResultMapper.toResponseDTO(any())).thenReturn(new LabResultResponseDTO());

        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends.get(0).getDataPoints().get(0).getValue()).isEqualTo("1.2");
        assertThat(trends.get(0).getDataPoints().get(1).getValue()).isEqualTo("1.0");
    }

    @Test void getLabResultTrends_abnormalFlagSet() {
        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID()); def.setName("Potassium"); def.setTestCode("K");

        LabResult lr = buildLabResult("6.0", "mEq/L", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(def); order.setOrderDatetime(LocalDateTime.now());
        lr.setLabOrder(order); lr.setResultDate(LocalDateTime.now());

        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("HIGH");
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(lr));
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends.get(0).getDataPoints().get(0).isAbnormal()).isTrue();
    }

    @Test void getLabResultTrends_normalNotAbnormal() {
        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID()); def.setName("Sodium"); def.setTestCode("NA");

        LabResult lr = buildLabResult("140", "mEq/L", true, false);
        LabOrder order = new LabOrder(); order.setLabTestDefinition(def); order.setOrderDatetime(LocalDateTime.now());
        lr.setLabOrder(order); lr.setResultDate(LocalDateTime.now());

        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag(null);
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(lr));
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        List<LabResultTrendDTO> trends = service.getLabResultTrends(patientId);
        assertThat(trends.get(0).getDataPoints().get(0).isAbnormal()).isFalse();
        assertThat(trends.get(0).getDataPoints().get(0).getStatus()).isEqualTo("NORMAL");
    }
}
