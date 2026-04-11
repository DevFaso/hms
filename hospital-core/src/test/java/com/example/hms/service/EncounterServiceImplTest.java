package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.model.User;
import com.example.hms.model.encounter.EncounterNoteHistory;
import com.example.hms.payload.dto.EncounterNoteHistoryResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import com.example.hms.payload.dto.TriageSubmissionResponseDTO;
import com.example.hms.payload.dto.NursingIntakeRequestDTO;
import com.example.hms.payload.dto.NursingIntakeResponseDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterHistoryRepository;
import com.example.hms.repository.EncounterNoteAddendumRepository;
import com.example.hms.repository.EncounterNoteHistoryRepository;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.ObgynReferralRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import java.util.Locale;

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
    @Mock private com.example.hms.repository.PatientVitalSignRepository patientVitalSignRepository;
    @Mock private com.example.hms.mapper.PatientVitalSignMapper patientVitalSignMapper;
    @Mock private com.example.hms.repository.PatientAllergyRepository patientAllergyRepository;
    @Mock private com.example.hms.mapper.CheckOutMapper checkOutMapper;

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

        UUID testHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(testHospitalId);
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

    // ─── submitTriage (MVP 2) ──────────────────────────────────

    @Test
    void submitTriage_happyPath_arrivedEncounter() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.ARRIVED);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder()
                .esiScore(3)
                .chiefComplaint("Headache")
                .roomAssignment("ER-Bay-3")
                .temperatureCelsius(37.2)
                .heartRateBpm(80)
                .build();

        PatientVitalSign savedVital = new PatientVitalSign();
        savedVital.setId(UUID.randomUUID());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(patientVitalSignRepository.save(any(PatientVitalSign.class))).thenReturn(savedVital);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        TriageSubmissionResponseDTO result = service.submitTriage(encounterId, request, "nurse1");

        assertThat(result.getEncounterId()).isEqualTo(encounterId);
        assertThat(result.getEncounterStatus()).isEqualTo("WAITING_FOR_PHYSICIAN");
        assertThat(result.getEsiScore()).isEqualTo(3);
        assertThat(result.getUrgency()).isEqualTo("ROUTINE");
        assertThat(result.getRoomAssignment()).isEqualTo("ER-Bay-3");
        assertThat(result.getChiefComplaint()).isEqualTo("Headache");
        assertThat(result.getTriageTimestamp()).isNotNull();
        assertThat(result.getRoomedTimestamp()).isNotNull();
        assertThat(result.getVitalSignId()).isEqualTo(savedVital.getId());

        verify(patientVitalSignRepository).save(any(PatientVitalSign.class));
        verify(encounterRepository).save(any(Encounter.class));
    }

    @Test
    void submitTriage_encounterNotFound_throws() {
        UUID encounterId = UUID.randomUUID();
        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder().esiScore(2).build();

        when(encounterRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenReturn("Encounter not found");

        assertThatThrownBy(() -> service.submitTriage(encounterId, request, "nurse1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void submitTriage_invalidStatus_throws() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.COMPLETED);

        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder().esiScore(2).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> service.submitTriage(encounterId, request, "nurse1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot submit triage");
    }

    @Test
    void submitTriage_triageStatus_succeeds() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.TRIAGE);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder()
                .esiScore(1)
                .build();

        PatientVitalSign savedVital = new PatientVitalSign();
        savedVital.setId(UUID.randomUUID());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(patientVitalSignRepository.save(any(PatientVitalSign.class))).thenReturn(savedVital);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        TriageSubmissionResponseDTO result = service.submitTriage(encounterId, request, "nurse1");

        assertThat(result.getEncounterStatus()).isEqualTo("WAITING_FOR_PHYSICIAN");
        assertThat(result.getUrgency()).isEqualTo("EMERGENT");
        assertThat(result.getEsiScore()).isEqualTo(1);
    }

    @Test
    void submitTriage_esiMapping() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        PatientVitalSign savedVital = new PatientVitalSign();
        savedVital.setId(UUID.randomUUID());

        // Test ESI 2 → URGENT
        Encounter enc2 = new Encounter();
        enc2.setId(encounterId);
        enc2.setStatus(EncounterStatus.ARRIVED);
        enc2.setHospital(hospital);
        enc2.setPatient(patient);

        TriageSubmissionRequestDTO req2 = TriageSubmissionRequestDTO.builder().esiScore(2).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(enc2));
        when(patientVitalSignRepository.save(any(PatientVitalSign.class))).thenReturn(savedVital);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.submitTriage(encounterId, req2, "nurse1").getUrgency()).isEqualTo("URGENT");

        // Test ESI 4 → LOW
        Encounter enc4 = new Encounter();
        enc4.setId(encounterId);
        enc4.setStatus(EncounterStatus.ARRIVED);
        enc4.setHospital(hospital);
        enc4.setPatient(patient);

        TriageSubmissionRequestDTO req4 = TriageSubmissionRequestDTO.builder().esiScore(4).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(enc4));

        assertThat(service.submitTriage(encounterId, req4, "nurse1").getUrgency()).isEqualTo("LOW");

        // Test ESI 5 → LOW
        Encounter enc5 = new Encounter();
        enc5.setId(encounterId);
        enc5.setStatus(EncounterStatus.ARRIVED);
        enc5.setHospital(hospital);
        enc5.setPatient(patient);

        TriageSubmissionRequestDTO req5 = TriageSubmissionRequestDTO.builder().esiScore(5).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(enc5));

        assertThat(service.submitTriage(encounterId, req5, "nurse1").getUrgency()).isEqualTo("LOW");
    }

    // ---------- submitNursingIntake ----------

    @Test
    void submitNursingIntake_happyPath_succeeds() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder()
                .nursingAssessmentNotes("Alert and oriented x3")
                .chiefComplaint("Headache persists")
                .painAssessment("Pain 6/10 frontal")
                .fallRiskDetail("Morse score 25 — low risk")
                .build();

        User user = new User();
        user.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("nurse1")).thenReturn(Optional.of(user));
        when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId()))
                .thenReturn(Optional.of(staff));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1");

        assertThat(result.getEncounterId()).isEqualTo(encounterId);
        assertThat(result.getEncounterStatus()).isEqualTo("WAITING_FOR_PHYSICIAN");
        assertThat(result.getIntakeTimestamp()).isNotNull();
        assertThat(result.getAllergyCount()).isZero();
        assertThat(result.getMedicationCount()).isZero();
        assertThat(result.isNursingNoteRecorded()).isTrue();

        verify(encounterRepository).save(any(Encounter.class));
    }

    @Test
    void submitNursingIntake_encounterNotFound_throws() {
        UUID encounterId = UUID.randomUUID();
        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder().build();

        when(encounterRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenReturn("Encounter not found");

        assertThatThrownBy(() -> service.submitNursingIntake(encounterId, request, "nurse1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void submitNursingIntake_invalidStatus_throws() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.COMPLETED);

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder().build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> service.submitNursingIntake(encounterId, request, "nurse1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot submit nursing intake");
    }

    @Test
    void submitNursingIntake_withAllergies_savesAllergies() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        com.example.hms.payload.dto.PatientAllergyRequestDTO allergyReq =
                com.example.hms.payload.dto.PatientAllergyRequestDTO.builder()
                        .allergenDisplay("Penicillin")
                        .category("MEDICATION")
                        .severity(com.example.hms.enums.AllergySeverity.SEVERE)
                        .build();

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder()
                .allergies(List.of(allergyReq))
                .build();

        User user = new User();
        user.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("nurse1")).thenReturn(Optional.of(user));
        when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId()))
                .thenReturn(Optional.of(staff));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientAllergyRepository.save(any(com.example.hms.model.PatientAllergy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1");

        assertThat(result.getAllergyCount()).isEqualTo(1);
        verify(patientAllergyRepository).save(any(com.example.hms.model.PatientAllergy.class));
    }

    @Test
    void submitNursingIntake_triageStatus_succeeds() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.TRIAGE);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder()
                .nursingAssessmentNotes("Baseline vitals obtained")
                .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1");

        assertThat(result.getEncounterStatus()).isEqualTo("TRIAGE");
        assertThat(result.isNursingNoteRecorded()).isTrue();
    }

    @Test
    void submitNursingIntake_emptyRequest_noNoteRecorded() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder().build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1");

        assertThat(result.isNursingNoteRecorded()).isFalse();
        assertThat(result.getAllergyCount()).isZero();
        assertThat(result.getMedicationCount()).isZero();
    }

    // ---------- checkOut (MVP 6) ----------

    @Test
    void checkOut_fromInProgress_transitionsToCompleted() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpInstructions("Follow up in 2 weeks")
            .dischargeDiagnoses(List.of("Upper respiratory infection"))
            .build();

        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder()
            .encounterId(encounterId)
            .followUpInstructions("Follow up in 2 weeks")
            .build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.serializeDiagnoses(List.of("Upper respiratory infection"))).thenReturn("[\"Upper respiratory infection\"]");
        when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any())).thenReturn(avs);

        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1");

        assertThat(result.getEncounterId()).isEqualTo(encounterId);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(encounter.getCheckoutTimestamp()).isNotNull();
        assertThat(encounter.getFollowUpInstructions()).isEqualTo("Follow up in 2 weeks");
        verify(encounterRepository).save(encounter);
    }

    @Test
    void checkOut_fromReadyForDischarge_transitionsToCompleted() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.READY_FOR_DISCHARGE);
        encounter.setHospital(new Hospital());
        encounter.setPatient(new Patient());

        CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder().encounterId(encounterId).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any())).thenReturn(avs);

        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1");

        assertThat(result).isNotNull();
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }

    @Test
    void checkOut_fromAwaitingResults_transitionsToCompleted() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.AWAITING_RESULTS);
        encounter.setHospital(new Hospital());
        encounter.setPatient(new Patient());

        CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder().encounterId(encounterId).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any())).thenReturn(avs);

        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1");

        assertThat(result).isNotNull();
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }

    @Test
    void checkOut_alreadyCompleted_throwsBusinessException() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.COMPLETED);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
        assertThatThrownBy(() -> service.checkOut(encounterId, request, "doctor1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("COMPLETED");

        verify(encounterRepository, never()).save(any());
    }

    @Test
    void checkOut_cancelled_throwsBusinessException() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.CANCELLED);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        CheckOutRequestDTO cancelledRequest = CheckOutRequestDTO.builder().build();
        assertThatThrownBy(() -> service.checkOut(encounterId, cancelledRequest, "doctor1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CANCELLED");

        verify(encounterRepository, never()).save(any());
    }

    @Test
    void checkOut_encounterNotFound_throwsResourceNotFound() {
        UUID encounterId = UUID.randomUUID();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Encounter not found");

        CheckOutRequestDTO notFoundRequest = CheckOutRequestDTO.builder().build();
        assertThatThrownBy(() -> service.checkOut(encounterId, notFoundRequest, "doctor1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void checkOut_withLinkedAppointment_transitionsAppointmentToCompleted() {
        UUID encounterId = UUID.randomUUID();

        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setStatus(AppointmentStatus.CHECKED_IN);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(new Hospital());
        encounter.setPatient(new Patient());
        encounter.setAppointment(appointment);

        CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder().encounterId(encounterId).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any())).thenReturn(avs);

        service.checkOut(encounterId, request, "doctor1");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void checkOut_appointmentAlreadyCompleted_skipsAppointmentUpdate() {
        UUID encounterId = UUID.randomUUID();

        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setStatus(AppointmentStatus.COMPLETED);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(new Hospital());
        encounter.setPatient(new Patient());
        encounter.setAppointment(appointment);

        CheckOutRequestDTO request = CheckOutRequestDTO.builder().build();
        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder().encounterId(encounterId).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any())).thenReturn(avs);

        service.checkOut(encounterId, request, "doctor1");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void checkOut_nullRequest_setsNoDischargeFields() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(new Hospital());
        encounter.setPatient(new Patient());

        AfterVisitSummaryDTO avs = AfterVisitSummaryDTO.builder().encounterId(encounterId).build();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any())).thenReturn(avs);

        AfterVisitSummaryDTO result = service.checkOut(encounterId, null, "doctor1");

        assertThat(result).isNotNull();
        assertThat(encounter.getFollowUpInstructions()).isNull();
        assertThat(encounter.getDischargeDiagnoses()).isNull();
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }
}
