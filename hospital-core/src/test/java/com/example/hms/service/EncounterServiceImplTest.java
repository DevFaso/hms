package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.*;
import com.example.hms.model.encounter.EncounterNoteHistory;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncounterServiceImplTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private EncounterMapper encounterMapper;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private EncounterHistoryRepository encounterHistoryRepository;
    @Mock private EncounterNoteRepository encounterNoteRepository;
    @Mock private EncounterNoteAddendumRepository encounterNoteAddendumRepository;
    @Mock private EncounterNoteHistoryRepository encounterNoteHistoryRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private ObgynReferralRepository obgynReferralRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks private EncounterServiceImpl service;

    private final Locale locale = Locale.ENGLISH;

    // ---------- getEncounterById ----------

    @Test
    void getEncounterById_success() {
        UUID id = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(id);
        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(id);

        when(encounterRepository.findById(id)).thenReturn(Optional.of(encounter));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        EncounterResponseDTO result = service.getEncounterById(id, locale);
        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void getEncounterById_notFound() {
        UUID id = UUID.randomUUID();
        when(encounterRepository.findById(id)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getEncounterById(id, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- deleteEncounter ----------

    @Test
    void deleteEncounter_success() {
        UUID id = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(id);
        when(encounterRepository.existsById(id)).thenReturn(true);
        when(encounterRepository.findById(id)).thenReturn(Optional.of(encounter));

        service.deleteEncounter(id, locale);

        verify(encounterRepository).deleteById(id);
    }

    @Test
    void deleteEncounter_notFound() {
        UUID id = UUID.randomUUID();
        when(encounterRepository.existsById(id)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.deleteEncounter(id, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getEncountersByPatientId ----------

    @Test
    void getEncountersByPatientId_success() {
        UUID patientId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        EncounterResponseDTO dto = new EncounterResponseDTO();

        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(encounter));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        List<EncounterResponseDTO> result = service.getEncountersByPatientId(patientId, locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEncountersByPatientId_patientNotFound() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.existsById(patientId)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getEncountersByPatientId(patientId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getEncountersByDoctorId ----------

    @Test
    void getEncountersByDoctorId_success() {
        UUID staffId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().build();
        user.setId(userId);
        Staff staff = Staff.builder().user(user).build();
        staff.setId(staffId);
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        EncounterResponseDTO dto = new EncounterResponseDTO();

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(roleValidator.isDoctor(userId, null)).thenReturn(true);
        when(encounterRepository.findByStaff_Id(staffId)).thenReturn(List.of(encounter));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        List<EncounterResponseDTO> result = service.getEncountersByDoctorId(staffId, locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEncountersByDoctorId_staffNotFound() {
        UUID staffId = UUID.randomUUID();
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getEncountersByDoctorId(staffId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getEncountersByDoctorId_notDoctor() {
        UUID staffId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().build();
        user.setId(userId);
        Staff staff = Staff.builder().user(user).build();
        staff.setId(staffId);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(roleValidator.isDoctor(userId, null)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("invalid");

        assertThatThrownBy(() -> service.getEncountersByDoctorId(staffId, locale))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void getEncountersByDoctorId_nullUser() {
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.builder().user(null).build();
        staff.setId(staffId);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("invalid");

        assertThatThrownBy(() -> service.getEncountersByDoctorId(staffId, locale))
            .isInstanceOf(BusinessException.class);
    }

    // ---------- getEncountersByPatientIdentifier ----------

    @Test
    void getEncountersByPatientIdentifier_success() {
        String identifier = "patient@example.com";
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        EncounterResponseDTO dto = new EncounterResponseDTO();

        when(patientRepository.findByUsernameOrEmail(identifier)).thenReturn(Optional.of(patient));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(encounter));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        List<EncounterResponseDTO> result = service.getEncountersByPatientIdentifier(identifier, locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEncountersByPatientIdentifier_patientNotFound() {
        when(patientRepository.findByUsernameOrEmail("unknown")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getEncountersByPatientIdentifier("unknown", locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- list ----------

    @Test
    @SuppressWarnings("unchecked")
    void list_returnsPage() {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        EncounterResponseDTO dto = new EncounterResponseDTO();
        Page<Encounter> page = new PageImpl<>(List.of(encounter));

        when(encounterRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        Pageable pageable = PageRequest.of(0, 10);
        Page<EncounterResponseDTO> result = service.list(null, null, null, null, null, null, pageable, locale);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_withAllFilters() {
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now();
        Page<Encounter> page = new PageImpl<>(List.of());

        when(encounterRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        Pageable pageable = PageRequest.of(0, 10);
        Page<EncounterResponseDTO> result = service.list(patientId, staffId, hospitalId, from, to, EncounterStatus.ARRIVED, pageable, locale);
        assertThat(result.getContent()).isEmpty();
    }

    // ---------- getEncounterNoteHistory ----------

    @Test
    void getEncounterNoteHistory_success() {
        UUID encounterId = UUID.randomUUID();

        EncounterNoteHistory history = new EncounterNoteHistory();
        history.setId(UUID.randomUUID());
        EncounterNoteHistoryResponseDTO histDto = EncounterNoteHistoryResponseDTO.builder()
            .id(history.getId()).build();

        when(encounterRepository.existsById(encounterId)).thenReturn(true);
        when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(encounterId))
            .thenReturn(List.of(history));
        when(encounterMapper.toEncounterNoteHistoryResponseDTO(history)).thenReturn(histDto);

        List<EncounterNoteHistoryResponseDTO> result = service.getEncounterNoteHistory(encounterId, locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEncounterNoteHistory_encounterNotFound() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.existsById(encounterId)).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.getEncounterNoteHistory(encounterId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- toDto (EncounterTreatment) ----------

    @Test
    void toDto_encounterTreatment_nullEntity() {
        assertThat(service.toDto(null)).isNull();
    }

    @Test
    void toDto_encounterTreatment_full() {
        Patient patient = Patient.builder().firstName("Jane").lastName("Doe").phoneNumberPrimary("555").build();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setPatient(patient);

        User user = User.builder().firstName("Dr").lastName("Smith").build();
        user.setId(UUID.randomUUID());
        Staff staff = Staff.builder().user(user).build();
        staff.setId(UUID.randomUUID());

        Treatment treatment = Treatment.builder().name("Therapy").build();
        treatment.setId(UUID.randomUUID());

        EncounterTreatment et = EncounterTreatment.builder()
            .encounter(encounter)
            .staff(staff)
            .treatment(treatment)
            .performedAt(LocalDateTime.now())
            .outcome("Good")
            .notes("Notes")
            .build();
        et.setId(UUID.randomUUID());

        EncounterTreatmentResponseDTO result = service.toDto(et);
        assertThat(result).isNotNull();
        assertThat(result.getEncounterId()).isEqualTo(encounter.getId());
        assertThat(result.getStaffFullName()).isEqualTo("Dr Smith");
        assertThat(result.getTreatmentName()).isEqualTo("Therapy");
    }

    @Test
    void toDto_encounterTreatment_nullStaffUser() {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        Staff staff = Staff.builder().user(null).name("Nurse Bob").build();
        staff.setId(UUID.randomUUID());

        EncounterTreatment et = EncounterTreatment.builder()
            .encounter(encounter)
            .staff(staff)
            .build();
        et.setId(UUID.randomUUID());

        EncounterTreatmentResponseDTO result = service.toDto(et);
        assertThat(result.getStaffFullName()).isEqualTo("Nurse Bob");
    }

    @Test
    void toDto_encounterTreatment_nullStaff() {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());

        EncounterTreatment et = EncounterTreatment.builder()
            .encounter(encounter)
            .staff(null)
            .treatment(null)
            .build();
        et.setId(UUID.randomUUID());

        EncounterTreatmentResponseDTO result = service.toDto(et);
        assertThat(result.getStaffId()).isNull();
        assertThat(result.getStaffFullName()).isNull();
    }

    @Test
    void toDto_encounterTreatment_nullEncounter() {
        EncounterTreatment et = EncounterTreatment.builder()
            .encounter(null)
            .build();
        et.setId(UUID.randomUUID());

        EncounterTreatmentResponseDTO result = service.toDto(et);
        assertThat(result.getEncounterId()).isNull();
        assertThat(result.getPatientId()).isNull();
    }
}
