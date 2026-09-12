package com.example.hms.service;

import static org.mockito.ArgumentMatchers.isNull;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.InOrder;
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
import com.example.hms.payload.dto.PatientTimelineEntryDTO;
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
import java.util.Set;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock
    private PatientRepository patientRepository;

    /** Needed the moment a test actually reaches deletePatient: it is a
     *  constructor dependency, so without this @InjectMocks passes null. */
    @Mock
    private com.example.hms.repository.PatientProxyRepository patientProxyRepository;
    @Mock
    private com.example.hms.repository.PatientAddressHistoryRepository addressHistoryRepository;
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
    @Mock
    private com.example.hms.utility.RoleValidator roleValidator;
    @Mock
    private PhoneVerificationService phoneVerificationService;

    /** E8 #49/#51 — constructor deps; without these @InjectMocks passes null
     *  and every timeline test NPEs. Defaults below keep pre-E8 behaviour. */
    @Mock
    private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;

    @Mock
    private com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;

    /** E9 #59 — the reach ledger is a component now. */
    @Mock
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.BreakGlassGate breakGlassGate;

    /** E9 #56 — constructor deps for the one-allergy-store change. */
    @Mock
    private com.example.hms.service.allergy.LegacyAllergyTextImporter legacyAllergyTextImporter;

    @Mock
    private com.example.hms.service.allergy.PatientAllergySummarySync allergySummarySync;

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

        // Pre-E8 behaviour by default: only the acting hospital is readable and
        // nothing is categorised, so no row is foreign and none is withheld.
        // Tests that exercise the widening override these.
        lenient().when(recordAccessPolicy.readableHospitalIds(any(), any(), any()))
            .thenAnswer(inv -> java.util.Set.of(inv.getArgument(2, UUID.class)));
        lenient().when(sensitivityClassifier.effectiveCategory(any(com.example.hms.model.Encounter.class)))
            .thenReturn(null);

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

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        PatientResponseDTO result = patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(patientRepository).findByIdUnscoped(patientId);
        verify(registrationRepository).existsByPatientIdAndHospitalId(patientId, hospitalId);
        verify(patientMapper).toPatientDTO(patient, hospitalId);
    }

    @Test
    void getPatientByIdThrowsWhenNotRegistered() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        // SECURITY FIX: Now returns 404 (ResourceNotFoundException) instead of IllegalStateException
        // to prevent information leakage about patients in other hospitals
        assertThatThrownBy(() -> patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ getPatientByIdUnscoped ═══════════════

    @Test
    void getPatientByIdUnscopedReturnsDtoWithoutScopeCheck() {
        PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).build();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(patientMapper.toPatientDTO(patient, null)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, null)).thenReturn(Optional.empty());

        PatientResponseDTO result = patientService.getPatientByIdUnscoped(patientId, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(patientRepository).findByIdUnscoped(patientId);
        verify(registrationRepository, never()).existsByPatientIdAndHospitalId(any(), any());
    }

    @Test
    void getPatientByIdUnscopedThrowsWhenNotFound() {
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());
        when(patientRepository.findByUserId(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.getPatientByIdUnscoped(patientId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
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
    void createPatientByStaffStampsPhoneVerifiedWhenChallengeConsumes() {
        UUID userId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder()
            .userId(userId)
            .hospitalId(hospitalId)
            .phoneVerificationId(challengeId)
            .build();

        User user = new User();
        user.setId(userId);
        user.setActive(true);

        patient.setPhoneNumberPrimary("+22670707070");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
        when(phoneVerificationService.consumeVerifiedChallenge(challengeId, "+22670707070")).thenReturn(true);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(patientMapper.toPatientDTO(patient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatientByStaff(request, Locale.ENGLISH);

        assertThat(patient.getPhoneVerifiedAt()).isNotNull();
        verify(patientRepository).save(patient);
    }

    @Test
    void createPatientByStaffLeavesPhoneUnverifiedWhenChallengeRejected() {
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder()
            .userId(userId)
            .hospitalId(hospitalId)
            .phoneVerificationId(UUID.randomUUID())
            .build();

        User user = new User();
        user.setId(userId);
        user.setActive(true);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));
        when(phoneVerificationService.consumeVerifiedChallenge(any(), any())).thenReturn(false);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(patientMapper.toPatientDTO(patient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatientByStaff(request, Locale.ENGLISH);

        assertThat(patient.getPhoneVerifiedAt()).isNull();
        verify(patientRepository, never()).save(patient);
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
    void deletePatientFlushesSoTheForeignKeyAnswersInsideTheMethod() {
        // deleteById only queues the removal. Without the flush the DELETE
        // reaches the database at commit, after this method has returned, and
        // V156's foreign keys surface as an unhandled integrity error on the
        // way out instead of a 409. This test is the only thing pinning that
        // flush in place.
        when(patientRepository.existsById(patientId)).thenReturn(true);

        patientService.deletePatient(patientId, Locale.ENGLISH);

        InOrder order = inOrder(patientProxyRepository, patientRepository);
        order.verify(patientProxyRepository).deleteByGrantorPatient_Id(patientId);
        order.verify(patientRepository).deleteById(patientId);
        order.verify(patientRepository).flush();
    }

    @Test
    void deletePatientRefusesWhenTheChartWouldBeOrphaned() {
        // The dev log on 2026-09-07 showed three consultations and one
        // admission left pointing at a deleted patient, still holding PHI with
        // no identity attached. V156 constrains those tables with RESTRICT;
        // this turns the resulting integrity error into an actionable 409
        // instead of letting it escape as a 500.
        when(patientRepository.existsById(patientId)).thenReturn(true);
        doThrow(new DataIntegrityViolationException("fk_consultations_patient"))
            .when(patientRepository).flush();
        when(messageSource.getMessage(eq("patient.delete.hasclinicalrecords"), any(), any()))
            .thenReturn("Patient has clinical records and cannot be deleted.");

        assertThatThrownBy(() -> patientService.deletePatient(patientId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("cannot be deleted");
    }

    @Test
    void deletePatientRefusalUsesTheMessageKeyNotResolvedProse() {
        // MessageUtil-style resolution happens in the bundle, so the key must
        // reach messageSource verbatim; handing it an already-translated
        // sentence is how a response body ends up reading
        // "[Missing translation] ...".
        when(patientRepository.existsById(patientId)).thenReturn(true);
        doThrow(new DataIntegrityViolationException("fk_admissions_patient"))
            .when(patientRepository).flush();
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("refused");

        assertThatThrownBy(() -> patientService.deletePatient(patientId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);

        verify(messageSource).getMessage(
            eq("patient.delete.hasclinicalrecords"), any(), eq(Locale.ENGLISH));
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

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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

        User orderingUser = new User();
        orderingUser.setId(UUID.randomUUID());
        orderingUser.setFirstName("Miranda");
        orderingUser.setLastName("Bailey");
        Staff orderingStaff = Staff.builder()
            .user(orderingUser)
            .hospital(hospital)
            .assignment(assignment)
            .build();

        LabOrder labOrder = LabOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .orderingStaff(orderingStaff)
            .clinicalIndication("HIV Screening")
            .build();
        labOrder.setId(UUID.randomUUID());

        LabResult labResult = LabResult.builder()
            .labOrder(labOrder)
            // Not a person. This is what the chart rendered under
            // "who treated the patient" before this change.
            .releasedByDisplay("Autoverification")
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

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(encounter));
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(prescription));
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(allergy));
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

        // E8 #50 — the chart renders hospital + clinician + date + what on every
        // row, so the prescriber has to reach the wire. Asserting the value and
        // not merely the key: a null clinician still satisfies containsKey, and
        // that is exactly the regression this guards.
        assertThat(response.getEntries())
            .hasSize(4)
            .filteredOn(entry -> "PRESCRIPTION".equals(entry.getCategory()))
            .singleElement()
            .extracting(entry -> entry.getMetadata().get("clinician"))
            .isEqualTo("Meredith Grey");
        // The lab row carries the ORDERING clinician, never releasedByDisplay:
        // that column holds "Autoverification" for auto-verified results and can
        // hold a bare email address, and #582 ships this row to other hospitals.
        assertThat(response.getEntries())
            .filteredOn(entry -> "LAB_RESULT".equals(entry.getCategory()))
            .singleElement()
            .satisfies(entry -> assertThat(entry.getMetadata())
                .containsEntry("clinician", "Miranda Bailey")
                .doesNotContainKey("releasedBy")
                .doesNotContainValue("Autoverification"));
        assertThat(response.getPatientId()).isEqualTo(patientId);
        assertThat(response.getHospitalId()).isEqualTo(hospitalId);
        assertThat(response.isContainsSensitiveData()).isTrue();
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void getDoctorTimelineReportsTheRowsItWithheld() {
        // E9 #64 — a foreign encounter in a sensitive department is withheld
        // (D3) AND counted, so the chart can render "Dossier restreint
        // (hôpital, département, n)" instead of a gap; an untagged foreign
        // encounter travels and is not counted.
        UUID doctorId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);

        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName("CHU Yalgado");
        com.example.hms.model.Department psychiatry = new com.example.hms.model.Department();
        psychiatry.setName("Psychiatrie");

        Encounter withheld = Encounter.builder()
            .patient(patient)
            .hospital(other)
            .department(psychiatry)
            .encounterDate(LocalDateTime.now().minusDays(2))
            .encounterType(EncounterType.CONSULTATION)
            .build();
        withheld.setId(UUID.randomUUID());
        Encounter travelling = Encounter.builder()
            .patient(patient)
            .hospital(other)
            .encounterDate(LocalDateTime.now().minusDays(1))
            .encounterType(EncounterType.CONSULTATION)
            .build();
        travelling.setId(UUID.randomUUID());

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(recordAccessPolicy.readableHospitalIds(doctorId, patientId, hospitalId))
            .thenReturn(Set.of(hospitalId, other.getId()));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(withheld, travelling));
        when(sensitivityClassifier.effectiveCategory(withheld))
            .thenReturn(com.example.hms.enums.SensitivityCategory.BEHAVIOURAL_HEALTH);
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        PatientTimelineResponseDTO response = patientService.getDoctorTimeline(
            patientId, hospitalId, doctorId, assignment,
            PatientTimelineAccessRequestDTO.builder().accessReason("Suivi clinique").includeSensitiveData(true).build());

        assertThat(response.getEntries())
            .extracting(PatientTimelineEntryDTO::getEntryId)
            .containsExactly(travelling.getId().toString());
        assertThat(response.getRestrictedRows()).singleElement().satisfies(r -> {
            assertThat(r.getHospitalId()).isEqualTo(other.getId());
            assertThat(r.getHospitalName()).isEqualTo("CHU Yalgado");
            assertThat(r.getDepartmentName()).isEqualTo("Psychiatrie");
            assertThat(r.getCount()).isEqualTo(1L);
        });
    }

    @Test
    void getDoctorTimelineNamesNothingRestrictedUnderALiveSession() {
        // E9 #64 — under break-the-glass the row surfaces, so there is
        // nothing to call restricted.
        UUID doctorId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName("CHU Yalgado");
        Encounter sensitive = Encounter.builder()
            .patient(patient)
            .hospital(other)
            .encounterDate(LocalDateTime.now().minusDays(2))
            .encounterType(EncounterType.CONSULTATION)
            .build();
        sensitive.setId(UUID.randomUUID());

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(recordAccessPolicy.readableHospitalIds(doctorId, patientId, hospitalId))
            .thenReturn(Set.of(hospitalId, other.getId()));
        when(breakGlassGate.isUnlocked(doctorId, patientId, hospitalId)).thenReturn(true);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(sensitive));
        when(sensitivityClassifier.effectiveCategory(sensitive))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        PatientTimelineResponseDTO response = patientService.getDoctorTimeline(
            patientId, hospitalId, doctorId, assignment,
            PatientTimelineAccessRequestDTO.builder().accessReason("Urgence").includeSensitiveData(true).build());

        assertThat(response.getEntries()).extracting(PatientTimelineEntryDTO::getEntryId)
            .containsExactly(sensitive.getId().toString());
        assertThat(response.getRestrictedRows()).isEmpty();
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

        // E9 #63 — sensitivity comes from the classifier, not from words in the
        // fixtures: the note, the problem and the recent encounter are tagged;
        // the prescription and the result have no encounter and are not.
        when(sensitivityClassifier.effectiveCategory(note))
            .thenReturn(com.example.hms.enums.SensitivityCategory.BEHAVIOURAL_HEALTH);
        when(sensitivityClassifier.effectiveCategory(problem))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);
        when(sensitivityClassifier.effectiveCategory(recentEncounter))
            .thenReturn(com.example.hms.enums.SensitivityCategory.BEHAVIOURAL_HEALTH);
        DoctorPatientRecordRequestDTO request = DoctorPatientRecordRequestDTO.builder()
            .hospitalId(hospitalId)
            .accessReason("Pre-op review")
            .includeSensitiveData(true)
            .build();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(patientDto);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());
        when(patientRepository.findMrnForHospital(patientId, hospitalId)).thenReturn(Optional.of("MRN001"));
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(allergy));
        when(patientAllergyMapper.toResponseDto(allergy)).thenReturn(allergyResponse);
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(prescriptionResponse);
        when(labResultRepository.findByLabOrder_Patient_Id(patientId)).thenReturn(List.of(labResult));
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(labResultResponse);
        when(ultrasoundOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedDateDesc(patientId, Set.of(hospitalId))).thenReturn(List.of(ultrasoundOrder));
        when(ultrasoundMapper.toOrderResponseDTO(ultrasoundOrder)).thenReturn(orderResponse);
        when(ultrasoundReportRepository.findByUltrasoundOrder_Patient_IdAndHospital_IdInOrderByScanDateDesc(patientId, Set.of(hospitalId))).thenReturn(List.of(ultrasoundReport));
        when(ultrasoundMapper.toReportResponseDTO(ultrasoundReport)).thenReturn(reportResponse);
        when(nursingNoteRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, Set.of(hospitalId))).thenReturn(List.of(note));
        when(nursingNoteMapper.toResponse(note)).thenReturn(noteResponse);
        when(patientProblemRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(problem));
        when(patientProblemMapper.toResponseDto(problem)).thenReturn(problemResponse);
        when(patientSurgicalHistoryRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(surgicalHistory));
        when(patientSurgicalHistoryMapper.toResponseDto(surgicalHistory)).thenReturn(surgicalResponse);
        when(advanceDirectiveRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(directive));
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
        // The life-threatening allergy and the high-risk / anomalous ultrasound
        // are clinical alerts; the rest come from the classifier stubs above.
        assertThat(response.getSensitiveSections()).containsExactly(
            "ALLERGIES",
            "IMAGING",
            "NOTES",
            "MEDICAL_HISTORY",
            "ENCOUNTERS"
        );
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void getPatientByIdWorksForMultiHospitalPatient() {
        // Patient originally registered at Hospital B (hospitalId on entity differs)
        UUID hospitalBId = UUID.randomUUID();
        UUID hospitalAId = UUID.randomUUID();
        patient.setHospitalId(hospitalBId); // Patient.hospitalId permanently set to first hospital (B)

        PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).build();

        // findByIdUnscoped bypasses TenantScopeSpecification, so Hospital B's id on the entity won't block
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        // Patient IS registered at Hospital A via PatientHospitalRegistration
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalAId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalAId)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalAId)).thenReturn(Optional.empty());

        PatientResponseDTO result = patientService.getPatientById(patientId, hospitalAId, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(patientRepository).findByIdUnscoped(patientId);
        verify(registrationRepository).existsByPatientIdAndHospitalId(patientId, hospitalAId);
    }

    @Test
    void getPatientByIdDeniesAccessWhenNotRegisteredAtHospital() {
        UUID hospitalBId = UUID.randomUUID();
        UUID hospitalCId = UUID.randomUUID();
        patient.setHospitalId(hospitalBId);

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        // Patient is NOT registered at Hospital C
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalCId)).thenReturn(false);

        assertThatThrownBy(() -> patientService.getPatientById(patientId, hospitalCId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ findRegistrationMatches (cross-hospital link-at-registration) ═══════════════

    private PatientHospitalRegistration activeRegistrationAt(Hospital h) {
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(h);
        reg.setActive(true);
        return reg;
    }

    @Test
    void findRegistrationMatchesByPhoneReturnsMaskedProjection() {
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setEmail("jane@example.com");
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        patient.getHospitalRegistrations().add(activeRegistrationAt(otherHospital));

        when(patientRepository.findAllByPhoneNumberPrimary("+22670707070")).thenReturn(List.of(patient));
        when(patientRepository.findAllByPhoneNumberSecondary("+22670707070")).thenReturn(List.of());

        var matches = patientService.findRegistrationMatches(null, "+22670707070", hospitalId);

        assertThat(matches).hasSize(1);
        var match = matches.get(0);
        assertThat(match.getPatientId()).isEqualTo(patientId);
        assertThat(match.getMatchedOn()).isEqualTo("PHONE");
        assertThat(match.getFullName()).isEqualTo("Jane Doe");
        assertThat(match.getBirthYear()).isEqualTo(1990);
        assertThat(match.getMaskedPhone()).startsWith("+").endsWith("70").contains("•");
        assertThat(match.getMaskedPhone()).doesNotContain("2267070");
        assertThat(match.getMaskedEmail()).isEqualTo("j•••@example.com");
        assertThat(match.getHospitalCount()).isEqualTo(1);
        assertThat(match.isAlreadyRegisteredHere()).isFalse();
    }

    @Test
    void findRegistrationMatchesFlagsAlreadyRegisteredHere() {
        patient.setPhoneNumberPrimary("+22670707070");
        patient.getHospitalRegistrations().add(activeRegistrationAt(hospital));

        when(patientRepository.findAllByPhoneNumberPrimary("+22670707070")).thenReturn(List.of(patient));
        when(patientRepository.findAllByPhoneNumberSecondary("+22670707070")).thenReturn(List.of());

        var matches = patientService.findRegistrationMatches(null, "+22670707070", hospitalId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).isAlreadyRegisteredHere()).isTrue();
    }

    @Test
    void findRegistrationMatchesFlagsInactiveRegistrationHereToo() {
        // POST /registrations rejects on ANY existing row (active or not), so the
        // card must not offer a Link that would dead-end in a 409.
        patient.setPhoneNumberPrimary("+22670707070");
        PatientHospitalRegistration inactive = activeRegistrationAt(hospital);
        inactive.setActive(false);
        patient.getHospitalRegistrations().add(inactive);

        when(patientRepository.findAllByPhoneNumberPrimary("+22670707070")).thenReturn(List.of(patient));
        when(patientRepository.findAllByPhoneNumberSecondary("+22670707070")).thenReturn(List.of());

        var matches = patientService.findRegistrationMatches(null, "+22670707070", hospitalId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).isAlreadyRegisteredHere()).isTrue();
        assertThat(matches.get(0).getHospitalCount()).isZero();
    }

    @Test
    void findRegistrationMatchesDedupesPhoneAndEmailHitsPreferringPhone() {
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setEmail("jane@example.com");

        when(patientRepository.findAllByPhoneNumberPrimary("+22670707070")).thenReturn(List.of(patient));
        when(patientRepository.findAllByPhoneNumberSecondary("+22670707070")).thenReturn(List.of());
        when(patientRepository.findAllByEmailIgnoreCase("jane@example.com")).thenReturn(List.of(patient));

        var matches = patientService.findRegistrationMatches("jane@example.com", "+22670707070", hospitalId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchedOn()).isEqualTo("PHONE");
    }

    @Test
    void findRegistrationMatchesReturnsEmptyForBlankInputsWithoutQuerying() {
        var matches = patientService.findRegistrationMatches("  ", null, hospitalId);

        assertThat(matches).isEmpty();
        verify(patientRepository, never()).findAllByEmailIgnoreCase(any());
        verify(patientRepository, never()).findAllByPhoneNumberPrimary(any());
    }

    @Test
    void getPatientAllergiesSurfacesOtherHospitalsRowsAndRecordsTheReach() {
        // E9 #56 — every active allergy travels; a row recorded elsewhere is
        // returned with its hospital and accounted as one RECORD_SHARE.
        UUID requester = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName("CHU Yalgado");
        PatientAllergy here = new PatientAllergy();
        here.setId(UUID.randomUUID());
        here.setHospital(hospital);
        here.setAllergenDisplay("Peanuts");
        here.setActive(true);
        PatientAllergy away = new PatientAllergy();
        away.setId(UUID.randomUUID());
        away.setHospital(other);
        away.setAllergenDisplay("Penicillin");
        away.setActive(true);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(here, away));
        when(patientAllergyMapper.toResponseDto(here)).thenReturn(PatientAllergyResponseDTO.builder()
            .id(here.getId()).hospitalId(hospitalId).allergenDisplay("Peanuts").build());
        when(patientAllergyMapper.toResponseDto(away)).thenReturn(PatientAllergyResponseDTO.builder()
            .id(away.getId()).hospitalId(other.getId()).hospitalName("CHU Yalgado").allergenDisplay("Penicillin").build());

        List<PatientAllergyResponseDTO> result = patientService.getPatientAllergies(patientId, hospitalId, requester);

        assertThat(result).extracting(PatientAllergyResponseDTO::getAllergenDisplay)
            .containsExactlyInAnyOrder("Peanuts", "Penicillin");
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), eq(requester), isNull(),
            eq(java.util.Map.of(other.getId().toString(), 1L)), anyString());
    }

    @Test
    void getPatientAllergiesFromTheRecordingHospitalRecordsNoReach() {
        UUID requester = UUID.randomUUID();
        PatientAllergy here = new PatientAllergy();
        here.setId(UUID.randomUUID());
        here.setHospital(hospital);
        here.setAllergenDisplay("Peanuts");
        here.setActive(true);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(here));
        when(patientAllergyMapper.toResponseDto(here)).thenReturn(PatientAllergyResponseDTO.builder()
            .id(here.getId()).hospitalId(hospitalId).allergenDisplay("Peanuts").build());

        patientService.getPatientAllergies(patientId, hospitalId, requester);

        // Every read reports its reach; a local-only read reports an empty one.
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), eq(requester), isNull(),
            eq(java.util.Map.of()), anyString());
    }

    @Test
    void createPatientImportsFreeTextAllergiesAsStructuredRows() {
        // E9 #56 — the registration form's free text becomes UNCONFIRMED rows at
        // the registering hospital; the mapper no longer writes the column.
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder()
            .userId(userId)
            .hospitalId(hospitalId)
            .allergies("Pénicilline, arachide")
            .build();
        User user = new User();
        user.setId(userId);
        Patient savedPatient = new Patient();
        savedPatient.setId(patientId);
        savedPatient.setHospitalRegistrations(new java.util.HashSet<>());
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setPatient(savedPatient);
        registration.setHospital(hospital);
        registration.setMrn("HSP0002");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(patientMapper.toPatient(request, user)).thenReturn(savedPatient);
        when(patientRepository.save(savedPatient)).thenReturn(savedPatient);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(registrationRepository.existsByMrnAndHospitalId(anyString(), eq(hospitalId))).thenReturn(false);
        when(registrationRepository.save(any(PatientHospitalRegistration.class))).thenReturn(registration);
        when(patientMapper.toPatientDTO(savedPatient, hospitalId)).thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatient(request, Locale.ENGLISH);

        verify(legacyAllergyTextImporter).importFreeText(savedPatient, hospital, null, "Pénicilline, arachide",
            com.example.hms.service.allergy.LegacyAllergyTextImporter.SOURCE_REGISTRATION);
    }

    @Test
    void listPatientDiagnosesFollowsThePatientAndWithholdsForeignSensitiveRows() {
        // E9 #59 (D1 + D3): a diagnosis recorded at another hospital is returned
        // with its hospital; a foreign one carrying a sensitivity category is
        // withheld; and the reach is accounted.
        UUID requester = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(otherId);
        other.setName("CHU Yalgado");
        PatientProblem local = new PatientProblem();
        local.setId(UUID.randomUUID());
        local.setHospital(hospital);
        local.setProblemDisplay("Hypertension");
        PatientProblem foreign = new PatientProblem();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        foreign.setProblemDisplay("Asthme");
        PatientProblem foreignSensitive = new PatientProblem();
        foreignSensitive.setId(UUID.randomUUID());
        foreignSensitive.setHospital(other);
        foreignSensitive.setProblemDisplay("Infection VIH");
        foreignSensitive.setSensitivityCategory(com.example.hms.enums.SensitivityCategory.HIV);

        when(roleValidator.getCurrentUserId()).thenReturn(requester);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(recordAccessPolicy.readableHospitalIds(requester, patientId, hospitalId)).thenReturn(Set.of(hospitalId, otherId));
        when(patientProblemRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId, otherId)))
            .thenReturn(List.of(local, foreign, foreignSensitive));
        when(sensitivityClassifier.effectiveCategory(any(PatientProblem.class)))
            .thenAnswer(inv -> inv.getArgument(0) == foreignSensitive ? com.example.hms.enums.SensitivityCategory.HIV : null);
        when(patientProblemMapper.toResponseDto(local)).thenReturn(PatientProblemResponseDTO.builder()
            .id(local.getId()).hospitalId(hospitalId).problemDisplay("Hypertension").build());
        when(patientProblemMapper.toResponseDto(foreign)).thenReturn(PatientProblemResponseDTO.builder()
            .id(foreign.getId()).hospitalId(otherId).hospitalName("CHU Yalgado").problemDisplay("Asthme").build());

        List<PatientProblemResponseDTO> result = patientService.listPatientDiagnoses(patientId, hospitalId, true);

        assertThat(result).extracting(PatientProblemResponseDTO::getProblemDisplay)
            .containsExactlyInAnyOrder("Hypertension", "Asthme");
        verify(patientProblemMapper, never()).toResponseDto(foreignSensitive);
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), eq(requester), isNull(),
            eq(java.util.Map.of(otherId.toString(), 1L)), anyString());
    }

}
