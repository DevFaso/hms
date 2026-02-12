package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    private static final String TEST_MEDICATION = "Ibuprofen";
    private static final String TEST_DOSAGE = "200mg";
    private static final String TEST_FREQUENCY = "BID";

    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PatientAllergyRepository patientAllergyRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private PrescriptionMapper prescriptionMapper;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private AuthService authService;
    @Mock
    private UserRoleHospitalAssignmentRepository urhaRepository;

    @InjectMocks
    private PrescriptionServiceImpl prescriptionService;

    private UUID patientId;
    private UUID staffId;
    private UUID encounterId;
    private UUID hospitalId;
    private Patient patient;
    private Staff staff;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

    Hospital hospital = new Hospital();
    hospital.setId(hospitalId);

        staff = new Staff();
        staff.setId(staffId);
        staff.setHospital(hospital);
        User user = new User();
        user.setId(UUID.randomUUID());
        staff.setUser(user);

        encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(staff);

        lenient().when(patientAllergyRepository.findByPatient_IdAndHospital_Id(any(), any()))
            .thenReturn(List.of());
    }

    @Test
    void createPrescriptionWhenAuthorizedPersistsEntityAndReturnsDto() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
    encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder()
            .id(UUID.randomUUID())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        assertThat(mappedEntity.getAssignment()).isEqualTo(encounterAssignment);
        verify(prescriptionRepository).save(mappedEntity);
    }

    @Test
    void createPrescriptionWhenRoleValidationFailsThrowsBusinessException() {
        PrescriptionRequestDTO request = buildRequest();

        UUID currentUser = UUID.randomUUID();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.only.doctor.admin");

        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescriptionWhenAssignmentResolvedViaUrhaUsesRepositoryLookup() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setAssignment(null);
        staff.setAssignment(null);

        UUID currentUser = UUID.randomUUID();
        UserRoleHospitalAssignment resolvedAssignment = new UserRoleHospitalAssignment();
        resolvedAssignment.setId(UUID.randomUUID());

        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staff.getUser().getId(), hospitalId, "DOCTOR"))
            .thenReturn(Optional.empty());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staff.getUser().getId(), hospitalId, "ROLE_DOCTOR"))
            .thenReturn(Optional.of(resolvedAssignment));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        assertThat(mappedEntity.getAssignment()).isEqualTo(resolvedAssignment);
        verify(urhaRepository).findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staff.getUser().getId(), hospitalId, "DOCTOR");
        verify(urhaRepository).findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staff.getUser().getId(), hospitalId, "ROLE_DOCTOR");
    }

    @Test
    void createPrescriptionWithoutStaffOrEncounterUsesCurrentDoctorContext() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UUID currentUser = UUID.randomUUID();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounter.setAssignment(encounterAssignment);

        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUser)).thenReturn(Optional.of(staff));
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(staffRepository).findFirstByUserIdOrderByCreatedAtAsc(currentUser);
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void createPrescriptionWithoutEncounterCreatesSnapshotWhenMissing() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UUID currentUser = UUID.randomUUID();
        UserRoleHospitalAssignment staffAssignment = new UserRoleHospitalAssignment();
        staffAssignment.setId(UUID.randomUUID());
        staffAssignment.setHospital(encounter.getHospital());
        staff.setAssignment(staffAssignment);

        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUser)).thenReturn(Optional.of(staff));
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.empty());
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(invocation -> {
            Encounter generated = invocation.getArgument(0);
            generated.setId(UUID.randomUUID());
            return generated;
        });
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(prescriptionMapper.toEntity(any(), any(), any(), any())).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);

        ArgumentCaptor<Encounter> encounterCaptor = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(encounterCaptor.capture());
        Encounter generatedEncounter = encounterCaptor.getValue();
        assertThat(generatedEncounter.getPatient()).isEqualTo(patient);
        assertThat(generatedEncounter.getStaff()).isEqualTo(staff);
        assertThat(generatedEncounter.getAssignment()).isEqualTo(staffAssignment);

        verify(prescriptionMapper).toEntity(request, patient, staff, generatedEncounter);
    }

    @Test
    void createPrescriptionWithPatientIdentifierResolvesPatientByUsernameOrEmail() {
        String patientIdentifier = "dev_patient_0014@seed.dev";
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier(patientIdentifier)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UUID currentUser = UUID.randomUUID();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounter.setAssignment(encounterAssignment);

        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findByUsernameOrEmail(patientIdentifier)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUser)).thenReturn(Optional.of(staff));
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        verify(patientRepository).findByUsernameOrEmail(patientIdentifier);
        verify(patientRepository, never()).findById(any());
    }

    @Test
    void createPrescriptionWhenEncounterPatientMismatchThrowsBusinessException() {
        PrescriptionRequestDTO request = buildRequest();
        Patient differentPatient = new Patient();
        differentPatient.setId(UUID.randomUUID());
        encounter.setPatient(differentPatient);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.encounter.patient.mismatch");
    }

    @Test
    void createPrescriptionWhenPatientMissingThrowsResourceNotFound() {
        PrescriptionRequestDTO request = buildRequest();
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("patient.notfound");
    }

    @Test
    void updatePrescriptionWhenAuthorizedUpdatesExistingEntity() {
        UUID prescriptionId = UUID.randomUUID();
        PrescriptionRequestDTO request = buildRequest();
        Prescription existing = new Prescription();
        existing.setId(prescriptionId);

        UserRoleHospitalAssignment staffAssignment = new UserRoleHospitalAssignment();
        staffAssignment.setId(UUID.randomUUID());
    staffAssignment.setHospital(encounter.getHospital());
        staff.setAssignment(staffAssignment);

        UUID currentUser = UUID.randomUUID();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(prescriptionId).build();

        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(existing));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(prescriptionRepository.save(existing)).thenReturn(existing);
        when(prescriptionMapper.toResponseDTO(existing)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.updatePrescription(prescriptionId, request, Locale.ENGLISH);

        assertThat(result).isSameAs(responseDTO);
        assertThat(existing.getAssignment()).isEqualTo(staffAssignment);
        verify(prescriptionMapper).updateEntity(existing, request, patient, staff, encounter);
        verify(prescriptionRepository).save(existing);
    }

    @Test
    void listWhenPatientFilterProvidedUsesPatientRepository() {
        Pageable pageable = PageRequest.of(0, 5);
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByPatient_Id(patientId, pageable)).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        Page<PrescriptionResponseDTO> result = prescriptionService.list(patientId, null, null, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(dto);
        verify(prescriptionRepository).findByPatient_Id(patientId, pageable);
        verify(prescriptionRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listWhenNoFiltersFallsBackToFindAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findAll(pageable)).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        Page<PrescriptionResponseDTO> result = prescriptionService.list(null, null, null, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(dto);
        verify(prescriptionRepository).findAll(pageable);
    }

    private PrescriptionRequestDTO buildRequest() {
        return PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .duration("5d")
            .notes("Take with food")
            .build();
    }
}
