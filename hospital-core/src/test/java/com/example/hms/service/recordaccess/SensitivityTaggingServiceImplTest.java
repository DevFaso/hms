package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SensitivityTaggingServiceImpl")
class SensitivityTaggingServiceImplTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private com.example.hms.repository.PatientProblemRepository patientProblemRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private SensitivityTaggingServiceImpl service;

    private final UUID encounterId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Encounter encounter;
    private Department department;

    @BeforeEach
    void setUp() {
        service = new SensitivityTaggingServiceImpl(encounterRepository, departmentRepository, patientProblemRepository,
            new SensitivityClassifierImpl(), auditEventLogService);

        department = new Department();
        department.setId(departmentId);

        Patient patient = new Patient();
        patient.setId(patientId);

        encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setPatient(patient);
        encounter.setDepartment(department);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(departmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("tagging an encounter stores it and reports it as not travelling")
    void tagEncounter() {
        SensitivityTagResponseDTO dto = service.tagEncounter(encounterId, SensitivityCategory.HIV, actor);

        assertThat(encounter.getSensitivityCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.explicitCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.effectiveCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.travelsCrossHospital()).isFalse();
    }

    @Test
    @DisplayName("clearing the tag falls back to the department default, not to untagged")
    void clearFallsBackToDepartment() {
        department.setDefaultSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);
        encounter.setSensitivityCategory(SensitivityCategory.HIV);

        SensitivityTagResponseDTO dto = service.tagEncounter(encounterId, null, actor);

        assertThat(dto.explicitCategory()).isNull();
        assertThat(dto.effectiveCategory()).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        assertThat(dto.departmentDefault()).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        assertThat(dto.travelsCrossHospital()).isFalse();
    }

    @Test
    @DisplayName("the audit carries the category names and the patient, never the clinical text")
    void auditShape() {
        encounter.setNotes("patient disclosed heroin use and HIV status");

        service.tagEncounter(encounterId, SensitivityCategory.SUBSTANCE_USE, actor);

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        AuditEventRequestDTO audit = captor.getValue();
        assertThat(audit.getEventType()).isEqualTo(AuditEventType.DATA_UPDATE);
        assertThat(audit.getPatientId()).isEqualTo(patientId);
        assertThat(audit.getEventDescription()).contains("SUBSTANCE_USE").doesNotContain("heroin");
    }

    @Test
    @DisplayName("re-setting the same category writes nothing and audits nothing")
    void noopWhenUnchanged() {
        encounter.setSensitivityCategory(SensitivityCategory.HIV);

        service.tagEncounter(encounterId, SensitivityCategory.HIV, actor);

        verify(encounterRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("a department default is a CONFIGURATION_CHANGED, because it re-classifies rows in bulk")
    void departmentDefaultIsConfiguration() {
        SensitivityTagResponseDTO dto = service.setDepartmentDefault(
            departmentId, SensitivityCategory.BEHAVIOURAL_HEALTH, actor);

        assertThat(department.getDefaultSensitivityCategory()).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
        assertThat(dto.travelsCrossHospital()).isFalse();

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(captor.getValue().getEventDescription()).contains("BEHAVIOURAL_HEALTH");
    }

    @Test
    @DisplayName("unknown encounter and unknown department are both 404 by key")
    void notFound() {
        UUID ghost = UUID.randomUUID();
        when(encounterRepository.findById(ghost)).thenReturn(Optional.empty());
        when(departmentRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEncounterTag(ghost)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getDepartmentDefault(ghost)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── QA D2730: a problem-list entry ──────────────────────────────────────

    private com.example.hms.model.PatientProblem problemOf(UUID id, Encounter visit) {
        Patient owner = new Patient();
        owner.setId(patientId);
        com.example.hms.model.PatientProblem problem = new com.example.hms.model.PatientProblem();
        problem.setId(id);
        problem.setPatient(owner);
        problem.setSourceEncounter(visit);
        when(patientProblemRepository.findById(id)).thenReturn(Optional.of(problem));
        when(patientProblemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return problem;
    }

    @Test
    @DisplayName("D2730: tagging a problem stores the tag and audits category names only")
    void tagProblem() {
        UUID problemId = UUID.randomUUID();
        com.example.hms.model.PatientProblem problem = problemOf(problemId, null);

        SensitivityTagResponseDTO dto = service.tagProblem(patientId, problemId, SensitivityCategory.HIV, actor);

        assertThat(problem.getSensitivityCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.explicitCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.effectiveCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.travelsCrossHospital()).isFalse();
        verify(patientProblemRepository).save(problem);
    }

    @Test
    @DisplayName("D2730: clearing a problem's own tag leaves the category of the visit it was recorded in")
    void clearingAProblemTagFallsBackToItsVisit() {
        UUID problemId = UUID.randomUUID();
        encounter.setSensitivityCategory(SensitivityCategory.BEHAVIOURAL_HEALTH);
        com.example.hms.model.PatientProblem problem = problemOf(problemId, encounter);
        problem.setSensitivityCategory(SensitivityCategory.HIV);

        SensitivityTagResponseDTO dto = service.tagProblem(patientId, problemId, null, actor);

        assertThat(problem.getSensitivityCategory()).isNull();
        assertThat(dto.explicitCategory()).isNull();
        assertThat(dto.effectiveCategory()).isEqualTo(SensitivityCategory.BEHAVIOURAL_HEALTH);
    }

    @Test
    @DisplayName("D2730: another patient's problem answers as unknown, and nothing is written")
    void aProblemOfAnotherPatientIsUnknown() {
        UUID problemId = UUID.randomUUID();
        problemOf(problemId, null);
        UUID otherPatient = UUID.randomUUID();

        assertThatThrownBy(() -> service.tagProblem(otherPatient, problemId, SensitivityCategory.HIV, actor))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientProblemRepository, never()).save(any());
    }

    @Test
    @DisplayName("D2730: the answer is built from the loaded problem, never from what save returns")
    void aSaveThatReturnsNothingStillAnswers() {
        UUID problemId = UUID.randomUUID();
        com.example.hms.model.PatientProblem problem = problemOf(problemId, null);
        when(patientProblemRepository.save(any())).thenReturn(null);

        SensitivityTagResponseDTO dto = service.tagProblem(patientId, problemId, SensitivityCategory.HIV, actor);

        assertThat(dto.id()).isEqualTo(problemId);
        assertThat(dto.explicitCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(dto.departmentDefault()).isNull();
        assertThat(problem.getSensitivityCategory()).isEqualTo(SensitivityCategory.HIV);
    }

    @Test
    @DisplayName("D2730: a problem with no visit, or a visit with no department, has no department default")
    void aProblemWithNoVisitOrNoDepartment() {
        UUID noVisit = UUID.randomUUID();
        problemOf(noVisit, null);
        SensitivityTagResponseDTO untagged = service.tagProblem(patientId, noVisit, null, actor);
        assertThat(untagged.effectiveCategory()).isNull();
        assertThat(untagged.departmentDefault()).isNull();
        assertThat(untagged.travelsCrossHospital()).isTrue();

        UUID noDepartment = UUID.randomUUID();
        Encounter visit = new Encounter();
        visit.setSensitivityCategory(SensitivityCategory.HIV);
        problemOf(noDepartment, visit);
        SensitivityTagResponseDTO fromVisit = service.tagProblem(patientId, noDepartment, null, actor);
        assertThat(fromVisit.effectiveCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(fromVisit.departmentDefault()).isNull();
    }
}
