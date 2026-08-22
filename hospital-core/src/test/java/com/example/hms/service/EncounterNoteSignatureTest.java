package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.EncounterMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.payload.dto.EncounterNoteRequestDTO;
import com.example.hms.repository.AppointmentRepository;
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
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Note sign/co-sign ceremony (P3 #20). Until V125 a note's "signature" was
 * whatever the client typed into two free-text inputs; these tests pin the
 * server-side ceremony: author-only sign with a digest, attending co-sign
 * with self-cosign refused, and the lock that finally makes the note form's
 * old promise ("signing closes the note for further edits") true.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EncounterNoteSignatureTest {

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
    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.example.hms.repository.PatientVitalSignRepository patientVitalSignRepository;
    @Mock private com.example.hms.mapper.PatientVitalSignMapper patientVitalSignMapper;
    @Mock private com.example.hms.repository.PatientAllergyRepository patientAllergyRepository;
    @Mock private com.example.hms.mapper.CheckOutMapper checkOutMapper;
    @Mock private com.example.hms.repository.ProcedureOrderRepository procedureOrderRepository;
    @Mock private com.example.hms.repository.PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    @Mock private com.example.hms.service.PatientTrackerEventPublisher trackerEventPublisher;

    @InjectMocks private EncounterServiceImpl service;

    private static final Locale LOCALE = Locale.ENGLISH;

    private UUID encounterId;
    private UUID authorUserId;
    private UUID hospitalId;
    private Encounter encounter;
    private EncounterNote note;
    private User authorUser;
    private Staff authorStaff;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(any(String.class), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));

        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(UUID.randomUUID());

        authorUserId = UUID.randomUUID();
        authorUser = new User();
        authorUser.setId(authorUserId);
        authorUser.setFirstName("Issa");
        authorUser.setLastName("Ouédraogo");
        authorUser.setUsername("issa.o");
        authorStaff = Staff.builder().user(authorUser).hospital(hospital).build();
        authorStaff.setId(UUID.randomUUID());

        encounterId = UUID.randomUUID();
        encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        encounter.setStaff(authorStaff);

        note = EncounterNote.builder()
            .encounter(encounter)
            .patient(patient)
            .hospital(hospital)
            .author(authorUser)
            .authorStaff(authorStaff)
            .authorName("Issa Ouédraogo")
            .documentedAt(LocalDateTime.now())
            .build();
        note.setId(UUID.randomUUID());

        when(encounterRepository.existsById(encounterId)).thenReturn(true);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(encounterNoteRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(note));
        when(encounterNoteRepository.save(any(EncounterNote.class))).thenAnswer(i -> i.getArgument(0));
        when(roleValidator.getCurrentUserId()).thenReturn(authorUserId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    /* ── sign ──────────────────────────────────────────────────────────── */

    @Test
    void signStampsServerEvidenceAndDigest() {
        service.signEncounterNote(encounterId, LOCALE);

        assertThat(note.getSignedAt()).isNotNull();
        assertThat(note.getSignedByUserId()).isEqualTo(authorUserId);
        assertThat(note.getSignedByName()).isEqualTo("Issa Ouédraogo");
        assertThat(note.getSignatureAlgorithm()).isEqualTo("SHA-256");
        assertThat(note.getSignatureValue()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void signRefusedForAnotherClinician() {
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        // 403, not 400: any doctor holds the role, only the author holds the note.
        assertThatThrownBy(() -> service.signEncounterNote(encounterId, LOCALE))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("author");
        assertThat(note.getSignedAt()).isNull();
    }

    @Test
    void signCannotBeReissued() {
        note.setSignedAt(LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> service.signEncounterNote(encounterId, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been signed");
    }

    @Test
    void signIs404ForAForeignHospitalScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.signEncounterNote(encounterId, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ── cosign ────────────────────────────────────────────────────────── */

    private void makeSignedRequiringCosign() {
        note.setRequiresCosign(true);
        note.setSignedAt(LocalDateTime.now().minusHours(1));
        note.setSignedByUserId(authorUserId);
    }

    @Test
    void cosignStampsTheAttendingAtTheNotesHospital() {
        makeSignedRequiringCosign();
        UUID attendingUserId = UUID.randomUUID();
        User attendingUser = new User();
        attendingUser.setId(attendingUserId);
        Staff attending = Staff.builder().user(attendingUser).build();
        attending.setId(UUID.randomUUID());
        when(roleValidator.getCurrentUserId()).thenReturn(attendingUserId);
        when(staffRepository.findByUserIdAndHospitalId(attendingUserId, hospitalId))
            .thenReturn(Optional.of(attending));

        service.cosignEncounterNote(encounterId, LOCALE);

        assertThat(note.getCosignedAt()).isNotNull();
        assertThat(note.getCosignedBy()).isEqualTo(attending);
    }

    @Test
    void cosignRefusedWithoutADeclaredRequirement() {
        note.setSignedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.cosignEncounterNote(encounterId, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not declare");
    }

    @Test
    void cosignRefusedBeforeTheAuthorSigns() {
        note.setRequiresCosign(true);

        assertThatThrownBy(() -> service.cosignEncounterNote(encounterId, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must sign the note before");
    }

    @Test
    void selfCosignRefused() {
        makeSignedRequiringCosign();
        when(staffRepository.findByUserIdAndHospitalId(authorUserId, hospitalId))
            .thenReturn(Optional.of(authorStaff));

        assertThatThrownBy(() -> service.cosignEncounterNote(encounterId, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("its own author");
    }

    @Test
    void cosignRequiresAStaffProfileAtTheNotesHospital() {
        makeSignedRequiringCosign();
        UUID foreignDoctorUserId = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(foreignDoctorUserId);
        // Unlike the prescription cosign shape (first staff row ANYWHERE),
        // the note ceremony resolves staff at the note's hospital.
        when(staffRepository.findByUserIdAndHospitalId(foreignDoctorUserId, hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cosignEncounterNote(encounterId, LOCALE))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("staff profile at this hospital");
    }

    /* ── the lock + client-assertion refusal ───────────────────────────── */

    @Test
    void upsertRefusesASignedNote() {
        note.setSignedAt(LocalDateTime.now().minusHours(1));
        encounter.setEncounterNote(note);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder()
            .assessment("Revised assessment")
            .build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("addendum");
    }

    @Test
    void upsertRefusesAClientAssertedSignature() {
        encounter.setEncounterNote(note);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder()
            .assessment("Assessment")
            .signedAt(LocalDateTime.now())
            .signedByName("Dr Somebody")
            .build();

        // The pre-V125 path copied these fields straight onto the entity.
        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be asserted");
    }

    @Test
    void requiresCosignIsSetOnly() {
        encounter.setEncounterNote(note);
        note.setRequiresCosign(true);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder()
            .assessment("Assessment")
            .requiresCosign(false)
            .build();

        service.upsertEncounterNote(encounterId, request, LOCALE);

        assertThat(note.isRequiresCosign()).isTrue();
    }
}
