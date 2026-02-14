package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PrescriptionMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
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
    private UserRoleHospitalAssignment assignment;

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

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(encounter.getHospital());

        lenient().when(prescriptionMapper.toEntity(any(), any(), any(), any()))
            .thenReturn(new Prescription());
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

    // ═══════════════ getPrescriptionById ═══════════════

    @Test
    void getPrescriptionByIdReturnsDto() {
        UUID id = UUID.randomUUID();
        Prescription prescription = new Prescription();
        prescription.setId(id);
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(id).build();

        when(prescriptionRepository.findById(id)).thenReturn(Optional.of(prescription));
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        assertThat(prescriptionService.getPrescriptionById(id, Locale.ENGLISH)).isSameAs(dto);
    }

    @Test
    void getPrescriptionByIdThrowsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(prescriptionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.getPrescriptionById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ deletePrescription ═══════════════

    @Test
    void deletePrescriptionWhenExistsDeletesById() {
        UUID id = UUID.randomUUID();
        when(prescriptionRepository.existsById(id)).thenReturn(true);

        prescriptionService.deletePrescription(id, Locale.ENGLISH);

        verify(prescriptionRepository).deleteById(id);
    }

    @Test
    void deletePrescriptionThrowsWhenNotExists() {
        UUID id = UUID.randomUUID();
        when(prescriptionRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> prescriptionService.deletePrescription(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ list (staff / encounter filters) ═══════════════

    @Test
    void listWhenStaffFilterProvidedUsesStaffRepository() {
        Pageable pageable = PageRequest.of(0, 5);
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByStaff_Id(staffId, pageable)).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        Page<PrescriptionResponseDTO> result = prescriptionService.list(null, staffId, null, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(dto);
        verify(prescriptionRepository).findByStaff_Id(staffId, pageable);
    }

    @Test
    void listWhenEncounterFilterProvidedUsesEncounterRepository() {
        Pageable pageable = PageRequest.of(0, 5);
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByEncounter_Id(encounterId, pageable)).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        Page<PrescriptionResponseDTO> result = prescriptionService.list(null, null, encounterId, pageable, Locale.ENGLISH);

        assertThat(result.getContent()).containsExactly(dto);
        verify(prescriptionRepository).findByEncounter_Id(encounterId, pageable);
    }

    // ═══════════════ getPrescriptionsByPatientId/StaffId/EncounterId ═══════════════

    @Test
    void getPrescriptionsByPatientIdReturnsList() {
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByPatient_Id(eq(patientId), any(Pageable.class))).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        List<PrescriptionResponseDTO> result = prescriptionService.getPrescriptionsByPatientId(patientId, Locale.ENGLISH);
        assertThat(result).containsExactly(dto);
    }

    @Test
    void getPrescriptionsByStaffIdReturnsList() {
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByStaff_Id(eq(staffId), any(Pageable.class))).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        List<PrescriptionResponseDTO> result = prescriptionService.getPrescriptionsByStaffId(staffId, Locale.ENGLISH);
        assertThat(result).containsExactly(dto);
    }

    @Test
    void getPrescriptionsByEncounterIdReturnsList() {
        Prescription prescription = new Prescription();
        PrescriptionResponseDTO dto = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();
        Page<Prescription> page = new PageImpl<>(List.of(prescription));

        when(prescriptionRepository.findByEncounter_Id(eq(encounterId), any(Pageable.class))).thenReturn(page);
        when(prescriptionMapper.toResponseDTO(prescription)).thenReturn(dto);

        List<PrescriptionResponseDTO> result = prescriptionService.getPrescriptionsByEncounterId(encounterId, Locale.ENGLISH);
        assertThat(result).containsExactly(dto);
    }

    // ═══════════════ resolvePatient edge cases ═══════════════

    @Test
    void createPrescriptionWithPatientIdentifierByMrnResolves() {
        String mrn = "MRN-0001";
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier(mrn)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findByUsernameOrEmail(mrn)).thenReturn(Optional.empty());
        when(patientRepository.findByMrn(mrn)).thenReturn(List.of(patient));
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
        verify(patientRepository).findByMrn(mrn);
    }

    @Test
    void createPrescriptionWithPatientIdentifierByUuidResolves() {
        UUID patientUuid = patientId;
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier(patientUuid.toString())
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findByUsernameOrEmail(patientUuid.toString())).thenReturn(Optional.empty());
        when(patientRepository.findByMrn(patientUuid.toString())).thenReturn(List.of());
        when(patientRepository.findById(patientUuid)).thenReturn(Optional.of(patient));
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
    }

    @Test
    void createPrescriptionWithPatientIdentifierNotFoundThrows() {
        String identifier = "nonexistent";
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier(identifier)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findByUsernameOrEmail(identifier)).thenReturn(Optional.empty());
        when(patientRepository.findByMrn(identifier)).thenReturn(List.of());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPrescriptionNoPatientIdOrIdentifierThrowsBusiness() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.patient.required");
    }

    // ═══════════════ ensureContextConsistency edge cases ═══════════════

    @Test
    void createPrescriptionWhenHospitalLinkMissingOnEncounterThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setHospital(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.hospital.link.required");
    }

    @Test
    void createPrescriptionWhenEncounterStaffMismatchThrows() {
        PrescriptionRequestDTO request = buildRequest();
        Staff differentStaff = new Staff();
        differentStaff.setId(UUID.randomUUID());
        encounter.setStaff(differentStaff);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.encounter.staff.mismatch");
    }

    @Test
    void createPrescriptionWhenStaffHospitalMismatchThrows() {
        PrescriptionRequestDTO request = buildRequest();
        Hospital differentHospital = new Hospital();
        differentHospital.setId(UUID.randomUUID());
        staff.setHospital(differentHospital);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.encounter.staff.hospital.mismatch");
    }

    // ═══════════════ resolveStaffContext edge cases ═══════════════

    @Test
    void createPrescriptionWhenStaffNotFoundByIdThrows() {
        PrescriptionRequestDTO request = buildRequest();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPrescriptionWhenNoStaffAndNullCurrentUserThrows() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(null);

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.staff.context.missing");
    }

    @Test
    void createPrescriptionWhenNoStaffFoundForCurrentUserThrows() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UUID currentUser = UUID.randomUUID();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.staff.context.missing");
    }

    // ═══════════════ resolveEncounterContext edge cases ═══════════════

    @Test
    void createPrescriptionWhenEncounterNotFoundByIdThrows() {
        PrescriptionRequestDTO request = buildRequest();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ allergy checks ═══════════════

    @Test
    void createPrescriptionWithSevereAllergyNoOverrideThrowsBusiness() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);
        allergy.setReaction("Anaphylaxis");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ALLERGY CONFLICT");
    }

    @Test
    void createPrescriptionWithSevereAllergyAndOverrideProceeds() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .forceOverride(true)
            .build();

        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);
        allergy.setReaction("Rash");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithLifeThreateningAllergyNoOverrideThrows() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.LIFE_THREATENING);
        allergy.setReaction("Anaphylaxis");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ALLERGY CONFLICT");
    }

    @Test
    void createPrescriptionWithMildAllergyNoOverrideProceeds() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.MILD);
        allergy.setReaction("Mild rash");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithNullSeverityAllergyProceeds() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(null);
        allergy.setReaction(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithInactiveAllergySkipsCheck() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(false); // inactive
        allergy.setAllergenDisplay("Ibuprofen");
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithNonMatchingAllergenProceeds() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay("Penicillin"); // does not match Ibuprofen
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithNullAllergenDisplaySkips() {
        PrescriptionRequestDTO request = buildRequest();
        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setActive(true);
        allergy.setAllergenDisplay(null);
        allergy.setSeverity(com.example.hms.enums.AllergySeverity.SEVERE);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(allergy));
        when(prescriptionMapper.toEntity(request, patient, staff, encounter)).thenReturn(mappedEntity);
        when(prescriptionRepository.save(mappedEntity)).thenReturn(mappedEntity);
        when(prescriptionMapper.toResponseDTO(mappedEntity)).thenReturn(responseDTO);

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isSameAs(responseDTO);
    }

    @Test
    void createPrescriptionWithNullMedicationNameSkipsAllergyCheck() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(null)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

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
        // null medication → checkAllergyConflicts returns early, no allergy repo call
        verify(patientAllergyRepository, never()).findByPatient_IdAndHospital_Id(any(), any());
    }

    @Test
    void createPrescriptionWithBlankMedicationNameSkipsAllergyCheck() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName("   ")
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        UserRoleHospitalAssignment encounterAssignment = new UserRoleHospitalAssignment();
        encounterAssignment.setId(UUID.randomUUID());
        encounterAssignment.setHospital(encounter.getHospital());
        encounter.setAssignment(encounterAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

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
        verify(patientAllergyRepository, never()).findByPatient_IdAndHospital_Id(any(), any());
    }

    // ═══════════════ resolveAssignmentForStaff edge cases ═══════════════

    @Test
    void createPrescriptionWhenStaffAssignmentMatchesHospitalUsesIt() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setAssignment(null);

        UserRoleHospitalAssignment staffAssignment = new UserRoleHospitalAssignment();
        staffAssignment.setId(UUID.randomUUID());
        staffAssignment.setHospital(encounter.getHospital());
        staff.setAssignment(staffAssignment);

        UUID currentUser = UUID.randomUUID();
        Prescription mappedEntity = new Prescription();
        PrescriptionResponseDTO responseDTO = PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build();

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
        assertThat(mappedEntity.getAssignment()).isEqualTo(staffAssignment);
    }

    @Test
    void createPrescriptionWhenNoAssignmentFoundThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setAssignment(null);
        staff.setAssignment(null);

        UUID currentUser = UUID.randomUUID();

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
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.assignment.missing");
    }

    @Test
    void createPrescriptionWhenStaffUserNullThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setAssignment(null);
        staff.setAssignment(null);
        staff.setUser(null);

        UUID currentUser = UUID.randomUUID();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(roleValidator.canCreatePrescription(currentUser, hospitalId)).thenReturn(true);

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("prescription.assignment.missing.staff.user");
    }

    // ═══════════════ updatePrescription not found ═══════════════

    @Test
    void updatePrescriptionWhenNotFoundThrows() {
        UUID prescriptionId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.updatePrescription(prescriptionId, buildRequest(), Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ hospital context null on createPrescription ═══════════════

    @Test
    void createPrescriptionWhenHospitalIdNullAfterContextThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.getHospital().setId(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(NullPointerException.class);
    }

    // ═══════════════ determineHospitalId fallbacks ═══════════════

    @Test
    void createPrescriptionWithoutEncounterUsesPatientHospitalId() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        // staff has no hospital → fallback to patient hospitalId
        Staff noHospitalStaff = new Staff();
        noHospitalStaff.setId(staffId);
        noHospitalStaff.setHospital(null);
        User staffUser = new User();
        staffUser.setId(UUID.randomUUID());
        noHospitalStaff.setUser(staffUser);

        patient.setHospitalId(hospitalId);

        UUID currentUser = UUID.randomUUID();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authService.getCurrentUserId()).thenReturn(currentUser);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUser)).thenReturn(Optional.of(noHospitalStaff));

        // encounterRepository lookup by patient/staff/hospital returns empty → auto-create
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.empty());

        // resolveAssignmentForStaff: staff has no assignment → URHA lookup
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staffUser.getId(), hospitalId, "DOCTOR"))
            .thenReturn(Optional.empty());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            staffUser.getId(), hospitalId, "ROLE_DOCTOR"))
            .thenReturn(Optional.empty());

        // staff hospital null → createEncounterSnapshot throws
        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ updatePrescription full path ═══════════════

    @Test
    void updatePrescriptionHappyPath() {
        UUID prescriptionId = UUID.randomUUID();
        Prescription existing = new Prescription();
        existing.setId(prescriptionId);

        PrescriptionRequestDTO request = buildRequest();

        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(existing));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR")))
            .thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(existing);
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(prescriptionId).build());

        PrescriptionResponseDTO result = prescriptionService.updatePrescription(prescriptionId, request, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(prescriptionId);
    }

    @Test
    void updatePrescriptionWhenHospitalIdNullThrows() {
        UUID prescriptionId = UUID.randomUUID();
        Prescription existing = new Prescription();
        existing.setId(prescriptionId);

        PrescriptionRequestDTO request = buildRequest();
        encounter.setHospital(null);

        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(existing));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        // encounter.getHospital() is null → ensureContextConsistency throws
        assertThatThrownBy(() -> prescriptionService.updatePrescription(prescriptionId, request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void updatePrescriptionNotAuthorizedThrows() {
        UUID prescriptionId = UUID.randomUUID();
        Prescription existing = new Prescription();
        existing.setId(prescriptionId);

        PrescriptionRequestDTO request = buildRequest();

        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(existing));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(false);

        assertThatThrownBy(() -> prescriptionService.updatePrescription(prescriptionId, request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ resolveAssignmentForStaff ROLE_DOCTOR fallback ═══════════════

    @Test
    void createPrescriptionUsesRoleDoctorFallback() {
        PrescriptionRequestDTO request = buildRequest();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);

        // DOCTOR lookup returns empty, ROLE_DOCTOR returns assignment
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR")))
            .thenReturn(Optional.empty());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("ROLE_DOCTOR")))
            .thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolveAssignmentForStaff: staff.getAssignment() matches ═══════════════

    @Test
    void createPrescriptionUsesStaffDirectAssignment() {
        PrescriptionRequestDTO request = buildRequest();

        // Set staff's assignment to match hospitalId
        com.example.hms.model.Hospital assignmentHospital = new com.example.hms.model.Hospital();
        assignmentHospital.setId(hospitalId);
        UserRoleHospitalAssignment directAssignment = new UserRoleHospitalAssignment();
        directAssignment.setHospital(assignmentHospital);
        staff.setAssignment(directAssignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
        // staff's direct assignment was used - no URHA lookup needed
        verify(urhaRepository, never()).findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(any(), any(), any());
    }

    // ═══════════════ resolveEncounterContext: auto-create with existing encounter ═══════════════

    @Test
    void createPrescriptionAutoCreatesEncounterWhenExistingFound() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            // no encounterId → auto-resolve
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // determineHospitalId → staff.getHospital().getId()
        // encounterRepository lookup returns existing encounter
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolveEncounterContext: auto-create new encounter ═══════════════

    @Test
    void createPrescriptionAutoCreatesNewEncounter() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // no existing encounter found → createEncounterSnapshot
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.empty());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        // createEncounterSnapshot saves new encounter
        when(encounterRepository.save(any())).thenReturn(encounter);
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
        verify(encounterRepository).save(any(Encounter.class));
    }

    // ═══════════════ determineHospitalId: roleValidator fallback ═══════════════

    @Test
    void createPrescriptionUsesRoleValidatorHospitalIdFallback() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        // staff has no hospital, patient has no hospitalId
        Staff noHospStaff = new Staff();
        noHospStaff.setId(staffId);
        noHospStaff.setHospital(null);
        User sUser = new User();
        sUser.setId(UUID.randomUUID());
        noHospStaff.setUser(sUser);

        patient.setHospitalId(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(noHospStaff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.getCurrentHospitalId()).thenReturn(hospitalId);

        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            patientId, staffId, hospitalId)).thenReturn(Optional.empty());

        // resolveAssignmentForStaff: no direct assignment, lookup DOCTOR
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            sUser.getId(), hospitalId, "DOCTOR")).thenReturn(Optional.of(assignment));

        // createEncounterSnapshot will fail because staff.getHospital() is null
        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ resolvePatient: findByUsernameOrEmail path ═══════════════

    @Test
    void createPrescriptionResolvesPatientByUsernameOrEmail() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier("john@example.com")
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findByUsernameOrEmail("john@example.com")).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolveAssignmentForStaff: staff assignment hospital mismatch ═══════════════

    @Test
    void createPrescriptionStaffAssignmentHospitalMismatchFallsToUrha() {
        PrescriptionRequestDTO request = buildRequest();

        // Set staff's assignment with a DIFFERENT hospital
        com.example.hms.model.Hospital otherHospital = new com.example.hms.model.Hospital();
        otherHospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment mismatchAssignment = new UserRoleHospitalAssignment();
        mismatchAssignment.setHospital(otherHospital);
        staff.setAssignment(mismatchAssignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
        // Falls through to URHA lookup because assignment hospital doesn't match
        verify(urhaRepository).findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"));
    }

    // ═══════════════ resolveAssignmentForStaff: assignment with null hospital ═══════════════

    @Test
    void createPrescriptionStaffAssignmentNullHospitalFallsToUrha() {
        PrescriptionRequestDTO request = buildRequest();

        UserRoleHospitalAssignment nullHospAssignment = new UserRoleHospitalAssignment();
        nullHospAssignment.setHospital(null);
        staff.setAssignment(nullHospAssignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ createPrescription: encounter assignment present ═══════════════

    @Test
    void createPrescriptionUsesEncounterAssignment() {
        PrescriptionRequestDTO request = buildRequest();

        // Set encounter's assignment directly
        encounter.setAssignment(assignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
        // encounter's assignment was used directly - no URHA lookup
        verify(urhaRepository, never()).findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(any(), any(), any());
    }

    // ═══════════════ resolveAssignmentForStaff: staff null hospitalId ═══════════════

    @Test
    void resolveAssignmentForStaffWithNullHospitalIdThrows() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        // encounter has no hospital → ensureContextConsistency throws
        Encounter noHospEncounter = Encounter.builder()
            .patient(patient).staff(staff).hospital(null).build();
        noHospEncounter.setId(encounterId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(noHospEncounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ list: findAll fallback ═══════════════

    @Test
    void listWithNoFiltersCallsFindAll() {
        when(prescriptionRepository.findAll(any(Pageable.class)))
            .thenReturn(Page.empty());

        Page<PrescriptionResponseDTO> result = prescriptionService.list(null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 10), Locale.ENGLISH);
        assertThat(result).isEmpty();
        verify(prescriptionRepository).findAll(any(Pageable.class));
    }

    // ═══════════════ determineHospitalId: null staff ═══════════════

    @Test
    void determineHospitalIdWithNullStaffUsesPatient() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        UUID currentUserId = UUID.randomUUID();
        when(authService.getCurrentUserId()).thenReturn(currentUserId);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(assignment));
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(
            PrescriptionResponseDTO.builder().id(UUID.randomUUID()).build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolvePatient: UUID found but patient not found throws ═══════════════

    @Test
    void createPrescriptionResolvesPatientByUuidStringNotFound() {
        UUID unknownPatientId = UUID.randomUUID();
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientIdentifier(unknownPatientId.toString())
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findByUsernameOrEmail(unknownPatientId.toString())).thenReturn(Optional.empty());
        when(patientRepository.findByMrn(unknownPatientId.toString())).thenReturn(List.of());
        when(patientRepository.findById(unknownPatientId)).thenReturn(Optional.empty());
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ═══════════════ ensureContextConsistency: encounter.getPatient() null ═══════════════

    @Test
    void createPrescriptionWhenEncounterPatientNullThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setPatient(null); // patient is null on encounter

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ ensureContextConsistency: encounter.getStaff() null ═══════════════

    @Test
    void createPrescriptionWhenEncounterStaffNullThrows() {
        PrescriptionRequestDTO request = buildRequest();
        encounter.setStaff(null); // staff is null on encounter

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ resolveEncounterContext: determineHospitalId returns null ═══════════════

    @Test
    void createPrescriptionWhenDetermineHospitalIdReturnsNullThrows() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            // no encounterId → triggers resolveEncounterContext
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        Staff noHospStaff = new Staff();
        noHospStaff.setId(staffId);
        noHospStaff.setHospital(null);
        User sUser = new User();
        sUser.setId(UUID.randomUUID());
        noHospStaff.setUser(sUser);

        patient.setHospitalId(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(noHospStaff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.getCurrentHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ resolveAssignmentForStaff: null staff throws ═══════════════

    @Test
    void createPrescriptionWhenResolveAssignmentWithNullStaffThrows() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // no existing encounter, auto-resolve
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            any(), any(), any())).thenReturn(Optional.empty());

        // resolveAssignmentForStaff: staff has no direct assignment
        // DOCTOR and ROLE_DOCTOR both empty → throws
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.empty());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("ROLE_DOCTOR"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ resolveAssignmentForStaff: fromStaff non-null but hospital null ═══════════════

    @Test
    void createPrescriptionWhenStaffAssignmentHospitalNullFallsToUrha() {
        PrescriptionRequestDTO request = buildRequest();

        UserRoleHospitalAssignment badAssignment = new UserRoleHospitalAssignment();
        badAssignment.setId(UUID.randomUUID());
        badAssignment.setHospital(null); // null hospital on assignment
        staff.setAssignment(badAssignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // encounter has assignment → resolvePrescriberAssignmentOrThrow returns it directly
        UserRoleHospitalAssignment encAssignment = new UserRoleHospitalAssignment();
        encAssignment.setId(UUID.randomUUID());
        encounter.setAssignment(encAssignment);

        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionMapper.toEntity(any(), any(), any(), any())).thenReturn(new Prescription());
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(PrescriptionResponseDTO.builder().build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolveAssignmentForStaff: fromStaff non-null, hospital non-null, but ID doesn't match ═══════════════

    @Test
    void createPrescriptionWhenStaffAssignmentHospitalIdMismatchFallsToUrha() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            // no encounterId → triggers auto-create
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID()); // different hospital ID

        UserRoleHospitalAssignment mismatchedAssignment = new UserRoleHospitalAssignment();
        mismatchedAssignment.setId(UUID.randomUUID());
        mismatchedAssignment.setHospital(otherHospital); // hospital doesn't match
        staff.setAssignment(mismatchedAssignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // no existing encounter
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            any(), any(), any())).thenReturn(Optional.empty());

        // URHA lookup returns a valid assignment
        UserRoleHospitalAssignment urhaAssignment = new UserRoleHospitalAssignment();
        urhaAssignment.setId(UUID.randomUUID());
        when(urhaRepository.findByUserIdAndHospitalIdAndRole_CodeIgnoreCaseAndActiveTrue(
            any(), eq(hospitalId), eq("DOCTOR"))).thenReturn(Optional.of(urhaAssignment));

        // auto-create encounter
        when(encounterRepository.save(any())).thenReturn(encounter);
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionMapper.toEntity(any(), any(), any(), any())).thenReturn(new Prescription());
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(PrescriptionResponseDTO.builder().build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolveAssignmentForStaff: staff.getUser() == null throws ═══════════════

    @Test
    void createPrescriptionWhenStaffUserNullThrowsMissingUser() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        staff.setUser(null); // null user on staff
        staff.setAssignment(null); // no direct assignment

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // no existing encounter
        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ matchesAllergen: reverse match (allergen contains medication) ═══════════════

    @Test
    void createPrescriptionWithAllergenContainingMedication() {
        // Set encounter assignment so resolvePrescriberAssignmentOrThrow returns immediately
        encounter.setAssignment(assignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);

        // allergen display is broader than medication name: "Amoxicillin Penicillin Compound"
        // medication is "penicillin" — allergenLower.contains(medLower) → true
        PatientAllergy allergy = PatientAllergy.builder()
            .allergenDisplay("Amoxicillin Penicillin Compound")
            .severity(com.example.hms.enums.AllergySeverity.MILD)
            .reaction("Rash")
            .active(true)
            .build();
        when(patientAllergyRepository.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId)))
            .thenReturn(List.of(allergy));

        when(prescriptionMapper.toEntity(any(), any(), any(), any())).thenReturn(new Prescription());
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(PrescriptionResponseDTO.builder().build());

        // MILD + no forceOverride → proceeds (logs warning but doesn't throw)
        PrescriptionRequestDTO allergenRequest = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .encounterId(encounterId)
            .medicationName("penicillin")
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        PrescriptionResponseDTO result = prescriptionService.createPrescription(allergenRequest, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ determineHospitalId: staff null, patient has hospitalId ═══════════════

    @Test
    void createPrescriptionDetermineHospitalIdFromPatientWhenStaffHospitalNull() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        // staff has no hospital → determineHospitalId falls to patient.getHospitalId()
        Staff noHospStaff = new Staff();
        noHospStaff.setId(staffId);
        noHospStaff.setHospital(null);
        User sUser = new User();
        sUser.setId(UUID.randomUUID());
        noHospStaff.setUser(sUser);

        // patient has hospitalId
        patient.setHospitalId(hospitalId);

        Hospital resolvedHosp = new Hospital();
        resolvedHosp.setId(hospitalId);

        // Build an encounter that passes ensureContextConsistency:
        // encounter.hospital != null, encounter.patient matches, encounter.staff matches noHospStaff
        // staff.hospital must not be null for consistency check → set it here after determineHospitalId
        // Actually, the found encounter sets its own staff to noHospStaff. But staff.hospital is null → throws.
        // This means the patient.hospitalId branch in determineHospitalId is tested but ensureContextConsistency fails.
        // We should expect BusinessException for the hospital mismatch.
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(noHospStaff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // The encounter found must also have noHospStaff as staff to pass consistency
        Encounter foundEnc = new Encounter();
        foundEnc.setId(UUID.randomUUID());
        foundEnc.setHospital(resolvedHosp);
        foundEnc.setPatient(patient);
        foundEnc.setStaff(noHospStaff);

        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            eq(patientId), eq(staffId), eq(hospitalId))).thenReturn(Optional.of(foundEnc));

        // staff.getHospital() is null → ensureContextConsistency throws
        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ determineHospitalId: staff null hospital, patient null hospitalId → roleValidator ═══════════════

    @Test
    void createPrescriptionDetermineHospitalIdFromRoleValidator() {
        PrescriptionRequestDTO request = PrescriptionRequestDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .medicationName(TEST_MEDICATION)
            .dosage(TEST_DOSAGE)
            .frequency(TEST_FREQUENCY)
            .build();

        // staff has no hospital, patient has no hospitalId
        // → determineHospitalId falls through to roleValidator.getCurrentHospitalId()
        Staff noHospStaff = new Staff();
        noHospStaff.setId(staffId);
        noHospStaff.setHospital(null);
        User sUser = new User();
        sUser.setId(UUID.randomUUID());
        noHospStaff.setUser(sUser);

        patient.setHospitalId(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(noHospStaff));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.getCurrentHospitalId()).thenReturn(hospitalId);

        Hospital resolvedHosp = new Hospital();
        resolvedHosp.setId(hospitalId);

        Encounter foundEnc = new Encounter();
        foundEnc.setId(UUID.randomUUID());
        foundEnc.setHospital(resolvedHosp);
        foundEnc.setPatient(patient);
        foundEnc.setStaff(noHospStaff); // matching staff

        when(encounterRepository.findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
            eq(patientId), eq(staffId), eq(hospitalId))).thenReturn(Optional.of(foundEnc));

        // staff.getHospital() is null → ensureContextConsistency throws
        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ updatePrescription: hospital null after ensureContextConsistency ═══════════════

    @Test
    void updatePrescriptionWhenHospitalNullAfterConsistencyThrows() {
        UUID prescId = UUID.randomUUID();
        PrescriptionRequestDTO request = buildRequest();

        Prescription existing = new Prescription();
        existing.setId(prescId);

        // encounter with null hospital will fail in ensureContextConsistency
        Encounter noHospEnc = Encounter.builder()
            .patient(patient)
            .staff(staff)
            .hospital(null)
            .build();
        noHospEnc.setId(encounterId);

        when(prescriptionRepository.findById(prescId)).thenReturn(Optional.of(existing));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(noHospEnc));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.updatePrescription(prescId, request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    // ═══════════════ updatePrescription: happy path all branches ═══════════════

    @Test
    void updatePrescriptionHappyPathFullFlow() {
        UUID prescId = UUID.randomUUID();
        PrescriptionRequestDTO request = buildRequest();

        Prescription existing = new Prescription();
        existing.setId(prescId);

        // Set encounter assignment so resolvePrescriberAssignmentOrThrow returns immediately
        encounter.setAssignment(assignment);

        when(prescriptionRepository.findById(prescId)).thenReturn(Optional.of(existing));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);
        when(prescriptionRepository.save(any())).thenReturn(existing);
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(PrescriptionResponseDTO.builder().build());

        PrescriptionResponseDTO result = prescriptionService.updatePrescription(prescId, request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ resolvePrescriberAssignmentOrThrow: encounter null ═══════════════

    @Test
    void createPrescriptionResolvePrescriberWhenEncounterAssignmentNull() {
        PrescriptionRequestDTO request = buildRequest();

        // encounter has no assignment → falls to resolveAssignmentForStaff
        encounter.setAssignment(null);
        // staff assignment matches hospital → resolveAssignmentForStaff returns it
        staff.setAssignment(assignment);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canCreatePrescription(any(), eq(hospitalId))).thenReturn(true);

        when(prescriptionMapper.toEntity(any(), any(), any(), any())).thenReturn(new Prescription());
        when(prescriptionRepository.save(any())).thenReturn(new Prescription());
        when(prescriptionMapper.toResponseDTO(any())).thenReturn(PrescriptionResponseDTO.builder().build());

        PrescriptionResponseDTO result = prescriptionService.createPrescription(request, Locale.ENGLISH);
        assertThat(result).isNotNull();
    }

    // ═══════════════ ensureContextConsistency: encounter.getStaff() non-null but ID mismatch ═══════════════

    @Test
    void createPrescriptionWhenEncounterStaffIdMismatchThrows() {
        PrescriptionRequestDTO request = buildRequest();

        Staff otherStaff = new Staff();
        otherStaff.setId(UUID.randomUUID()); // different staff ID
        encounter.setStaff(otherStaff);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> prescriptionService.createPrescription(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }
}
