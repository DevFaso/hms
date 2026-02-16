package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeSummaryMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.repository.DischargeApprovalRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DischargeSummaryServiceImplTest {

    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private DischargeSummaryMapper dischargeSummaryMapper;
    @Mock private PatientRepository patientRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private DischargeApprovalRepository dischargeApprovalRepository;

    @InjectMocks private DischargeSummaryServiceImpl service;

    private UUID summaryId, patientId, encounterId, hospitalId, staffId, assignmentId;
    private Patient patient;
    private Encounter encounter;
    private Hospital hospital;
    private Staff staff;
    private UserRoleHospitalAssignment assignment;
    private DischargeSummary summary;
    private DischargeSummaryResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        summaryId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();

        patient = new Patient(); patient.setId(patientId);
        encounter = new Encounter(); encounter.setId(encounterId);
        hospital = new Hospital(); hospital.setId(hospitalId);
        staff = new Staff(); staff.setId(staffId);
        assignment = new UserRoleHospitalAssignment(); assignment.setId(assignmentId);

        summary = DischargeSummary.builder()
            .patient(patient).encounter(encounter).hospital(hospital)
            .dischargingProvider(staff).assignment(assignment)
            .isFinalized(false)
            .dischargeDate(LocalDate.now())
            .build();
        summary.setId(summaryId);

        responseDTO = DischargeSummaryResponseDTO.builder().id(summaryId).build();
    }

    private DischargeSummaryRequestDTO buildRequest() {
        DischargeSummaryRequestDTO req = new DischargeSummaryRequestDTO();
        req.setPatientId(patientId);
        req.setEncounterId(encounterId);
        req.setHospitalId(hospitalId);
        req.setDischargingProviderId(staffId);
        req.setAssignmentId(assignmentId);
        req.setDischargeDate(LocalDate.now());
        req.setDischargeDiagnosis("Diagnosis");
        req.setHospitalCourse("Course");
        return req;
    }

    @Test void createDischargeSummary_success() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(false);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(dischargeSummaryRepository.save(any())).thenAnswer(inv -> { DischargeSummary s = inv.getArgument(0); s.setId(summaryId); return s; });
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);

        DischargeSummaryResponseDTO result = service.createDischargeSummary(req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void createDischargeSummary_alreadyExists_throws() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(true);
        assertThatThrownBy(() -> service.createDischargeSummary(req, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void createDischargeSummary_patientNotFound() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(false);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createDischargeSummary(req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void updateDischargeSummary_success() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any())).thenReturn(summary);
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);
        DischargeSummaryResponseDTO result = service.updateDischargeSummary(summaryId, req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void updateDischargeSummary_notFound() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void updateDischargeSummary_finalized_throws() {
        summary.setIsFinalized(true);
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void finalizeDischargeSummary_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any())).thenReturn(summary);
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);
        DischargeSummaryResponseDTO result = service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void finalizeDischargeSummary_wrongProvider_throws() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", randomId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void finalizeDischargeSummary_alreadyFinalized_throws() {
        summary.setIsFinalized(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void getDischargeSummaryById_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void getDischargeSummaryById_notFound() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDischargeSummaryById(summaryId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryByEncounter_success() {
        when(dischargeSummaryRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryByEncounter(encounterId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void getDischargeSummariesByPatient() {
        when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByPatient(patientId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByHospitalAndDateRange() {
        LocalDate start = LocalDate.now().minusDays(30); LocalDate end = LocalDate.now();
        when(dischargeSummaryRepository.findByHospitalAndDateRange(hospitalId, start, end)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByHospitalAndDateRange(hospitalId, start, end, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getUnfinalizedDischargeSummaries() {
        when(dischargeSummaryRepository.findUnfinalizedByHospital(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getUnfinalizedDischargeSummaries(hospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesWithPendingResults() {
        when(dischargeSummaryRepository.findWithPendingTestResults(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesWithPendingResults(hospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByProvider() {
        when(dischargeSummaryRepository.findByDischargingProvider_IdOrderByDischargeDateDesc(staffId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByProvider(staffId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void deleteDischargeSummary_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        service.deleteDischargeSummary(summaryId, staffId);
        verify(dischargeSummaryRepository).delete(summary);
    }

    @Test void deleteDischargeSummary_notFound() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void deleteDischargeSummary_finalized_throws() {
        summary.setIsFinalized(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(BusinessException.class);
    }
}
