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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.Map;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    /** QA D819 — the linked-account guard; its own rules are PatientAccountLinkGuardTest's. */
    @Mock
    private PatientAccountLinkGuard accountLinkGuard;

    /** QA D959 — the visit rows have their own test (VisitTimelineRowsTest); none here. */
    @Mock
    private com.example.hms.service.timeline.VisitTimelineRows visitTimelineRows;

    @InjectMocks
    private PatientServiceImpl patientService;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        // QA D763: the one MRN generator, real, over the mocked repository.
        org.springframework.test.util.ReflectionTestUtils.setField(patientService, "mrnGenerator",
            new PatientMrnGenerator(registrationRepository));
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

    // QA D110: an opted-out patient's allergy summary shows only this hospital's rows.
    @Test
    void getPatientByIdNarrowsTheAllergySummaryAfterAnOptOut() {
        PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).allergies("Peanuts, Penicillin").build();
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(responseDTO);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());
        when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(true);
        PatientAllergy local = PatientAllergy.builder().allergenDisplay("Peanuts").active(true).build();
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(local));

        PatientResponseDTO read = patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH);
        assertThat(read.getAllergies()).isEqualTo("Peanuts");
        // QA D504 (round 2): the narrowed column says it was narrowed.
        assertThat(read.isAllergiesWithheld()).isTrue();
        // QA D504 (round 3): and every other list on the chart is flagged too.
        assertThat(read.isRecordsWithheld()).isTrue();
    }

    // QA D504: break-the-glass overrides the opt-out on the allergy reads too.
    private com.example.hms.service.recordaccess.RecordAccessDecision breakGlassDecision(UUID actor) {
        return com.example.hms.service.recordaccess.RecordAccessDecision.permitted(patientId, hospitalId, actor,
            new com.example.hms.service.recordaccess.TreatmentRelationship(
                com.example.hms.enums.TreatmentRelationshipKind.BREAK_GLASS, UUID.randomUUID(), hospitalId, patientId,
                null, null, true),
            com.example.hms.enums.RecordAccessPosture.TREATMENT_PRESUMED);
    }

    @Test
    void d504BreakGlassShowsTheOtherHospitalsAllergyDespiteTheOptOut() {
        UUID doctorB = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(doctorB).build());
        try {
            Hospital other = new Hospital();
            other.setId(UUID.randomUUID());
            PatientAllergy severeAtA = new PatientAllergy();
            severeAtA.setId(UUID.randomUUID());
            severeAtA.setHospital(other);
            severeAtA.setAllergenDisplay("Penicillin");
            severeAtA.setActive(true);
            when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
            when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
            when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(true);
            when(recordAccessPolicy.decide(doctorB, patientId, hospitalId)).thenReturn(breakGlassDecision(doctorB));
            when(patientAllergyRepository.findByPatient_Id(patientId)).thenReturn(List.of(severeAtA));
            when(patientAllergyMapper.toResponseDto(severeAtA)).thenReturn(PatientAllergyResponseDTO.builder()
                .id(severeAtA.getId()).hospitalId(other.getId()).allergenDisplay("Penicillin").build());

            assertThat(patientService.getPatientAllergies(patientId, hospitalId, doctorB))
                .extracting(PatientAllergyResponseDTO::getAllergenDisplay).containsExactly("Penicillin");
            assertThat(patientService.foreignAllergiesWithheld(patientId, hospitalId, doctorB)).isFalse();
            verify(patientAllergyRepository, never()).findVisibleAt(any(), any());
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    void d504WithoutBreakGlassTheOptOutWithholdsAndSaysSo() {
        UUID doctorB = UUID.randomUUID();
        when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(true);
        when(recordAccessPolicy.decide(doctorB, patientId, hospitalId)).thenReturn(
            com.example.hms.service.recordaccess.RecordAccessDecision.refused(patientId, hospitalId, doctorB,
                com.example.hms.enums.RecordAccessDenialReason.PATIENT_OPTED_OUT,
                com.example.hms.enums.RecordAccessPosture.TREATMENT_PRESUMED));

        assertThat(patientService.foreignAllergiesWithheld(patientId, hospitalId, doctorB)).isTrue();
    }

    @Test
    void d504NoOptOutNothingIsWithheld() {
        UUID doctorB = UUID.randomUUID();
        when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(false);

        assertThat(patientService.foreignAllergiesWithheld(patientId, hospitalId, doctorB)).isFalse();
        verify(recordAccessPolicy, never()).decide(any(), any(), any());
    }

    @Test
    void d504BreakGlassKeepsTheWholeAllergySummaryDespiteTheOptOut() {
        UUID doctorB = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(doctorB).build());
        try {
            PatientResponseDTO responseDTO = PatientResponseDTO.builder().id(patientId).allergies("Peanuts, Penicillin").build();
            when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
            when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
            when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(responseDTO);
            when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());
            when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(true);
            when(recordAccessPolicy.decide(doctorB, patientId, hospitalId)).thenReturn(breakGlassDecision(doctorB));

            assertThat(patientService.getPatientById(patientId, hospitalId, Locale.ENGLISH).getAllergies())
                .isEqualTo("Peanuts, Penicillin");
            verify(patientAllergyRepository, never()).findByPatient_IdAndHospital_Id(any(), any());
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    // QA D69: an MRN lookup must not return a patient registered only at another hospital.
    @Test
    void lookupPatientsDropsPatientsNotRegisteredAtCallerHospital() {
        when(patientRepository.findByMrn("MRN-A1")).thenReturn(List.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        assertThat(patientService.lookupPatients(null, null, null, null, "MRN-A1", hospitalId, Locale.ENGLISH))
            .isEmpty();
        verify(patientMapper, never()).toPatientDTO(patient, hospitalId);
    }

    // Residual #13 class: an email held by a patient of another hospital first must not hide
    // this hospital's holder of the same address.
    @Test
    void lookupPatientsByIdentifierEmailKeepsEveryHolderBeforeTheScopeFilter() {
        Patient elsewhere = new Patient();
        elsewhere.setId(UUID.randomUUID());
        PatientResponseDTO scoped = PatientResponseDTO.builder().id(patientId).build();
        when(patientRepository.findAllByEmailIgnoreCase("family@example.com")).thenReturn(List.of(elsewhere, patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(elsewhere.getId(), hospitalId)).thenReturn(false);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(scoped);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        assertThat(patientService.lookupPatients("family@example.com", null, null, null, null, hospitalId, Locale.ENGLISH))
            .extracting(PatientResponseDTO::getId)
            .containsExactly(patientId);
    }

    @Test
    void lookupPatientsWithoutHospitalScopeIsRefusedForStaff() {
        when(patientRepository.findByMrn("MRN-A1")).thenReturn(List.of(patient));
        when(roleValidator.requireActiveHospitalId())
            .thenThrow(new com.example.hms.exception.BusinessException("Hospital context required"));

        assertThatThrownBy(() -> patientService.lookupPatients(null, null, null, null, "MRN-A1", null, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        verify(patientMapper, never()).toPatientDTO(patient, null);
    }

    // QA D903: with no hospital passed, the lookup filters at the active hospital — and must map at
    // it too, or the picker shows another hospital's MRN (the mapper's unscoped fallback).
    @Test
    void lookupPatientsMapsAtTheResolvedActiveHospitalSoTheMrnIsThatHospitals() {
        PatientResponseDTO scoped = PatientResponseDTO.builder().id(patientId).mrn("CHW5448").build();
        when(patientRepository.findByMrn("CHW5448")).thenReturn(List.of(patient));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(patientMapper.toPatientDTO(patient, hospitalId)).thenReturn(scoped);
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        assertThat(patientService.lookupPatients(null, null, null, null, "CHW5448", null, Locale.ENGLISH))
            .extracting(PatientResponseDTO::getMrn)
            .containsExactly("CHW5448");
        verify(patientMapper, never()).toPatientDTO(patient, null);
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
    void createPatientKeepsTheRecordActiveWhileTheAccountAwaitsActivation() {
        // QA D27: the record used to copy the pending account's state and so
        // vanished from every clinical picker until the patient signed in.
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder().userId(userId).hospitalId(hospitalId).build();
        User pending = new User();
        pending.setId(userId);
        pending.setActive(false);
        Patient savedPatient = new Patient();
        savedPatient.setId(patientId);
        savedPatient.setHospitalRegistrations(new java.util.HashSet<>());
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setPatient(savedPatient);
        registration.setHospital(hospital);
        registration.setMrn("HSP0002");

        when(userRepository.findById(userId)).thenReturn(Optional.of(pending));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientMapper.toPatient(request, pending)).thenReturn(savedPatient);
        when(patientRepository.save(savedPatient)).thenReturn(savedPatient);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(registrationRepository.existsByMrnAndHospitalId(anyString(), eq(hospitalId))).thenReturn(false);
        when(registrationRepository.save(any(PatientHospitalRegistration.class))).thenReturn(registration);
        when(patientMapper.toPatientDTO(savedPatient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatient(request, Locale.ENGLISH);

        assertThat(savedPatient.isActive()).isTrue();
        verify(patientRepository).save(savedPatient);
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

        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientMapper.toPatient(request, user)).thenReturn(patient);
        when(patientRepository.save(patient)).thenReturn(patient);
        when(phoneVerificationService.consumeVerifiedChallenge(challengeId, "+22670707070")).thenReturn(true);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(patientMapper.toPatientDTO(patient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatientByStaff(request, Locale.ENGLISH);

        assertThat(patient.getPhoneVerifiedAt()).isNotNull();
        // Once for the new record, once for the stamp.
        verify(patientRepository, times(2)).save(patient);
    }

    @Test
    void reviewRound16_anIvorianVerifiedNumberIsStampedThoughItIsStoredInE164() {
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
        hospital.setCountry("CI");
        patient.setPhoneNumberPrimary("07 01 23 45 67");

        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientMapper.toPatient(request, user)).thenReturn(patient);
        when(patientRepository.save(patient)).thenReturn(patient);
        // The challenge holds the number as the desk typed it.
        when(phoneVerificationService.consumeVerifiedChallenge(challengeId, "07 01 23 45 67")).thenReturn(true);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(patientMapper.toPatientDTO(patient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatientByStaff(request, Locale.ENGLISH);

        assertThat(patient.getPhoneNumberPrimary()).isEqualTo("+225701234567");
        assertThat(patient.getPhoneVerifiedAt()).isNotNull();
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

        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientMapper.toPatient(request, user)).thenReturn(patient);
        when(patientRepository.save(patient)).thenReturn(patient);
        when(phoneVerificationService.consumeVerifiedChallenge(any(), any())).thenReturn(false);
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(patientMapper.toPatientDTO(patient, hospitalId))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        when(patientVitalSignService.getLatestSnapshot(patientId, hospitalId)).thenReturn(Optional.empty());

        patientService.createPatientByStaff(request, Locale.ENGLISH);

        assertThat(patient.getPhoneVerifiedAt()).isNull();
        // Only the new record's own save: no stamp.
        verify(patientRepository, times(1)).save(patient);
    }

    @Test
    void deletePatientThrowsWhenNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);

        assertThatThrownBy(() -> patientService.deletePatient(patientId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "patient.notFound");

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
    void includeInactiveSearchesActiveAndInactiveRecords() {
        // QA D80: the clinical pickers must find an admitted patient whose
        // record is switched off; the default stays active-only.
        PatientSearchCriteria criteria = PatientSearchCriteria.builder()
            .name("Doctor").hospitalId(hospitalId).includeInactive(true).build();
        when(patientRepository.searchPatientsExtended(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());

        patientService.searchPatients(criteria, 0, 10, Locale.ENGLISH);

        verify(patientRepository).searchPatientsExtended(
            any(), eq("%doctor%"), any(), any(), any(), eq(hospitalId), org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
    }

    @Test
    void createPatientByStaffRequiresHospitalId() {
        PatientRequestDTO request = PatientRequestDTO.builder().userId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> patientService.createPatientByStaff(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("patient.r7.staffCreateNeedsHospital"));
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> patientService.createPatientDiagnosis(patientId, hospitalId, requesterUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("patient.r7.diagnosisIcd10Invalid"));

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
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("patient.r7.diagnosisStatusReasonRequired"));

        verify(patientProblemRepository, never()).save(any());
    }

    @Test
    void aRemovedDiagnosisIsNotRemovedAgainAndKeepsItsReason_D1186() {
        UUID requesterUserId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        PatientProblem problem = new PatientProblem();
        problem.setId(diagnosisId);
        problem.setPatient(patient);
        problem.setHospital(hospital);
        problem.setStatus(ProblemStatus.INACTIVE);
        problem.setStatusChangeReason("Erreur de saisie");
        when(patientProblemRepository.findById(diagnosisId)).thenReturn(Optional.of(problem));
        com.example.hms.model.PatientProblemHistory removal = new com.example.hms.model.PatientProblemHistory();
        removal.setChangeType(com.example.hms.enums.ProblemChangeType.DELETED);
        when(patientProblemHistoryRepository.findFirstByProblem_IdAndChangeTypeInOrderByChangedAtDesc(
            org.mockito.ArgumentMatchers.eq(diagnosisId), org.mockito.ArgumentMatchers.argThat(types -> types.contains(com.example.hms.enums.ProblemChangeType.DELETED)
                && !types.contains(com.example.hms.enums.ProblemChangeType.UPDATED)
                && !types.contains(com.example.hms.enums.ProblemChangeType.CHRONICITY_CHANGED))))
            .thenReturn(Optional.of(removal));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);

        assertThatThrownBy(() -> patientService.deletePatientDiagnosis(
            patientId, hospitalId, diagnosisId, requesterUserId, "Second motif"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("patient.r22.diagnosisAlreadyRemoved");
        assertThat(problem.getStatusChangeReason()).isEqualTo("Erreur de saisie");
        verify(patientProblemRepository, never()).save(any());
    }

    @Test
    void aDiagnosisMarkedInactiveCanStillBeRemoved_D1186() throws Exception {
        // INACTIVE is also a status the chart sets: only a removal (a DELETED
        // history row) makes the next removal a duplicate.
        UUID requesterUserId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        PatientProblem problem = new PatientProblem();
        problem.setId(diagnosisId);
        problem.setPatient(patient);
        problem.setHospital(hospital);
        problem.setStatus(ProblemStatus.INACTIVE);
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        when(patientProblemRepository.findById(diagnosisId)).thenReturn(Optional.of(problem));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findByUserIdAndHospitalId(requesterUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        // Removed once, then set active again, then marked INACTIVE by the chart: the latest change is not a removal.
        com.example.hms.model.PatientProblemHistory statusChange = new com.example.hms.model.PatientProblemHistory();
        statusChange.setChangeType(com.example.hms.enums.ProblemChangeType.STATUS_CHANGED);
        when(patientProblemHistoryRepository.findFirstByProblem_IdAndChangeTypeInOrderByChangedAtDesc(
            org.mockito.ArgumentMatchers.eq(diagnosisId), org.mockito.ArgumentMatchers.argThat(types -> types.contains(com.example.hms.enums.ProblemChangeType.DELETED)
                && !types.contains(com.example.hms.enums.ProblemChangeType.UPDATED)
                && !types.contains(com.example.hms.enums.ProblemChangeType.CHRONICITY_CHANGED))))
            .thenReturn(Optional.of(statusChange));

        patientService.deletePatientDiagnosis(patientId, hospitalId, diagnosisId, requesterUserId, "Saisie en double");

        assertThat(problem.getStatusChangeReason()).isEqualTo("Saisie en double");
        verify(patientProblemRepository).save(problem);
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
            .hasMessage(com.example.hms.utility.MessageUtil.resolveOrRaw("patient.r7.diagnosisRemoveReasonRequired"));
    }

    @Test
    void getDoctorTimelineNamesTheTestAndTheVisitTypeInTheReadersLanguage_D958_D996() {
        org.springframework.context.MessageSource previous = (org.springframework.context.MessageSource)
            org.springframework.test.util.ReflectionTestUtils.getField(com.example.hms.utility.MessageUtil.class, "messageSource");
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        try {
            UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
            assignment.setId(UUID.randomUUID());
            assignment.setHospital(hospital);
            Encounter stay = Encounter.builder().patient(patient).hospital(hospital)
                .encounterDate(LocalDateTime.now().minusDays(1)).encounterType(EncounterType.INPATIENT).build();
            stay.setId(UUID.randomUUID());
            com.example.hms.model.LabTestDefinition glycemia = new com.example.hms.model.LabTestDefinition();
            glycemia.setName("Glycémie à jeun");
            glycemia.setUnit("g/L");
            LabOrder order = LabOrder.builder().patient(patient).hospital(hospital)
                .labTestDefinition(glycemia)
                .clinicalIndication("Fièvre 39,2 °C, suspicion de paludisme")
                .orderingStaff(Staff.builder().hospital(hospital).build())
                .build();
            order.setId(UUID.randomUUID());
            LabResult result = LabResult.builder().labOrder(order).released(true).resultValue("0.92")
                .resultDate(LocalDateTime.now().minusHours(2)).build();
            result.setId(UUID.randomUUID());
            when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
            when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
            when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(stay));
            when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
                Pageable.unpaged())).thenReturn(List.of(result));

            PatientTimelineResponseDTO response = patientService.getDoctorTimeline(patientId, hospitalId,
                UUID.randomUUID(), assignment,
                PatientTimelineAccessRequestDTO.builder().accessReason("Suivi").includeSensitiveData(true).build());

            assertThat(response.getEntries())
                .filteredOn(entry -> "LAB_RESULT".equals(entry.getCategory()))
                .singleElement()
                .extracting(PatientTimelineEntryDTO::getSummary)
                // QA D1079 + D1106 (round 19): French colon spacing and the
                // value in the reader's notation ("0,92", was "0.92").
                .isEqualTo("Glycémie à jeun : 0,92 g/L");
            assertThat(response.getEntries())
                .filteredOn(entry -> "ENCOUNTER".equals(entry.getCategory()))
                .singleElement()
                .extracting(PatientTimelineEntryDTO::getSummary)
                .isEqualTo("Hospitalisation");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            com.example.hms.utility.MessageUtil.setMessageSource(previous);
        }
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
            .released(true)
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
        when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
            Pageable.unpaged())).thenReturn(List.of(labResult));
        when(patientAllergyRepository.findVisibleAt(eq(patientId), any())).thenReturn(List.of(allergy));
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
        // QA D504 (round 2): an opt-out with no break-glass narrows the allergy
        // entries, and the timeline says so instead of looking complete.
        when(patientAllergyRepository.hasActiveSharingOptOut(patientId)).thenReturn(true);
        // QA D504 (round 3): the other categories are narrowed by the same opt-out.
        when(recordAccessPolicy.foreignRowsWithheld(any(), eq(patientId), eq(hospitalId))).thenReturn(true);

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
        assertThat(response.isAllergiesWithheld()).isTrue();
        assertThat(response.isRecordsWithheld()).isTrue();
    }

    @Test
    void getDoctorTimelineShowsWhatThisHospitalsLaboratoryPerformed() {
        // #751 on the timeline: a result is readable where its order is
        // handled - ordered in the readable set (acting hospital + treatment
        // relationship), or performed by the acting hospital's own laboratory
        // for another hospital. The acting hospital is the query's performer
        // (LabResultPatientReadableQueryTest pins what that returns, and that
        // a result ordered and run elsewhere is not among it), and nothing is
        // filtered on hospital after the read.
        //
        // Accounting: the treatment row is the chart read's reach; the
        // performed-here row is its own reason, counted once there and never
        // in the chart read's count.
        UUID doctorId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        Hospital treating = new Hospital();
        treating.setId(UUID.randomUUID());
        treating.setName("CHU Yalgado");
        Hospital orderingElsewhere = new Hospital();
        orderingElsewhere.setId(UUID.randomUUID());
        orderingElsewhere.setName("CMA Pissy");

        LabResult orderedHere = timelineLabResult(hospital, null, "4.9");
        LabResult orderedAtTreating = timelineLabResult(treating, null, "5.4");
        LabResult performedHereForOther = timelineLabResult(orderingElsewhere, hospital, "6.3");

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(recordAccessPolicy.readableHospitalIds(doctorId, patientId, hospitalId))
            .thenReturn(Set.of(hospitalId, treating.getId()));
        when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId, treating.getId()),
            hospitalId, false, Pageable.unpaged()))
            .thenReturn(List.of(orderedHere, orderedAtTreating, performedHereForOther));
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        PatientTimelineResponseDTO response = patientService.getDoctorTimeline(
            patientId, hospitalId, doctorId, assignment,
            PatientTimelineAccessRequestDTO.builder().accessReason("Suivi clinique").includeSensitiveData(true).build());

        assertThat(response.getEntries())
            .filteredOn(entry -> "LAB_RESULT".equals(entry.getCategory()))
            .extracting(PatientTimelineEntryDTO::getEntryId)
            .containsExactlyInAnyOrder(orderedHere.getId().toString(), orderedAtTreating.getId().toString(),
                performedHereForOther.getId().toString());
        // The performed-here row is the ordering hospital's record.
        assertThat(response.getEntries())
            .filteredOn(entry -> performedHereForOther.getId().toString().equals(entry.getEntryId()))
            .singleElement()
            .satisfies(entry -> assertThat(entry.getMetadata())
                .containsEntry("sourceHospitalId", orderingElsewhere.getId().toString())
                .containsEntry("foreign", true));
        verify(labResultRepository).findPatientResultsReadableAt(patientId, Set.of(hospitalId, treating.getId()),
            hospitalId, false, Pageable.unpaged());
        verifyNoMoreInteractions(labResultRepository);
        verify(reachRecorder).recordReach(patientId, hospitalId, doctorId, assignment.getId(),
            Map.of(treating.getId().toString(), 1L), "Cross-hospital chart read on the treatment relationship");
        verify(reachRecorder).recordReach(patientId, hospitalId, doctorId, assignment.getId(),
            Map.of(orderingElsewhere.getId().toString(), 1L),
            com.example.hms.service.recordaccess.CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION);
    }

    @Test
    void getDoctorTimelineWithholdsASensitivePerformedHereRowLikeAnyForeignRow() {
        // The performed-here row keeps the D3 filter every foreign row passes:
        // in a sensitive category it is withheld without a session, counted as
        // restricted, and - since it never surfaced - not accounted.
        UUID doctorId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        Hospital orderingElsewhere = new Hospital();
        orderingElsewhere.setId(UUID.randomUUID());
        orderingElsewhere.setName("CMA Pissy");
        LabResult performedHereForOther = timelineLabResult(orderingElsewhere, hospital, "Reactive");
        Encounter sensitiveVisit = Encounter.builder().patient(patient).hospital(orderingElsewhere).build();
        performedHereForOther.getLabOrder().setEncounter(sensitiveVisit);

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
            Pageable.unpaged())).thenReturn(List.of(performedHereForOther));
        when(sensitivityClassifier.effectiveCategory(sensitiveVisit))
            .thenReturn(com.example.hms.enums.SensitivityCategory.HIV);
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        PatientTimelineResponseDTO response = patientService.getDoctorTimeline(
            patientId, hospitalId, doctorId, assignment,
            PatientTimelineAccessRequestDTO.builder().accessReason("Suivi clinique").includeSensitiveData(true).build());

        assertThat(response.getEntries()).noneMatch(entry -> "LAB_RESULT".equals(entry.getCategory()));
        assertThat(response.getRestrictedRows()).singleElement()
            .satisfies(r -> assertThat(r.getHospitalId()).isEqualTo(orderingElsewhere.getId()));
        verify(reachRecorder).recordReach(patientId, hospitalId, doctorId, assignment.getId(),
            Map.of(), com.example.hms.service.recordaccess.CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION);
    }

    @Test
    void getDoctorRecordShowsWhatThisHospitalsLaboratoryPerformed() {
        // The doctor record's lab section has always been acting-hospital only
        // (unlike its medications and imaging, which read the readable set),
        // and the treatment relationship still does not widen it. #751: it is
        // read for the orders the acting hospital HANDLES - placed here, or
        // run by this hospital's laboratory for another hospital - with the
        // acting hospital as the query's performer. The performed-here row is
        // accounted once, under the performing-laboratory reason, and is not
        // part of the record's treatment-relationship reach.
        UUID doctorId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        Hospital orderingElsewhere = new Hospital();
        orderingElsewhere.setId(UUID.randomUUID());
        orderingElsewhere.setName("CMA Pissy");
        LabResult orderedHere = timelineLabResult(hospital, null, "5.4");
        LabResult performedHereForOther = timelineLabResult(orderingElsewhere, hospital, "6.3");
        LabResultResponseDTO orderedHereResponse = new LabResultResponseDTO();
        LabResultResponseDTO performedHereResponse = new LabResultResponseDTO();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
            Pageable.unpaged())).thenReturn(List.of(orderedHere, performedHereForOther));
        when(labResultMapper.toResponseDTO(orderedHere)).thenReturn(orderedHereResponse);
        when(labResultMapper.toResponseDTO(performedHereForOther)).thenReturn(performedHereResponse);
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        DoctorPatientRecordDTO response = patientService.getDoctorRecord(patientId, hospitalId, doctorId, assignment,
            DoctorPatientRecordRequestDTO.builder().hospitalId(hospitalId).accessReason("Pre-op review")
                .includeSensitiveData(true).build());

        assertThat(response.getLabResults()).containsExactlyInAnyOrder(orderedHereResponse, performedHereResponse);
        verify(labResultRepository).findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
            Pageable.unpaged());
        verifyNoMoreInteractions(labResultRepository);
        verify(reachRecorder).recordReach(patientId, hospitalId, doctorId, assignment.getId(),
            Map.of(), "Cross-hospital doctor record read on the treatment relationship");
        verify(reachRecorder).recordReach(patientId, hospitalId, doctorId, assignment.getId(),
            Map.of(orderingElsewhere.getId().toString(), 1L),
            com.example.hms.service.recordaccess.CrossHospitalReachRecorder.LAB_RESULT_PERFORMED_HERE_DESCRIPTION);
    }

    private LabResult timelineLabResult(Hospital orderedAt, Hospital performedAt, String value) {
        User orderingUser = new User();
        orderingUser.setId(UUID.randomUUID());
        orderingUser.setFirstName("Awa");
        orderingUser.setLastName("Kaboré");
        LabOrder order = LabOrder.builder()
            .patient(patient)
            .hospital(orderedAt)
            .performingHospital(performedAt)
            .orderingStaff(Staff.builder().user(orderingUser).hospital(orderedAt).build())
            .clinicalIndication("Suivi")
            .build();
        order.setId(UUID.randomUUID());
        LabResult result = LabResult.builder()
            .labOrder(order)
            .released(true)
            .resultValue(value)
            .resultUnit("mmol/L")
            .resultDate(LocalDateTime.now().minusDays(1))
            .build();
        result.setId(UUID.randomUUID());
        return result;
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
            .released(true)
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
        when(patientAllergyRepository.findVisibleAt(eq(patientId), any())).thenReturn(List.of(allergy));
        when(patientAllergyMapper.toResponseDto(allergy)).thenReturn(allergyResponse);
        when(prescriptionRepository.findByPatient_IdAndHospital_IdIn(patientId, Set.of(hospitalId))).thenReturn(List.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(prescriptionResponse);
        when(labResultRepository.findPatientResultsReadableAt(patientId, Set.of(hospitalId), hospitalId, false,
            Pageable.unpaged())).thenReturn(List.of(labResult));
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

        when(patientRepository.findAllByPhoneSubscriber(eq("%70707070"), any())).thenReturn(List.of(patient));

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
    void qaD1061_aPhoneMatchSaysWhetherItIsTheSamePerson() {
        // A child registered under the father's number: the father is listed,
        // and the card is told he is NOT the person being registered.
        patient.setPhoneNumberPrimary("+22676334455");
        when(patientRepository.findAllByPhoneSubscriber(eq("%76334455"), any())).thenReturn(List.of(patient));

        var child = patientService.findRegistrationMatches(null, "+22676334455", "Abdoul Aziz", "Nikiéma",
            LocalDate.of(2021, 8, 19), hospitalId);
        assertThat(child).singleElement().satisfies(m -> {
            assertThat(m.getMatchedOn()).isEqualTo("PHONE");
            assertThat(m.getSameIdentity()).isFalse();
        });

        var same = patientService.findRegistrationMatches(null, "+22676334455", "JANE", "doe",
            LocalDate.of(1990, 1, 1), hospitalId);
        assertThat(same).singleElement().satisfies(m -> assertThat(m.getSameIdentity()).isTrue());

        // Without a name and date of birth there is nothing to compare: null, as before.
        assertThat(patientService.findRegistrationMatches(null, "+22676334455", hospitalId))
            .singleElement().satisfies(m -> assertThat(m.getSameIdentity()).isNull());
    }

    @Test
    void qaD1064_theSamePersonAtAnotherHospitalOfTheOrganisationIsProposedWithAnotherPhone() {
        com.example.hms.model.Organization organization = new com.example.hms.model.Organization();
        organization.setId(UUID.randomUUID());
        hospital.setOrganization(organization);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        Patient homonym = new Patient();
        homonym.setId(UUID.randomUUID());
        homonym.setFirstName("Jane");
        homonym.setLastName("Smith");
        homonym.setDateOfBirth(LocalDate.of(1990, 1, 1));
        homonym.setHospitalRegistrations(new java.util.HashSet<>());
        when(patientRepository.findByDateOfBirthInOrganization(LocalDate.of(1990, 1, 1), organization.getId()))
            .thenReturn(List.of(patient, homonym));

        var matches = patientService.findRegistrationMatches(null, "+22670440099", "Jané", "DOE",
            LocalDate.of(1990, 1, 1), hospitalId);

        // The same first name, last name and date of birth; never a mere same-birthday homonym.
        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.getPatientId()).isEqualTo(patientId);
            assertThat(m.getMatchedOn()).isEqualTo("IDENTITY");
            assertThat(m.getSameIdentity()).isTrue();
        });
    }

    @Test
    void qaD1064_noIdentityMatchOutsideAnOrganisation() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThat(patientService.findRegistrationMatches(null, null, "Jane", "Doe", LocalDate.of(1990, 1, 1),
            hospitalId)).isEmpty();
        verify(patientRepository, never()).findByDateOfBirthInOrganization(any(), any());
    }

    @Test
    void findRegistrationMatchesFlagsAlreadyRegisteredHere() {
        patient.setPhoneNumberPrimary("+22670707070");
        patient.getHospitalRegistrations().add(activeRegistrationAt(hospital));

        when(patientRepository.findAllByPhoneSubscriber(eq("%70707070"), any())).thenReturn(List.of(patient));

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

        when(patientRepository.findAllByPhoneSubscriber(eq("%70707070"), any())).thenReturn(List.of(patient));

        var matches = patientService.findRegistrationMatches(null, "+22670707070", hospitalId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).isAlreadyRegisteredHere()).isTrue();
        assertThat(matches.get(0).getHospitalCount()).isZero();
    }

    @Test
    void reviewRound10_aShortNumberSharedByTwoRecordsMatchesBoth() {
        // Too short to name a subscriber, so matched exactly as stored; a
        // shared number lists every holder, as develop's match did.
        patient.setPhoneNumberPrimary("700010");
        Patient sibling = new Patient();
        sibling.setId(UUID.randomUUID());
        sibling.setFirstName("Awa");
        sibling.setLastName("Doe");
        sibling.setPhoneNumberSecondary("700010");
        sibling.setHospitalRegistrations(new java.util.HashSet<>());
        when(patientRepository.findAllByPhoneNumberPrimary("700010")).thenReturn(List.of(patient));
        when(patientRepository.findAllByPhoneNumberSecondary("700010")).thenReturn(List.of(sibling, patient));

        var matches = patientService.findRegistrationMatches(null, "700010", hospitalId);

        assertThat(matches).extracting(com.example.hms.payload.dto.RegistrationMatchDTO::getPatientId)
            .containsExactly(patientId, sibling.getId());
    }

    @Test
    void findRegistrationMatchesDedupesPhoneAndEmailHitsPreferringPhone() {
        patient.setPhoneNumberPrimary("+22670707070");
        patient.setEmail("jane@example.com");

        when(patientRepository.findAllByPhoneSubscriber(eq("%70707070"), any())).thenReturn(List.of(patient));
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
        verify(patientRepository, never()).findAllByPhoneSubscriber(any(), any());
    }

    @Test
    void findRegistrationMatchesFindsTheSameSubscriberHoweverEitherSideIsWritten() {
        // QA D942: "+22670001002" typed at B, "70001002" stored at A, matched on
        // the eight national digits (the query folds the stored side to digits).
        patient.setPhoneNumberPrimary("70001002");
        when(patientRepository.findAllByPhoneSubscriber(eq("%70001002"), any())).thenReturn(List.of(patient));

        for (String typed : List.of("+22670001002", "70 00 10 02", "0022670001002", "70001002")) {
            var matches = patientService.findRegistrationMatches(null, typed, hospitalId);
            assertThat(matches).as(typed).hasSize(1);
        }
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
        when(patientAllergyRepository.findVisibleAt(eq(patientId), any())).thenReturn(List.of(here, away));
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
        when(patientAllergyRepository.findVisibleAt(eq(patientId), any())).thenReturn(List.of(here));
        when(patientAllergyMapper.toResponseDto(here)).thenReturn(PatientAllergyResponseDTO.builder()
            .id(here.getId()).hospitalId(hospitalId).allergenDisplay("Peanuts").build());

        patientService.getPatientAllergies(patientId, hospitalId, requester);

        // Every read reports its reach; a local-only read reports an empty one.
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), eq(requester), isNull(),
            eq(java.util.Map.of()), anyString());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819: POST /patients with a staff account's userId is refused before any record is written")
    void createPatientByStaffRefusesAStaffAccount() {
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder().userId(userId).hospitalId(hospitalId).build();
        User staffUser = new User();
        staffUser.setId(userId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(staffUser));
        doThrow(new com.example.hms.exception.ConflictException("userId:patient.account.staffAccount"))
            .when(accountLinkGuard).requireLinkable(staffUser, null);

        assertThatThrownBy(() -> patientService.createPatientByStaff(request, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessage("userId:patient.account.staffAccount");

        verify(patientRepository, never()).save(any());
        verify(registrationRepository, never()).save(any());
        verify(patientInsuranceService, never()).addInsuranceToPatient(any(), any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819 (and D725): an account already linked to a record is refused, never re-used with the typed fields dropped")
    void createPatientByStaffRefusesAnAlreadyLinkedAccount() {
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder().userId(userId).hospitalId(hospitalId).build();
        User user = new User();
        user.setId(userId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new com.example.hms.exception.ConflictException("userId:patient.account.alreadyLinked"))
            .when(accountLinkGuard).requireLinkable(user, null);

        assertThatThrownBy(() -> patientService.createPatientByStaff(request, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(patientRepository, never()).save(any());
        verify(patientRepository, never()).findByUserId(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819: createPatient (the backfill's path) runs the same guard")
    void createPatientRunsTheGuard() {
        UUID userId = UUID.randomUUID();
        PatientRequestDTO request = PatientRequestDTO.builder().userId(userId).hospitalId(hospitalId).build();
        User staffUser = new User();
        staffUser.setId(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(staffUser));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        doThrow(new com.example.hms.exception.ConflictException("userId:patient.account.staffAccount"))
            .when(accountLinkGuard).requireLinkable(staffUser, null);

        assertThatThrownBy(() -> patientService.createPatient(request, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(patientRepository, never()).save(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819: PUT /patients relinking the record to another account runs the guard for that account")
    void updatePatientRelinkRunsTheGuard() {
        UUID newUserId = UUID.randomUUID();
        User current = new User();
        current.setId(UUID.randomUUID());
        patient.setUser(current);
        User staffUser = new User();
        staffUser.setId(newUserId);
        PatientRequestDTO request = PatientRequestDTO.builder().userId(newUserId).build();
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(userRepository.findById(newUserId)).thenReturn(Optional.of(staffUser));
        doThrow(new com.example.hms.exception.ConflictException("userId:patient.account.staffAccount"))
            .when(accountLinkGuard).requireLinkable(staffUser, patientId);

        assertThatThrownBy(() -> patientService.updatePatient(patientId, request, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(patientMapper, never()).updatePatientFromDto(any(), any(), any());
        verify(patientRepository, never()).save(any());
        assertThat(patient.getUser()).isSameAs(current);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819: PUT /patients keeping the record's own account is not re-checked (a demographic edit)")
    void updatePatientKeepingItsAccountSkipsTheGuard() {
        User current = new User();
        current.setId(UUID.randomUUID());
        patient.setUser(current);
        PatientRequestDTO request = PatientRequestDTO.builder().userId(current.getId()).build();
        org.springframework.test.util.ReflectionTestUtils.setField(patientService, "addressHistoryRecorder",
            mock(PatientAddressHistoryRecorder.class));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(userRepository.findById(current.getId())).thenReturn(Optional.of(current));
        when(patientRepository.save(patient)).thenReturn(patient);
        org.mockito.Mockito.lenient().when(patientMapper.toPatientDTO(any(), any()))
            .thenReturn(PatientResponseDTO.builder().id(patientId).build());
        org.mockito.Mockito.lenient().when(patientVitalSignService.getLatestSnapshot(any(), any()))
            .thenReturn(Optional.empty());

        patientService.updatePatient(patientId, request, Locale.ENGLISH);

        verify(accountLinkGuard, never()).requireLinkable(any(), any());
        verify(patientRepository).save(patient);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D819: the backfill never gives a staff account that also holds PATIENT a patient record")
    void backfillSkipsAStaffAccount() {
        User staffUser = new User();
        staffUser.setId(UUID.randomUUID());
        when(userRepository.findUsersWithRolePatientButNoPatientEntry()).thenReturn(List.of(staffUser));
        when(accountLinkGuard.isStaffAccount(staffUser)).thenReturn(true);

        patientService.backfillMissingPatients();

        verify(userRepository, never()).findById(any());
        verify(patientRepository, never()).save(any());
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
