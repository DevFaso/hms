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
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.support.PatientChartAccess;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import java.util.Map;
import java.util.Set;
import static org.mockito.Mockito.lenient;
import com.example.hms.enums.AbnormalFlag;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class PatientLabResultServiceImplTest {

    @Mock private LabResultRepository labResultRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

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
        // E9 #59b — the read spans the readable set; by default just the acting hospital.
        lenient().when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId));
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
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of());
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 10)).isEmpty();
    }

    @Test void getLabResults_patientNotFound() {
        when(patientChartAccess.require(eq(patientId), any()))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));
        assertThatThrownBy(() -> service.getLabResultsForPatient(patientId, hospitalId, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getLabResults_hospitalNotFound() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getLabResultsForPatient(patientId, hospitalId, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getLabResults_defaultLimit() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of());
        // passing 0 should use default limit of 25
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 0)).isEmpty();
    }

    @Test void getLabResults_exceedsMaxLimit() {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of());
        // passing 999 should be clamped to 100
        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 999)).isEmpty();
    }

    /** An unreleased glucose with everything a leak could carry: value, unit, notes, performer. */
    private LabResult unreleasedGlucoseWithEverything() {
        LabResult lr = buildLabResult("5.0", "mg/dL", false, false);
        lr.setNotes("preliminary — repeat requested");
        LabTestDefinition testDef = new LabTestDefinition();
        testDef.setName("Glucose"); testDef.setTestCode("GLU"); testDef.setCategory("CHEMISTRY");
        LabOrder order = new LabOrder();
        order.setLabTestDefinition(testDef);
        order.setOrderDatetime(LocalDateTime.now());
        lr.setLabOrder(order);
        User tech = new User(); tech.setFirstName("Tech"); tech.setLastName("Nician");
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment(); assignment.setUser(tech);
        lr.setAssignment(assignment);
        return lr;
    }

    private void givenTheOnlyRowIs(LabResult lr) {
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of(lr));
    }

    /** Staff path: the unreleased row keeps its preliminary value, labelled released=false. */
    @Test void getLabResults_withResults_pendingStatus() {
        LabResult lr = unreleasedGlucoseWithEverything();
        when(labResultMapper.toResponseDTO(lr)).thenReturn(null);
        givenTheOnlyRowIs(lr);

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results).hasSize(1);
        PatientLabResultResponseDTO row = results.get(0);
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.isReleased()).isFalse();
        assertThat(row.getTestName()).isEqualTo("Glucose");
        assertThat(row.getTestCode()).isEqualTo("GLU");
        assertThat(row.getValue()).isEqualTo("5.0");
        assertThat(row.getUnit()).isEqualTo("mg/dL");
        assertThat(row.getPerformedBy()).isEqualTo("Tech Nician");
    }

    /** B3 — patient path: the row is there so the patient knows a result is expected, but nothing of it. */
    @Test void portalView_unreleasedRow_isPendingWithoutValue() {
        LabResult lr = unreleasedGlucoseWithEverything();
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setReferenceRanges(List.of(LabResultReferenceRangeDTO.builder().minValue(3.9).maxValue(6.1).unit("mg/dL").build()));
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);
        givenTheOnlyRowIs(lr);

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatientPortal(patientId, hospitalId, 10);
        assertThat(results).hasSize(1);
        PatientLabResultResponseDTO row = results.get(0);
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.isReleased()).isFalse();
        assertThat(row.getTestName()).isEqualTo("Glucose");
        assertThat(row.getTestCode()).isEqualTo("GLU");
        assertThat(row.getCategory()).isEqualTo("CHEMISTRY");
        assertThat(row.getValue()).isNull();
        assertThat(row.getUnit()).isNull();
        assertThat(row.getReferenceRange()).isNull();
        assertThat(row.getNotes()).isNull();
        assertThat(row.getPerformedBy()).isNull();
    }

    /** B3 — the same row, once released, reaches the patient whole. */
    @Test void portalView_releasedRow_carriesTheValue() {
        LabResult lr = unreleasedGlucoseWithEverything();
        lr.setReleased(true);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        mapped.setSeverityFlag("NORMAL");
        mapped.setReferenceRanges(List.of(LabResultReferenceRangeDTO.builder().minValue(3.9).maxValue(6.1).unit("mg/dL").build()));
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);
        givenTheOnlyRowIs(lr);

        PatientLabResultResponseDTO row = service.getLabResultsForPatientPortal(patientId, hospitalId, 10).get(0);
        assertThat(row.isReleased()).isTrue();
        assertThat(row.getStatus()).isEqualTo("NORMAL");
        assertThat(row.getValue()).isEqualTo("5.0");
        assertThat(row.getUnit()).isEqualTo("mg/dL");
        assertThat(row.getReferenceRange()).isEqualTo("3.9 - 6.1 mg/dL");
        assertThat(row.getNotes()).isEqualTo("preliminary — repeat requested");
        assertThat(row.getPerformedBy()).isEqualTo("Tech Nician");
    }

    /** B18 — both gradings present: the more severe wins, direction from the directional one. */
    @Test void releasedRow_withRangeAndFlag_takesTheMoreSevereAndKeepsDirection() {
        record Case(String rangeSeverity, boolean acknowledged, AbnormalFlag flag, String expected) {}
        List<Case> cases = List.of(
            // inside the range, but the analyzer flagged H → the flag's direction
            new Case("NORMAL", false, AbnormalFlag.ABNORMAL_HIGH, "ABNORMAL_HIGH"),
            // range says mildly high (acknowledged), analyzer said HH → CRITICAL, never merely ABNORMAL_HIGH
            new Case("HIGH", true, AbnormalFlag.CRITICAL, "CRITICAL"),
            // range says low, analyzer said nothing abnormal → the range's direction
            new Case("LOW", false, AbnormalFlag.NORMAL, "ABNORMAL_LOW"),
            // range says low, technologist recorded an undirected ABNORMAL → the directional source
            new Case("LOW", false, AbnormalFlag.ABNORMAL, "ABNORMAL_LOW"),
            // both directional and disagreeing → the hospital's own range
            new Case("LOW", false, AbnormalFlag.ABNORMAL_HIGH, "ABNORMAL_LOW"),
            // range critical, flag directional → CRITICAL
            new Case("CRITICAL", false, AbnormalFlag.ABNORMAL_LOW, "CRITICAL"),
            // both normal
            new Case("NORMAL", false, AbnormalFlag.NORMAL, "NORMAL"));
        for (Case c : cases) {
            LabResult lr = buildLabResult("1", null, true, c.acknowledged());
            lr.setAbnormalFlag(c.flag());
            LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
            lr.setLabOrder(order);
            LabResultResponseDTO mapped = new LabResultResponseDTO();
            mapped.setSeverityFlag(c.rangeSeverity());
            when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);
            givenTheOnlyRowIs(lr);

            assertThat(service.getLabResultsForPatientPortal(patientId, hospitalId, 10).get(0).getStatus())
                .as("range %s (ack=%s) + flag %s", c.rangeSeverity(), c.acknowledged(), c.flag())
                .isEqualTo(c.expected());
        }
    }

    /** B18 — no reference range to grade against: the recorded flag decides, direction kept. */
    @Test void releasedRow_withoutRanges_statusFollowsTheRecordedFlag() {
        for (Map.Entry<AbnormalFlag, String> expected : Map.of(
                AbnormalFlag.ABNORMAL_LOW, "ABNORMAL_LOW",
                AbnormalFlag.ABNORMAL_HIGH, "ABNORMAL_HIGH",
                AbnormalFlag.ABNORMAL, "ABNORMAL",
                AbnormalFlag.CRITICAL, "CRITICAL",
                AbnormalFlag.NORMAL, "NORMAL").entrySet()) {
            LabResult lr = buildLabResult("1", null, true, false);
            lr.setAbnormalFlag(expected.getKey());
            LabOrder order = new LabOrder(); order.setLabTestDefinition(new LabTestDefinition());
            lr.setLabOrder(order);
            LabResultResponseDTO mapped = new LabResultResponseDTO();
            mapped.setSeverityFlag(LabResultMapper.FLAG_UNSPECIFIED);
            when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);
            givenTheOnlyRowIs(lr);

            assertThat(service.getLabResultsForPatientPortal(patientId, hospitalId, 10).get(0).getStatus())
                .as("flag %s", expected.getKey())
                .isEqualTo(expected.getValue());
        }
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getReferenceRange()).isEqualTo("3 - 6 mg/dL");
    }

    @Test void getLabResults_noLabOrder_fallbackTestName() {
        LabResult lr = buildLabResult("5.0", null, true, false);
        lr.setLabOrder(null);
        LabResultResponseDTO mapped = new LabResultResponseDTO();
        when(labResultMapper.toResponseDTO(lr)).thenReturn(mapped);

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
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

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of(lr));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);
        assertThat(results.get(0).getPerformedBy()).isEqualTo("Jane Doe");
    }

    @Test void getLabResults_followsThePatientWithProvenanceAndAccountsTheReach() {
        // E9 #59b — a result released at Hôpital B is on the list at Hôpital A
        // with its hospital on the row, and the disclosure is accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital(); other.setId(otherHospitalId); other.setName("Hôpital B");
        LabOrder localOrder = new LabOrder(); localOrder.setHospital(hospital);
        LabOrder foreignOrder = new LabOrder(); foreignOrder.setHospital(other);
        LabResult local = buildLabResult("5.1", "mmol/L", true, false); local.setLabOrder(localOrder);
        LabResult foreign = buildLabResult("6.2", "mmol/L", true, false); foreign.setLabOrder(foreignOrder);
        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(
                eq(patientId), eq(Set.of(hospitalId, otherHospitalId)), any(Pageable.class)))
            .thenReturn(List.of(local, foreign));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatient(patientId, hospitalId, 10);

        assertThat(results).extracting(PatientLabResultResponseDTO::getHospitalId)
            .containsExactly(hospitalId, otherHospitalId);
        assertThat(results.get(1).getHospitalName()).isEqualTo("Hôpital B");
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }
}
