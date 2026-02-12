package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
}
