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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
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
    @Mock private tools.jackson.databind.ObjectMapper objectMapper;
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
        // QA D831: one row per note entry; the current entry and the entry list.
        when(encounterNoteRepository.findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(encounterId))
            .thenReturn(Optional.of(note));
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note));
        when(encounterNoteRepository.save(any(EncounterNote.class))).thenAnswer(i -> i.getArgument(0));
        when(roleValidator.getCurrentUserId()).thenReturn(authorUserId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        // D406: the note's author is resolved from the session.
        lenient().when(staffRepository.findByUserIdAndHospitalId(authorUserId, hospitalId))
            .thenReturn(Optional.of(authorStaff));
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
    void d751_theSignedHistoryRowNamesTheSignerLikeEveryOtherRow() {
        authorUser.setEmail("doctor@example.test");

        service.signEncounterNote(encounterId, LOCALE);

        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteHistory> history =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteHistory.class);
        verify(encounterNoteHistoryRepository).save(history.capture());
        assertThat(history.getValue().getChangeType()).isEqualTo("NOTE_SIGNED");
        assertThat(history.getValue().getChangedBy()).isEqualTo("doctor@example.test");
    }

    @Test
    void d751_theCosignedHistoryRowNamesTheCosignerNotTheirId() {
        makeSignedRequiringCosign();
        UUID attendingUserId = UUID.randomUUID();
        User attendingUser = new User();
        attendingUser.setId(attendingUserId);
        attendingUser.setEmail("attending@example.test");
        Staff attending = Staff.builder().user(attendingUser).build();
        attending.setId(UUID.randomUUID());
        when(roleValidator.getCurrentUserId()).thenReturn(attendingUserId);
        when(staffRepository.findByUserIdAndHospitalId(attendingUserId, hospitalId))
            .thenReturn(Optional.of(attending));

        service.cosignEncounterNote(encounterId, LOCALE);

        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteHistory> history =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteHistory.class);
        verify(encounterNoteHistoryRepository).save(history.capture());
        assertThat(history.getValue().getChangeType()).isEqualTo("NOTE_COSIGNED");
        assertThat(history.getValue().getChangedBy()).isEqualTo("attending@example.test");
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

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, true, null))
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
        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, true, null))
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

        service.upsertEncounterNote(encounterId, request, LOCALE, true, null);

        assertThat(note.isRequiresCosign()).isTrue();
    }

    /* ── D406: a note is its author's; a second clinician appends ─────── */

    private Staff nurseStaff() {
        User nurse = new User();
        nurse.setId(UUID.randomUUID());
        nurse.setFirstName("Mariam");
        nurse.setLastName("Sawadogo");
        nurse.setEmail("nurse@example.test");
        Staff staff = Staff.builder().user(nurse).hospital(encounter.getHospital()).build();
        staff.setId(UUID.randomUUID());
        when(roleValidator.getCurrentUserId()).thenReturn(nurse.getId());
        when(staffRepository.findByUserIdAndHospitalId(nurse.getId(), hospitalId)).thenReturn(Optional.of(staff));
        return staff;
    }

    @Test
    void d406_aNurseCannotOverwriteTheDoctorsNote() {
        encounter.setEncounterNote(note);
        note.setAssessment("Pneumopathie");
        nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().subjective("nurse note").build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("addendum");
        assertThat(note.getAuthor()).isEqualTo(authorUser);
        assertThat(note.getAssessment()).isEqualTo("Pneumopathie");
        verify(encounterNoteRepository, never()).save(any(EncounterNote.class));
    }

    @Test
    void d406_aNurseCannotWriteUnderTheDoctorsStaffId() {
        nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder()
            .subjective("nurse note")
            .authorStaffId(authorStaff.getId())
            .build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void d406_theNursesAddendumIsHersAndTheHistorySaysSo() {
        Staff nurse = nurseStaff();
        when(encounterNoteAddendumRepository.save(any(com.example.hms.model.encounter.EncounterNoteAddendum.class)))
            .thenAnswer(i -> i.getArgument(0));
        com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO request =
            com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO.builder()
                .content("Surveillance infirmière")
                .authorName("Dr Issa Ouédraogo")
                .build();

        service.addEncounterNoteAddendum(encounterId, request, LOCALE, false, hospitalId);

        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteAddendum> added =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteAddendum.class);
        verify(encounterNoteAddendumRepository).save(added.capture());
        assertThat(added.getValue().getAuthorStaff()).isEqualTo(nurse);
        // The body's name is not the author's: the server's is.
        assertThat(added.getValue().getAuthorName()).isEqualTo("Mariam Sawadogo");
        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteHistory> history =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteHistory.class);
        verify(encounterNoteHistoryRepository).save(history.capture());
        assertThat(history.getValue().getChangedBy()).isEqualTo("nurse@example.test");
        // The doctor's note itself is unchanged.
        assertThat(note.getAuthor()).isEqualTo(authorUser);
    }

    @Test
    void d406_theAuthorStillRevisesTheirOwnNoteWithHistory() {
        encounter.setEncounterNote(note);
        authorUser.setEmail("doctor@example.test");
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Revised").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        assertThat(note.getAssessment()).isEqualTo("Revised");
        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteHistory> history =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteHistory.class);
        verify(encounterNoteHistoryRepository).save(history.capture());
        assertThat(history.getValue().getChangedBy()).isEqualTo("doctor@example.test");
        assertThat(history.getValue().getChangeType()).isEqualTo("NOTE_UPDATED");
    }

    /** The entry the service saved last: a stay's new progress entry is a new row. */
    private EncounterNote lastSavedEntry() {
        org.mockito.ArgumentCaptor<EncounterNote> saved = org.mockito.ArgumentCaptor.forClass(EncounterNote.class);
        verify(encounterNoteRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(saved.getAllValues().size() - 1);
    }

    private List<com.example.hms.model.encounter.EncounterNoteHistory> historyRows(int expected) {
        org.mockito.ArgumentCaptor<com.example.hms.model.encounter.EncounterNoteHistory> history =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.encounter.EncounterNoteHistory.class);
        verify(encounterNoteHistoryRepository, times(expected)).save(history.capture());
        return history.getAllValues();
    }

    @Test
    void d711_aSecondProgressNoteOnAStayIsANewEntryWithoutYesterdaysSections() {
        // QA D711: the second POST /notes on the stay was a NOTE_UPDATED and
        // the day-1 objective stayed under the day-2 assessment.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(UUID.randomUUID());
        authorUser.setEmail("doctor@example.test");
        note.setObjective("Day 1: febrile 39.2");
        note.setAssessment("Day 1 assessment");
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Day 2: afebrile").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        EncounterNote dayTwo = lastSavedEntry();
        assertThat(dayTwo).isNotSameAs(note);
        assertThat(dayTwo.getAssessment()).isEqualTo("Day 2: afebrile");
        assertThat(dayTwo.getObjective()).isNull();
        // QA D831: day 1 is its own row, kept exactly as written.
        assertThat(note.getObjective()).isEqualTo("Day 1: febrile 39.2");
        assertThat(note.getAssessment()).isEqualTo("Day 1 assessment");
        List<com.example.hms.model.encounter.EncounterNoteHistory> rows = historyRows(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo("NOTE_CREATED");
        assertThat(encounter.getEncounterNotes()).containsExactly(note, dayTwo);
        assertThat(encounter.getEncounterNote()).isSameAs(dayTwo);
    }

    @Test
    void d711_aDayOneAddendumStaysWithDayOneNotUnderTheDayTwoEntry() {
        // #940 review: a day-1 correction must not show under the day-2 entry.
        // QA D831: it stays on the day-1 row, which the new entry never touches.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(UUID.randomUUID());
        note.setAssessment("Day 1 assessment");
        note.addAddendum(com.example.hms.model.encounter.EncounterNoteAddendum.builder()
            .author(authorUser).authorName("Day 1 author")
            .content("Correction: dose was 500 mg").documentedAt(LocalDateTime.now().minusHours(18)).build());
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Day 2: afebrile").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        EncounterNote dayTwo = lastSavedEntry();
        assertThat(note.getAddenda()).hasSize(1);
        assertThat(note.getAddenda().iterator().next().getContent()).isEqualTo("Correction: dose was 500 mg");
        assertThat(dayTwo.getAddenda() == null || dayTwo.getAddenda().isEmpty()).isTrue();
    }

    @Test
    void d711_aSignedDayOneNoteDoesNotBlockDayTwo() {
        // #940 review: the signed lock ran before the stay-entry branch, so a
        // signed day-1 note refused every later progress entry of the stay.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(UUID.randomUUID());
        note.setAssessment("Day 1 assessment");
        LocalDateTime signedAt = LocalDateTime.now().minusHours(20);
        note.setSignedAt(signedAt);
        note.setSignedByName("Issa Ouédraogo");
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Day 2: afebrile").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        EncounterNote dayTwo = lastSavedEntry();
        assertThat(dayTwo.getAssessment()).isEqualTo("Day 2: afebrile");
        assertThat(dayTwo.isSigned()).isFalse();
        assertThat(dayTwo.getSignedByName()).isNull();
        // The day-1 signature stays on the day-1 entry.
        assertThat(note.getSignedAt()).isEqualTo(signedAt);
        assertThat(note.getAssessment()).isEqualTo("Day 1 assessment");
    }

    @Test
    void d711_aCoveringClinicianWritesTheNextStayEntryInTheirOwnName() {
        // #940 review: the other-author lock refused a covering clinician's
        // progress entry on a stay whose day-1 entry another clinician wrote.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(UUID.randomUUID());
        note.setAssessment("Day 1 assessment");
        Staff covering = nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Night: stable").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        EncounterNote night = lastSavedEntry();
        assertThat(night.getAssessment()).isEqualTo("Night: stable");
        assertThat(night.getAuthorStaff()).isEqualTo(covering);
        assertThat(note.getAuthorStaff()).isEqualTo(authorStaff);
        assertThat(note.getAssessment()).isEqualTo("Day 1 assessment");
    }

    @Test
    void d738_eachEntrysHistoryRowNamesItsOwnAuthor() {
        // QA D738 (r10 re-test s5.js): MULTI_A wrote J2, DOCTOR2_A posted J3;
        // the row holding the J2 text named DOCTOR2_A. QA D831: J2 is no
        // longer rewritten at all, so the only row this POST writes is J3's
        // NOTE_CREATED, in the name of the clinician who wrote J3.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(UUID.randomUUID());
        authorUser.setEmail("doctor@example.test");
        note.setAssessment("J2: improving");
        nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("J3: stable").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        List<com.example.hms.model.encounter.EncounterNoteHistory> rows = historyRows(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo("NOTE_CREATED");
        assertThat(rows.get(0).getChangedBy()).isEqualTo("nurse@example.test");
        assertThat(note.getAssessment()).isEqualTo("J2: improving");
    }

    /* ── QA D831: a second doctor never overwrites the first doctor's draft ── */

    /** DOCTOR2_A of the round-13 inpatient journey (k5.js). */
    private Staff secondDoctor() {
        User doctor2 = new User();
        doctor2.setId(UUID.randomUUID());
        doctor2.setFirstName("Issouf");
        doctor2.setLastName("Compaoré");
        doctor2.setEmail("doctor2@example.test");
        Staff staff = Staff.builder().user(doctor2).hospital(encounter.getHospital()).build();
        staff.setId(UUID.randomUUID());
        when(roleValidator.getCurrentUserId()).thenReturn(doctor2.getId());
        when(staffRepository.findByUserIdAndHospitalId(doctor2.getId(), hospitalId)).thenReturn(Optional.of(staff));
        return staff;
    }

    @Test
    void d831_theSecondDoctorsProgressNoteIsItsOwnRowAndTheFirstDraftIsUntouched() {
        // k5.js: DOCTOR_A posts an unsigned SOAP note on the stay, then
        // DOCTOR2_A posts his J1 progress note on the same encounter. The one
        // row came to hold DOCTOR2_A's text under his name, and DOCTOR_A's
        // findings survived only as a NOTE_UPDATED snapshot.
        encounter.setAdmissionId(UUID.randomUUID());
        encounter.setEncounterNote(note);
        authorUser.setEmail("doctor@example.test");
        note.setTemplate(com.example.hms.enums.EncounterNoteTemplate.SOAP);
        note.setSubjective("QA R13 J0 toux fébrile");
        note.setObjective("T 39.4, crépitants base droite");
        note.setAssessment("Pneumopathie");
        note.setPlan("Ceftriaxone IV, paracétamol");
        note.setCreatedAt(LocalDateTime.now().minusHours(2));
        Staff doctor2 = secondDoctor();
        EncounterNoteRequestDTO j1 = EncounterNoteRequestDTO.builder()
            .template(com.example.hms.enums.EncounterNoteTemplate.SOAP)
            .subjective("QA R13 J1 apyrétique").assessment("Amélioration").plan("Poursuivre")
            .build();

        service.upsertEncounterNote(encounterId, j1, LOCALE, false, hospitalId);

        EncounterNote second = lastSavedEntry();
        assertThat(second).isNotSameAs(note);
        assertThat(second.getAuthorStaff()).isEqualTo(doctor2);
        assertThat(second.getAuthor()).isEqualTo(doctor2.getUser());
        assertThat(second.getSubjective()).isEqualTo("QA R13 J1 apyrétique");
        assertThat(second.getObjective()).isNull();
        // DOCTOR_A's draft: same author, same text, still unsigned and signable.
        assertThat(note.getAuthor()).isEqualTo(authorUser);
        assertThat(note.getAuthorStaff()).isEqualTo(authorStaff);
        assertThat(note.getSubjective()).isEqualTo("QA R13 J0 toux fébrile");
        assertThat(note.getObjective()).isEqualTo("T 39.4, crépitants base droite");
        assertThat(note.getAssessment()).isEqualTo("Pneumopathie");
        assertThat(note.isSigned()).isFalse();
        // One NOTE_CREATED, for the new entry, by DOCTOR2_A; nothing rewrites DOCTOR_A's.
        List<com.example.hms.model.encounter.EncounterNoteHistory> rows = historyRows(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo("NOTE_CREATED");
        assertThat(rows.get(0).getChangedBy()).isEqualTo("doctor2@example.test");
        assertThat(encounter.getEncounterNotes()).hasSize(2);
        assertThat(encounter.getEncounterNote()).isSameAs(second);
    }

    @Test
    void d831_aSignWithoutAnEntryNeverReachesADraftThatIsNotOnScreen() {
        // #965 review, k5.js replay: DOCTOR_A clicks Sign under DOCTOR2_A's J1
        // (the current entry on screen). The server used to sign DOCTOR_A's
        // J0 instead, a text the signer was not shown. Without a note id only
        // the current entry is a candidate, so the author-only refusal stands;
        // DOCTOR_A signs J0 by naming it.
        note.setCreatedAt(LocalDateTime.now().minusHours(2));
        User doctor2 = new User();
        doctor2.setId(UUID.randomUUID());
        EncounterNote secondEntry = EncounterNote.builder()
            .encounter(encounter).patient(encounter.getPatient()).hospital(encounter.getHospital())
            .author(doctor2).authorName("Issouf Compaoré").documentedAt(LocalDateTime.now()).build();
        secondEntry.setId(UUID.randomUUID());
        secondEntry.setCreatedAt(LocalDateTime.now());
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note, secondEntry));

        assertThatThrownBy(() -> service.signEncounterNote(encounterId, LOCALE))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(note.isSigned()).isFalse();
        assertThat(secondEntry.isSigned()).isFalse();

        service.signEncounterNote(encounterId, note.getId(), LOCALE);

        assertThat(note.isSigned()).isTrue();
        assertThat(note.getSignedByUserId()).isEqualTo(authorUserId);
        assertThat(secondEntry.isSigned()).isFalse();
    }

    @Test
    void d831_aNamedEntryIsSignedAndAnEntryOfAnotherEncounterIs404() {
        EncounterNote other = EncounterNote.builder().author(authorUser).documentedAt(LocalDateTime.now()).build();
        other.setId(UUID.randomUUID());
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note, other));

        service.signEncounterNote(encounterId, note.getId(), LOCALE);
        assertThat(note.isSigned()).isTrue();
        assertThat(other.isSigned()).isFalse();

        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> service.signEncounterNote(encounterId, foreign, LOCALE))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void d831_anotherDoctorStillCannotSignADraftThatIsNotTheirs() {
        // Path re-verified: with no own entry, the current entry is chosen and
        // the author-only refusal stands.
        secondDoctor();

        assertThatThrownBy(() -> service.signEncounterNote(encounterId, LOCALE))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(note.isSigned()).isFalse();
    }

    @Test
    void d831_cosignActsOnTheNamedEntryNeverOnOneThatIsNotOnScreen() {
        // #965 review: without a note id the co-sign used to reach back to an
        // earlier entry the attending was not shown; it now acts on the
        // current entry only, and an earlier entry is co-signed by its id.
        makeSignedRequiringCosign();
        note.setCreatedAt(LocalDateTime.now().minusHours(3));
        EncounterNote laterDraft = EncounterNote.builder().author(authorUser).documentedAt(LocalDateTime.now()).build();
        laterDraft.setId(UUID.randomUUID());
        laterDraft.setCreatedAt(LocalDateTime.now());
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note, laterDraft));
        UUID attendingUserId = UUID.randomUUID();
        User attendingUser = new User();
        attendingUser.setId(attendingUserId);
        Staff attending = Staff.builder().user(attendingUser).build();
        attending.setId(UUID.randomUUID());
        when(roleValidator.getCurrentUserId()).thenReturn(attendingUserId);
        when(staffRepository.findByUserIdAndHospitalId(attendingUserId, hospitalId)).thenReturn(Optional.of(attending));

        assertThatThrownBy(() -> service.cosignEncounterNote(encounterId, LOCALE))
            .isInstanceOf(BusinessException.class);
        assertThat(note.getCosignedBy()).isNull();

        service.cosignEncounterNote(encounterId, note.getId(), LOCALE);

        assertThat(note.getCosignedBy()).isEqualTo(attending);
        assertThat(laterDraft.getCosignedBy()).isNull();
    }

    @Test
    void d831_anAddendumNamingAnEarlierEntryLandsOnThatEntryAndNotOnTheLatest() {
        // #965 review, k5.js replay: DOCTOR_A signs J0, DOCTOR2_A posts J1 (an
        // unsigned draft), then DOCTOR_A writes an addendum to correct J0. It
        // was filed under J1, DOCTOR2_A's entry, and its history row named J1:
        // J0, signed and locked, could never be corrected.
        encounter.setAdmissionId(UUID.randomUUID());
        authorUser.setEmail("doctor@example.test");
        note.setSubjective("QA R13 J0 toux fébrile");
        note.setCreatedAt(LocalDateTime.now().minusHours(2));
        service.signEncounterNote(encounterId, note.getId(), LOCALE);
        User doctor2 = new User();
        doctor2.setId(UUID.randomUUID());
        EncounterNote j1 = EncounterNote.builder()
            .encounter(encounter).patient(encounter.getPatient()).hospital(encounter.getHospital())
            .author(doctor2).authorName("Issouf Compaoré").subjective("QA R13 J1 apyrétique")
            .documentedAt(LocalDateTime.now()).build();
        j1.setId(UUID.randomUUID());
        j1.setCreatedAt(LocalDateTime.now());
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note, j1));
        when(encounterNoteRepository.findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(encounterId))
            .thenReturn(Optional.of(j1));
        when(encounterNoteAddendumRepository.save(any(com.example.hms.model.encounter.EncounterNoteAddendum.class)))
            .thenAnswer(i -> i.getArgument(0));
        com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO request =
            com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO.builder()
                .content("Correction J0 : T 38.4, non 39.4")
                .attestAccuracy(true)
                .build();

        service.addEncounterNoteAddendum(encounterId, note.getId(), request, LOCALE, false, hospitalId);

        assertThat(note.getAddenda()).hasSize(1);
        assertThat(note.getAddenda().get(0).getNote()).isSameAs(note);
        assertThat(note.getAddenda().get(0).getContent()).isEqualTo("Correction J0 : T 38.4, non 39.4");
        assertThat(j1.getAddenda()).isEmpty();
        // J0's text and signature are as signed; J1 is untouched.
        assertThat(note.getSubjective()).isEqualTo("QA R13 J0 toux fébrile");
        assertThat(note.isSigned()).isTrue();
        assertThat(j1.getSubjective()).isEqualTo("QA R13 J1 apyrétique");
        // The NOTE_ADDENDUM row names J0 (the sign wrote the first row).
        List<com.example.hms.model.encounter.EncounterNoteHistory> rows = historyRows(2);
        assertThat(rows.get(1).getChangeType()).isEqualTo("NOTE_ADDENDUM");
        assertThat(rows.get(1).getNoteId()).isEqualTo(note.getId());
    }

    @Test
    void d831_anAddendumWithoutAnEntryGoesToTheCurrentOneAndAForeignEntryIs404() {
        User doctor2 = new User();
        doctor2.setId(UUID.randomUUID());
        EncounterNote j1 = EncounterNote.builder()
            .encounter(encounter).patient(encounter.getPatient()).hospital(encounter.getHospital())
            .author(doctor2).documentedAt(LocalDateTime.now()).build();
        j1.setId(UUID.randomUUID());
        when(encounterNoteRepository.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounterId))
            .thenReturn(List.of(note, j1));
        when(encounterNoteRepository.findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(encounterId))
            .thenReturn(Optional.of(j1));
        when(encounterNoteAddendumRepository.save(any(com.example.hms.model.encounter.EncounterNoteAddendum.class)))
            .thenAnswer(i -> i.getArgument(0));
        com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO request =
            com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO.builder().content("Ajout").build();

        service.addEncounterNoteAddendum(encounterId, null, request, LOCALE, false, hospitalId);

        assertThat(j1.getAddenda()).hasSize(1);
        assertThat(note.getAddenda()).isEmpty();

        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> service.addEncounterNoteAddendum(encounterId, foreign, request, LOCALE, false, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(note.getAddenda()).isEmpty();
        assertThat(j1.getAddenda()).hasSize(1);
    }

    @Test
    void d831_anAddendumToAnEntryAtAnotherHospitalIs404() {
        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        note.setHospital(elsewhere);
        com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO request =
            com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO.builder().content("Ajout").build();

        assertThatThrownBy(() -> service.addEncounterNoteAddendum(encounterId, note.getId(), request, LOCALE, false, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(note.getAddenda()).isEmpty();
        verify(encounterNoteAddendumRepository, never()).save(any(com.example.hms.model.encounter.EncounterNoteAddendum.class));
    }

    @Test
    void d831_aSecondNoteOnAnOutpatientVisitStillEditsTheOneNote() {
        // Path re-verified: off a stay the visit keeps one note, revised in place.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(null);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Revised").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        assertThat(lastSavedEntry()).isSameAs(note);
        assertThat(encounter.getEncounterNotes()).containsExactly(note);
    }

    @Test
    void d711_anOutpatientNoteOfAnotherAuthorIsStillLocked() {
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(null);
        nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Overwrite").build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void d711_aVisitNoteRevisionStillKeepsTheSectionsItDoesNotResend() {
        // Path re-verified: an outpatient visit's note is still revised in place.
        encounter.setEncounterNote(note);
        encounter.setAdmissionId(null);
        note.setObjective("Kept");
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Revised").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        assertThat(note.getObjective()).isEqualTo("Kept");
    }

    @Test
    void d406_aNurseWritesTheFirstNoteInHerOwnName() {
        encounter.setEncounterNote(null);
        when(encounterNoteRepository.findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(encounterId))
            .thenReturn(Optional.empty());
        Staff nurse = nurseStaff();
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().subjective("Patiente calme").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, false, hospitalId);

        org.mockito.ArgumentCaptor<EncounterNote> saved = org.mockito.ArgumentCaptor.forClass(EncounterNote.class);
        verify(encounterNoteRepository).save(saved.capture());
        assertThat(saved.getValue().getAuthorStaff()).isEqualTo(nurse);
        assertThat(saved.getValue().getAuthor()).isEqualTo(nurse.getUser());
    }

    /* ── QA D513: a checked-out visit's note is closed ─────────────────── */

    @Test
    void d513_theNoteOfACheckedOutVisitCannotBeRewritten() {
        // QA round 8: DOCTOR_A POSTed /encounters/{id}/notes after checkout and
        // the unsigned note was rewritten in place, updatedAt unchanged.
        encounter.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        encounter.setEncounterNote(note);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder()
            .assessment("Rewritten after checkout")
            .build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, true, null))
            .isInstanceOf(BusinessException.class)
            .matches(e -> ((BusinessException) e).getMessageKey().equals("encounter.note.closedLocked"));
        assertThat(note.getAssessment()).isNotEqualTo("Rewritten after checkout");
    }

    @Test
    void d513_aCancelledVisitsNoteIsClosedToo() {
        encounter.setStatus(com.example.hms.enums.EncounterStatus.CANCELLED);
        encounter.setEncounterNote(note);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("x").build();

        assertThatThrownBy(() -> service.upsertEncounterNote(encounterId, request, LOCALE, true, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void d513_anOpenVisitsNoteStaysEditable_andTheResponseIsFlushed() {
        encounter.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
        encounter.setEncounterNote(note);
        EncounterNoteRequestDTO request = EncounterNoteRequestDTO.builder().assessment("Revised").build();

        service.upsertEncounterNote(encounterId, request, LOCALE, true, null);

        assertThat(note.getAssessment()).isEqualTo("Revised");
        // updatedAt is stamped at flush; the response must be mapped after it.
        verify(encounterNoteRepository).flush();
    }
}
