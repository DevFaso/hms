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
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.encounter.EncounterNoteHistory;
import com.example.hms.model.discharge.DischargeSummary;
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
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import java.util.Locale;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import java.util.Map;
import java.util.Set;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.service.i18n.PatientLocaleResolver;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class EncounterServiceImplTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private EncounterMapper encounterMapper;
    @Mock private com.example.hms.service.allergy.PatientAllergySummarySync allergySummarySync;
    @Mock private MessageSource messageSource;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;
    @Mock private com.example.hms.service.recordaccess.BreakGlassGate breakGlassGate;
    @Mock private EncounterHistoryRepository encounterHistoryRepository;
    @Mock private EncounterNoteRepository encounterNoteRepository;
    @Mock private EncounterNoteAddendumRepository encounterNoteAddendumRepository;
    @Mock private EncounterNoteHistoryRepository encounterNoteHistoryRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private ObgynReferralRepository obgynReferralRepository;
    @Mock private UserRepository userRepository;
    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.example.hms.repository.PatientVitalSignRepository patientVitalSignRepository;
    @Mock private com.example.hms.mapper.PatientVitalSignMapper patientVitalSignMapper;
    @Mock private com.example.hms.repository.PatientAllergyRepository patientAllergyRepository;
    @Mock private com.example.hms.mapper.CheckOutMapper checkOutMapper;
    @Mock private com.example.hms.repository.ProcedureOrderRepository procedureOrderRepository;
    @Mock private com.example.hms.repository.PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    @Mock private com.example.hms.service.PatientTrackerEventPublisher trackerEventPublisher;
    @Mock private com.example.hms.repository.scheduling.PatientRecallRepository patientRecallRepository;
    @Mock private PatientLocaleResolver patientLocaleResolver;
    @Mock private com.example.hms.controller.support.ControllerAuthUtils authUtils;
    @Mock private com.example.hms.service.vitals.News2EscalationService news2EscalationService;

    /** QA D1060: the slot rules, stubbed to their 30-minute fallback (SlotPolicyStubs). */
    @org.mockito.Mock
    private com.example.hms.service.scheduling.AppointmentSlotPolicy slotPolicy;

    @InjectMocks private EncounterServiceImpl service;

    private final Locale locale = Locale.ENGLISH;

    /**
     * QA D788: the check-out of a visit IN_PROGRESS is the clinician's own; the
     * existing check-out tests are the doctor's, so they run as a doctor. The
     * desk's refusals are in {@code EncounterCloseUnseenTest}.
     */
    private static void signInAsDoctor() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("doctor1", "n/a",
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_DOCTOR")));
    }

    @org.junit.jupiter.api.AfterEach
    void clearSignInAndLocale() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        // QA D952 tests switch the request locale to French.
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    @BeforeEach
    void setUpCheckoutDefaults() {
        com.example.hms.testsupport.SlotPolicyStubs.defaultLength(slotPolicy);
        signInAsDoctor();
        // The services ask the one PatientSubjectReadGuard; it is built here over
        // this class's authUtils and patientRepository so ownership is decided
        // exactly as before, by those two.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subjectReadGuard",
            new PatientSubjectReadGuard(authUtils, patientRepository));
        lenient().when(dischargeSummaryRepository.findByEncounter_Id(any(UUID.class))).thenReturn(Optional.empty());
        lenient().when(dischargeSummaryRepository.save(any(DischargeSummary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- getEncounterById ----------

    @Test
    void getEncounterById_success() {
        UUID id = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(id);
        // The encounter needs a hospital, and the caller needs to be at it:
        // the read guard refuses an encounter it cannot place, as the write
        // guard requireEncounterInScope already did. Encounter.hospital is
        // nullable = false, so the old fixture's hospital-less encounter was
        // a shape the database cannot hold.
        encounter.setHospital(hospital);
        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(id);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(encounterRepository.findById(id)).thenReturn(Optional.of(encounter));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        EncounterResponseDTO result = service.getEncounterById(id, locale);
        assertThat(result.getId()).isEqualTo(id);
    }

    @Test
    void getEncounterById_notFound() {
        UUID id = UUID.randomUUID();
        // A caller who could otherwise read: with no scope and no verified
        // super-admin flag the read is refused BEFORE the lookup, and this
        // test is about the answer the lookup gives.
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(encounterRepository.findById(id)).thenReturn(Optional.empty());

        // No messageSource stub: the read now builds its not-found through
        // encounterNotFound(id), which carries the KEY and the id rather than
        // resolved prose — the rule the write paths already follow.
        assertThatThrownBy(() -> service.getEncounterById(id, locale))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(e -> ((ResourceNotFoundException) e).getMessageKey())
            .isEqualTo("encounter.notfound");
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
    void getEncountersByPatientId_leavesPrescriptionAnchorsOut() {
        // QA D759: the clinician's visit list showed each prescription anchor
        // as a COMPLETED consultation; only the patient portal filtered them.
        UUID patientId = UUID.randomUUID();
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        Encounter anchor = new Encounter();
        anchor.setId(UUID.randomUUID());
        anchor.setNotes(Encounter.PRESCRIPTION_ANCHOR_NOTE);
        EncounterResponseDTO dto = new EncounterResponseDTO();

        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(visit, anchor));
        when(encounterMapper.toEncounterResponseDTO(visit)).thenReturn(dto);

        assertThat(service.getEncountersByPatientId(patientId, locale)).containsExactly(dto);
        verify(encounterMapper, never()).toEncounterResponseDTO(anchor);
    }

    @Test
    void getEncountersByPatientId_patientNotFound() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.existsById(patientId)).thenReturn(false);

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
        UUID hospitalId = UUID.randomUUID();
        Hospital noteHistoryHospital = new Hospital();
        noteHistoryHospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(noteHistoryHospital);

        EncounterNoteHistory history = new EncounterNoteHistory();
        history.setId(UUID.randomUUID());
        EncounterNoteHistoryResponseDTO histDto = EncounterNoteHistoryResponseDTO.builder()
            .id(history.getId()).build();

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterNoteHistoryRepository.findByEncounterIdOrderByChangedAtDesc(encounterId))
            .thenReturn(List.of(history));
        when(encounterMapper.toEncounterNoteHistoryResponseDTO(history)).thenReturn(histDto);

        List<EncounterNoteHistoryResponseDTO> result = service.getEncounterNoteHistory(encounterId, locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEncounterNoteHistory_encounterNotFound() {
        UUID encounterId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEncounterNoteHistory(encounterId, locale))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(e -> ((ResourceNotFoundException) e).getMessageKey())
            .isEqualTo("encounter.notfound");
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

        TriageSubmissionResponseDTO result = service.submitTriage(encounterId, request, "nurse1", true, null);

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
    void submitTriage_routesTheVitalsThroughTheNews2Escalation_D265() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        com.example.hms.model.User doctorUser = new com.example.hms.model.User();
        doctorUser.setUsername("doctor_a");
        Staff doctor = new Staff();
        doctor.setUser(doctorUser);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.ARRIVED);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(doctor);

        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder()
                .esiScore(2).respiratoryRateBpm(30).spo2Percent(88).systolicBpMmHg(85)
                .heartRateBpm(135).temperatureCelsius(39.5).build();

        PatientVitalSign savedVital = new PatientVitalSign();
        savedVital.setId(UUID.randomUUID());
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(patientVitalSignRepository.save(any(PatientVitalSign.class))).thenReturn(savedVital);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitTriage(encounterId, request, "nurse_a", true, null);

        org.mockito.ArgumentCaptor<PatientVitalSign> vital = org.mockito.ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(news2EscalationService).escalateIfNeeded(vital.capture(), eq(patient.getId()),
                eq(hospital.getId()), eq("nurse_a"), eq("doctor_a"));
        assertThat(vital.getValue().getRespiratoryRateBpm()).isEqualTo(30);
        assertThat(vital.getValue().getSpo2Percent()).isEqualTo(88);
    }

    @Test
    void submitTriage_encounterNotFound_throws() {
        UUID encounterId = UUID.randomUUID();
        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder().esiScore(2).build();

        when(encounterRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        // The not-found refusal now carries the message KEY, not resolved prose,
        // so no messageSource stub is needed (and strict stubs reject one).

        assertThatThrownBy(() -> service.submitTriage(encounterId, request, "nurse1", true, null))
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

        assertThatThrownBy(() -> service.submitTriage(encounterId, request, "nurse1", true, null))
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

        TriageSubmissionResponseDTO result = service.submitTriage(encounterId, request, "nurse1", true, null);

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

        assertThat(service.submitTriage(encounterId, req2, "nurse1", true, null).getUrgency()).isEqualTo("URGENT");

        // Test ESI 4 → LOW
        Encounter enc4 = new Encounter();
        enc4.setId(encounterId);
        enc4.setStatus(EncounterStatus.ARRIVED);
        enc4.setHospital(hospital);
        enc4.setPatient(patient);

        TriageSubmissionRequestDTO req4 = TriageSubmissionRequestDTO.builder().esiScore(4).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(enc4));

        assertThat(service.submitTriage(encounterId, req4, "nurse1", true, null).getUrgency()).isEqualTo("LOW");

        // Test ESI 5 → LOW
        Encounter enc5 = new Encounter();
        enc5.setId(encounterId);
        enc5.setStatus(EncounterStatus.ARRIVED);
        enc5.setHospital(hospital);
        enc5.setPatient(patient);

        TriageSubmissionRequestDTO req5 = TriageSubmissionRequestDTO.builder().esiScore(5).build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(enc5));

        assertThat(service.submitTriage(encounterId, req5, "nurse1", true, null).getUrgency()).isEqualTo("LOW");
    }

    // ---------- submitNursingIntake ----------

    /** A mock message source that answers from the real bundles, in French. */
    private void answerFromTheRealFrenchBundles() {
        org.springframework.context.MessageSource real = new com.example.hms.config.LocaleConfig().messageSource();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenAnswer(inv -> real.getMessage(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    @Test
    void qaD952_theIntakeNoteHeadersAreWrittenInTheCliniciansLanguage() {
        answerFromTheRealFrenchBundles();
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
        encounter.setNotes("Motif : céphalées");

        NursingIntakeRequestDTO request = NursingIntakeRequestDTO.builder()
                .nursingAssessmentNotes("Conscient, orienté")
                .painAssessment("EVA 6/10")
                .fallRiskDetail("Morse 25")
                .medications(List.of(NursingIntakeRequestDTO.MedicationReconciliationEntry.builder()
                        .medicationName("Paracétamol").stillTaking(false).build()))
                .build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitNursingIntake(encounterId, request, null, true, null);

        assertThat(encounter.getNotes())
            .contains("--- Admission infirmière ---")
            .contains("[Évaluation infirmière]")
            .contains("[Évaluation de la douleur]")
            .contains("[Risque de chute]")
            .contains("[Réconciliation médicamenteuse] 1 médicament déclaré :")
            .contains("Paracétamol (arrêté)")
            .doesNotContain("Nursing")
            .doesNotContain("medication(s)")
            .doesNotContain("DISCONTINUED");
    }

    @Test
    void qaD989_theTriagePainScoreIsStoredInTheCliniciansLanguage() {
        answerFromTheRealFrenchBundles();
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
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        org.mockito.ArgumentCaptor<PatientVitalSign> vital = org.mockito.ArgumentCaptor.forClass(PatientVitalSign.class);
        PatientVitalSign saved = new PatientVitalSign();
        saved.setId(UUID.randomUUID());
        when(patientVitalSignRepository.save(vital.capture())).thenReturn(saved);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitTriage(encounterId, TriageSubmissionRequestDTO.builder().esiScore(3).painScale(8).build(),
            null, true, null);

        assertThat(vital.getValue().getNotes()).isEqualTo("Douleur : 8/10");
    }

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

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1", true, null);

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
        // The not-found refusal now carries the message KEY, not resolved prose,
        // so no messageSource stub is needed (and strict stubs reject one).

        assertThatThrownBy(() -> service.submitNursingIntake(encounterId, request, "nurse1", true, null))
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

        assertThatThrownBy(() -> service.submitNursingIntake(encounterId, request, "nurse1", true, null))
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

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1", true, null);

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

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1", true, null);

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

        NursingIntakeResponseDTO result = service.submitNursingIntake(encounterId, request, "nurse1", true, null);

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
        hospital.setName("General Hospital");

        User patientUser = User.builder().username("patient1").build();
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setEmail("patient@example.com");
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setUser(patientUser);

        Staff staff = new Staff();
        staff.setName("Dr. Smith");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(staff);
        encounter.setAssignment(assignment);

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

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1", true, null);

        assertThat(result.getEncounterId()).isEqualTo(encounterId);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(encounter.getCheckoutTimestamp()).isNotNull();
        assertThat(encounter.getFollowUpInstructions()).isEqualTo("Follow up in 2 weeks");
        verify(encounterRepository).save(encounter);
        verify(dischargeSummaryRepository).save(any(DischargeSummary.class));
        verify(notificationService).created(anyString(), org.mockito.ArgumentMatchers.eq("patient1"), org.mockito.ArgumentMatchers.eq("DISCHARGE_SUMMARY"));
        verify(emailService).sendHtml(org.mockito.ArgumentMatchers.eq(List.of("patient@example.com")), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.argThat((String subject) -> !"Your After-Visit Summary is Ready".equals(subject)), anyString());
    }

    @Test
    void qaD972_checkoutPublishesTheCompletedVisitWithItsBillableItems() {
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
        encounter.setEncounterType(com.example.hms.enums.EncounterType.OUTPATIENT);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any()))
            .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");
        com.example.hms.model.Prescription signed = new com.example.hms.model.Prescription();
        signed.setId(UUID.randomUUID());
        signed.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        com.example.hms.model.Prescription cancelled = new com.example.hms.model.Prescription();
        cancelled.setId(UUID.randomUUID());
        cancelled.setStatus(com.example.hms.enums.PrescriptionStatus.CANCELLED);
        when(prescriptionRepository.findIdsByEncounterIdAndStatusNotIn(eq(encounterId),
                org.mockito.ArgumentMatchers.argThat(excluded -> excluded.contains(com.example.hms.enums.PrescriptionStatus.CANCELLED))))
            .thenReturn(List.of(signed.getId()));
        UUID labOrderId = UUID.randomUUID();
        when(labOrderRepository.findIdsByEncounterIdAndStatusNotIn(eq(encounterId),
                org.mockito.ArgumentMatchers.argThat(excluded -> excluded.contains(com.example.hms.enums.LabOrderStatus.CANCELLED))))
            .thenReturn(List.of(labOrderId));

        service.checkOut(encounterId, CheckOutRequestDTO.builder().build(), "doctor1", true, null);

        org.mockito.ArgumentCaptor<com.example.hms.event.EncounterCheckedOutEvent> event =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.event.EncounterCheckedOutEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().encounterId()).isEqualTo(encounterId);
        assertThat(event.getValue().prescriptionIds()).containsExactly(signed.getId());
        assertThat(event.getValue().labOrderIds()).containsExactly(labOrderId);
        assertThat(event.getValue().checkedOutAt()).isNotNull();
        assertThat(event.getValue().encounterType()).isEqualTo("OUTPATIENT");
        assertThat(event.getValue().patientId()).isEqualTo(patient.getId());
    }

    @Test
    void qaD930_theDoctorWhoChecksOutAVisitANurseOpenedOwnsItsSummary() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Staff nurse = new Staff();
        nurse.setId(UUID.randomUUID());
        User nurseUser = new User();
        nurseUser.setId(UUID.randomUUID());
        nurse.setUser(nurseUser);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(nurse);
        User doctorUser = new User();
        doctorUser.setId(UUID.randomUUID());
        Staff doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setUser(doctorUser);
        UserRoleHospitalAssignment doctorAssignment = new UserRoleHospitalAssignment();
        doctorAssignment.setId(UUID.randomUUID());
        doctor.setAssignment(doctorAssignment);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(doctorUser, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DOCTOR"))));
        try {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserIdAndHospitalId(doctorUser.getId(), hospital.getId())).thenReturn(Optional.of(doctor));
            // Review of #971: the closing doctor is a doctor at the visit's hospital.
            when(roleValidator.isDoctor(doctorUser.getId(), hospital.getId())).thenReturn(true);
            lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any()))
                .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
            lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
            lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

            service.checkOut(encounterId, CheckOutRequestDTO.builder().build(), "doctor1", true, null);

            org.mockito.ArgumentCaptor<DischargeSummary> saved = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
            verify(dischargeSummaryRepository).save(saved.capture());
            assertThat(saved.getValue().getDischargingProvider()).isEqualTo(doctor);
            // Review of #971: filed under her doctor assignment, not any assignment she holds.
            assertThat(saved.getValue().getAssignment()).isSameAs(doctorAssignment);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void qaD930_aDoctorElsewhereWhoIsANurseHereDoesNotTakeTheSummary() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Staff nurse = new Staff();
        nurse.setId(UUID.randomUUID());
        User nurseUser = new User();
        nurseUser.setId(UUID.randomUUID());
        nurse.setUser(nurseUser);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(nurse);
        User doctorUser = new User();
        doctorUser.setId(UUID.randomUUID());
        Staff doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setUser(doctorUser);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(doctorUser, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DOCTOR"))));
        try {
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserIdAndHospitalId(doctorUser.getId(), hospital.getId())).thenReturn(Optional.of(doctor));
            lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any()))
                .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
            lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
            lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

            service.checkOut(encounterId, CheckOutRequestDTO.builder().build(), "doctor1", true, null);

            org.mockito.ArgumentCaptor<DischargeSummary> saved = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
            verify(dischargeSummaryRepository).save(saved.capture());
            // Her token says doctor (another hospital); here she is not one, so the
            // summary stays with the visit's clinician of record.
            assertThat(saved.getValue().getDischargingProvider()).isEqualTo(nurse);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void checkOut_keepsThePrescriptionSummaryAndEducationOnTheDischargeSummary_D224() {
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
            .dischargeDiagnoses(List.of("Paludisme simple"))
            .prescriptionSummary("Artéméther-luméfantrine 3 jours")
            .referralSummary("Aucune")
            .patientEducationMaterials("Moustiquaire imprégnée chaque nuit")
            .build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any()))
            .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        service.checkOut(encounterId, request, "doctor1", true, null);

        org.mockito.ArgumentCaptor<DischargeSummary> saved = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
        verify(dischargeSummaryRepository).save(saved.capture());
        assertThat(saved.getValue().getPrescriptionSummary()).isEqualTo("Artéméther-luméfantrine 3 jours");
        assertThat(saved.getValue().getReferralSummary()).isEqualTo("Aucune");
        assertThat(saved.getValue().getPatientEducationProvided()).isEqualTo("Moustiquaire imprégnée chaque nuit");
    }

    @Test
    void qaD744_checkOut_storesTheSignedNoteInstructionsAndPlanForThePatient() {
        // Round 10 re-test: the patient AVS (the stored discharge summary)
        // showed only the checkout follow-up text.
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
        com.example.hms.model.encounter.EncounterNote note = new com.example.hms.model.encounter.EncounterNote();
        note.setPatientInstructions("Marcher 30 minutes par jour.");
        note.setPlan("Metformine 500 mg deux fois par jour.");
        note.setSignedAt(java.time.LocalDateTime.now().minusMinutes(5));
        encounter.setEncounterNote(note);
        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpInstructions("Revoir dans 3 mois avec le résultat HbA1c")
            .build();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(checkOutMapper.toAfterVisitSummary(any(Encounter.class), any(CheckOutRequestDTO.class), any()))
            .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        service.checkOut(encounterId, request, "doctor1", true, null);

        org.mockito.ArgumentCaptor<DischargeSummary> saved = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
        verify(dischargeSummaryRepository).save(saved.capture());
        assertThat(saved.getValue().getFollowUpInstructions())
            .contains("Revoir dans 3 mois avec le résultat HbA1c")
            .contains("Marcher 30 minutes par jour.")
            .contains("Metformine 500 mg deux fois par jour.");
    }

    @Test
    void checkOut_notificationOrEmailFailure_doesNotRollback() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("General Hospital");

        User patientUser = User.builder().username("patient1").email("patient.user@example.com").build();
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setEmail(null); // force fallback to user email
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setUser(patientUser);

        Staff staff = new Staff();
        staff.setName("Dr. Smith");

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(staff);

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
        doThrow(new RuntimeException("ws down")).when(notificationService)
            .created(anyString(), org.mockito.ArgumentMatchers.eq("patient1"), org.mockito.ArgumentMatchers.eq("DISCHARGE_SUMMARY"));
        doThrow(new RuntimeException("smtp down")).when(emailService)
            .sendHtml(org.mockito.ArgumentMatchers.eq(List.of("patient.user@example.com")), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.argThat((String subject) -> !"Your After-Visit Summary is Ready".equals(subject)), anyString());

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1", true, null);

        assertThat(result.getEncounterId()).isEqualTo(encounterId);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        verify(dischargeSummaryRepository).save(any(DischargeSummary.class));
        verify(emailService).sendHtml(org.mockito.ArgumentMatchers.eq(List.of("patient.user@example.com")), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.argThat((String subject) -> !"Your After-Visit Summary is Ready".equals(subject)), anyString());
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

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1", true, null);

        assertThat(result).isNotNull();
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }

    // ---------- checkout recall feed (P3 #22) ----------

    private Encounter checkoutRecallEncounter(UUID encounterId) {
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        encounter.setPatient(patient);
        Staff staff = new Staff();
        encounter.setStaff(staff);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkOutMapper.toAfterVisitSummary(any(), any(), any()))
            .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounterId).build());
        return encounter;
    }

    @Test
    void checkOut_followUpRequest_becomesARecall() {
        // The follow-up request was captured since MVP 6 and silently dropped —
        // this is where the desk finally sees it.
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = checkoutRecallEncounter(encounterId);
        LocalDate preferred = LocalDate.now().plusWeeks(6);

        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                .reason("Repeat HbA1c")
                .preferredDate(preferred.toString())
                .notes("Morning preferred")
                .build())
            .build();

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO returned = service.checkOut(encounterId, request, "doctor1", true, null);

        // QA D321: the checkout's own AVS shows the follow-up it filed.
        assertThat(returned.getFollowUpAppointmentDate()).isEqualTo(preferred.toString());
        org.mockito.ArgumentCaptor<com.example.hms.model.scheduling.PatientRecall> captor =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.scheduling.PatientRecall.class);
        verify(patientRecallRepository).save(captor.capture());
        com.example.hms.model.scheduling.PatientRecall recall = captor.getValue();
        assertThat(recall.getSource()).isEqualTo(com.example.hms.enums.RecallSource.CHECKOUT);
        assertThat(recall.getStatus()).isEqualTo(com.example.hms.enums.RecallStatus.PENDING);
        assertThat(recall.getDueDate()).isEqualTo(preferred);
        assertThat(recall.getReason()).isEqualTo("Repeat HbA1c");
        assertThat(recall.getNotes()).isEqualTo("Morning preferred");
        assertThat(recall.getEncounter()).isSameAs(encounter);
        assertThat(recall.getPatient()).isSameAs(encounter.getPatient());
        assertThat(recall.getPreferredProvider()).isSameAs(encounter.getStaff());
        assertThat(recall.getCreatedBy()).isEqualTo("doctor1");
    }

    @Test
    void checkOut_aFollowUpWithOnlyADateIsStillFiled_D426() {
        // QA D426 (D321): a date without a reason was dropped by the portal
        // and refused by the DTO; it is filed with the generic reason.
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);
        LocalDate preferred = LocalDate.now().plusWeeks(3);
        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                .preferredDate(preferred.toString())
                .build())
            .build();
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        AfterVisitSummaryDTO returned = service.checkOut(encounterId, request, "doctor1", true, null);

        assertThat(returned.getFollowUpAppointmentDate()).isEqualTo(preferred.toString());
        org.mockito.ArgumentCaptor<com.example.hms.model.scheduling.PatientRecall> captor =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.scheduling.PatientRecall.class);
        verify(patientRecallRepository).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isEqualTo(preferred);
        // QA D952 class: the default recall reason comes from the bundle, not an English literal.
        assertThat(captor.getValue().getReason()).isEqualTo("ok");
    }

    @Test
    void checkOut_theAvsListsTheVisitsMedicinesWithoutRetyping_D426() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = checkoutRecallEncounter(encounterId);
        DischargeSummary summary = new DischargeSummary();
        summary.addMedicationReconciliation(com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
            .medicationName("Amoxicilline").dosage("1 g").frequency("3x/j")
            .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
            .continueAtDischarge(true).build());
        when(dischargeSummaryRepository.findByEncounter_Id(encounter.getId())).thenReturn(Optional.of(summary));
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        AfterVisitSummaryDTO returned = service.checkOut(encounterId, CheckOutRequestDTO.builder().build(),
            "doctor1", true, null);

        assertThat(returned.getPrescriptionSummary()).isEqualTo("Amoxicilline 1 g 3x/j");
    }

    @Test
    void checkOut_unparseableFollowUpDate_defaultsToTwoWeeks() {
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);

        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                .reason("Wound check")
                .preferredDate("next Tuesday")
                .build())
            .build();

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        service.checkOut(encounterId, request, "doctor1", true, null);

        org.mockito.ArgumentCaptor<com.example.hms.model.scheduling.PatientRecall> captor =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.scheduling.PatientRecall.class);
        verify(patientRecallRepository).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isEqualTo(LocalDate.now().plusWeeks(2));
    }

    @Test
    void checkOut_withoutFollowUpRequest_createsNoRecall() {
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);

        service.checkOut(encounterId, CheckOutRequestDTO.builder().build(), "doctor1", true, null);

        verify(patientRecallRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void checkOut_recallPersistenceFailure_neverFailsTheCheckout() {
        // The checkout is the clinical record; the recall is a worklist row.
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = checkoutRecallEncounter(encounterId);
        when(patientRecallRepository.save(any())).thenThrow(new RuntimeException("db down"));

        CheckOutRequestDTO request = CheckOutRequestDTO.builder()
            .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                .reason("Repeat labs")
                .build())
            .build();

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1", true, null);

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

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        AfterVisitSummaryDTO result = service.checkOut(encounterId, request, "doctor1", true, null);

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
        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        assertThatThrownBy(() -> service.checkOut(encounterId, request, "doctor1", true, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already Completed");

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
        assertThatThrownBy(() -> service.checkOut(encounterId, cancelledRequest, "doctor1", true, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already Cancelled");

        verify(encounterRepository, never()).save(any());
    }

    @Test
    void checkOut_encounterNotFound_throwsResourceNotFound() {
        UUID encounterId = UUID.randomUUID();

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());
        // The not-found refusal now carries the message KEY, not resolved prose,
        // so no messageSource stub is needed (and strict stubs reject one).

        CheckOutRequestDTO notFoundRequest = CheckOutRequestDTO.builder().build();
        assertThatThrownBy(() -> service.checkOut(encounterId, notFoundRequest, "doctor1", true, null))
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

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        service.checkOut(encounterId, request, "doctor1", true, null);

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

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        service.checkOut(encounterId, request, "doctor1", true, null);

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

        AfterVisitSummaryDTO result = service.checkOut(encounterId, null, "doctor1", true, null);

        assertThat(result).isNotNull();
        assertThat(encounter.getFollowUpInstructions()).isNull();
        assertThat(encounter.getDischargeDiagnoses()).isNull();
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }

    // ---------- startEncounter ----------

    @Test
    void startEncounter_happyPath_transitionsToInProgress() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        UUID userId = UUID.randomUUID();
        User user = User.builder().build();
        user.setId(userId);
        Staff staff = Staff.builder().user(user).build();
        staff.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        encounter.setStaff(staff);

        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(encounterId);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.startEncounter(encounterId, "doctor1", false, hospitalId);

        assertThat(result.getId()).isEqualTo(encounterId);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        verify(encounterRepository).save(encounter);
    }

    @Test
    void startEncounter_alreadyInProgress_returnsIdempotent() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        UUID userId = UUID.randomUUID();
        User user = User.builder().build();
        user.setId(userId);
        Staff staff = Staff.builder().user(user).build();
        staff.setId(UUID.randomUUID());

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);
        encounter.setStaff(staff);

        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(encounterId);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("doctor1")).thenReturn(Optional.of(user));
        when(encounterMapper.toEncounterResponseDTO(encounter)).thenReturn(dto);

        EncounterResponseDTO result = service.startEncounter(encounterId, "doctor1", false, hospitalId);

        assertThat(result.getId()).isEqualTo(encounterId);
        verify(encounterRepository, never()).save(any(Encounter.class));
    }

    @Test
    void startEncounter_invalidStatus_throws() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.COMPLETED);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> service.startEncounter(encounterId, "doctor1", false, hospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot start an encounter in status Completed");
    }

    @Test
    void startEncounter_notFound_throws() {
        UUID encounterId = UUID.randomUUID();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());
        // The not-found refusal now carries the message KEY, not resolved prose,
        // so no messageSource stub is needed (and strict stubs reject one).

        assertThatThrownBy(() -> service.startEncounter(encounterId, "doctor1", false, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void startEncounter_superAdmin_skipsHospitalAndStaffChecks() {
        UUID encounterId = UUID.randomUUID();

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);

        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(encounterId);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.startEncounter(encounterId, "admin", true, null);

        assertThat(result.getId()).isEqualTo(encounterId);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
    }

    @Test
    void startEncounter_wrongPhysician_throws() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        UUID assignedUserId = UUID.randomUUID();
        User assignedUser = User.builder().build();
        assignedUser.setId(assignedUserId);
        Staff staff = Staff.builder().user(assignedUser).build();
        staff.setId(UUID.randomUUID());

        UUID callerUserId = UUID.randomUUID();
        User callerUser = User.builder().build();
        callerUser.setId(callerUserId);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        encounter.setStaff(staff);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("other-doctor")).thenReturn(Optional.of(callerUser));

        assertThatThrownBy(() -> service.startEncounter(encounterId, "other-doctor", false, hospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not the assigned physician");
    }

    @Test
    void startEncounter_midwifeStartsTheVisitBookedWithHer_D1015() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        User midwife = User.builder().build();
        midwife.setId(UUID.randomUUID());
        Staff staff = Staff.builder().user(midwife).build();
        staff.setId(UUID.randomUUID());
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        encounter.setStaff(staff);
        EncounterResponseDTO dto = new EncounterResponseDTO();
        dto.setId(encounterId);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("midwife1")).thenReturn(Optional.of(midwife));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        service.startEncounter(encounterId, "midwife1", false, hospitalId, true);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
    }

    @Test
    void startEncounter_midwifeCannotPickUpAnUnassignedVisit_D1015() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        User midwife = User.builder().build();
        midwife.setId(UUID.randomUUID());
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        encounter.setHospital(hospital);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(userRepository.findByUsername("midwife1")).thenReturn(Optional.of(midwife));

        assertThatThrownBy(() -> service.startEncounter(encounterId, "midwife1", false, hospitalId, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not the assigned physician");
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.WAITING_FOR_PHYSICIAN);
    }

    // ---------- completeExamination ----------

    @Test
    void completeExamination_noPendingOrders_transitionsToReadyForDischarge() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(labOrderRepository.existsByEncounter_IdAndStatusNotIn(any(UUID.class), any())).thenReturn(false);
        when(procedureOrderRepository.existsByEncounter_IdAndStatusNotIn(any(UUID.class), any())).thenReturn(false);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.completeExamination(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.READY_FOR_DISCHARGE);
        verify(encounterRepository).save(encounter);
    }

    @Test
    void completeExamination_withPendingLabOrders_transitionsToAwaitingResults() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(labOrderRepository.existsByEncounter_IdAndStatusNotIn(any(UUID.class), any())).thenReturn(true);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.completeExamination(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.AWAITING_RESULTS);
    }

    @Test
    void completeExamination_withPendingProcedureOrders_transitionsToAwaitingResults() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(labOrderRepository.existsByEncounter_IdAndStatusNotIn(any(UUID.class), any())).thenReturn(false);
        when(procedureOrderRepository.existsByEncounter_IdAndStatusNotIn(any(UUID.class), any())).thenReturn(true);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.completeExamination(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.AWAITING_RESULTS);
    }

    @Test
    void completeExamination_alreadyAwaitingResults_returnsIdempotent() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.AWAITING_RESULTS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.completeExamination(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void completeExamination_invalidStatus_throws() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.SCHEDULED);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> service.completeExamination(encounterId, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Expected In progress");
    }

    @Test
    void completeExamination_wrongHospital_throws() {
        UUID encounterId = UUID.randomUUID();
        UUID encounterHospitalId = UUID.randomUUID();
        UUID callerHospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(encounterHospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        // The not-found refusal now carries the message KEY, not resolved prose,
        // so no messageSource stub is needed (and strict stubs reject one).

        assertThatThrownBy(() -> service.completeExamination(encounterId, false, callerHospitalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- markReadyForDischarge ----------

    @Test
    void markReadyForDischarge_fromAwaitingResults_transitions() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.AWAITING_RESULTS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.markReadyForDischarge(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.READY_FOR_DISCHARGE);
        verify(encounterRepository).save(encounter);
    }

    @Test
    void markReadyForDischarge_fromInProgress_transitions() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.IN_PROGRESS);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.markReadyForDischarge(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.READY_FOR_DISCHARGE);
    }

    @Test
    void markReadyForDischarge_alreadyReady_idempotent() {
        UUID encounterId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital(); hospital.setId(hospitalId);
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.READY_FOR_DISCHARGE);
        encounter.setHospital(hospital);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        EncounterResponseDTO result = service.markReadyForDischarge(encounterId, false, hospitalId);

        assertThat(result).isSameAs(dto);
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void markReadyForDischarge_invalidStatus_throws() {
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.COMPLETED);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        assertThatThrownBy(() -> service.markReadyForDischarge(encounterId, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Expected Awaiting results or In progress");
    }

    // ---------- updateEncounter (PUT) — COMPLETED-transition AVS persistence ----------

    /**
     * When a clinician PUT-updates an encounter and that update flips the status to
     * COMPLETED, EncounterServiceImpl.updateEncounter must mirror the dedicated
     * /checkout flow: backfill checkoutTimestamp if absent, persist a DischargeSummary,
     * and notify the patient — otherwise the patient-portal / Android / iOS After-Visit
     * Summary screens stay empty for that encounter (the bug Copilot review #2 covers).
     */
    @Test
    void updateEncounter_transitionToCompleted_persistsDischargeSummaryAndNotifies() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(departmentId);
        department.setHospital(hospital);
        hospital.setDepartments(java.util.Set.of(department));

        User patientUser = User.builder().username("patient1").email("patient.user@example.com").build();
        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setEmail("patient@example.com");
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setUser(patientUser);

        User staffUser = User.builder().username("dr.smith").build();
        staffUser.setId(userId);
        Staff staff = Staff.builder().user(staffUser).hospital(hospital).build();
        staff.setId(staffId);
        staff.setName("Dr. Smith");

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());

        Encounter existing = new Encounter();
        existing.setId(encounterId);
        existing.setStatus(EncounterStatus.IN_PROGRESS);
        existing.setHospital(hospital);
        existing.setPatient(patient);
        existing.setStaff(staff);
        existing.setAssignment(assignment);
        existing.setEncounterDate(LocalDateTime.now().minusHours(1));

        // The merged encounter (post-mapper) has status=COMPLETED, follow-up
        // instructions, and serialised diagnoses — exactly what a clinician's PUT
        // payload would produce when wrapping up a visit via the generic update path.
        Encounter merged = new Encounter();
        merged.setId(encounterId);
        merged.setStatus(EncounterStatus.COMPLETED);
        merged.setHospital(hospital);
        merged.setPatient(patient);
        merged.setStaff(staff);
        merged.setAssignment(assignment);
        merged.setEncounterDate(existing.getEncounterDate());
        merged.setFollowUpInstructions("Return in 2 weeks for cardiology review");
        merged.setDischargeDiagnoses("[\"Hypertension\"]");

        com.example.hms.payload.dto.EncounterRequestDTO request =
            new com.example.hms.payload.dto.EncounterRequestDTO();
        request.setPatientId(patientId);
        request.setStaffId(staffId);
        request.setHospitalId(hospitalId);
        request.setDepartmentId(departmentId);
        request.setStatus(EncounterStatus.COMPLETED);
        request.setEncounterDate(existing.getEncounterDate());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(true);
        // The request keeps the recorded attending, so neither the role check nor the
        // assignment lookup runs (see updateEncounter_keepingTheRecordedAttending_...).
        when(encounterMapper.mergeEncounter(
                any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                any(Encounter.class),
                any(Patient.class),
                any(Staff.class),
                any(Hospital.class),
                org.mockito.ArgumentMatchers.<Appointment>isNull(),
                any(UserRoleHospitalAssignment.class)))
            .thenReturn(merged);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class)))
            .thenReturn(new EncounterResponseDTO());
        when(checkOutMapper.parseDiagnoses("[\"Hypertension\"]"))
            .thenReturn(List.of("Hypertension"));
        when(prescriptionRepository.findByEncounter_Id(org.mockito.ArgumentMatchers.eq(encounterId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        EncounterResponseDTO result = service.updateEncounter(encounterId, request, locale, true, null);

        assertThat(result).isNotNull();
        // checkoutTimestamp should have been backfilled in-flight when the merge had none
        assertThat(merged.getCheckoutTimestamp()).isNotNull();
        // The new branch must persist the AVS via the same path /checkout uses
        verify(dischargeSummaryRepository).save(any(DischargeSummary.class));
        // …and the patient must be notified (in-app + email)
        verify(notificationService).created(
            anyString(),
            org.mockito.ArgumentMatchers.eq("patient1"),
            org.mockito.ArgumentMatchers.eq("DISCHARGE_SUMMARY"));
        verify(emailService).sendHtml(
            org.mockito.ArgumentMatchers.eq(List.of("patient@example.com")),
            org.mockito.ArgumentMatchers.eq(List.of()),
            org.mockito.ArgumentMatchers.eq(List.of()),
            org.mockito.ArgumentMatchers.argThat((String subject) -> !"Your After-Visit Summary is Ready".equals(subject)),
            anyString());
        // QA D972: completing through PUT hands the visit to billing like a checkout.
        verify(eventPublisher).publishEvent(any(com.example.hms.event.EncounterCheckedOutEvent.class));
    }

    /**
     * Sibling case — when updateEncounter does NOT change the status to COMPLETED,
     * the AVS-persistence side effects must NOT fire. Otherwise every routine note
     * edit on an encounter would write a duplicate DischargeSummary and spam the
     * patient with a fresh email.
     */
    @Test
    void qaD990_aScheduledVisitMarkedArrivedThroughPutWaitsFromNow() {
        UUID encounterId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(UUID.randomUUID());
        department.setHospital(hospital);
        hospital.setDepartments(java.util.Set.of(department));
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(hospital);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        Encounter existing = new Encounter();
        existing.setId(encounterId);
        existing.setStatus(EncounterStatus.SCHEDULED);
        existing.setHospital(hospital);
        existing.setPatient(patient);
        existing.setStaff(staff);
        existing.setAssignment(assignment); // the request keeps the recorded attending
        existing.setEncounterDate(java.time.LocalDate.now().atStartOfDay());
        Encounter merged = new Encounter();
        merged.setId(encounterId);
        merged.setStatus(EncounterStatus.ARRIVED);
        merged.setHospital(hospital);
        merged.setPatient(patient);
        merged.setStaff(staff);
        merged.setDepartment(department);
        merged.setEncounterDate(existing.getEncounterDate());
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        lenient().when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospital.getId()))
            .thenReturn(true);
        lenient().when(departmentRepository.findById(department.getId())).thenReturn(Optional.of(department));
        lenient().when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(user.getId(), hospital.getId()))
            .thenReturn(Optional.of(assignment));
        when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                any(Encounter.class), any(Patient.class), any(Staff.class), any(Hospital.class),
                org.mockito.ArgumentMatchers.<Appointment>isNull(), any(UserRoleHospitalAssignment.class)))
            .thenReturn(merged);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(new EncounterResponseDTO());
        com.example.hms.payload.dto.EncounterRequestDTO request = com.example.hms.payload.dto.EncounterRequestDTO.builder()
            .patientId(patient.getId()).staffId(staff.getId()).hospitalId(hospital.getId())
            .departmentId(department.getId()).encounterType(com.example.hms.enums.EncounterType.OUTPATIENT)
            .encounterDate(existing.getEncounterDate()).status(EncounterStatus.ARRIVED).build();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        service.updateEncounter(encounterId, request, Locale.ENGLISH, true, null);

        assertThat(merged.getArrivalTimestamp()).isNotNull().isAfter(before);
    }

    @Test
    void updateEncounter_noTransitionToCompleted_doesNotPersistDischargeSummary() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(departmentId);
        department.setHospital(hospital);
        hospital.setDepartments(java.util.Set.of(department));

        Patient patient = new Patient();
        patient.setId(patientId);

        User staffUser = User.builder().username("dr.smith").build();
        staffUser.setId(userId);
        Staff staff = Staff.builder().user(staffUser).hospital(hospital).build();
        staff.setId(staffId);

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());

        Encounter existing = new Encounter();
        existing.setId(encounterId);
        existing.setStatus(EncounterStatus.IN_PROGRESS);
        existing.setHospital(hospital);
        existing.setPatient(patient);
        existing.setStaff(staff);
        existing.setAssignment(assignment);
        existing.setEncounterDate(LocalDateTime.now().minusHours(1));

        // Status stays IN_PROGRESS — only an unrelated field changes
        Encounter merged = new Encounter();
        merged.setId(encounterId);
        merged.setStatus(EncounterStatus.IN_PROGRESS);
        merged.setHospital(hospital);
        merged.setPatient(patient);
        merged.setStaff(staff);
        merged.setAssignment(assignment);
        merged.setEncounterDate(existing.getEncounterDate());
        merged.setNotes("intermediate note edit");

        com.example.hms.payload.dto.EncounterRequestDTO request =
            new com.example.hms.payload.dto.EncounterRequestDTO();
        request.setPatientId(patientId);
        request.setStaffId(staffId);
        request.setHospitalId(hospitalId);
        request.setDepartmentId(departmentId);
        request.setEncounterDate(existing.getEncounterDate());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(true);
        // The request keeps the recorded attending, so neither the role check nor the
        // assignment lookup runs (see updateEncounter_keepingTheRecordedAttending_...).
        when(encounterMapper.mergeEncounter(
                any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                any(Encounter.class),
                any(Patient.class),
                any(Staff.class),
                any(Hospital.class),
                org.mockito.ArgumentMatchers.<Appointment>isNull(),
                any(UserRoleHospitalAssignment.class)))
            .thenReturn(merged);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class)))
            .thenReturn(new EncounterResponseDTO());

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        service.updateEncounter(encounterId, request, locale, true, null);

        verify(dischargeSummaryRepository, never()).save(any(DischargeSummary.class));
        verify(notificationService, never()).created(anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtml(any(), any(), any(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any(com.example.hms.event.EncounterCheckedOutEvent.class));
        assertThat(merged.getCheckoutTimestamp()).isNull();
    }

    // =====================================================================
    // Tenant scoping on every encounter WRITE
    // =====================================================================

    @Nested
    @DisplayName("encounter writes are hospital-scoped")
    class EncounterWriteTenantScoping {

        private static final UUID CALLER_HOSPITAL = UUID.randomUUID();
        private static final UUID OTHER_HOSPITAL = UUID.randomUUID();

        private Encounter encounterAt(UUID hospitalId) {
            Encounter e = new Encounter();
            e.setId(UUID.randomUUID());
            e.setStatus(EncounterStatus.ARRIVED);
            e.setEsiScore(3);
            com.example.hms.model.Patient p = new com.example.hms.model.Patient();
            p.setId(UUID.randomUUID());
            e.setPatient(p);
            PatientVitalSign v = new PatientVitalSign();
            v.setRecordedAt(LocalDateTime.now());
            org.mockito.Mockito.lenient().when(patientVitalSignRepository
                .findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(eq(p.getId()), any()))
                .thenReturn(Optional.of(v));
            if (hospitalId != null) {
                Hospital h = new Hospital();
                h.setId(hospitalId);
                e.setHospital(h);
            }
            return e;
        }

        private void encounterExists(Encounter e) {
            when(encounterRepository.findById(e.getId())).thenReturn(Optional.of(e));
        }

        @Test
        @DisplayName("submitTriage refuses an encounter belonging to another hospital")
        void submitTriageRefusesForeignEncounter() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            assertThatThrownBy(() -> service.submitTriage(
                    foreign.getId(), new TriageSubmissionRequestDTO(), "nurse",
                    false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);

            // Nothing may be written on the way to the refusal.
            verify(patientVitalSignRepository, never()).save(any());
            verify(encounterRepository, never()).save(any());
        }

        @Test
        @DisplayName("completeTriage refuses an encounter belonging to another hospital")
        void completeTriageRefusesForeignEncounter() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            assertThatThrownBy(() ->
                    service.completeTriage(foreign.getId(), false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);

            verify(encounterRepository, never()).save(any());
        }

        @Test
        @DisplayName("upsertEncounterNote refuses a foreign encounter")
        void noteRefusesForeignEncounter() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            assertThatThrownBy(() -> service.upsertEncounterNote(
                    foreign.getId(), new com.example.hms.payload.dto.EncounterNoteRequestDTO(), Locale.ENGLISH,
                    false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("addEncounterNoteAddendum refuses a foreign encounter")
        void addendumRefusesForeignEncounter() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            assertThatThrownBy(() -> service.addEncounterNoteAddendum(
                    foreign.getId(), new com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO(), Locale.ENGLISH,
                    false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("checkOut refuses a foreign encounter")
        void checkOutRefusesForeignEncounter() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            assertThatThrownBy(() -> service.checkOut(
                    foreign.getId(), new com.example.hms.payload.dto.clinical.CheckOutRequestDTO(),
                    "doctor", false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);

            // Nothing on the checkout side effects may fire on the way out.
            verify(encounterRepository, never()).save(any());
        }

        @Test
        @DisplayName("the refusal carries the message KEY and the id, not resolved prose")
        void refusalCarriesKeyAndArgs() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);

            // ResourceNotFoundException resolves its FIRST argument as a
            // message key, so handing it an already-translated sentence
            // produces "[Missing translation] …" in the response body. Assert
            // on the carried key/args rather than the rendered text: the
            // messageSource here is an unstubbed mock, so any assertion on the
            // message itself passes no matter what we do.
            ResourceNotFoundException thrown = catchThrowableOfType(
                    () -> service.completeTriage(foreign.getId(), false, CALLER_HOSPITAL),
                    ResourceNotFoundException.class);

            assertThat(thrown.getMessageKey()).isEqualTo("encounter.notfound");
            assertThat(thrown.getArgs()).containsExactly(foreign.getId());
        }

        @Test
        @DisplayName("an encounter with NO hospital is refused, not waved through")
        void unattributedEncounterIsRefused() {
            // The guard the three scoped siblings carried read
            // `hospitalId != null && !equals(caller)`, so an encounter with no
            // hospital passed it. An encounter we cannot place is exactly the
            // one not to write to.
            Encounter unattributed = encounterAt(null);
            encounterExists(unattributed);

            assertThatThrownBy(() ->
                    service.completeTriage(unattributed.getId(), false, CALLER_HOSPITAL))
                .isInstanceOf(ResourceNotFoundException.class);

            verify(encounterRepository, never()).save(any());
        }

        @Test
        @DisplayName("the caller's own hospital is allowed through")
        void ownHospitalPasses() {
            Encounter mine = encounterAt(CALLER_HOSPITAL);
            encounterExists(mine);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

            service.completeTriage(mine.getId(), false, CALLER_HOSPITAL);

            assertThat(mine.getStatus()).isEqualTo(EncounterStatus.WAITING_FOR_PHYSICIAN);
            verify(encounterRepository).save(mine);
        }

        @Test
        @DisplayName("QA D155: triage cannot be completed with no ESI and no vitals")
        void completeTriageRefusesAnUnrecordedTriage() {
            Encounter mine = encounterAt(CALLER_HOSPITAL);
            mine.setEsiScore(null);
            encounterExists(mine);
            UUID id = mine.getId();

            assertThatThrownBy(() -> service.completeTriage(id, false, CALLER_HOSPITAL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Record the vital signs and the ESI");
            assertThat(mine.getStatus()).isEqualTo(EncounterStatus.ARRIVED);
        }

        @Test
        @DisplayName("QA D155: an ESI alone is not a triage: vitals since arrival are needed")
        void completeTriageRefusesWithoutVitalsSinceArrival() {
            Encounter mine = encounterAt(CALLER_HOSPITAL);
            mine.setArrivalTimestamp(LocalDateTime.now().plusMinutes(5));
            encounterExists(mine);
            UUID id = mine.getId();

            assertThatThrownBy(() -> service.completeTriage(id, false, CALLER_HOSPITAL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Record the vital signs and the ESI");
        }

        @Test
        @DisplayName("a super admin is not hospital-bound")
        void superAdminPasses() {
            Encounter foreign = encounterAt(OTHER_HOSPITAL);
            encounterExists(foreign);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

            service.completeTriage(foreign.getId(), true, null);

            assertThat(foreign.getStatus()).isEqualTo(EncounterStatus.WAITING_FOR_PHYSICIAN);
        }
    }

    @Test
    void getEncountersByPatientId_followThePatientAndWithholdAForeignSensitiveEncounter() {
        // E9 #59e — the consultation at Hôpital B is on the list at Hôpital A; a
        // foreign encounter in a sensitive category (D3) is withheld; accounted.
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID otherHospitalId = UUID.randomUUID();
        Hospital local = new Hospital(); local.setId(hospitalId);
        Hospital other = new Hospital(); other.setId(otherHospitalId);
        Encounter here = new Encounter(); here.setId(UUID.randomUUID()); here.setHospital(local);
        Encounter foreign = new Encounter(); foreign.setId(UUID.randomUUID()); foreign.setHospital(other);
        Encounter foreignSensitive = new Encounter(); foreignSensitive.setId(UUID.randomUUID()); foreignSensitive.setHospital(other);
        EncounterResponseDTO dto = new EncounterResponseDTO();
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId, otherHospitalId));
        when(encounterRepository.findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(patientId, Set.of(hospitalId, otherHospitalId)))
            .thenReturn(List.of(here, foreign, foreignSensitive));
        when(sensitivityClassifier.effectiveCategory(any(Encounter.class)))
            .thenAnswer(inv -> inv.getArgument(0) == foreignSensitive ? SensitivityCategory.HIV : null);
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class))).thenReturn(dto);

        assertThat(service.getEncountersByPatientId(patientId, locale)).hasSize(2);
        verify(encounterRepository, never()).findByPatient_Id(any());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    // =====================================================================
    // An update that keeps the recorded attending does not re-credential them
    // =====================================================================

    /**
     * dev, 2026-09-13: a nurse's edit on a doctor's encounter was refused with
     * "not authorized to be attending" because that doctor's role assignment had
     * since been hard-deleted. The attending of record is not being chosen again
     * by an update that keeps them, so neither the role check nor the assignment
     * lookup runs, and the encounter keeps the assignment it already carries.
     */
    @Test
    void updateEncounter_keepingTheRecordedAttending_doesNotRecredentialThem() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(departmentId);
        department.setHospital(hospital);
        hospital.setDepartments(java.util.Set.of(department));

        Patient patient = new Patient();
        patient.setId(patientId);

        User staffUser = User.builder().username("doctor_b").build();
        staffUser.setId(UUID.randomUUID());
        Staff staff = Staff.builder().user(staffUser).hospital(hospital).build();
        staff.setId(staffId);

        UserRoleHospitalAssignment recorded = new UserRoleHospitalAssignment();
        recorded.setId(UUID.randomUUID());

        Encounter existing = new Encounter();
        existing.setId(encounterId);
        existing.setStatus(EncounterStatus.ARRIVED);
        existing.setHospital(hospital);
        existing.setPatient(patient);
        existing.setStaff(staff);
        existing.setAssignment(recorded);
        existing.setEncounterDate(LocalDateTime.now().minusHours(1));

        Encounter merged = new Encounter();
        merged.setId(encounterId);
        merged.setStatus(EncounterStatus.ARRIVED);
        merged.setHospital(hospital);
        merged.setPatient(patient);
        merged.setStaff(staff);
        merged.setAssignment(recorded);
        merged.setEncounterDate(existing.getEncounterDate());
        merged.setNotes("ghftghh");

        com.example.hms.payload.dto.EncounterRequestDTO request =
            new com.example.hms.payload.dto.EncounterRequestDTO();
        request.setPatientId(patientId);
        request.setStaffId(staffId);
        request.setHospitalId(hospitalId);
        request.setDepartmentId(departmentId);
        request.setEncounterDate(existing.getEncounterDate());
        request.setNotes("ghftghh");

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(true);
        when(encounterMapper.mergeEncounter(
                any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                any(Encounter.class),
                any(Patient.class),
                any(Staff.class),
                any(Hospital.class),
                org.mockito.ArgumentMatchers.<Appointment>isNull(),
                any(UserRoleHospitalAssignment.class)))
            .thenReturn(merged);
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encounterMapper.toEncounterResponseDTO(any(Encounter.class)))
            .thenReturn(new EncounterResponseDTO());

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        service.updateEncounter(encounterId, request, locale, false, hospitalId);

        verify(roleValidator, never()).isDoctor(any(), any());
        verify(roleValidator, never()).isNurse(any(), any());
        verify(roleValidator, never()).isHospitalAdmin(any(), any());
        verify(assignmentRepository, never()).findActiveStaffAssignment(any(), any());
        verify(encounterMapper).mergeEncounter(
            any(com.example.hms.payload.dto.EncounterRequestDTO.class),
            eq(existing), eq(patient), eq(staff), eq(hospital),
            org.mockito.ArgumentMatchers.<Appointment>isNull(),
            eq(recorded));
    }

    /** Choosing a different attending on an update is a choice, and is credentialed as one. */
    @Test
    void updateEncounter_choosingANewAttending_stillCredentialsThem() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID recordedStaffId = UUID.randomUUID();
        UUID newStaffId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(departmentId);
        department.setHospital(hospital);
        hospital.setDepartments(java.util.Set.of(department));

        Patient patient = new Patient();
        patient.setId(patientId);

        Staff recordedStaff = Staff.builder().hospital(hospital).build();
        recordedStaff.setId(recordedStaffId);
        User newUser = User.builder().username("nurse_c").build();
        newUser.setId(newUserId);
        Staff newStaff = Staff.builder().user(newUser).hospital(hospital).build();
        newStaff.setId(newStaffId);

        UserRoleHospitalAssignment recorded = new UserRoleHospitalAssignment();
        recorded.setId(UUID.randomUUID());

        Encounter existing = new Encounter();
        existing.setId(encounterId);
        existing.setStatus(EncounterStatus.ARRIVED);
        existing.setHospital(hospital);
        existing.setPatient(patient);
        existing.setStaff(recordedStaff);
        existing.setAssignment(recorded);
        existing.setEncounterDate(LocalDateTime.now().minusHours(1));

        com.example.hms.payload.dto.EncounterRequestDTO request =
            new com.example.hms.payload.dto.EncounterRequestDTO();
        request.setPatientId(patientId);
        request.setStaffId(newStaffId);
        request.setHospitalId(hospitalId);
        request.setDepartmentId(departmentId);
        request.setEncounterDate(existing.getEncounterDate());

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(staffRepository.findById(newStaffId)).thenReturn(Optional.of(newStaff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(true);
        when(roleValidator.isDoctor(newUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isNurse(newUserId, hospitalId)).thenReturn(false);
        when(roleValidator.isHospitalAdmin(newUserId, hospitalId)).thenReturn(false);

        // The after-visit summary bell entry is rendered through the message
        // source in the patient's language; lenient, since not every path notifies.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("Votre résumé de visite est disponible.");
        assertThatThrownBy(() -> service.updateEncounter(encounterId, request, locale, false, hospitalId))
            .isInstanceOf(BusinessException.class);

        verify(assignmentRepository, never()).findActiveStaffAssignment(any(), any());
        verify(encounterRepository, never()).save(any(Encounter.class));
    }


    // =====================================================================
    // QA D406 (verification GAP-2): the note nested in the encounter body is
    // authored by the signed-in user, on create and on update.
    // =====================================================================

    @Nested
    @DisplayName("nested encounter note is authored by the session")
    class NestedNoteSessionAuthor {

        private final UUID hospitalId = UUID.randomUUID();
        private final UUID departmentId = UUID.randomUUID();
        private final UUID patientId = UUID.randomUUID();
        private final UUID staffId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final UUID encounterId = UUID.randomUUID();
        private Hospital hospital;
        private Patient patient;
        private Staff staff;
        private UserRoleHospitalAssignment assignment;

        @BeforeEach
        void setUpContext() {
            hospital = new Hospital();
            hospital.setId(hospitalId);
            com.example.hms.model.Department department = new com.example.hms.model.Department();
            department.setId(departmentId);
            department.setHospital(hospital);
            hospital.setDepartments(java.util.Set.of(department));
            patient = new Patient();
            patient.setId(patientId);
            User staffUser = User.builder().username("dr.session").build();
            staffUser.setId(userId);
            staff = Staff.builder().user(staffUser).hospital(hospital).build();
            staff.setId(staffId);
            assignment = new UserRoleHospitalAssignment();
            assignment.setId(UUID.randomUUID());

            lenient().when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
            lenient().when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
            lenient().when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
                .thenReturn(true);
            lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(roleValidator.getCurrentUserId()).thenReturn(userId);
            lenient().when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId))
                .thenReturn(Optional.of(staff));
            lenient().when(assignmentRepository.findActiveStaffAssignment(any(), any()))
                .thenReturn(Optional.of(assignment));
            lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("msg");
        }

        private Encounter encounter() {
            Encounter e = new Encounter();
            e.setId(encounterId);
            e.setStatus(EncounterStatus.IN_PROGRESS);
            e.setHospital(hospital);
            e.setPatient(patient);
            e.setStaff(staff);
            e.setAssignment(assignment);
            e.setEncounterDate(LocalDateTime.now().minusHours(1));
            return e;
        }

        private com.example.hms.payload.dto.EncounterRequestDTO request(
                com.example.hms.payload.dto.EncounterNoteRequestDTO note, LocalDateTime date) {
            com.example.hms.payload.dto.EncounterRequestDTO request =
                new com.example.hms.payload.dto.EncounterRequestDTO();
            request.setPatientId(patientId);
            request.setStaffId(staffId);
            request.setHospitalId(hospitalId);
            request.setDepartmentId(departmentId);
            request.setEncounterDate(date);
            request.setEncounterType(com.example.hms.enums.EncounterType.CONSULTATION);
            request.setNote(note);
            return request;
        }

        private Encounter stubUpdate() {
            Encounter existing = encounter();
            Encounter merged = encounter();
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    any(Encounter.class), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any(UserRoleHospitalAssignment.class)))
                .thenReturn(merged);
            return merged;
        }

        private com.example.hms.payload.dto.EncounterNoteRequestDTO note(UUID authorStaffId, String assessment) {
            com.example.hms.payload.dto.EncounterNoteRequestDTO note =
                new com.example.hms.payload.dto.EncounterNoteRequestDTO();
            note.setAuthorStaffId(authorStaffId);
            note.setAssessment(assessment);
            return note;
        }

        @Test
        void updateRefusesANestedNoteInAnotherClinicianName() {
            Encounter merged = stubUpdate();
            com.example.hms.payload.dto.EncounterRequestDTO request =
                request(note(UUID.randomUUID(), "stable"), merged.getEncounterDate());

            assertThatThrownBy(() -> service.updateEncounter(encounterId, request, locale, true, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verify(encounterNoteRepository, never()).save(any());
        }

        @Test
        void updateRefusesANestedNoteOverAnotherAuthorsNote() {
            Encounter merged = stubUpdate();
            User otherAuthor = User.builder().username("dr.other").build();
            otherAuthor.setId(UUID.randomUUID());
            com.example.hms.model.encounter.EncounterNote theirs = new com.example.hms.model.encounter.EncounterNote();
            theirs.setId(UUID.randomUUID());
            theirs.setAuthor(otherAuthor);
            merged.setEncounterNote(theirs);
            com.example.hms.payload.dto.EncounterRequestDTO request =
                request(note(null, "rewritten"), merged.getEncounterDate());

            assertThatThrownBy(() -> service.updateEncounter(encounterId, request, locale, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("written by another clinician");
            verify(encounterNoteRepository, never()).save(any());
        }

        @Test
        void d513_updateRefusesANestedNoteOnACheckedOutVisit() {
            // #922 review: PUT /encounters/{id} with a nested note bypassed the
            // lock that POST /encounters/{id}/notes enforces.
            Encounter existing = encounter();
            existing.setStatus(EncounterStatus.COMPLETED);
            Encounter merged = encounter();
            merged.setStatus(EncounterStatus.COMPLETED);
            com.example.hms.model.encounter.EncounterNote mine = new com.example.hms.model.encounter.EncounterNote();
            mine.setId(UUID.randomUUID());
            mine.setAssessment("original");
            merged.setEncounterNote(mine);
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(existing));
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    any(Encounter.class), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any(UserRoleHospitalAssignment.class)))
                .thenReturn(merged);
            com.example.hms.payload.dto.EncounterRequestDTO request =
                request(note(null, "rewritten after checkout"), merged.getEncounterDate());
            request.setStatus(EncounterStatus.COMPLETED);

            assertThatThrownBy(() -> service.updateEncounter(encounterId, request, locale, true, null))
                .isInstanceOf(BusinessException.class)
                .matches(e -> "encounter.note.closedLocked".equals(((BusinessException) e).getMessageKey()));
            assertThat(mine.getAssessment()).isEqualTo("original");
            verify(encounterNoteRepository, never()).save(any());
        }

        @Test
        void d761_aWalkInPostedAsArrivedIsStoredArrived() {
            // The QA call: the reception's walk-in dialog posts POST /encounters
            // with status ARRIVED; the entity's IN_PROGRESS default won, and
            // triage then answered 400 "expected ARRIVED or TRIAGE".
            Encounter fresh = encounter();
            fresh.setStatus(EncounterStatus.IN_PROGRESS); // the entity default
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            com.example.hms.payload.dto.EncounterRequestDTO request = request(null, LocalDateTime.now());
            request.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);
            request.setStatus(EncounterStatus.ARRIVED);

            service.createEncounter(request, locale);

            org.mockito.ArgumentCaptor<Encounter> saved = org.mockito.ArgumentCaptor.forClass(Encounter.class);
            verify(encounterRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(EncounterStatus.ARRIVED);
        }

        @Test
        void d761_aClinicianEncounterWithNoStatusKeepsItsInProgressDefault() {
            // Path re-verified: the encounters page posts no status.
            Encounter fresh = encounter();
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            actWithRole("ROLE_DOCTOR");

            service.createEncounter(request(null, LocalDateTime.now()), locale);

            assertThat(fresh.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        }

        private void actWithRole(String role) {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("someone", null,
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role))));
        }

        @org.junit.jupiter.api.AfterEach
        void clearSecurityContext() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        void qaD990_aNurseCreatedUrgencesVisitStartsArrivedAndWaitsFromNow() {
            Encounter fresh = encounter();
            fresh.setStatus(EncounterStatus.IN_PROGRESS); // the entity default
            fresh.setArrivalTimestamp(null);
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            org.mockito.Mockito.lenient().when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            actWithRole("ROLE_NURSE");
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            com.example.hms.payload.dto.EncounterRequestDTO request = request(null, LocalDate.now().atStartOfDay());
            request.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);

            service.createEncounter(request, locale);

            assertThat(fresh.getStatus()).isEqualTo(EncounterStatus.ARRIVED);
            assertThat(fresh.getArrivalTimestamp()).isAfter(before);
        }

        @Test
        void qaD990_aMidwifeLedConsultationKeepsItsInProgressDefault() {
            // Review of #971: a nurse/midwife-led visit stays one she can check out.
            Encounter fresh = encounter();
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            org.mockito.Mockito.lenient().when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            actWithRole("ROLE_MIDWIFE");

            service.createEncounter(request(null, LocalDateTime.now()), locale);

            assertThat(fresh.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        }

        @Test
        void qaD990_aPastVisitEnteredByANurseKeepsThePreviousDefault() {
            // Review of #971: a visit recorded late for last week is not an arrival now.
            Encounter fresh = encounter();
            fresh.setStatus(EncounterStatus.IN_PROGRESS);
            fresh.setArrivalTimestamp(null);
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            org.mockito.Mockito.lenient().when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            actWithRole("ROLE_NURSE");

            com.example.hms.payload.dto.EncounterRequestDTO past = request(null, LocalDateTime.now().minusDays(7));
            past.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);
            service.createEncounter(past, locale);

            assertThat(fresh.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
            assertThat(fresh.getArrivalTimestamp()).isNull();
        }

        @Test
        void qaD990_anEmergencyVisitGoesThroughTriageEvenWhenADoctorOpensIt() {
            Encounter fresh = encounter();
            fresh.setStatus(EncounterStatus.IN_PROGRESS);
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(fresh);
            org.mockito.Mockito.lenient().when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            actWithRole("ROLE_DOCTOR");
            com.example.hms.payload.dto.EncounterRequestDTO request = request(null, LocalDateTime.now());
            request.setEncounterType(com.example.hms.enums.EncounterType.EMERGENCY);

            service.createEncounter(request, locale);

            assertThat(fresh.getStatus()).isEqualTo(EncounterStatus.ARRIVED);
        }

        @Test
        void d761_aNewEncounterCannotBeCreatedCompleted() {
            com.example.hms.payload.dto.EncounterRequestDTO request = request(null, LocalDateTime.now());
            request.setStatus(EncounterStatus.COMPLETED);

            assertThatThrownBy(() -> service.createEncounter(request, locale))
                .isInstanceOf(BusinessException.class);
            verify(encounterRepository, never()).save(any());
        }

        @Test
        void createRefusesANestedNoteInAnotherClinicianName() {
            when(encounterMapper.mergeEncounter(any(com.example.hms.payload.dto.EncounterRequestDTO.class),
                    isNull(), any(Patient.class), any(Staff.class), any(Hospital.class),
                    org.mockito.ArgumentMatchers.<Appointment>isNull(), any()))
                .thenReturn(encounter());
            when(roleValidator.isDoctor(userId, hospitalId)).thenReturn(true);
            com.example.hms.payload.dto.EncounterRequestDTO request =
                request(note(UUID.randomUUID(), "stable"), LocalDateTime.now());

            assertThatThrownBy(() -> service.createEncounter(request, locale))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verify(encounterNoteRepository, never()).save(any());
        }
    }

    /* ── QA round 8 ──────────────────────────────────────────────────── */

    @Test
    void d511_aTriageWithNoEsiScoreIsRefused() {
        // QA D511: POST /encounters/{id}/triage with vitals but no esiScore
        // returned 200 and queued the patient for the doctor with no acuity.
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setStatus(EncounterStatus.ARRIVED);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        TriageSubmissionRequestDTO request = TriageSubmissionRequestDTO.builder()
            .temperatureCelsius(38.5).heartRateBpm(96).chiefComplaint("Fièvre").build();

        assertThatThrownBy(() -> service.submitTriage(encounterId, request, "nurse1", true, null))
            .isInstanceOf(BusinessException.class)
            .matches(e -> ((BusinessException) e).getMessageKey().equals("triage.esi.required"));
        verify(patientVitalSignRepository, never()).save(any());
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ARRIVED);
    }

    @Test
    void d526_theRealPortalCheckoutPayloadFilesTheFollowUpOnTheDischargeSummary() throws Exception {
        // QA D526 (D321/D426 regression): the body the portal's checkout
        // dialog sends (checkout-dialog.component.ts submit()). The recall was
        // filed but the visit's discharge summary — what the discharge view
        // and the patient portal read — carried no follow-up at all.
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);
        LocalDate preferred = LocalDate.now().plusDays(7);
        String portalBody = """
            {"followUpInstructions":"Revenir si la fièvre persiste plus de 48 h.",
             "dischargeDiagnoses":["B54 Paludisme simple"],
             "prescriptionSummary":"Paracétamol 500 mg 3 fois par jour pendant 3 jours",
             "patientEducationMaterials":"Moustiquaire imprégnée",
             "followUpAppointment":{"reason":"Contrôle paludisme","preferredDate":"%s"}}
            """.formatted(preferred);
        CheckOutRequestDTO request = tools.jackson.databind.json.JsonMapper.builder().build()
            .readValue(portalBody, CheckOutRequestDTO.class);
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        AfterVisitSummaryDTO returned = service.checkOut(encounterId, request, "doctor1", true, null);

        assertThat(returned.getFollowUpAppointmentDate()).isEqualTo(preferred.toString());
        verify(patientRecallRepository).save(any(com.example.hms.model.scheduling.PatientRecall.class));
        org.mockito.ArgumentCaptor<DischargeSummary> summary = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
        verify(dischargeSummaryRepository).save(summary.capture());
        assertThat(summary.getValue().getFollowUpAppointments())
            .singleElement()
            .satisfies(f -> {
                assertThat(f.getAppointmentDate()).isEqualTo(preferred);
                assertThat(f.getPurpose()).isEqualTo("Contrôle paludisme");
                assertThat(f.getAppointmentType()).isEqualTo("FOLLOW_UP");
            });
    }

    @Test
    void d526_aCheckoutWithoutFollowUpAddsNoneToTheSummary() {
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        service.checkOut(encounterId, CheckOutRequestDTO.builder().followUpInstructions("Repos").build(),
            "doctor1", true, null);

        org.mockito.ArgumentCaptor<DischargeSummary> summary = org.mockito.ArgumentCaptor.forClass(DischargeSummary.class);
        verify(dischargeSummaryRepository).save(summary.capture());
        assertThat(summary.getValue().getFollowUpAppointments()).isEmpty();
    }

    @Test
    void d526_aDatedFollowUpIsBookedAsAPendingAppointment() throws Exception {
        // QA D526: the QA replay looks the follow-up up on GET /appointments;
        // a recall alone never showed there.
        UUID encounterId = UUID.randomUUID();
        Encounter encounter = checkoutRecallEncounter(encounterId);
        Hospital hospital = encounter.getHospital();
        encounter.getStaff().setHospital(hospital);
        com.example.hms.model.UserRoleHospitalAssignment assignment = new com.example.hms.model.UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        encounter.setAssignment(assignment);
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(UUID.randomUUID());
        encounter.setDepartment(department);
        encounter.getStaff().setId(UUID.randomUUID());
        Appointment alreadyAtEight = new Appointment();
        alreadyAtEight.setStartTime(java.time.LocalTime.of(8, 0));
        when(appointmentRepository.findByStaff_IdAndAppointmentDate(any(), any())).thenReturn(List.of(alreadyAtEight));
        UUID bookedId = UUID.randomUUID();
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
            Appointment a = inv.getArgument(0);
            a.setId(bookedId);
            return a;
        });
        LocalDate preferred = LocalDate.now().plusDays(7);
        CheckOutRequestDTO request = tools.jackson.databind.json.JsonMapper.builder().build().readValue("""
            {"followUpInstructions":"Revenir si la fièvre persiste.",
             "followUpAppointment":{"reason":"Contrôle paludisme","preferredDate":"%s","notes":"QA"}}
            """.formatted(preferred), CheckOutRequestDTO.class);
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        service.checkOut(encounterId, request, "doctor1", true, null);

        org.mockito.ArgumentCaptor<Appointment> booked = org.mockito.ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(booked.capture());
        assertThat(booked.getValue().getPatient()).isSameAs(encounter.getPatient());
        assertThat(booked.getValue().getHospital()).isSameAs(hospital);
        assertThat(booked.getValue().getStaff()).isSameAs(encounter.getStaff());
        assertThat(booked.getValue().getAppointmentDate()).isEqualTo(preferred);
        assertThat(booked.getValue().getStatus()).isEqualTo(com.example.hms.enums.AppointmentStatus.PENDING);
        assertThat(booked.getValue().getReason()).isEqualTo("Contrôle paludisme");
        // 08:00 is taken: the placeholder moves on rather than collide with uq_appt_staff_slot.
        assertThat(booked.getValue().getStartTime()).isEqualTo(java.time.LocalTime.of(8, 30));
        assertThat(booked.getValue().getEndTime()).isAfter(booked.getValue().getStartTime());
        verify(checkOutMapper).toAfterVisitSummary(any(), any(), org.mockito.ArgumentMatchers.eq(bookedId));
        verify(patientRecallRepository).save(any(com.example.hms.model.scheduling.PatientRecall.class));
    }

    @Test
    void d526_anIncompleteBookingContextKeepsTheRecallAndBooksNothing() {
        // The staff has no hospital: Appointment#validate would throw inside
        // the checkout transaction, so nothing is saved and the recall stands.
        UUID encounterId = UUID.randomUUID();
        checkoutRecallEncounter(encounterId);
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("ok");

        service.checkOut(encounterId, CheckOutRequestDTO.builder()
                .followUpAppointment(CheckOutRequestDTO.FollowUpAppointmentRequest.builder()
                    .reason("Contrôle").preferredDate(LocalDate.now().plusDays(7).toString()).build())
                .build(), "doctor1", true, null);

        verify(appointmentRepository, never()).save(any(Appointment.class));
        verify(patientRecallRepository).save(any(com.example.hms.model.scheduling.PatientRecall.class));
    }

    /**
     * QA D788: RECEPTIONIST_A booked and checked in PATIENT_AB (ARRIVED, no
     * triage, no note) and {@code POST /encounters/{id}/checkout} answered 200:
     * the encounter and the appointment became COMPLETED with an After-Visit
     * Summary. A visit is checked out once the clinician has completed the
     * examination; a visit nobody saw is closed without a consultation.
     */
    @org.junit.jupiter.api.Nested
    class QaD788ClosingAVisit {

        private Appointment appointment;

        @BeforeEach
        void signInAsReceptionist() {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("receptionist1", "n/a",
                    org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_RECEPTIONIST")));
        }

        private Encounter visit(EncounterStatus status) {
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            Patient patient = new Patient();
            patient.setId(UUID.randomUUID());
            appointment = new Appointment();
            appointment.setId(UUID.randomUUID());
            appointment.setStatus(AppointmentStatus.CHECKED_IN);
            Encounter encounter = new Encounter();
            encounter.setId(UUID.randomUUID());
            encounter.setStatus(status);
            encounter.setHospital(hospital);
            encounter.setPatient(patient);
            encounter.setAppointment(appointment);
            when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
            return encounter;
        }

        private String keyOf(Throwable ex) {
            return ((com.example.hms.exception.BusinessException) ex).getMessageKey();
        }

        @Test
        @DisplayName("the QA call: the desk checks out a never-seen ARRIVED visit: refused, nothing changes, no AVS")
        void deskCheckoutOfANeverSeenVisitIsRefused() {
            Encounter encounter = visit(EncounterStatus.ARRIVED);

            assertThatThrownBy(() -> service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(),
                    "receptionist1", true, null))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.checkout.notSeen"));

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ARRIVED);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
            verify(encounterRepository, never()).save(any());
            verify(dischargeSummaryRepository, never()).save(any());
            verify(appointmentRepository, never()).save(any());
            verify(checkOutMapper, never()).toAfterVisitSummary(any(), any(), any());
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(value = EncounterStatus.class,
            names = {"SCHEDULED", "ARRIVED", "TRIAGE", "WAITING_FOR_PHYSICIAN"})
        @DisplayName("no clinician either checks out a visit nobody has seen")
        void nobodyChecksOutANeverSeenVisit(EncounterStatus status) {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("doctor1", "n/a",
                    org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_DOCTOR")));
            Encounter encounter = visit(status);

            assertThatThrownBy(() -> service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(),
                    "doctor1", true, null))
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.checkout.notSeen"));
        }

        @Test
        @DisplayName("the desk cannot check out a visit still IN_PROGRESS: the examination is not complete")
        void deskCheckoutMidConsultationIsRefused() {
            Encounter encounter = visit(EncounterStatus.IN_PROGRESS);

            assertThatThrownBy(() -> service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(),
                    "receptionist1", true, null))
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.checkout.examinationNotCompleted"));
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("the desk cannot reach a closable status through ready-for-discharge on a visit IN_PROGRESS, so the check-out stays refused")
        void deskReadyForDischargeMidConsultationIsRefused() {
            Encounter encounter = visit(EncounterStatus.IN_PROGRESS);

            assertThatThrownBy(() -> service.markReadyForDischarge(encounter.getId(), true, null))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .satisfies(ex -> assertThat(keyOf(ex))
                    .isEqualTo("encounter.readyForDischarge.examinationNotCompleted"));
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);

            assertThatThrownBy(() -> service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(),
                    "receptionist1", true, null))
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.checkout.examinationNotCompleted"));

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
            verify(encounterRepository, never()).save(any());
            verify(trackerEventPublisher, never()).publishStatusTransition(any(), any(), any());
            verify(checkOutMapper, never()).toAfterVisitSummary(any(), any(), any());
        }

        @Test
        @DisplayName("the desk still marks an examined visit (AWAITING_RESULTS) ready for discharge")
        void deskReadyForDischargeAfterTheExaminationIsAllowed() {
            Encounter encounter = visit(EncounterStatus.AWAITING_RESULTS);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(encounterMapper.toEncounterResponseDTO(any())).thenReturn(new EncounterResponseDTO());

            service.markReadyForDischarge(encounter.getId(), true, null);

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.READY_FOR_DISCHARGE);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(value = EncounterStatus.class,
            names = {"AWAITING_RESULTS", "READY_FOR_DISCHARGE"})
        @DisplayName("the desk still checks out an examined visit (the encounters page and the tracker)")
        void deskCheckoutOfAnExaminedVisit(EncounterStatus status) {
            Encounter encounter = visit(status);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(checkOutMapper.toAfterVisitSummary(any(), any(), any()))
                .thenReturn(AfterVisitSummaryDTO.builder().encounterId(encounter.getId()).build());
            lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);
            lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("x");

            service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(), "receptionist1", true, null);

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        }

        @Test
        @DisplayName("round 13, the QA call: close-unseen LEFT_WITHOUT_BEING_SEEN gives its own status (not Cancelled), with its outcome and reason, no AVS")
        void closeUnseenCancelsWithAReason() {
            Encounter encounter = visit(EncounterStatus.ARRIVED);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(encounterMapper.toEncounterResponseDTO(any())).thenReturn(new EncounterResponseDTO());

            service.closeUnseen(encounter.getId(), com.example.hms.payload.dto.clinical.CloseUnseenVisitRequestDTO.builder()
                    .outcome(com.example.hms.enums.UnseenVisitOutcome.LEFT_WITHOUT_BEING_SEEN)
                    .reason("  Parti avant l'appel  ").build(),
                "receptionist1", true, null);

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
            assertThat(encounter.getExtraFields())
                .containsEntry("unseenOutcome", "LEFT_WITHOUT_BEING_SEEN")
                .containsEntry("unseenReason", "Parti avant l'appel");
            assertThat(encounter.getCheckoutTimestamp()).isNull();
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            verify(dischargeSummaryRepository, never()).save(any());
            verify(checkOutMapper, never()).toAfterVisitSummary(any(), any(), any());
            verify(encounterHistoryRepository).save(any());
            verify(trackerEventPublisher).publishStatusTransition(encounter, "ARRIVED", "LEFT_WITHOUT_BEING_SEEN");
        }

        @Test
        @DisplayName("round 13: close-unseen CANCELLED stays the staff's Cancelled, never left-without-being-seen")
        void closeUnseenCancelledStaysCancelled() {
            Encounter encounter = visit(EncounterStatus.TRIAGE);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(encounterMapper.toEncounterResponseDTO(any())).thenReturn(new EncounterResponseDTO());

            service.closeUnseen(encounter.getId(), com.example.hms.payload.dto.clinical.CloseUnseenVisitRequestDTO.builder()
                    .outcome(com.example.hms.enums.UnseenVisitOutcome.CANCELLED)
                    .reason("Inscrit par erreur").build(),
                "receptionist1", true, null);

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.CANCELLED);
            assertThat(encounter.getExtraFields()).containsEntry("unseenOutcome", "CANCELLED");
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            verify(trackerEventPublisher).publishStatusTransition(encounter, "TRIAGE", "CANCELLED");
        }

        @Test
        @DisplayName("round 13: a visit left without being seen is closed: no check-out, no second close-unseen")
        void aLeftWithoutBeingSeenVisitIsClosed() {
            Encounter encounter = visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);

            assertThatThrownBy(() -> service.checkOut(encounter.getId(), CheckOutRequestDTO.builder().build(),
                    "receptionist1", true, null))
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.checkout.alreadyClosed"));
            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
            verify(encounterRepository, never()).save(any());
        }

        @Test
        @DisplayName("QA D788: close-unseen also cancels an appointment the desk had marked COMPLETED by hand")
        void closeUnseenCancelsAHandCompletedAppointment() {
            Encounter encounter = visit(EncounterStatus.ARRIVED);
            appointment.setStatus(AppointmentStatus.COMPLETED);
            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
            when(encounterMapper.toEncounterResponseDTO(any())).thenReturn(new EncounterResponseDTO());

            service.closeUnseen(encounter.getId(), com.example.hms.payload.dto.clinical.CloseUnseenVisitRequestDTO.builder()
                    .outcome(com.example.hms.enums.UnseenVisitOutcome.LEFT_WITHOUT_BEING_SEEN)
                    .reason("Parti avant l'appel").build(),
                "receptionist1", true, null);

            assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
            assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            verify(appointmentRepository).save(appointment);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(value = EncounterStatus.class,
            names = {"IN_PROGRESS", "AWAITING_RESULTS", "READY_FOR_DISCHARGE", "COMPLETED", "CANCELLED",
                "LEFT_WITHOUT_BEING_SEEN"})
        @DisplayName("close-unseen refuses a visit a clinician has seen, or one already closed")
        void closeUnseenRefusesASeenVisit(EncounterStatus status) {
            Encounter encounter = visit(status);

            assertThatThrownBy(() -> service.closeUnseen(encounter.getId(),
                    com.example.hms.payload.dto.clinical.CloseUnseenVisitRequestDTO.builder()
                        .outcome(com.example.hms.enums.UnseenVisitOutcome.CANCELLED).reason("x").build(),
                    "receptionist1", true, null))
                .satisfies(ex -> assertThat(keyOf(ex)).isEqualTo("encounter.closeUnseen.wrongStatus"));
            assertThat(encounter.getStatus()).isEqualTo(status);
            verify(encounterRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("QA D788: the rules of a direct status write (PUT, the flow board)")
    void qaD788_directTransitionRules() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.ARRIVED, EncounterStatus.COMPLETED, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.IN_PROGRESS, EncounterStatus.COMPLETED, false))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.TRIAGE, EncounterStatus.READY_FOR_DISCHARGE, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.ARRIVED, EncounterStatus.CANCELLED, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        // Round 13: left-without-being-seen carries a reason too: only close-unseen writes it,
        // and a visit closed that way is not reopened by a status write (PUT, the flow board).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.ARRIVED, EncounterStatus.LEFT_WITHOUT_BEING_SEEN, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDeskTransition(
                EncounterStatus.WAITING_FOR_PHYSICIAN, EncounterStatus.LEFT_WITHOUT_BEING_SEEN))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDeskTransition(
                EncounterStatus.LEFT_WITHOUT_BEING_SEEN, EncounterStatus.ARRIVED))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.LEFT_WITHOUT_BEING_SEEN, EncounterStatus.IN_PROGRESS, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDeskTransition(
                EncounterStatus.COMPLETED, EncounterStatus.ARRIVED))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EncounterClosureRules.requireDirectTransition(
                EncounterStatus.CANCELLED, EncounterStatus.ARRIVED, true))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatCode(() -> {
            EncounterClosureRules.requireDirectTransition(EncounterStatus.COMPLETED, EncounterStatus.COMPLETED, false);
            EncounterClosureRules.requireDirectTransition(EncounterStatus.IN_PROGRESS, EncounterStatus.COMPLETED, true);
            EncounterClosureRules.requireDirectTransition(EncounterStatus.READY_FOR_DISCHARGE, EncounterStatus.COMPLETED, false);
            EncounterClosureRules.requireDirectTransition(EncounterStatus.ARRIVED, EncounterStatus.ARRIVED, false);
            EncounterClosureRules.requireDirectTransition(EncounterStatus.ARRIVED, null, false);
            EncounterClosureRules.requireDirectTransition(EncounterStatus.ARRIVED, EncounterStatus.TRIAGE, false);
            EncounterClosureRules.requireDeskTransition(EncounterStatus.IN_PROGRESS, EncounterStatus.ARRIVED);
        }).doesNotThrowAnyException();
    }
}
