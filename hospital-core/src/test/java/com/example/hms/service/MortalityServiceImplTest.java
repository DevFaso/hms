package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PlaceOfDeath;
import com.example.hms.enums.RecallStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.impl.MortalityServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>Two things are pinned hardest here. The CASCADE, because every record it
 * closes is one that would otherwise keep running against a person who has died
 * — and the reminder sweep in particular would text the family. And the WHO
 * MATERNAL DEATH BOUNDARY, because a late maternal death counted as a maternal
 * death overstates the ratio the facility is judged on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MortalityServiceImplTest {

    // QA D1232 (round 24): the refusals are message keys now; the assertions
    // read them in English through the real bundles (set up in setUp).
    @org.junit.jupiter.api.AfterEach
    void resetRefusalLocale() {
        com.example.hms.utility.MessageUtil.setMessageSource(null);
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    @Mock private DeathRecordRepository deathRecordRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRecallRepository recallRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.service.BedAssignmentService bedAssignmentService;
    @Mock private com.example.hms.repository.DeathDisputeRepository disputeRepository;
    @Mock private com.example.hms.service.OrderReviewHoldService orderReview;
    @Mock private com.example.hms.service.InBasketService inBasketService;
    @Mock private com.example.hms.repository.NewbornAssessmentRepository newbornAssessmentRepository;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Spy private Clock clock = Clock.systemDefaultZone();
    @Spy private MortalityMapper mapper = new MortalityMapper();

    @InjectMocks private MortalityServiceImpl service;

    private UUID hospitalId;
    private UUID patientId;
    private UUID callerUserId;
    private Hospital hospital;
    private Patient patient;
    private Staff caller;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        caller = new Staff();
        caller.setId(UUID.randomUUID());
        caller.setHospital(hospital);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(callerUserId);
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.of(caller));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientChartAccess.require(any(), any())).thenReturn(patient);
        when(deathRecordRepository.existsByPatient_Id(patientId)).thenReturn(false);
        when(deathRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(patientRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of());
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any())).thenReturn(List.of());
        when(recallRepository.findByPatient_IdAndStatusIn(any(), any())).thenReturn(List.of());
    }

    private DeathRecordRequestDTO.DeathRecordRequestDTOBuilder base() {
        return DeathRecordRequestDTO.builder()
            .patientId(patientId)
            .diedAt(LocalDateTime.now().minusHours(2))
            .placeOfDeath(PlaceOfDeath.FACILITY)
            .mannerOfDeath(MannerOfDeath.NATURAL)
            .immediateCause("Hypovolaemic shock");
    }

    // ── Recording ───────────────────────────────────────────────────────

    // ── QA D1490: the classification, never the death ────────────────

    @Test
    void aDeathOutsideThePerinatalPeriodSavesWithoutTheClassification() {
        patient.setDateOfBirth(java.time.LocalDate.of(1940, 1, 1));

        service.recordDeath(base().build());

        assertThat(patient.isDeceased()).isTrue();
        verify(deathRecordRepository).save(any());
    }

    @Test
    void theSameDeathIsRefusedOnlyWithTheClassification() {
        patient.setDateOfBirth(java.time.LocalDate.of(1940, 1, 1));
        DeathRecordRequestDTO classified = base().perinatalDeath(true)
            .perinatalType(com.example.hms.enums.PerinatalDeathType.STILLBIRTH).build();

        assertThatThrownBy(() -> service.recordDeath(classified))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("perinatalType"));
        assertThat(patient.isDeceased()).isFalse();

        service.recordDeath(base().build());
        assertThat(patient.isDeceased()).isTrue();
    }

    @Test
    void aMalePatientSavesWithoutTheMaternalClassificationAndIsRefusedWithIt() {
        patient.setGender("M");
        DeathRecordRequestDTO classified = base().maternalDeath(true)
            .maternalDeathTiming(com.example.hms.enums.MaternalDeathTiming.DURING_PREGNANCY).build();

        assertThatThrownBy(() -> service.recordDeath(classified))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("maternalDeath"));

        service.recordDeath(base().build());
        assertThat(patient.isDeceased()).isTrue();
    }

    @Test
    void aDeathBeforeTheBirthIsRefusedOnTheDate() {
        patient.setDateOfBirth(java.time.LocalDate.now().minusDays(1));
        DeathRecordRequestDTO before = base().diedAt(LocalDateTime.now().minusDays(5)).build();

        assertThatThrownBy(() -> service.recordDeath(before))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("diedAt"));
    }

    @Test
    void anAmendmentCannotClassifyWhatTheRecordCannotBe() {
        patient.setGender("male");
        DeathRecord existing = DeathRecord.builder().patient(patient).hospital(hospital)
            .diedAt(LocalDateTime.now().minusDays(1)).immediateCause("x").build();
        existing.setId(UUID.randomUUID());
        when(deathRecordRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        UUID id = existing.getId();
        DeathRecordAmendmentDTO amendment = DeathRecordAmendmentDTO.builder().amendmentReason("Autopsy")
            .maternalDeath(true).maternalDeathTiming(com.example.hms.enums.MaternalDeathTiming.DURING_PREGNANCY).build();

        assertThatThrownBy(() -> service.amendDeathRecord(id, amendment))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
    }

    // ── QA D1488: entered in error ────────────────────────────────

    @Test
    void anotherHospitalDoesNotReadThisRecordThroughTheChart() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        DeathRecord elsewhere = DeathRecord.builder().patient(patient).hospital(other)
            .diedAt(LocalDateTime.now().minusDays(1)).immediateCause("Private").build();
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.of(elsewhere));

        assertThatThrownBy(() -> service.getForPatient(patientId))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }

    @Test
    void enteredInErrorEndsEveryOpenDisputeOfTheRecord() {
        DeathRecord existing = recordedDeath();
        com.example.hms.model.DeathDispute open = com.example.hms.model.DeathDispute.builder()
            .deathRecordId(existing.getId()).patientId(patientId).hospitalId(UUID.randomUUID()).build();
        open.setId(UUID.randomUUID());
        when(disputeRepository.lockOpenForRecord(existing.getId())).thenReturn(List.of(open));

        service.markEnteredInError(existing.getId(),
            com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong patient").build());

        assertThat(open.getStatus()).isEqualTo(com.example.hms.enums.DeathDisputeStatus.ENTERED_IN_ERROR);
        assertThat(open.getResolutionReason()).isEqualTo("Wrong patient");
        assertThat(open.getResolvedById()).isEqualTo(callerUserId);
        verify(inBasketService).removeOpenItemsForReference(any(), org.mockito.ArgumentMatchers.eq("DEATH_DISPUTE"));
        verify(orderReview).disputeClosed(open.getId());
    }

    @Test
    void aDisputedDeathStaysListedAndCountedAndIsFlagged() {
        DeathRecord existing = recordedDeath();
        when(deathRecordRepository.findRegister(any(), any(), any())).thenReturn(List.of(existing));
        when(disputeRepository.findOpenRecordIds(any())).thenReturn(List.of(existing.getId()));

        var register = service.getRegister(java.time.LocalDate.now().minusDays(3), java.time.LocalDate.now());

        assertThat(register.getTotalDeaths()).isEqualTo(1);
        assertThat(register.getDisputedDeaths()).isEqualTo(1);
        assertThat(register.getDeaths().get(0).isDisputed()).isTrue();
    }

    @Test
    void anAdministratorOfAnotherHospitalWhoIsANurseHereIsRefused() {
        DeathRecord existing = recordedDeath();
        UUID id = existing.getId();
        when(roleValidator.isHospitalAdmin(callerUserId, hospitalId)).thenReturn(false);
        var body = com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong").build();

        assertThatThrownBy(() -> service.markEnteredInError(id, body))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(patient.isDeceased()).isTrue();
        assertThat(existing.isEnteredInError()).isFalse();
    }

    @Test
    void aSuperAdminMayMarkItWithNoRoleAtTheHospital() {
        DeathRecord existing = recordedDeath();
        when(roleValidator.isHospitalAdmin(callerUserId, hospitalId)).thenReturn(false);
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().superAdmin(true).build());
        try {
            service.markEnteredInError(existing.getId(),
                com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong").build());
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }

        assertThat(patient.isDeceased()).isFalse();
    }

    private DeathRecord recordedDeath() {
        when(roleValidator.isHospitalAdmin(callerUserId, hospitalId)).thenReturn(true);
        patient.setDeceasedAt(LocalDateTime.now().minusDays(1));
        DeathRecord existing = DeathRecord.builder().patient(patient).hospital(hospital)
            .diedAt(patient.getDeceasedAt()).immediateCause("x").build();
        existing.setId(UUID.randomUUID());
        when(deathRecordRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        return existing;
    }

    @Test
    void enteredInErrorKeepsTheRowMarkedAndReopensCare() {
        DeathRecord existing = recordedDeath();
        when(roleValidator.getCurrentUserId()).thenReturn(callerUserId);

        var result = service.markEnteredInError(existing.getId(),
            com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason(" Wrong patient ").build());

        assertThat(patient.isDeceased()).isFalse();
        verify(patientRepository).save(patient);
        assertThat(existing.isEnteredInError()).isTrue();
        assertThat(existing.getEnteredInErrorById()).isEqualTo(callerUserId);
        assertThat(existing.getEnteredInErrorReason()).isEqualTo("Wrong patient");
        assertThat(result.isEnteredInError()).isTrue();
        assertThat(result.getEnteredInErrorReason()).isEqualTo("Wrong patient");
        com.example.hms.service.support.DeceasedPatientGuard.refuseNewCare(patient);
    }

    @Test
    void enteredInErrorIsAudited() {
        DeathRecord existing = recordedDeath();
        var audit = org.mockito.Mockito.mock(com.example.hms.service.AuditEventLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditEventLogService", audit);

        service.markEnteredInError(existing.getId(),
            com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong patient").build());

        var captor = org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(audit).logEvent(captor.capture());
        assertThat(captor.getValue().getEntityType()).isEqualTo("DEATH_RECORD");
        assertThat(captor.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(captor.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(captor.getValue().getEventDescription()).contains("entered in error").contains("Wrong patient");
    }

    @Test
    void enteredInErrorNeedsAReason() {
        DeathRecord existing = recordedDeath();
        UUID id = existing.getId();
        var blank = com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("  ").build();

        assertThatThrownBy(() -> service.markEnteredInError(id, blank))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(patient.isDeceased()).isTrue();
    }

    @Test
    void enteredInErrorAtAnotherHospitalAnswersAsMissing() {
        DeathRecord existing = recordedDeath();
        UUID id = existing.getId();
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        var body = com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong").build();

        assertThatThrownBy(() -> service.markEnteredInError(id, body))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(patient.isDeceased()).isTrue();
    }

    @Test
    void aRowEnteredInErrorIsNotMarkedTwiceNorAmended() {
        DeathRecord existing = recordedDeath();
        UUID id = existing.getId();
        var body = com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong").build();
        service.markEnteredInError(id, body);

        assertThatThrownBy(() -> service.markEnteredInError(id, body)).isInstanceOf(BusinessException.class);
        DeathRecordAmendmentDTO amendment = DeathRecordAmendmentDTO.builder().amendmentReason("Autopsy").build();
        assertThatThrownBy(() -> service.amendDeathRecord(id, amendment)).isInstanceOf(BusinessException.class);
    }

    // ── QA D1516: what a death entered in error had closed ───────────

    private Admission openStayWith(Staff attending) {
        Admission stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setHospital(hospital);
        stay.setPatient(patient);
        stay.setStatus(AdmissionStatus.ACTIVE);
        stay.setAdmissionDateTime(LocalDateTime.now().minusDays(3));
        stay.setAttendingPhysician(attending);
        return stay;
    }

    private Staff doctorWithUser() {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(UUID.randomUUID());
        Staff doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setUser(user);
        doctor.setHospital(hospital);
        return doctor;
    }

    private com.example.hms.model.Encounter openVisit() {
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setId(UUID.randomUUID());
        visit.setHospital(hospital);
        visit.setStatus(com.example.hms.enums.EncounterStatus.IN_PROGRESS);
        visit.setEncounterDate(LocalDateTime.now().minusHours(5));
        return visit;
    }

    private com.example.hms.model.Appointment futureBooking() {
        com.example.hms.model.Appointment booking = new com.example.hms.model.Appointment();
        booking.setId(UUID.randomUUID());
        booking.setHospital(hospital);
        booking.setStatus(com.example.hms.enums.AppointmentStatus.SCHEDULED);
        booking.setAppointmentDate(java.time.LocalDate.now().plusDays(7));
        return booking;
    }

    /** Records the death through the service, then lets the repositories find what it closed by id. */
    private DeathRecord deathClosing(Admission stay, com.example.hms.model.Encounter visit,
                                     com.example.hms.model.Appointment booking) {
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(stay));
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(visit));
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any())).thenReturn(List.of(booking));
        when(admissionRepository.findAllById(List.of(stay.getId()))).thenReturn(List.of(stay));
        when(encounterRepository.findAllById(List.of(visit.getId()))).thenReturn(List.of(visit));
        when(appointmentRepository.findAllById(List.of(booking.getId()))).thenReturn(List.of(booking));
        var captor = org.mockito.ArgumentCaptor.forClass(DeathRecord.class);
        service.recordDeath(base().build());
        verify(deathRecordRepository).save(captor.capture());
        DeathRecord recorded = captor.getValue();
        recorded.setId(UUID.randomUUID());
        when(deathRecordRepository.findById(recorded.getId())).thenReturn(Optional.of(recorded));
        when(roleValidator.isHospitalAdmin(callerUserId, hospitalId)).thenReturn(true);
        return recorded;
    }

    private void markInError(DeathRecord recorded) {
        service.markEnteredInError(recorded.getId(),
            com.example.hms.payload.dto.mortality.DeathRecordEnteredInErrorDTO.builder().reason("Wrong patient").build());
    }

    @Test
    void theDeathKeepsWhatItClosed() {
        Admission stay = openStayWith(doctorWithUser());
        var visit = openVisit();
        var booking = futureBooking();

        DeathRecord recorded = deathClosing(stay, visit, booking);

        assertThat(recorded.getClosedRecords())
            .containsEntry(DeathRecord.CLOSED_ADMISSIONS, List.of(stay.getId().toString()))
            .containsEntry(DeathRecord.CLOSED_ENCOUNTERS, List.of(visit.getId().toString()))
            .containsEntry(DeathRecord.CLOSED_APPOINTMENTS, List.of(booking.getId().toString()));
        assertThat(stay.getStatus()).isEqualTo(AdmissionStatus.DECEASED);
    }

    @Test
    void enteredInErrorFlagsTheStayItClosedEndsItWhenTheClosureRanAndFilesAnUrgentReviewToItsAttending() {
        Staff attending = doctorWithUser();
        Admission stay = openStayWith(attending);
        var visit = openVisit();
        var booking = futureBooking();
        DeathRecord recorded = deathClosing(stay, visit, booking);
        LocalDateTime closedAt = LocalDateTime.now().minusMinutes(10);
        recorded.setCreatedAt(closedAt);
        var notices = org.mockito.Mockito.mock(com.example.hms.service.impl.OrderReviewAfterCommit.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notices", notices);

        markInError(recorded);

        assertThat(stay.getStatus()).isEqualTo(AdmissionStatus.CLOSED_IN_ERROR);
        assertThat(stay.getActualDischargeDateTime()).isEqualTo(closedAt);
        assertThat(stay.getLengthOfStayDays()).isNotNull();
        // The visit stays completed and the appointment cancelled: nothing is reopened (#1068).
        assertThat(visit.getStatus()).isEqualTo(com.example.hms.enums.EncounterStatus.COMPLETED);
        assertThat(booking.getStatus()).isEqualTo(com.example.hms.enums.AppointmentStatus.CANCELLED);
        // In the staff language, as the closure note and the in-basket are.
        assertThat(booking.getNotes()).contains("Patient deceased").contains("Décès saisi par erreur");
        var notice = org.mockito.ArgumentCaptor.forClass(com.example.hms.service.impl.OrderReviewAfterCommit.Notice.class);
        verify(notices).file(notice.capture());
        assertThat(notice.getValue().hospital()).isEqualTo(hospitalId);
        assertThat(notice.getValue().patient()).isEqualTo(patientId);
        assertThat(notice.getValue().recipients()).containsExactly(attending.getUser().getId());
        assertThat(notice.getValue().referenceType()).isEqualTo("DEATH_ENTERED_IN_ERROR_CLOSURES");
        assertThat(notice.getValue().titleKey()).isEqualTo("mortality.enteredInError.closures.title");
        assertThat(notice.getValue().bodyArgs()).hasSize(4);
        assertThat(String.valueOf(notice.getValue().bodyArgs()[3])).isNotEqualTo("—");
    }

    @Test
    void withNoAttendingTheReviewFallsToTheHospitalsDoctors() {
        Admission stay = openStayWith(null);
        DeathRecord recorded = deathClosing(stay, openVisit(), futureBooking());
        var notices = org.mockito.Mockito.mock(com.example.hms.service.impl.OrderReviewAfterCommit.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notices", notices);

        markInError(recorded);

        var notice = org.mockito.ArgumentCaptor.forClass(com.example.hms.service.impl.OrderReviewAfterCommit.Notice.class);
        verify(notices).file(notice.capture());
        // Empty recipients: OrderReviewAfterCommit.file tells the hospital's doctors (OrderReviewAfterCommitTest).
        assertThat(notice.getValue().recipients()).isEmpty();
    }

    @Test
    void aFailureToFileTheReviewNeverUndoesTheEnteredInError() {
        Admission stay = openStayWith(doctorWithUser());
        DeathRecord recorded = deathClosing(stay, openVisit(), futureBooking());
        var notices = org.mockito.Mockito.mock(com.example.hms.service.impl.OrderReviewAfterCommit.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("in-basket down")).when(notices).file(any());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notices", notices);

        markInError(recorded);

        assertThat(recorded.isEnteredInError()).isTrue();
        assertThat(patient.isDeceased()).isFalse();
        assertThat(stay.getStatus()).isEqualTo(AdmissionStatus.CLOSED_IN_ERROR);
    }

    @Test
    void aRowFromBeforeV281FlagsTheStaysStillDeceasedAtItsHospitalOnly() {
        DeathRecord legacy = recordedDeath();
        Admission here = openStayWith(null);
        here.setStatus(AdmissionStatus.DECEASED);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Admission elsewhere = openStayWith(null);
        elsewhere.setHospital(other);
        elsewhere.setStatus(AdmissionStatus.DECEASED);
        Admission discharged = openStayWith(null);
        discharged.setStatus(AdmissionStatus.DISCHARGED);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(here, elsewhere, discharged));

        markInError(legacy);

        assertThat(here.getStatus()).isEqualTo(AdmissionStatus.CLOSED_IN_ERROR);
        assertThat(here.getActualDischargeDateTime()).isNotNull();
        assertThat(elsewhere.getStatus()).isEqualTo(AdmissionStatus.DECEASED);
        assertThat(discharged.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
    }

    @Test
    void aRowFromBeforeV281NeverTakesTheClosuresOfALaterRealDeath() {
        DeathRecord legacy = recordedDeath();
        markInError(legacy);
        Admission closedByTheRealDeath = openStayWith(null);
        closedByTheRealDeath.setStatus(AdmissionStatus.DECEASED);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(closedByTheRealDeath));
        DeathRecord live = DeathRecord.builder().patient(patient).hospital(hospital)
            .diedAt(LocalDateTime.now().minusHours(1)).immediateCause("y").build();
        live.setId(UUID.randomUUID());
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.of(live));
        when(deathRecordRepository.findEnteredInErrorForPatient(patientId, hospitalId)).thenReturn(List.of(legacy));

        assertThat(service.getVoidedClosures(patientId)).isEmpty();
        assertThat(closedByTheRealDeath.getStatus()).isEqualTo(AdmissionStatus.DECEASED);
    }

    @Test
    void theChartReadsWhatAVoidedDeathClosedAtThisHospital() {
        Admission stay = openStayWith(doctorWithUser());
        var visit = openVisit();
        var booking = futureBooking();
        DeathRecord recorded = deathClosing(stay, visit, booking);
        markInError(recorded);
        when(deathRecordRepository.findEnteredInErrorForPatient(patientId, hospitalId)).thenReturn(List.of(recorded));

        var closures = service.getVoidedClosures(patientId);

        assertThat(closures).hasSize(1);
        assertThat(closures.get(0).deathRecordId()).isEqualTo(recorded.getId());
        assertThat(closures.get(0).stays()).extracting(s -> s.admissionId()).containsExactly(stay.getId());
        assertThat(closures.get(0).stays().get(0).closedAt()).isNotNull();
        assertThat(closures.get(0).visits()).extracting(v -> v.encounterId()).containsExactly(visit.getId());
        assertThat(closures.get(0).appointments()).extracting(a -> a.appointmentId()).containsExactly(booking.getId());
        verify(patientChartAccess, org.mockito.Mockito.atLeastOnce()).require(patientId, hospitalId);
    }

    @Test
    void theDeathEventSnapshotsOutstandingOrdersAfterItsCascade() {
        service.recordDeath(base().build());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recallRepository, orderReview);
        order.verify(recallRepository).findByPatient_IdAndStatusIn(any(), any());
        order.verify(orderReview).snapshotAtDeath(any());
    }

    @Test
    void recordingADeathMarksThePatientDeceased() {
        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(patient.getDeceasedAt()).isNotNull();
        assertThat(patient.isDeceased()).isTrue();
        assertThat(result.getDeathRecord().getImmediateCause()).isEqualTo("Hypovolaemic shock");
        verify(patientRepository).save(patient);
    }

    @Test
    void aSecondDeathRecordIsRefused() {
        when(deathRecordRepository.existsByPatient_Id(patientId)).thenReturn(true);
        DeathRecordRequestDTO request = base().build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already recorded");
        verify(deathRecordRepository, never()).save(any());
    }

    @Test
    void aDeathCannotBeRecordedInTheFuture() {
        DeathRecordRequestDTO request = base().diedAt(LocalDateTime.now().plusDays(1)).build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be in the future");
    }

    // ── The cascade ─────────────────────────────────────────────────────

    @Test
    void recordingADeathClosesTheOpenAdmission() {
        Admission open = new Admission();
        open.setId(UUID.randomUUID());
        open.setHospital(hospital);
        open.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(open));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        // AdmissionStatus.DECEASED has existed since V1 with no writer at all.
        assertThat(open.getStatus()).isEqualTo(AdmissionStatus.DECEASED);
        assertThat(result.getClosure().getAdmissionsClosed()).isEqualTo(1);
        // QA D515: the bed (and any pending transfer's reservation) goes back.
        verify(bedAssignmentService).releaseBed(open);
    }

    @Test
    void anAlreadyDischargedAdmissionIsLeftAlone() {
        Admission done = new Admission();
        done.setId(UUID.randomUUID());
        done.setHospital(hospital);
        done.setStatus(AdmissionStatus.DISCHARGED);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(done));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(done.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(result.getClosure().getAdmissionsClosed()).isZero();
        verify(bedAssignmentService, never()).releaseBed(done);
    }

    @Test
    void recordingADeathClosesOpenEncounters() {
        Encounter open = new Encounter();
        open.setId(UUID.randomUUID());
        open.setHospital(hospital);
        open.setStatus(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(open));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(open.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(result.getClosure().getEncountersClosed()).isEqualTo(1);
        // QA D972 (round 18): the closed visit is stamped and handed to billing like a checkout.
        assertThat(open.getCheckoutTimestamp()).isNotNull();
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(com.example.hms.event.EncounterCheckedOutEvent.class));
    }

    @Test
    void recordingADeathCancelsFutureAppointments() {
        // This is the one that matters most: the reminder sweep selects purely
        // on appointment state, so a live appointment here means a text to the
        // family of someone who died.
        Appointment upcoming = new Appointment();
        upcoming.setId(UUID.randomUUID());
        upcoming.setHospital(hospital);
        upcoming.setStatus(AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any()))
            .thenReturn(List.of(upcoming));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(upcoming.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(upcoming.getNotes()).contains("Patient deceased");
        assertThat(result.getClosure().getAppointmentsCancelled()).isEqualTo(1);
    }

    @Test
    void anAlreadyCompletedAppointmentIsNotRewritten() {
        // A past appointment the patient actually attended is history.
        Appointment attended = new Appointment();
        attended.setId(UUID.randomUUID());
        attended.setHospital(hospital);
        attended.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any()))
            .thenReturn(List.of(attended));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(attended.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(result.getClosure().getAppointmentsCancelled()).isZero();
    }

    @Test
    void recordingADeathClosesPendingRecalls() {
        PatientRecall recall = new PatientRecall();
        recall.setId(UUID.randomUUID());
        recall.setHospital(hospital);
        recall.setStatus(RecallStatus.PENDING);
        when(recallRepository.findByPatient_IdAndStatusIn(any(), any())).thenReturn(List.of(recall));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(recall.getStatus()).isEqualTo(RecallStatus.CANCELLED);
        assertThat(result.getClosure().getRecallsClosed()).isEqualTo(1);
    }

    @Test
    void theCascadeStaysInsideTheRecordingHospital() {
        // A patient linked to two facilities has open records at each. Closing
        // another facility's ward entry would be acting outside scope.
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Admission foreign = new Admission();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        foreign.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(foreign));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(foreign.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        assertThat(result.getClosure().getAdmissionsClosed()).isZero();
    }

    // ── The WHO maternal boundary ───────────────────────────────────────

    @Test
    void aMaternalDeathMustSayWhenItOccurred() {
        DeathRecordRequestDTO request = base().maternalDeath(Boolean.TRUE).build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("when it occurred relative to the pregnancy");
    }

    @Test
    void aDeathWithin42DaysPostpartumIsAWhoMaternalDeath() {
        RecordDeathResponseDTO result = service.recordDeath(base()
            .maternalDeath(Boolean.TRUE)
            .maternalDeathTiming(MaternalDeathTiming.WITHIN_42_DAYS_POSTPARTUM)
            .build());

        assertThat(result.getDeathRecord().getWhoMaternalDeath()).isTrue();
    }

    @Test
    void aLateMaternalDeathIsNotAWhoMaternalDeath() {
        // 42 days to a year falls OUTSIDE the WHO definition and is reported
        // separately. Counting it in would overstate the facility's ratio.
        RecordDeathResponseDTO result = service.recordDeath(base()
            .maternalDeath(Boolean.TRUE)
            .maternalDeathTiming(MaternalDeathTiming.LATE_MATERNAL)
            .build());

        assertThat(result.getDeathRecord().getMaternalDeath()).isTrue();
        assertThat(result.getDeathRecord().getWhoMaternalDeath()).isFalse();
    }

    @Test
    void aPerinatalDeathMustSayWhetherItWasAStillbirth() {
        // A stillborn infant was never born alive, so it has no neonatal period: separate categories.
        DeathRecordRequestDTO request = base().perinatalDeath(Boolean.TRUE).build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("stillbirth or a neonatal death");
    }

    // ── Amendment ───────────────────────────────────────────────────────

    private DeathRecord persisted() {
        DeathRecord deathRecord = DeathRecord.builder()
            .patient(patient)
            .hospital(hospital)
            .diedAt(LocalDateTime.now().minusDays(3))
            .placeOfDeath(PlaceOfDeath.FACILITY)
            .mannerOfDeath(MannerOfDeath.NATURAL)
            .immediateCause("Cardiac arrest")
            .maternalDeath(Boolean.FALSE)
            .perinatalDeath(Boolean.FALSE)
            .autopsyRequested(Boolean.TRUE)
            .build();
        deathRecord.setId(UUID.randomUUID());
        when(deathRecordRepository.findById(deathRecord.getId())).thenReturn(Optional.of(deathRecord));
        return deathRecord;
    }

    @Test
    void anAutopsyCanReviseTheCause() {
        DeathRecord deathRecord = persisted();

        service.amendDeathRecord(deathRecord.getId(), DeathRecordAmendmentDTO.builder()
            .amendmentReason("Post-mortem findings")
            .underlyingCause("Pulmonary embolism")
            .underlyingCauseCode("I26.9")
            .build());

        assertThat(deathRecord.getUnderlyingCause()).isEqualTo("Pulmonary embolism");
        assertThat(deathRecord.isAmended()).isTrue();
        assertThat(deathRecord.getAmendmentReason()).isEqualTo("Post-mortem findings");
        // The FACT of death is untouched — only the account of it.
        assertThat(deathRecord.getDiedAt()).isNotNull();
    }

    @Test
    void anAmendmentRequiresAReason() {
        UUID recordId = persisted().getId();
        DeathRecordAmendmentDTO blankReason =
            DeathRecordAmendmentDTO.builder().amendmentReason("  ").build();

        assertThatThrownBy(() -> service.amendDeathRecord(recordId, blankReason))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void anAmendmentCannotMakeADeathMaternalWithoutSayingWhen() {
        // Re-checked AFTER the edits: otherwise the reporting query breaks on
        // a maternal death with no timing.
        UUID recordId = persisted().getId();
        DeathRecordAmendmentDTO amendment = DeathRecordAmendmentDTO.builder()
            .amendmentReason("Reclassified after review")
            .maternalDeath(Boolean.TRUE)
            .build();

        assertThatThrownBy(() -> service.amendDeathRecord(recordId, amendment))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("when it occurred");
    }

    @Test
    void anAmendmentCannotMakeADeathPerinatalWithoutAType() {
        UUID recordId = persisted().getId();
        DeathRecordAmendmentDTO amendment = DeathRecordAmendmentDTO.builder()
            .amendmentReason("Reclassified after review")
            .perinatalDeath(Boolean.TRUE)
            .build();

        assertThatThrownBy(() -> service.amendDeathRecord(recordId, amendment))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("stillbirth or a neonatal death");
    }

    // ── Register ────────────────────────────────────────────────────────

    private DeathRecord death(MaternalDeathTiming maternal, PerinatalDeathType perinatal) {
        return DeathRecord.builder()
            .patient(patient)
            .hospital(hospital)
            .diedAt(LocalDateTime.now().minusDays(1))
            .immediateCause("cause")
            .maternalDeath(maternal != null)
            .maternalDeathTiming(maternal)
            .perinatalDeath(perinatal != null)
            .perinatalType(perinatal)
            .build();
    }

    @Test
    void theRegisterBreaksOutTheCountsTheFacilityIsMeasuredOn() {
        when(deathRecordRepository.findRegister(any(), any(), any())).thenReturn(List.of(
            death(MaternalDeathTiming.WITHIN_42_DAYS_POSTPARTUM, null),
            death(MaternalDeathTiming.DURING_LABOUR_OR_DELIVERY, null),
            death(MaternalDeathTiming.LATE_MATERNAL, null),
            death(null, PerinatalDeathType.STILLBIRTH),
            death(null, PerinatalDeathType.EARLY_NEONATAL),
            death(null, null)));

        MortalityRegisterDTO register = service.getRegister(
            LocalDate.now().minusDays(30), LocalDate.now());

        assertThat(register.getTotalDeaths()).isEqualTo(6);
        // The late maternal death is NOT in the maternal count.
        assertThat(register.getMaternalDeaths()).isEqualTo(2);
        assertThat(register.getLateMaternalDeaths()).isEqualTo(1);
        assertThat(register.getPerinatalDeaths()).isEqualTo(2);
        assertThat(register.getStillbirths()).isEqualTo(1);
    }

    // ── CR-07: the WHO perinatal numerator ──────────────────────────────

    private DeathRecord classified(PerinatalDeathType type, Integer gestationWeeks,
                                   com.example.hms.enums.PerinatalBirthStatus status,
                                   com.example.hms.enums.PerinatalAgeBasis basis) {
        DeathRecord row = death(null, type);
        row.setGestationalAgeWeeks(gestationWeeks);
        row.setBirthStatus(status);
        row.setPerinatalAgeBasis(basis);
        return row;
    }

    @Test
    void aLateNeonatalDeathIsReportedApartAndNotInThePerinatalNumerator() {
        var live = com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH;
        var elapsed = com.example.hms.enums.PerinatalAgeBasis.ELAPSED_TIME;
        DeathRecord flagOffWithAType = death(null, PerinatalDeathType.LATE_NEONATAL);
        flagOffWithAType.setPerinatalDeath(Boolean.FALSE);
        when(deathRecordRepository.findRegister(any(), any(), any())).thenReturn(List.of(
            classified(PerinatalDeathType.LATE_NEONATAL, 38, live, elapsed),
            death(null, PerinatalDeathType.LATE_NEONATAL),
            classified(PerinatalDeathType.EARLY_NEONATAL, 38, live, elapsed),
            death(null, PerinatalDeathType.STILLBIRTH),
            classified(PerinatalDeathType.STILLBIRTH, 21, com.example.hms.enums.PerinatalBirthStatus.STILLBIRTH, elapsed),
            flagOffWithAType,
            death(null, null)));

        MortalityRegisterDTO register = service.getRegister(LocalDate.now().minusDays(30), LocalDate.now());

        assertThat(register.getTotalDeaths()).isEqualTo(7);
        assertThat(register.getLateNeonatalDeaths()).isEqualTo(2);
        assertThat(register.getEarlyNeonatalDeaths()).isEqualTo(1);
        assertThat(register.getStillbirths()).isEqualTo(2);
        // The early neonatal death and the stillbirth of unknown gestation.
        assertThat(register.getPerinatalDeaths()).isEqualTo(2);
        assertThat(register.getPerinatalEligibilityUnknown()).isEqualTo(1);
        assertThat(register.getPerinatalBelowGestation()).isEqualTo(1);
    }

    @Test
    void aRowFromBeforeTheFactsStaysCountedAndReadsUnconfirmedWithoutARewrite() {
        DeathRecord historic = death(null, PerinatalDeathType.EARLY_NEONATAL);
        when(deathRecordRepository.findRegister(any(), any(), any())).thenReturn(List.of(historic));

        MortalityRegisterDTO register = service.getRegister(LocalDate.now().minusDays(30), LocalDate.now());

        assertThat(register.getPerinatalDeaths()).isEqualTo(1);
        assertThat(register.getPerinatalEligibilityUnknown()).isEqualTo(1);
        var row = register.getDeaths().get(0);
        assertThat(row.getPerinatalEligibility()).isEqualTo(com.example.hms.enums.PerinatalReportingEligibility.UNKNOWN);
        assertThat(row.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.UNKNOWN);
        assertThat(row.getBirthStatus()).isEqualTo(com.example.hms.enums.PerinatalBirthStatus.UNKNOWN);
        verify(deathRecordRepository, never()).save(any());
    }

    // ── CR-08: the facts behind the classification ──────────────────────

    /** A past date on purpose, and fixed: the reported pair, 1 November 23:50 and 8 November 00:10. */
    private static final LocalDateTime BORN_AT = LocalDateTime.of(2025, 11, 1, 23, 50);
    private static final LocalDateTime DIED_AT = LocalDateTime.of(2025, 11, 8, 0, 10);

    private DeathRecordRequestDTO.DeathRecordRequestDTOBuilder newbornDeath(PerinatalDeathType type) {
        patient.setDateOfBirth(BORN_AT.toLocalDate());
        return base().diedAt(DIED_AT).perinatalDeath(true).perinatalType(type);
    }

    private void deliveryOnFile(int infants, Hospital at, boolean liveBirth) {
        com.example.hms.model.labor.DeliveryRecord delivery = com.example.hms.model.labor.DeliveryRecord.builder()
            .birthDateTime(BORN_AT).birthTimeStated(true).numberOfInfants(infants).liveBirth(liveBirth).gestationalAgeWeeksAtBirth(36).build();
        com.example.hms.model.neonatal.NewbornAssessment assessment = com.example.hms.model.neonatal.NewbornAssessment.builder()
            .hospital(at).deliveryRecord(delivery).build();
        when(newbornAssessmentRepository.findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId))
            .thenReturn(Optional.of(assessment));
    }

    @Test
    void aSingleBirthOnFileGivesTheElapsedAgeAndAnswersWhatWasLeftUnknown() {
        deliveryOnFile(1, hospital, true);

        var saved = service.recordDeath(newbornDeath(PerinatalDeathType.EARLY_NEONATAL).build()).getDeathRecord();

        assertThat(saved.getBornAt()).isEqualTo(BORN_AT);
        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.ELAPSED_TIME);
        assertThat(saved.getBirthStatus()).isEqualTo(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH);
        assertThat(saved.getGestationalAgeWeeks()).isEqualTo(36);
        assertThat(saved.getPerinatalEligibility()).isEqualTo(com.example.hms.enums.PerinatalReportingEligibility.ELIGIBLE);
    }

    @Test
    void theBirthOnFileRefusesLateNeonatalWhereTheDatesAloneWouldHaveKeptIt() {
        DeathRecordRequestDTO late = newbornDeath(PerinatalDeathType.LATE_NEONATAL).build();

        // The dates cannot decide: seven days apart, under seven completed days.
        assertThat(service.recordDeath(late).getDeathRecord().getPerinatalAgeBasis())
            .isEqualTo(com.example.hms.enums.PerinatalAgeBasis.DATE_ONLY_BOUNDARY);

        deliveryOnFile(1, hospital, true);
        patient.setDeceasedAt(null);
        assertThatThrownBy(() -> service.recordDeath(late))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("perinatalType"));
    }

    @Test
    void aDeliveryOfSeveralInfantsOrAtAnotherHospitalDoesNotSpeakForThisChart() {
        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        for (Runnable onFile : List.<Runnable>of(() -> deliveryOnFile(2, hospital, true), () -> deliveryOnFile(1, elsewhere, true))) {
            onFile.run();
            var saved = service.recordDeath(newbornDeath(PerinatalDeathType.LATE_NEONATAL).build()).getDeathRecord();
            assertThat(saved.getBornAt()).isNull();
            assertThat(saved.getGestationalAgeWeeks()).isNull();
            assertThat(saved.getBirthStatus()).isEqualTo(com.example.hms.enums.PerinatalBirthStatus.UNKNOWN);
            assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.DATE_ONLY_BOUNDARY);
        }
    }

    @Test
    void aDeliveryDocumentedWithItsTimeLeftBlankGivesNoExactAge() {
        deliveryOnFile(1, hospital, true);
        newbornAssessmentRepository
            .findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patientId)
            .orElseThrow().getDeliveryRecord().setBirthTimeStated(false);

        var saved = service.recordDeath(newbornDeath(PerinatalDeathType.LATE_NEONATAL).build()).getDeathRecord();

        // The stored time is the moment of documenting: it must not refuse this type, nor read as exact.
        assertThat(saved.getBornAt()).isNull();
        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.DATE_ONLY_BOUNDARY);
        assertThat(saved.getBirthStatus()).isEqualTo(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH);
        assertThat(saved.getGestationalAgeWeeks()).isEqualTo(36);
    }

    @Test
    void aMothersOwnDeliveryIsNotReadAsHerBirth() {
        deliveryOnFile(1, hospital, false);
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        DeathRecordRequestDTO stillbirth = base().diedAt(DIED_AT).perinatalDeath(true)
            .perinatalType(PerinatalDeathType.STILLBIRTH).build();

        assertThatThrownBy(() -> service.recordDeath(stillbirth))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getMessage()).contains("delivery record"));
        assertThat(patient.isDeceased()).isFalse();
    }

    @Test
    void whatTheRecorderStatesStandsOverTheDeliveryOnFile() {
        deliveryOnFile(1, hospital, false);
        LocalDateTime stated = BORN_AT.minusMinutes(30);

        var saved = service.recordDeath(newbornDeath(PerinatalDeathType.EARLY_NEONATAL)
            .bornAt(stated).birthStatus(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH).gestationalAgeWeeks(30).build())
            .getDeathRecord();

        assertThat(saved.getBornAt()).isEqualTo(stated);
        assertThat(saved.getBirthStatus()).isEqualTo(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH);
        assertThat(saved.getGestationalAgeWeeks()).isEqualTo(30);
    }

    @Test
    void missingGestationLeavesEligibilityUnknownAndKeepsTheTimingFromALiveBirthAndKnownTimes() {
        var saved = service.recordDeath(newbornDeath(PerinatalDeathType.EARLY_NEONATAL)
            .bornAt(BORN_AT).birthStatus(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH).build()).getDeathRecord();

        assertThat(saved.getPerinatalType()).isEqualTo(PerinatalDeathType.EARLY_NEONATAL);
        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.ELAPSED_TIME);
        assertThat(saved.getGestationalAgeWeeks()).isNull();
        assertThat(saved.getPerinatalEligibility()).isEqualTo(com.example.hms.enums.PerinatalReportingEligibility.UNKNOWN);
    }

    @Test
    void noDateOfBirthSavesAndReadsUnknownNotConfirmed() {
        var saved = service.recordDeath(base().perinatalDeath(true).perinatalType(PerinatalDeathType.EARLY_NEONATAL)
            .birthStatus(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH).gestationalAgeWeeks(38).build())
            .getDeathRecord();

        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.UNKNOWN);
        assertThat(saved.getPerinatalEligibility()).isEqualTo(com.example.hms.enums.PerinatalReportingEligibility.UNKNOWN);
        assertThat(patient.isDeceased()).isTrue();
    }

    @Test
    void aStillbirthWithNoDateOfBirthReadsUnknownEvenWithEveryOtherFact() {
        var saved = service.recordDeath(base().perinatalDeath(true).perinatalType(PerinatalDeathType.STILLBIRTH)
            .birthStatus(com.example.hms.enums.PerinatalBirthStatus.STILLBIRTH).gestationalAgeWeeks(38).build())
            .getDeathRecord();

        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.UNKNOWN);
        assertThat(saved.getPerinatalEligibility()).isEqualTo(com.example.hms.enums.PerinatalReportingEligibility.UNKNOWN);
    }

    @Test
    void withNoClassificationAFactThatCannotBeTrueRefusesNothingAndIsNotKept() {
        patient.setDateOfBirth(BORN_AT.toLocalDate());

        var saved = service.recordDeath(base().diedAt(DIED_AT).gestationalAgeWeeks(60).bornAt(DIED_AT.plusHours(1)).build())
            .getDeathRecord();

        assertThat(patient.isDeceased()).isTrue();
        assertThat(saved.getGestationalAgeWeeks()).isNull();
        assertThat(saved.getBornAt()).isNull();
    }

    @Test
    void factsWithNoClassificationAreKeptAndNotReadAgainstTheDeliveryOrTheType() {
        deliveryOnFile(1, hospital, false);
        patient.setDateOfBirth(BORN_AT.toLocalDate());

        var saved = service.recordDeath(base().diedAt(DIED_AT).gestationalAgeWeeks(30).build()).getDeathRecord();

        assertThat(saved.getGestationalAgeWeeks()).isEqualTo(30);
        assertThat(saved.getBornAt()).isNull();
        assertThat(saved.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.UNKNOWN);
        assertThat(saved.getPerinatalEligibility()).isNull();
    }

    @Test
    void eachRefusalNamesItsOwnFieldSaysTheDeathCanBeRecordedAndTheDeathSavesWithoutIt() {
        var live = com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH;
        var stillborn = com.example.hms.enums.PerinatalBirthStatus.STILLBIRTH;
        patient.setDateOfBirth(BORN_AT.toLocalDate());
        patient.setGender("M");
        List<java.util.Map.Entry<String, DeathRecordRequestDTO>> refused = List.of(
            java.util.Map.entry("maternalDeath", base().diedAt(DIED_AT).maternalDeath(true)
                .maternalDeathTiming(MaternalDeathTiming.DURING_PREGNANCY).build()),
            java.util.Map.entry("maternalDeathTiming", base().diedAt(DIED_AT).maternalDeath(true).build()),
            java.util.Map.entry("perinatalType", base().diedAt(DIED_AT).perinatalDeath(true).build()),
            java.util.Map.entry("perinatalType", newbornDeath(PerinatalDeathType.LATE_NEONATAL).bornAt(BORN_AT).build()),
            java.util.Map.entry("perinatalType", base().diedAt(BORN_AT.plusDays(2)).perinatalDeath(true)
                .perinatalType(PerinatalDeathType.LATE_NEONATAL).build()),
            java.util.Map.entry("perinatalType", base().diedAt(BORN_AT.plusDays(10)).perinatalDeath(true)
                .perinatalType(PerinatalDeathType.EARLY_NEONATAL).build()),
            java.util.Map.entry("perinatalType", base().diedAt(BORN_AT.plusDays(40)).perinatalDeath(true)
                .perinatalType(PerinatalDeathType.LATE_NEONATAL).build()),
            java.util.Map.entry("perinatalType", newbornDeath(PerinatalDeathType.STILLBIRTH).birthStatus(live).build()),
            java.util.Map.entry("perinatalType", newbornDeath(PerinatalDeathType.EARLY_NEONATAL).birthStatus(stillborn).build()),
            java.util.Map.entry("gestationalAgeWeeks", newbornDeath(PerinatalDeathType.EARLY_NEONATAL).gestationalAgeWeeks(60).build()),
            java.util.Map.entry("bornAt", newbornDeath(PerinatalDeathType.EARLY_NEONATAL).bornAt(DIED_AT.plusHours(1)).build()));

        for (var entry : refused) {
            assertThatThrownBy(() -> service.recordDeath(entry.getValue()))
                .as(entry.getKey())
                .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo(entry.getKey());
                    assertThat(e.getMessage()).contains("can be recorded without");
                });
            assertThat(patient.isDeceased()).isFalse();
        }
        verify(deathRecordRepository, never()).save(any());

        for (var entry : refused) {
            DeathRecordRequestDTO without = base().diedAt(entry.getValue().getDiedAt()).build();
            service.recordDeath(without);
            assertThat(patient.isDeceased()).isTrue();
            patient.setDeceasedAt(null);
        }
    }

    @Test
    void anAmendmentIsJudgedOnWhatItSetsAndNeverOverAnOlderRowsFlags() {
        DeathRecord older = persisted();
        patient.setDateOfBirth(older.getDiedAt().toLocalDate().minusDays(2));
        older.setPerinatalDeath(Boolean.TRUE);
        older.setPerinatalType(PerinatalDeathType.LATE_NEONATAL);
        UUID id = older.getId();

        service.amendDeathRecord(id, DeathRecordAmendmentDTO.builder().amendmentReason("Autopsy")
            .underlyingCause("Sepsis").gestationalAgeWeeks(30).build());

        assertThat(older.getUnderlyingCause()).isEqualTo("Sepsis");
        assertThat(older.getGestationalAgeWeeks()).isEqualTo(30);
        assertThat(older.getPerinatalType()).isEqualTo(PerinatalDeathType.LATE_NEONATAL);

        DeathRecordAmendmentDTO status = DeathRecordAmendmentDTO.builder().amendmentReason("Review")
            .birthStatus(com.example.hms.enums.PerinatalBirthStatus.LIVE_BIRTH).build();
        assertThatThrownBy(() -> service.amendDeathRecord(id, status))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("perinatalType"));
    }

    @Test
    void anAmendmentReachesTheSameRulesAndRecordsTheBasis() {
        DeathRecord deathRecord = persisted();
        patient.setDateOfBirth(deathRecord.getDiedAt().toLocalDate().minusDays(2));
        UUID id = deathRecord.getId();
        DeathRecordAmendmentDTO late = DeathRecordAmendmentDTO.builder().amendmentReason("Review")
            .perinatalDeath(true).perinatalType(PerinatalDeathType.LATE_NEONATAL).build();
        DeathRecordAmendmentDTO wrongWeeks = DeathRecordAmendmentDTO.builder().amendmentReason("Review")
            .gestationalAgeWeeks(99).build();

        assertThatThrownBy(() -> service.amendDeathRecord(id, late))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("perinatalType"));
        assertThatThrownBy(() -> service.amendDeathRecord(id, wrongWeeks))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("gestationalAgeWeeks"));

        service.amendDeathRecord(id, DeathRecordAmendmentDTO.builder().amendmentReason("Review")
            .perinatalDeath(true).perinatalType(PerinatalDeathType.EARLY_NEONATAL).build());
        assertThat(deathRecord.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.DATE_ONLY);

        service.amendDeathRecord(id, DeathRecordAmendmentDTO.builder().amendmentReason("Review").perinatalDeath(false).build());
        assertThat(deathRecord.getPerinatalType()).isNull();
        assertThat(deathRecord.getPerinatalAgeBasis()).isEqualTo(com.example.hms.enums.PerinatalAgeBasis.UNKNOWN);
    }

    @Test
    void theRegisterRefusesAnInvertedPeriod() {
        LocalDate from = LocalDate.now();
        LocalDate to = from.minusDays(7);

        assertThatThrownBy(() -> service.getRegister(from, to))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot precede");
    }

    @Test
    void theRegisterNeedsAPeriod() {
        LocalDate to = LocalDate.now();

        assertThatThrownBy(() -> service.getRegister(null, to))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("period is required");
    }

    // ── Reads and tenancy ───────────────────────────────────────────────

    @Test
    void aPatientDeathRecordIsReadable() {
        DeathRecord deathRecord = persisted();
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.of(deathRecord));

        assertThat(service.getForPatient(patientId).getImmediateCause()).isEqualTo("Cardiac arrest");
    }

    @Test
    void aLivingPatientHasNoDeathRecord() {
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForPatient(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aRecordAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        DeathRecord foreign = DeathRecord.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(deathRecordRepository.findById(id)).thenReturn(Optional.of(foreign));
        DeathRecordAmendmentDTO amendment =
            DeathRecordAmendmentDTO.builder().amendmentReason("Not mine").build();

        assertThatThrownBy(() -> service.amendDeathRecord(id, amendment))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminWithNoActiveHospitalCannotCertifyADeath() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        DeathRecordRequestDTO request = base().build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital is required");
    }

    @Test
    void aCertifierAtAnotherHospitalIsRejected() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Staff foreign = new Staff();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(staffRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        DeathRecordRequestDTO request = base().certifiedByStaffId(foreign.getId()).build();

        assertThatThrownBy(() -> service.recordDeath(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void namingACertifierStampsTheCertificationTime() {
        Staff certifier = new Staff();
        certifier.setId(UUID.randomUUID());
        certifier.setHospital(hospital);
        when(staffRepository.findById(certifier.getId())).thenReturn(Optional.of(certifier));

        RecordDeathResponseDTO result = service.recordDeath(base()
            .certifiedByStaffId(certifier.getId()).build());

        assertThat(result.getDeathRecord().getCertifiedAt()).isNotNull();
    }

    /** QA D1435: a death ends the stay's nursing work, as a discharge does. */
    @Test
    void qaD1435_aDeathClosesTheStaysNursingWork() {
        com.example.hms.service.AdmissionStayEncounterService stayEncounters =
            org.mockito.Mockito.mock(com.example.hms.service.AdmissionStayEncounterService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "stayEncounters", stayEncounters);
        Admission open = new Admission();
        open.setId(UUID.randomUUID());
        open.setHospital(hospital);
        open.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(open));

        service.recordDeath(base().build());

        verify(stayEncounters).closeNursingWorkOfEndedStay(open);
    }
}
