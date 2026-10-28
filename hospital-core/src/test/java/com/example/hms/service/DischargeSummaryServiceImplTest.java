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
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import java.util.Map;
import java.util.Set;

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
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.repository.EncounterNoteRepository encounterNoteRepository;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    /** Real registry so the portal-fetch tests can assert counter values. */
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock private org.springframework.context.MessageSource messageSource;
    @Mock private com.example.hms.service.i18n.PatientLocaleResolver patientLocaleResolver;

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
        // QA r5 tenancy: the summary's encounter is this patient's, at this hospital.
        encounter.setPatient(patient);
        encounter.setHospital(hospital);
        staff = new Staff(); staff.setId(staffId);
        // QA D503: the discharging provider is the signed-in doctor.
        com.example.hms.model.User providerUser = new com.example.hms.model.User();
        providerUser.setId(UUID.randomUUID());
        staff.setUser(providerUser);
        staff.setHospital(hospital);
        actAs(providerUser);
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void qaD503_anotherDoctorCannotFinalizeInTheProvidersName() {
        // DOCTOR2 sends POST /finalize?providerId=<DOCTOR_A staffId>.
        com.example.hms.model.User doctorTwo = new com.example.hms.model.User();
        doctorTwo.setId(UUID.randomUUID());
        actAs(doctorTwo);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Doctor A", staffId, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(summary.getIsFinalized()).isFalse();
        verify(dischargeSummaryRepository, never()).save(any());
    }

    @Test void qaD503_theProviderFinalizesWithoutSendingAProviderId() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any())).thenReturn(summary);
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);
        assertThat(service.finalizeDischargeSummary(summaryId, "Dr. Sig", null, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    private static void actAs(com.example.hms.model.User user) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
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

    @Test void qaD930_anotherDoctorCannotDeleteTheAuthorsDraft() {
        com.example.hms.model.User doctorTwo = new com.example.hms.model.User();
        doctorTwo.setId(UUID.randomUUID());
        actAs(doctorTwo);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        UUID doctorTwoStaffId = UUID.randomUUID();
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, doctorTwoStaffId))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(dischargeSummaryRepository, never()).delete(any(DischargeSummary.class));
    }

    @Test void qaD930_aSummaryWithNoDischargingProviderIsTheTreatingDoctors() {
        // Review of #971: a backfilled summary with no discharging provider is
        // edited by the doctor who wrote the visit's note.
        summary.setDischargingProvider(null);
        com.example.hms.model.User doctor = new com.example.hms.model.User();
        doctor.setId(UUID.randomUUID());
        actAs(doctor);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor.getId());
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(doctor.getId(), hospitalId)).thenReturn(true);
        when(encounterNoteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, doctor.getId())).thenReturn(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        service.deleteDischargeSummary(summaryId, null);
        verify(dischargeSummaryRepository).delete(summary);
    }

    @Test void qaD930_aConsultantsNoteDoesNotOpenAColleaguesDraft() {
        // Review of #971: the treating-clinician fallback never overrides a named provider.
        com.example.hms.model.User consultant = new com.example.hms.model.User();
        consultant.setId(UUID.randomUUID());
        // The draft's provider is a doctor.
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(staff.getUser().getId(), hospitalId)).thenReturn(true);
        actAs(consultant);
        org.mockito.Mockito.lenient().when(encounterNoteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, consultant.getId()))
            .thenReturn(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, null))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(dischargeSummaryRepository, never()).delete(any(DischargeSummary.class));
    }

    @Test void qaD930_aDraftHeldByTheNurseWhoOpenedTheVisitIsTheTreatingDoctors() {
        // Review of #971: a nurse-held draft is not stuck; the doctor who wrote the note finishes it.
        com.example.hms.model.User doctor = new com.example.hms.model.User();
        doctor.setId(UUID.randomUUID());
        actAs(doctor);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor.getId());
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(doctor.getId(), hospitalId)).thenReturn(true);
        when(encounterNoteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, doctor.getId())).thenReturn(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        service.deleteDischargeSummary(summaryId, null);
        verify(dischargeSummaryRepository).delete(summary);
    }

    @Test void qaD930_theTreatingDoctorWhoEditsANurseHeldDraftBecomesItsProviderAndCanFinalizeIt() {
        // Review of #971: editing a nurse-held draft hands it to the doctor, so
        // the finalize rule (discharging provider only) lets her sign it.
        com.example.hms.model.User doctor = new com.example.hms.model.User();
        doctor.setId(UUID.randomUUID());
        Staff doctorStaff = new Staff();
        doctorStaff.setId(UUID.randomUUID());
        doctorStaff.setUser(doctor);
        actAs(doctor);
        when(roleValidator.getCurrentUserId()).thenReturn(doctor.getId());
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(doctor.getId(), hospitalId)).thenReturn(true);
        when(encounterNoteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, doctor.getId())).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(doctor.getId(), hospitalId)).thenReturn(Optional.of(doctorStaff));
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any(DischargeSummary.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateDischargeSummary(summaryId, buildRequest(), Locale.ENGLISH);

        assertThat(summary.getDischargingProvider()).isEqualTo(doctorStaff);
    }

    @Test void qaD930_aNurseWhoWroteANoteCannotDeleteAColleaguesNurseHeldDraft() {
        // Review of #971: the treating-clinician fallback is for physicians only.
        com.example.hms.model.User nurseB = new com.example.hms.model.User();
        nurseB.setId(UUID.randomUUID());
        actAs(nurseB);
        when(roleValidator.getCurrentUserId()).thenReturn(nurseB.getId());
        org.mockito.Mockito.lenient().when(encounterNoteRepository.existsByEncounter_IdAndAuthor_Id(encounterId, nurseB.getId()))
            .thenReturn(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, null))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(dischargeSummaryRepository, never()).delete(any(DischargeSummary.class));
    }

    @Test void qaD930_theRowSaysWhetherTheCallerMayEditIt() {
        // Review of #971: the portal shows the buttons the server accepts.
        DischargeSummaryResponseDTO own = DischargeSummaryResponseDTO.builder().id(summaryId).build();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(own);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getEditableByCaller()).isTrue();

        com.example.hms.model.User doctorTwo = new com.example.hms.model.User();
        doctorTwo.setId(UUID.randomUUID());
        actAs(doctorTwo);
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(doctorTwo.getId());
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(staff.getUser().getId(), hospitalId)).thenReturn(true);
        DischargeSummaryResponseDTO theirs = DischargeSummaryResponseDTO.builder().id(summaryId).build();
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(theirs);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getEditableByCaller()).isFalse();
    }

    @Test void qaD930_anotherDoctorCannotRewriteTheAuthorsDraft() {
        com.example.hms.model.User doctorTwo = new com.example.hms.model.User();
        doctorTwo.setId(UUID.randomUUID());
        actAs(doctorTwo);
        DischargeSummaryRequestDTO req = buildRequest();
        req.setDischargeDiagnosis("Rewritten by another doctor");
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(summary.getDischargeDiagnosis()).isNotEqualTo("Rewritten by another doctor");
        verify(dischargeSummaryRepository, never()).save(any());
    }

    // ── enforceHospitalScope: cross-hospital → 404 ────────────────────

    @Test void getDischargeSummaryById_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.getDischargeSummaryById(summaryId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryById_sameHospital_success() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void updateDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void finalizeDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryByEncounter_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.getDischargeSummaryByEncounter(encounterId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void deleteDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Scoped list queries: activeHospitalId non-null ─────────────────

    @Test void getDischargeSummariesByPatient_scoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId));
        when(dischargeSummaryRepository.findByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(patientId, Set.of(hospitalId)))
            .thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByPatient(patientId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByProvider_scoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findByDischargingProvider_IdAndHospital_IdOrderByDischargeDateDesc(staffId, hospitalId))
            .thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByProvider(staffId, Locale.ENGLISH)).hasSize(1);
    }

    // ── effectiveHospitalId override: activeHospitalId overrides param ─

    @Test void getDischargeSummariesByHospitalAndDateRange_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findByHospitalAndDateRange(hospitalId, start, end)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        // passes otherHospitalId but activeHospitalId overrides it
        assertThat(service.getDischargeSummariesByHospitalAndDateRange(otherHospitalId, start, end, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getUnfinalizedDischargeSummaries_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findUnfinalizedByHospital(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getUnfinalizedDischargeSummaries(otherHospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesWithPendingResults_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findWithPendingTestResults(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesWithPendingResults(otherHospitalId, Locale.ENGLISH)).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // getDischargeSummariesForPortalPatient — root-cause coverage for the
    // 2026-04-11 incident (encounter COMPLETED yesterday but mobile AVS empty).
    // See docs/avs-mobile-user-stories.md (US-AVS-008, US-AVS-009).
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Portal-patient AVS retrieval — observability + backfill")
    class PortalPatientAvs {

        private double counterCount(String name, String outcome) {
            io.micrometer.core.instrument.Counter c = meterRegistry.find(name)
                    .tag(DischargeSummaryServiceImpl.TAG_OUTCOME, outcome)
                    .counter();
            return c == null ? 0.0d : c.count();
        }

        @Test
        @DisplayName("empty patient → counter tagged outcome=empty, no save calls")
        void portalFetch_empty_taggedEmpty() {
            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of());

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).isEmpty();
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_EMPTY)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isZero();
        }

        @Test
        @DisplayName("existing summary returned → counter tagged outcome=hit")
        void portalFetch_existingSummary_taggedHit() {
            // Prime the existing summary with hospitalCourse + a medication so the
            // enrichment branch does not fire and the outcome resolves to "hit".
            summary.setHospitalCourse("seen");
            summary.addMedicationReconciliation(
                    com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
                        .medicationName("Aspirin").dosage("81mg")
                        .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
                        .continueAtDischarge(true)
                        .build());

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).hasSize(1);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isZero();
        }

        @Test
        @DisplayName("QA D744: the patient AVS carries the signed note's instructions and plan")
        void qaD744_portalFetch_addsSignedNoteInstructionsAndPlan() {
            // Round 10 re-test (encounter 81b42d8d): the patient saw only the
            // checkout follow-up; the staff AVS also showed the instructions and plan.
            com.example.hms.model.encounter.EncounterNote note = new com.example.hms.model.encounter.EncounterNote();
            note.setPatientInstructions("Marcher 30 minutes par jour.");
            note.setPlan("Metformine 500 mg deux fois par jour.");
            note.setSignedAt(java.time.LocalDateTime.of(2026, 10, 24, 8, 0));
            encounter.setEncounterNote(note);
            summary.setEncounter(encounter);
            summary.setFollowUpInstructions("Revoir dans 3 mois avec le résultat HbA1c");
            summary.setHospitalCourse("seen");

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId)).thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);

            service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(summary.getFollowUpInstructions())
                    .contains("Revoir dans 3 mois avec le résultat HbA1c")
                    .contains("Marcher 30 minutes par jour.")
                    .contains("Metformine 500 mg deux fois par jour.");
            verify(dischargeSummaryRepository).save(summary);

            // A second read adds nothing twice.
            String once = summary.getFollowUpInstructions();
            service.getDischargeSummariesForPortalPatient(patientId);
            assertThat(summary.getFollowUpInstructions()).isEqualTo(once);
        }

        @Test
        @DisplayName("QA D744: an unsigned draft never reaches the patient AVS")
        void qaD744_portalFetch_ignoresUnsignedDraft() {
            com.example.hms.model.encounter.EncounterNote note = new com.example.hms.model.encounter.EncounterNote();
            note.setPatientInstructions("brouillon");
            encounter.setEncounterNote(note);
            summary.setEncounter(encounter);
            summary.setFollowUpInstructions("Revoir dans 3 mois");

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId)).thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));

            service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(summary.getFollowUpInstructions()).isEqualTo("Revoir dans 3 mois");
        }

        @Test
        @DisplayName("QA D151: a prescription's anchor consultation gets no after-visit summary, old or new")
        void portalFetch_prescriptionAnchor_isNeitherBackfilledNorShown() {
            Encounter anchor = new Encounter();
            anchor.setId(UUID.randomUUID());
            anchor.setPatient(patient);
            anchor.setHospital(hospital);
            anchor.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            anchor.setNotes(Encounter.PRESCRIPTION_ANCHOR_NOTE);
            DischargeSummary phantom = new DischargeSummary();
            phantom.setEncounter(anchor);
            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of(anchor));
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(phantom));

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).isEmpty();
            verify(dischargeSummaryRepository, never()).save(any());
        }

        @Test
        @DisplayName("QA D151: the missing-diagnosis placeholder is written in the patient's language")
        void portalFetch_missingDiagnosis_isLocalised() {
            Encounter orphan = new Encounter();
            orphan.setId(UUID.randomUUID());
            orphan.setPatient(patient);
            orphan.setHospital(hospital);
            orphan.setStaff(staff);
            orphan.setAssignment(assignment);
            orphan.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            orphan.setCheckoutTimestamp(java.time.LocalDateTime.now().minusDays(1));
            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of(orphan));
            when(patientLocaleResolver.resolve(any(), any())).thenReturn(java.util.Locale.FRENCH);
            when(messageSource.getMessage(eq("dischargeSummary.diagnosis.notSpecified"), any(), any(),
                    eq(java.util.Locale.FRENCH))).thenReturn("Diagnostic de sortie non précisé lors de la sortie.");
            when(dischargeSummaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of());

            service.getDischargeSummariesForPortalPatient(patientId);

            org.mockito.ArgumentCaptor<DischargeSummary> saved = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
            verify(dischargeSummaryRepository).save(saved.capture());
            assertThat(saved.getValue().getDischargeDiagnosis())
                    .isEqualTo("Diagnostic de sortie non précisé lors de la sortie.");
        }

        @Test
        @DisplayName("orphan COMPLETED encounter → backfilled, counter tagged backfilled")
        void portalFetch_orphanEncounter_backfilled() {
            // Simulate the 2026-04-11 incident: encounter COMPLETED with notes +
            // discharge_diagnoses + checkout_timestamp but no DischargeSummary row.
            Encounter orphan = new Encounter();
            orphan.setId(UUID.randomUUID());
            orphan.setPatient(patient);
            orphan.setHospital(hospital);
            orphan.setStaff(staff);
            orphan.setAssignment(assignment);
            orphan.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            orphan.setCheckoutTimestamp(java.time.LocalDateTime.now().minusDays(1));
            orphan.setNotes("testing updates");
            orphan.setDischargeDiagnoses("[\"Patient is suffering from testing workflow\"]");
            orphan.setFollowUpInstructions("return to clinic after seeing the Cardiologist in 2 weeks");

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of(orphan));
            when(dischargeSummaryRepository.save(any())).thenAnswer(inv -> {
                DischargeSummary s = inv.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).hasSize(1);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_BACKFILL_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("calling twice with no orphans is idempotent — no duplicate writes")
        void portalFetch_idempotent_noDuplicateWrites() {
            summary.setHospitalCourse("already enriched");
            summary.addMedicationReconciliation(
                    com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
                        .medicationName("Aspirin").dosage("81mg")
                        .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
                        .continueAtDischarge(true)
                        .build());

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);

            service.getDischargeSummariesForPortalPatient(patientId);
            service.getDischargeSummariesForPortalPatient(patientId);

            // No saves at all — the existing summary is already enriched.
            verify(dischargeSummaryRepository, org.mockito.Mockito.never()).save(any());
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isEqualTo(2.0d);
        }

        @Test
        @DisplayName("QA D152: the medication reconciliation leaves out DRAFT, unsigned and withdrawn orders")
        void portalFetch_reconciliation_leavesOutDraftUnsignedAndWithdrawn() {
            summary.setHospitalCourse("already enriched");
            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId)).thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            List<com.example.hms.model.Prescription> rxs = new java.util.ArrayList<>();
            for (com.example.hms.enums.PrescriptionStatus st : List.of(
                    com.example.hms.enums.PrescriptionStatus.DRAFT,
                    com.example.hms.enums.PrescriptionStatus.PENDING_SIGNATURE,
                    com.example.hms.enums.PrescriptionStatus.CANCELLED,
                    com.example.hms.enums.PrescriptionStatus.DISCONTINUED,
                    com.example.hms.enums.PrescriptionStatus.SIGNED)) {
                com.example.hms.model.Prescription rx = new com.example.hms.model.Prescription();
                rx.setMedicationName(st.name());
                rx.setStatus(st);
                rxs.add(rx);
            }
            when(prescriptionRepository.findByEncounter_Id(eq(encounterId), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(rxs));

            service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(summary.getMedicationReconciliation())
                    .extracting(com.example.hms.model.discharge.MedicationReconciliationEntry::getMedicationName)
                    .containsExactly("SIGNED");
        }

        /**
         * Concurrency contract for the portal-side backfill.
         *
         * <p>Two concurrent {@code /me/patient/after-visit-summaries} calls can
         * both observe the same orphan COMPLETED encounter. One wins the
         * insert; the other's {@code save()} surfaces as a
         * {@link org.springframework.dao.DataIntegrityViolationException} thanks
         * to the unique constraint on {@code discharge_summaries.encounter_id}
         * added in V92. The service must catch that exception, log a friendly
         * message, and let the GET endpoint return 200 with the row that
         * landed first. (Copilot review on PR #259.)
         */
        @Test
        @DisplayName("backfill is race-safe — DataIntegrityViolationException treated as no-op")
        void portalFetch_concurrentInsertRace_isHandled() {
            Encounter orphan = new Encounter();
            orphan.setId(UUID.randomUUID());
            orphan.setPatient(patient);
            orphan.setHospital(hospital);
            orphan.setStaff(staff);
            orphan.setAssignment(assignment);
            orphan.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            orphan.setCheckoutTimestamp(java.time.LocalDateTime.now().minusDays(1));

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of(orphan));
            when(dischargeSummaryRepository.save(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate key value violates unique constraint "
                                + "\"uk_discharge_summaries_encounter_id\""));
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).hasSize(1);
            verify(dischargeSummaryRepository, org.mockito.Mockito.times(1)).save(any());
        }
    }

    @Test void getDischargeSummariesByPatient_followThePatientAndAccountTheReach() {
        // E9 #59e — the discharge summary written at Hôpital B is on the list at
        // Hôpital A when the policy reads B for this patient; accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital(); other.setId(otherHospitalId);
        DischargeSummary foreign = DischargeSummary.builder().patient(patient).hospital(other).build();
        foreign.setId(UUID.randomUUID());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId, otherHospitalId));
        when(dischargeSummaryRepository.findByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(patientId, Set.of(hospitalId, otherHospitalId)))
            .thenReturn(List.of(summary, foreign));
        when(dischargeSummaryMapper.toResponseDTO(any(DischargeSummary.class))).thenReturn(responseDTO);

        assertThat(service.getDischargeSummariesByPatient(patientId, Locale.ENGLISH)).hasSize(2);
        verify(dischargeSummaryRepository, never()).findByPatient_IdOrderByDischargeDateDesc(any());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }
}
