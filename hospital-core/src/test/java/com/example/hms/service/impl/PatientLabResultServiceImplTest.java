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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * The analyzer's preliminary and its final are two stored rows — the ingest
     * keeps both on purpose — so the patient's view is where the pair is
     * resolved: the finished value alone, with no "pending" lingering beside it.
     */
    @Test void portalView_hidesThePreliminaryThatAReleaseSuperseded() {
        LabTestDefinition testDef = new LabTestDefinition();
        testDef.setName("Haemoglobin"); testDef.setTestCode("HGB");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(testDef);

        LabResult preliminary = buildLabResult("13.1", "g/dL", false, false);
        preliminary.setLabOrder(order);
        preliminary.setTestCode("HGB");
        preliminary.setSourceSendingApplication("SYSMEX");
        preliminary.setObservationResultStatus("P");
        preliminary.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        LabResult finalResult = buildLabResult("13.7", "g/dL", true, false);
        finalResult.setLabOrder(order);
        finalResult.setTestCode("HGB");
        finalResult.setSourceSendingApplication("SYSMEX");
        finalResult.setObservationResultStatus("F");
        finalResult.setResultDate(preliminary.getResultDate());
        finalResult.setCreatedAt(LocalDateTime.now());
        lenient().when(labResultMapper.toResponseDTO(preliminary)).thenReturn(null);
        when(labResultMapper.toResponseDTO(finalResult)).thenReturn(new LabResultResponseDTO());

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of(preliminary, finalResult));

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatientPortal(patientId, hospitalId, 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(finalResult.getId());
        assertThat(results.get(0).getValue()).isEqualTo("13.7");
        assertThat(results.get(0).getStatus()).isNotEqualTo("PENDING");
    }

    /**
     * The pairing has to be resolved BEFORE the limit. Filtering afterwards cut
     * rows out of an already-short page — asking for two results returned one —
     * and a preliminary could sit inside the page while the final that
     * supersedes it sat just outside it, leaving a permanent "Result pending"
     * for a test that is released.
     */
    @Test void portalView_limitIsHonouredAfterTheSupersededRowIsRemoved() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(new LabTestDefinition());

        LabResult preliminary = buildLabResult("13.1", "g/dL", false, false);
        preliminary.setLabOrder(order);
        preliminary.setTestCode("HGB");
        preliminary.setSourceSendingApplication("SYSMEX");
        preliminary.setObservationResultStatus("P");
        preliminary.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        LabResult finalResult = buildLabResult("13.7", "g/dL", true, false);
        finalResult.setLabOrder(order);
        finalResult.setTestCode("HGB");
        finalResult.setSourceSendingApplication("SYSMEX");
        finalResult.setObservationResultStatus("F");
        finalResult.setResultDate(preliminary.getResultDate());
        finalResult.setCreatedAt(LocalDateTime.now());
        LabResult another = buildLabResult("4.1", "mmol/L", true, false);
        another.setLabOrder(order);
        another.setTestCode("K");
        another.setSourceObservationSetId("2");
        when(labResultMapper.toResponseDTO(any(LabResult.class))).thenReturn(new LabResultResponseDTO());

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        // Honour the page size, or the mock hands back every row whatever was
        // asked for and the short-page path this test exists for never runs.
        List<LabResult> newestFirst = List.of(finalResult, preliminary, another);
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), pageCaptor.capture()))
            .thenAnswer(invocation -> {
                Pageable requested = invocation.getArgument(2);
                return newestFirst.subList(0, Math.min(requested.getPageSize(), newestFirst.size()));
            });

        List<PatientLabResultResponseDTO> results = service.getLabResultsForPatientPortal(patientId, hospitalId, 2);

        assertThat(results).as("two asked for, two returned — not one short of the page").hasSize(2);
        assertThat(pageCaptor.getAllValues()).extracting(Pageable::getPageSize)
            .as("the caller's limit first; the cap only because the pairing removed a row — "
                + "never a hundred-row page on every call, and never a fixed +1 that a second "
                + "pair on the page would defeat")
            .containsExactly(2, 100);
    }

    /** The staff record is the record: both rows stay, each labelled. */
    @Test void staffView_keepsBothRowsOfAPreliminaryFinalPair() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(new LabTestDefinition());

        LabResult preliminary = buildLabResult("13.1", "g/dL", false, false);
        preliminary.setLabOrder(order);
        preliminary.setTestCode("HGB");
        preliminary.setSourceSendingApplication("SYSMEX");
        preliminary.setObservationResultStatus("P");
        LabResult finalResult = buildLabResult("13.7", "g/dL", true, false);
        finalResult.setLabOrder(order);
        finalResult.setTestCode("HGB");
        finalResult.setSourceSendingApplication("SYSMEX");
        finalResult.setObservationResultStatus("F");
        when(labResultMapper.toResponseDTO(any(LabResult.class))).thenReturn(new LabResultResponseDTO());

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn(eq(patientId), eq(Set.of(hospitalId)), any(Pageable.class)))
            .thenReturn(List.of(preliminary, finalResult));

        assertThat(service.getLabResultsForPatient(patientId, hospitalId, 10))
            .as("the clinical record keeps what the analyzer said first")
            .hasSize(2);
    }

    /** B18 — both gradings present: the more severe wins, direction from the directional one. */
    @Test void releasedRow_withRangeAndFlag_takesTheMoreSevereAndKeepsDirection() {
        record Case(String rangeSeverity, boolean acknowledged, AbnormalFlag flag, String expected) {}
        List<Case> cases = List.of(
            // inside the range, but the analyzer flagged H → the flag's direction
            new Case("NORMAL", false, AbnormalFlag.ABNORMAL_HIGH, "ABNORMAL_HIGH"),
            // range says high, analyzer said HH → CRITICAL, never merely ABNORMAL_HIGH
            new Case("HIGH", true, AbnormalFlag.CRITICAL, "CRITICAL"),
            // 16.0 against 12–15.5 that the analyzer flagged N and auto-released: out of range,
            // unacknowledged — ABNORMAL_HIGH, never a synthetic CRITICAL the patient reads unreviewed
            new Case("HIGH", false, AbnormalFlag.NORMAL, "ABNORMAL_HIGH"),
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

    /** Out of range is ABNORMAL_HIGH whether or not a clinician has acknowledged it: no synthetic CRITICAL. */
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
        assertThat(results.get(0).getStatus()).isEqualTo("ABNORMAL_HIGH");
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

    // -- No hospital scope: the staff path refuses, the portal path does not --

    /**
     * The staff read with no resolvable hospital scope must NOT fall through to
     * the patient-only query, which returns every hospital's rows with neither
     * RecordAccessPolicy nor a cross-hospital disclosure row. It refuses, with
     * the same 404 PatientChartAccess throws, and it refuses BEFORE reading.
     */
    @Test void staffView_withNoHospitalScope_refusesInsteadOfReadingEveryHospital() {
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital(); other.setId(otherHospitalId); other.setName("Hopital B");
        LabOrder foreignOrder = new LabOrder(); foreignOrder.setHospital(other);
        LabResult foreign = buildLabResult("6.2", "mmol/L", true, false); foreign.setLabOrder(foreignOrder);
        when(patientChartAccess.require(eq(patientId), isNull())).thenReturn(patient);
        // Stubbed so the test would SEE the leak if the fallback ever ran again.
        lenient().when(labResultRepository.findPatientResultsReadableAt(any(), any(), any(), anyBoolean(), any()))
            .thenReturn(List.of(foreign));

        // The key matters as much as the type. ResourceNotFoundException is
        // @ResponseStatus(NOT_FOUND), so the type pins 404-not-403; the key
        // pins that the refusal is indistinguishable from "no such patient",
        // and that it stays a resolvable key rather than the prose that once
        // rendered as "[Missing translation] Patient not found with ID: ...".
        assertThatThrownBy(() -> service.getLabResultsForPatient(patientId, null, 10))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(thrown -> ((ResourceNotFoundException) thrown).getMessageKey())
            .isEqualTo("patient.notFound");

        verify(labResultRepository, never()).findPatientResultsReadableAt(any(), any(), any(), anyBoolean(), any());
        verifyNoInteractions(reachRecorder);
    }

    /**
     * The patient reading their own results: no hospital scope is legitimate,
     * and refusing the staff case must not take this branch with it.
     *
     * <p>NOT end-to-end portal coverage, deliberately. {@code patientChartAccess}
     * is stubbed to admit the null scope, but the real
     * {@code PatientChartAccess.require} throws on a null scope for any
     * principal the context does not mark a super-admin — a patient included —
     * so a portal caller with no resolvable hospital is refused one frame
     * earlier than this, and has been since before this change. That is a
     * separate defect in the portal's use of the STAFF chart-access gate; what
     * this test pins is the branch inside this service.
     */
    @Test void portalView_withNoHospitalScope_stillReturnsThePatientsOwnResults() {
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital(); other.setId(otherHospitalId); other.setName("Hopital B");
        LabOrder order = new LabOrder(); order.setHospital(other);
        LabResult own = buildLabResult("5.1", "mmol/L", true, false); own.setLabOrder(order);
        when(patientChartAccess.require(eq(patientId), isNull())).thenReturn(patient);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(labResultRepository.findPatientResultsReadableAt(eq(patientId), eq(Set.of(new UUID(0L, 0L))),
            isNull(), eq(true), page.capture())).thenReturn(List.of(own));

        List<PatientLabResultResponseDTO> results =
            service.getLabResultsForPatientPortal(patientId, null, 10);

        assertThat(results).extracting(PatientLabResultResponseDTO::getValue).containsExactly("5.1");
        // Newest ten at the database — this used to load the patient's whole
        // result history, fully hydrated, to sort and cut it in memory.
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.getValue().getSort().getOrderFor("resultDate"))
            .isNotNull()
            .returns(Sort.Direction.DESC, Sort.Order::getDirection);
        assertThat(results).extracting(PatientLabResultResponseDTO::getHospitalId)
            .containsExactly(otherHospitalId);
        // Nothing to disclose against: there is no acting hospital.
        verifyNoInteractions(reachRecorder);
    }
}
