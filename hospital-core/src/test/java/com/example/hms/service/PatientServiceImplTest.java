package com.example.hms.service;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.mapper.NursingNoteMapper;
import com.example.hms.mapper.PatientAllergyMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.mapper.PatientProblemMapper;
import com.example.hms.mapper.PatientSurgicalHistoryMapper;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.NursingNote;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientSurgicalHistory;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AdvanceDirectiveResponseDTO;
import com.example.hms.payload.dto.DoctorPatientRecordDTO;
import com.example.hms.payload.dto.DoctorPatientRecordRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientSearchCriteria;
import com.example.hms.payload.dto.PatientTimelineAccessRequestDTO;
import com.example.hms.payload.dto.PatientTimelineResponseDTO;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundOrderResponseDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportResponseDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.NursingNoteRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemHistoryRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientSurgicalHistoryRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PatientMapper patientMapper;
    @Mock
    private MessageSource messageSource;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private PatientInsuranceService patientInsuranceService;
    @Mock
    private PatientVitalSignService patientVitalSignService;
    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private PatientAllergyRepository patientAllergyRepository;
    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private AuditEventLogService auditEventLogService;
    @Mock
    private PatientAllergyMapper patientAllergyMapper;
    @Mock
    private PrescriptionMapper prescriptionMapper;
    @Mock
    private LabResultMapper labResultMapper;
    @Mock
    private PatientProblemRepository patientProblemRepository;
    @Mock
    private PatientProblemHistoryRepository patientProblemHistoryRepository;
    @Mock
    private PatientProblemMapper patientProblemMapper;
    @Mock
    private PatientSurgicalHistoryRepository patientSurgicalHistoryRepository;
    @Mock
    private PatientSurgicalHistoryMapper patientSurgicalHistoryMapper;
    @Mock
    private AdvanceDirectiveRepository advanceDirectiveRepository;
    @Mock
    private AdvanceDirectiveMapper advanceDirectiveMapper;
    @Mock
    private UltrasoundOrderRepository ultrasoundOrderRepository;
    @Mock
    private UltrasoundReportRepository ultrasoundReportRepository;
    @Mock
    private UltrasoundMapper ultrasoundMapper;
    @Mock
    private NursingNoteRepository nursingNoteRepository;
    @Mock
    private NursingNoteMapper nursingNoteMapper;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PatientServiceImpl patientService;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new java.util.HashSet<>());
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setPatientRegistrations(new java.util.HashSet<>());
        hospital.setCode("HSP");
        hospital.setName("General Hospital");
    }

    @Test
    void getPatientByIdReturnsDtoWhenRegistered() {
        PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        PatientResponseDTO result = patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(patientRepository).findById(patientId);
        verify(registrationRepository).isPatientRegisteredInHospitalFixed(patientId, hospitalId);
        verify(patientMapper).toPatientDTO(patient, hospitalId);
    }

    @Test
    void getPatientByIdThrowsWhenNotRegistered() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not registered");
    }

    @Test
    void createPatientCreatesNewPatientAndInsurance() {
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder()
            .userId(userId)
            .hospitalId(hospitalId)
            .insurance(PatientInsuranceRequestDTO.builder().providerName("Aetna").policyNumber("123").build())
            .build();

        User user = new User();
        user.setId(userId);

        Patient savedPatient = new Patient();
        savedPatient.setId(patientId);
    savedPatient.setHospitalRegistrations(new java.util.HashSet<>());

        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setPatient(savedPatient);
        registration.setHospital(hospital);
        registration.setMrn("HSP0001");

        PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(patientMapper.toPatient(request, user)).thenReturn(savedPatient);
        when(patientRepository.save(savedPatient)).thenReturn(savedPatient);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(registrationRepository.existsByMrnAndHospitalId(anyString(), eq(hospitalId))).thenReturn(false);
        when(registrationRepository.save(any(PatientHospitalRegistration.class))).thenReturn(registration);
        when(patientMapper.toPatientDTO(savedPatient, hospitalId)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        PatientResponseDTO result = patientService.createPatient(request, Locale.ENGLISH);

        assertThat(result).isEqualTo(responseDTO);
        verify(patientRepository).save(savedPatient);
        verify(patientInsuranceService).addInsuranceToPatient(argThat(dto -> patientId.equals(dto.getPatientId())), eq(Locale.ENGLISH));
    }

    @Test
    void deletePatientThrowsWhenNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("not found");

        assertThatThrownBy(() -> patientService.deletePatient(patientId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not found");

        verify(patientRepository, never()).deleteById(any());
    }

    @Test
    void searchPatientsBuildsPatternsAndReturnsMappedPage() {
        PatientSearchCriteria criteria = PatientSearchCriteria.builder()
            .mrn("  MRN123  ")
            .name("  Alice  ")
            .dateOfBirth(" 1990-01-01 ")
            .phone(" 555 ")
            .email(" Alice@example.com ")
            .hospitalId(hospitalId)
            .build();

        Pageable pageable = PageRequest.of(0, 10);
        PatientResponseDTO dto = PatientResponseDTO.builder().id(patientId).build();
        Page<Patient> patientPage = new PageImpl<>(List.of(patient), pageable, 1);

        when(patientRepository.searchPatientsExtended(anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean(), any(Pageable.class)))
            .thenReturn(patientPage);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(dto);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        List<PatientResponseDTO> results = patientService.searchPatients(criteria, 0, 10, Locale.ENGLISH);

        assertThat(results).containsExactly(dto);

        ArgumentCaptor<String> mrnCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dobCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phoneCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> activeCaptor = ArgumentCaptor.forClass(Boolean.class);

        verify(patientRepository).searchPatientsExtended(
            mrnCaptor.capture(),
            nameCaptor.capture(),
            dobCaptor.capture(),
            phoneCaptor.capture(),
            emailCaptor.capture(),
            eq(hospitalId),
            activeCaptor.capture(),
            any(Pageable.class)
        );

        assertThat(mrnCaptor.getValue()).isEqualTo("MRN123");
        assertThat(nameCaptor.getValue()).isEqualTo("%alice%");
        assertThat(dobCaptor.getValue()).isEqualTo("1990-01-01");
        assertThat(phoneCaptor.getValue()).isEqualTo("%555%");
        assertThat(emailCaptor.getValue()).isEqualTo("%alice@example.com%");
        assertThat(activeCaptor.getValue()).isTrue();
    }

    @Test
    void createPatientByStaffRequiresHospitalId() {
        PatientRequestDTO request = PatientRequestDTO.builder().userId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> patientService.createPatientByStaff(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital must be resolved");
    }

    @Test
    void createPatientDiagnosisPersistsProblemAndHistory() throws Exception {
        UUID requesterUserId = UUID.randomUUID();
        PatientDiagnosisRequestDTO request = PatientDiagnosisRequestDTO.builder()
            .hospitalId(hospitalId)
            .problemDisplay("Hypertension")
            .problemCode("I10")
            .status(ProblemStatus.ACTIVE)
            .build();

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setName("Dr. Carter");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(patientProblemRepository.save(any(PatientProblem.class))).thenAnswer(invocation -> {
            PatientProblem value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            return value;
        });
        PatientProblemResponseDTO responseDTO = PatientProblemResponseDTO.builder().id(UUID.randomUUID()).build();
        when(patientProblemMapper.toResponseDto(any(PatientProblem.class))).thenReturn(responseDTO);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"snapshot\":true}");

        PatientProblemResponseDTO response = patientService
            .createPatientDiagnosis(patientId, hospitalId, requesterUserId, request);

        assertThat(response).isEqualTo(responseDTO);
        verify(patientProblemRepository).save(any(PatientProblem.class));
        verify(patientProblemHistoryRepository).save(any());
    }

    @Test
    void createPatientDiagnosisAllowsNonIcdCodeWhenVersionNotStrict() throws Exception {
        UUID requesterUserId = UUID.randomUUID();
        PatientDiagnosisRequestDTO request = PatientDiagnosisRequestDTO.builder()
            .hospitalId(hospitalId)
            .problemDisplay("Localized issue")
            .problemCode("Nom affiché code")
            .icdVersion("Code diagnostic Version CIM")
            .build();

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));

        ArgumentCaptor<PatientProblem> problemCaptor = ArgumentCaptor.forClass(PatientProblem.class);
        when(patientProblemRepository.save(problemCaptor.capture())).thenAnswer(invocation -> {
            PatientProblem value = problemCaptor.getValue();
            value.setId(UUID.randomUUID());
            return value;
        });
        when(patientProblemMapper.toResponseDto(any(PatientProblem.class))).thenReturn(PatientProblemResponseDTO.builder().build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"snapshot\":true}");

        patientService.createPatientDiagnosis(patientId, hospitalId, requesterUserId, request);

        PatientProblem saved = problemCaptor.getValue();
        assertThat(saved.getProblemCode()).isEqualTo("NOM AFFICHÉ CODE");
    }

    @Test
    void createPatientDiagnosisEnforcesIcdPatternWhenVersionStrict() {
        UUID requesterUserId = UUID.randomUUID();
        PatientDiagnosisRequestDTO request = PatientDiagnosisRequestDTO.builder()
            .hospitalId(hospitalId)
            .problemDisplay("Hypertension")
            .problemCode("Invalid code")
            .icdVersion("ICD-10")
            .build();

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> patientService.createPatientDiagnosis(patientId, hospitalId, requesterUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ICD-10");

        verify(patientProblemRepository, never()).save(any());
    }

    @Test
    void updatePatientDiagnosisRequiresReasonForStatusChange() throws Exception {
        UUID requesterUserId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        PatientProblem problem = new PatientProblem();
        problem.setId(diagnosisId);
        problem.setPatient(patient);
        problem.setHospital(hospital);
        problem.setStatus(ProblemStatus.ACTIVE);

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());

        when(patientProblemRepository.findById(diagnosisId)).thenReturn(Optional.of(problem));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(patientProblemMapper.toResponseDto(problem)).thenReturn(PatientProblemResponseDTO.builder().build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        PatientDiagnosisUpdateRequestDTO request = PatientDiagnosisUpdateRequestDTO.builder()
            .hospitalId(hospitalId)
            .status(ProblemStatus.RESOLVED)
            .build();

        assertThatThrownBy(() -> patientService.updatePatientDiagnosis(
            patientId,
            hospitalId,
            diagnosisId,
            requesterUserId,
            request
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");

        verify(patientProblemRepository, never()).save(any());
    }

    @Test
    void deletePatientDiagnosisRequiresReason() {
        UUID requesterUserId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();

        assertThatThrownBy(() -> patientService.deletePatientDiagnosis(
            patientId,
            hospitalId,
            diagnosisId,
            requesterUserId,
            "  "
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("justification");
    }

    @Test
    void getDoctorTimelineAggregatesClinicalEventsAndLogsAudit() {
        User doctorUser = new User();
        UUID doctorId = UUID.randomUUID();
        doctorUser.setId(doctorId);
        doctorUser.setFirstName("Meredith");
        doctorUser.setLastName("Grey");
        doctorUser.setUsername("mgrey");

        Role doctorRole = new Role();
        doctorRole.setName("Doctor");
        doctorRole.setCode("ROLE_DOCTOR");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setUser(doctorUser);
        assignment.setHospital(hospital);
        assignment.setRole(doctorRole);

        Staff staff = Staff.builder()
            .user(doctorUser)
            .hospital(hospital)
            .assignment(assignment)
            .build();

        Encounter encounter = Encounter.builder()
            .patient(patient)
            .hospital(hospital)
            .staff(staff)
            .assignment(assignment)
            .encounterDate(LocalDateTime.now().minusDays(1))
            .encounterType(EncounterType.CONSULTATION)
            .notes("Psych consult")
            .build();
        encounter.setId(UUID.randomUUID());

    Prescription prescription = new Prescription();
    prescription.setId(UUID.randomUUID());
        prescription.setPatient(patient);
        prescription.setHospital(hospital);
        prescription.setStaff(staff);
        prescription.setAssignment(assignment);
        prescription.setEncounter(encounter);
        prescription.setMedicationName("Fentanyl Patch");
        prescription.setDosage("25mcg");
        prescription.setFrequency("q72h");
        prescription.setCreatedAt(LocalDateTime.now().minusHours(12));
        prescription.setUpdatedAt(LocalDateTime.now().minusHours(12));

        LabOrder labOrder = LabOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .clinicalIndication("HIV Screening")
            .build();
        labOrder.setId(UUID.randomUUID());

        LabResult labResult = LabResult.builder()
            .labOrder(labOrder)
            .resultValue("Reactive")
            .resultUnit("IgG")
            .resultDate(LocalDateTime.now().minusHours(6))
            .notes("Requires confirmatory western blot")
            .build();
        labResult.setId(UUID.randomUUID());

        PatientAllergy allergy = PatientAllergy.builder()
            .patient(patient)
            .hospital(hospital)
            .allergenDisplay("Peanuts")
            .severity(AllergySeverity.LIFE_THREATENING)
            .recordedDate(LocalDate.now().minusYears(1))
            .build();
        allergy.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(encounter));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(prescription));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(allergy));
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        PatientTimelineAccessRequestDTO request = PatientTimelineAccessRequestDTO.builder()
            .accessReason("Care coordination for pre-op")
            .includeSensitiveData(true)
            .build();

        PatientTimelineResponseDTO response = patientService.getDoctorTimeline(
            patientId,
            hospitalId,
            doctorId,
            assignment,
            request
        );

        assertThat(response.getEntries()).hasSize(4);
        assertThat(response.getPatientId()).isEqualTo(patientId);
        assertThat(response.getHospitalId()).isEqualTo(hospitalId);
        assertThat(response.isContainsSensitiveData()).isTrue();
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void getDoctorRecordAggregatesSectionsAndLogsAudit() {
        UUID doctorId = UUID.randomUUID();
        User doctor = new User();
        doctor.setId(doctorId);
        doctor.setFirstName("Cristina");
        doctor.setLastName("Yang");

        Role role = new Role();
        role.setCode("ROLE_DOCTOR");
        role.setName("Doctor");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setUser(doctor);
        assignment.setHospital(hospital);
        assignment.setRole(role);

        PatientResponseDTO patientDto = PatientResponseDTO.builder().id(patientId).build();

        PatientAllergy allergy = PatientAllergy.builder()
            .patient(patient)
            .hospital(hospital)
            .severity(AllergySeverity.LIFE_THREATENING)
            .allergenDisplay("Peanuts")
            .build();
        allergy.setId(UUID.randomUUID());
        PatientAllergyResponseDTO allergyResponse = PatientAllergyResponseDTO.builder().id(allergy.getId()).build();

        Prescription prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setPatient(patient);
        prescription.setHospital(hospital);
        prescription.setMedicationName("Fentanyl");
        prescription.setCreatedAt(LocalDateTime.now().minusHours(2));
        prescription.setUpdatedAt(LocalDateTime.now().minusHours(1));
        PrescriptionResponseDTO prescriptionResponse = PrescriptionResponseDTO.builder().id(prescription.getId()).build();

        LabOrder labOrder = LabOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .clinicalIndication("HIV screening")
            .build();
        labOrder.setId(UUID.randomUUID());
        LabResult labResult = LabResult.builder()
            .labOrder(labOrder)
            .resultValue("Reactive")
            .resultUnit("IgG")
            .resultDate(LocalDateTime.now().minusHours(1))
            .notes("HIV confirmatory test required")
            .build();
        labResult.setId(UUID.randomUUID());
        LabResultResponseDTO labResultResponse = LabResultResponseDTO.builder().id(labResult.getId().toString()).build();

        UltrasoundOrder ultrasoundOrder = new UltrasoundOrder();
        ultrasoundOrder.setId(UUID.randomUUID());
        ultrasoundOrder.setPatient(patient);
        ultrasoundOrder.setHospital(hospital);
        ultrasoundOrder.setOrderedDate(LocalDateTime.now().minusDays(1));
        ultrasoundOrder.setIsHighRiskPregnancy(true);
        UltrasoundOrderResponseDTO orderResponse = UltrasoundOrderResponseDTO.builder().id(ultrasoundOrder.getId()).build();

        UltrasoundReport ultrasoundReport = new UltrasoundReport();
        ultrasoundReport.setId(UUID.randomUUID());
        ultrasoundReport.setUltrasoundOrder(ultrasoundOrder);
        ultrasoundReport.setHospital(hospital);
        ultrasoundReport.setScanDate(LocalDate.now());
        ultrasoundReport.setAnomaliesDetected(true);
        UltrasoundReportResponseDTO reportResponse = UltrasoundReportResponseDTO.builder().id(ultrasoundReport.getId()).build();

        NursingNote note = new NursingNote();
        note.setPatient(patient);
        note.setHospital(hospital);
        note.setNarrative("Assault recovery plan");
        NursingNoteResponseDTO noteResponse = NursingNoteResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientProblem problem = PatientProblem.builder()
            .patient(patient)
            .hospital(hospital)
            .problemDisplay("Oncology follow-up")
            .build();
        problem.setId(UUID.randomUUID());
        problem.setOnsetDate(LocalDate.now().minusWeeks(2));
        problem.setLastReviewedAt(LocalDateTime.now());
        PatientProblemResponseDTO problemResponse = PatientProblemResponseDTO.builder().id(problem.getId()).build();

        PatientSurgicalHistory surgicalHistory = PatientSurgicalHistory.builder()
            .patient(patient)
            .hospital(hospital)
            .procedureDisplay("Oncology surgery")
            .build();
        surgicalHistory.setId(UUID.randomUUID());
        surgicalHistory.setProcedureDate(LocalDate.now().minusMonths(1));
        surgicalHistory.setLastUpdatedAt(LocalDateTime.now());
        PatientSurgicalHistoryResponseDTO surgicalResponse = PatientSurgicalHistoryResponseDTO.builder().id(surgicalHistory.getId()).build();

        AdvanceDirective directive = AdvanceDirective.builder()
            .patient(patient)
            .hospital(hospital)
            .description("Psychiatry directive")
            .build();
        directive.setId(UUID.randomUUID());
        directive.setEffectiveDate(LocalDate.now().minusYears(1));
        AdvanceDirectiveResponseDTO directiveResponse = AdvanceDirectiveResponseDTO.builder().id(directive.getId()).build();

        Encounter recentEncounter = new Encounter();
        recentEncounter.setId(UUID.randomUUID());
        recentEncounter.setPatient(patient);
        recentEncounter.setHospital(hospital);
        recentEncounter.setEncounterDate(LocalDateTime.now().minusDays(2));
        recentEncounter.setEncounterType(EncounterType.CONSULTATION);
        recentEncounter.setNotes("Trauma counseling follow-up");

        DoctorPatientRecordRequestDTO request = DoctorPatientRecordRequestDTO.builder()
            .hospitalId(hospitalId)
            .accessReason("Pre-op review")
            .includeSensitiveData(true)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(patientDto);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());
        when(patientRepository.findMrnForHospital(patientId, hospitalId)).thenReturn(Optional.of("MRN001"));
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(allergy));
        when(patientAllergyMapper.toResponseDto(allergy)).thenReturn(allergyResponse);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(prescriptionResponse);
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(labResultResponse);
        when(ultrasoundOrderRepository.findAllByPatientId(patientId)).thenReturn(List.of(ultrasoundOrder));
        when(ultrasoundMapper.toOrderResponseDTO(ultrasoundOrder)).thenReturn(orderResponse);
        when(ultrasoundReportRepository.findAllByPatientId(patientId)).thenReturn(List.of(ultrasoundReport));
        when(ultrasoundMapper.toReportResponseDTO(ultrasoundReport)).thenReturn(reportResponse);
        when(nursingNoteRepository.findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(patientId, hospitalId)).thenReturn(List.of(note));
        when(nursingNoteMapper.toResponse(note)).thenReturn(noteResponse);
        when(patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(problem));
        when(patientProblemMapper.toResponseDto(problem)).thenReturn(problemResponse);
        when(patientSurgicalHistoryRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(surgicalHistory));
        when(patientSurgicalHistoryMapper.toResponseDto(surgicalHistory)).thenReturn(surgicalResponse);
        when(advanceDirectiveRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(directive));
        when(advanceDirectiveMapper.toResponseDto(directive)).thenReturn(directiveResponse);
    when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(recentEncounter));
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        DoctorPatientRecordDTO response = patientService.getDoctorRecord(
            patientId,
            hospitalId,
            doctorId,
            assignment,
            request
        );

        assertThat(response.getPatientId()).isEqualTo(patientId);
        assertThat(response.getHospitalId()).isEqualTo(hospitalId);
        assertThat(response.getPatient()).isSameAs(patientDto);
        assertThat(response.getHospitalMrn()).isEqualTo("MRN001");
        assertThat(response.getAllergies()).containsExactly(allergyResponse);
        assertThat(response.getMedications()).containsExactly(prescriptionResponse);
        assertThat(response.getLabResults()).containsExactly(labResultResponse);
        assertThat(response.getImagingOrders()).containsExactly(orderResponse);
        assertThat(response.getImagingReports()).containsExactly(reportResponse);
        assertThat(response.getNotes()).containsExactly(noteResponse);
        assertThat(response.getRecentEncounters()).hasSize(1);
        assertThat(response.getRecentEncounters().get(0).getCategory()).isEqualTo("ENCOUNTER");
        assertThat(response.getProblems()).containsExactly(problemResponse);
        assertThat(response.getSurgicalHistory()).containsExactly(surgicalResponse);
        assertThat(response.getAdvanceDirectives()).containsExactly(directiveResponse);
        assertThat(response.isContainsSensitiveData()).isTrue();
        assertThat(response.getSensitiveSections()).containsExactly(
            "ALLERGIES",
            "MEDICATIONS",
            "LABS",
            "IMAGING",
            "NOTES",
            "MEDICAL_HISTORY",
            "ENCOUNTERS"
        );
        verify(auditEventLogService).logEvent(any());
    }
}
