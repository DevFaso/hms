package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.PrenatalVisitType;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalReminderRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalRescheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalVisitRecommendationDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.i18n.PatientLocaleResolver;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class PrenatalSchedulingServiceImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private AppointmentService appointmentService;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS)
    private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock
    private PatientLocaleResolver patientLocaleResolver;
    @Mock
    private com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    @Mock
    private com.example.hms.repository.LaborEpisodeRepository laborEpisodeRepository;

    private PrenatalSchedulingServiceImpl service;
    private static final String SCHEDULER_USERNAME = "scheduler";
    private static final String PRENATAL_REASON = "Prenatal follow-up";

    private UUID hospitalId;
    private UUID patientId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2024-04-01T10:00:00Z"), ZoneId.of("UTC"));
        service = new PrenatalSchedulingServiceImpl(
            appointmentRepository,
            patientRepository,
            hospitalRepository,
            staffRepository,
            appointmentService,
            notificationService,
            fixedClock,
            TestMessageSources.bundles(),
            deliveryRecordRepository,
            laborEpisodeRepository
        );
        // The patient in these fixtures states no language: the resolver
        // answers the fallback, which is what production does too.
        lenient().when(patientLocaleResolver.resolve(any(), any())).thenReturn(Locale.FRENCH);

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = Hospital.builder()
            .name("Central Hospital")
            .code("CENTRAL")
            .build();
        hospital.setId(hospitalId);

        patient = Patient.builder()
            .firstName("Jane")
            .lastName("Doe")
            .email("jane@example.com")
            .phoneNumberPrimary("555-0001")
            .dateOfBirth(LocalDate.of(1994, 2, 8))
            .user(User.builder()
                .username("jane.doe")
                .passwordHash("hash")
                .email("jane@example.com")
                .phoneNumber("555-0001")
                .build())
            .build();
        patient.setId(patientId);

        PatientHospitalRegistration registration = PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN-1")
            .active(true)
            .build();
        patient.getHospitalRegistrations().add(registration);

        HospitalContext context = HospitalContext.builder()
            .principalUsername(SCHEDULER_USERNAME)
            .activeHospitalId(hospitalId)
            .permittedHospitalIds(Set.of(hospitalId))
            .build();
        HospitalContextHolder.setContext(context);

    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void generateScheduleHighRiskProducesWeeklyVisitsAfterTwentyEightWeeks() {
        LocalDate lmp = LocalDate.of(2023, 9, 4); // 30 weeks on fixed clock date

        Appointment existing = Appointment.builder()
            .patient(patient)
            .hospital(hospital)
            .department(Department.builder().name("OB").hospital(hospital).build())
            .appointmentDate(LocalDate.of(2024, 4, 8))
            .startTime(LocalTime.of(10, 0))
            .endTime(LocalTime.of(10, 30))
            .status(AppointmentStatus.SCHEDULED)
            .reason("Routine prenatal check")
            .build();
        existing.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(appointmentRepository.findByHospital_IdAndPatient_Id(hospitalId, patientId))
            .thenReturn(List.of(existing));

        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(lmp)
            .highRisk(true)
            .build();

    PrenatalScheduleResponseDTO response = service.generateSchedule(request, Locale.US, SCHEDULER_USERNAME);

    assertThat(response.isHighRisk()).isTrue();
    assertThat(response.getAlerts()).anySatisfy(alert -> assertThat(alert).contains("High-risk"));

        List<Integer> weeks = response.getRecommendations().stream()
            .filter(rec -> rec.getGestationalWeek() >= 30)
            .map(PrenatalVisitRecommendationDTO::getGestationalWeek)
            .toList();

        assertThat(weeks).isNotEmpty()
            .isSorted()
            .hasSizeGreaterThanOrEqualTo(8);
        for (int i = 1; i < weeks.size(); i++) {
            assertThat(weeks.get(i) - weeks.get(i - 1)).isEqualTo(1);
        }

        PrenatalVisitType firstType = response.getRecommendations().get(0).getVisitType();
        assertThat(firstType).isEqualTo(PrenatalVisitType.INITIAL_INTAKE);

        assertThat(response.getRecommendations())
            .anySatisfy(rec -> assertThat(rec.isScheduled()).isTrue());
    }

    /** QA D519: a GP visit and a cancelled prenatal appointment are not antenatal visits. */
    @Test
    void generateScheduleIgnoresGpAndCancelledAppointments() {
        LocalDate lmp = LocalDate.of(2023, 9, 4);
        Appointment gp = Appointment.builder()
            .patient(patient).hospital(hospital)
            .department(Department.builder().name("Médecine générale").hospital(hospital).build())
            .appointmentDate(LocalDate.of(2024, 4, 8)).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(9, 30))
            .status(AppointmentStatus.SCHEDULED).reason("Consultation").build();
        gp.setId(UUID.randomUUID());
        Appointment cancelled = Appointment.builder()
            .patient(patient).hospital(hospital)
            .department(Department.builder().name("Maternité").hospital(hospital).build())
            .appointmentDate(LocalDate.of(2024, 4, 15)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
            .status(AppointmentStatus.CANCELLED).reason("Consultation prénatale").build();
        cancelled.setId(UUID.randomUUID());
        Appointment prenatal = Appointment.builder()
            .patient(patient).hospital(hospital)
            .department(Department.builder().name("Maternité").hospital(hospital).build())
            .appointmentDate(LocalDate.of(2024, 4, 22)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
            .status(AppointmentStatus.SCHEDULED).reason("CPN").build();
        prenatal.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(appointmentRepository.findByHospital_IdAndPatient_Id(hospitalId, patientId))
            .thenReturn(List.of(gp, cancelled, prenatal));

        PrenatalScheduleResponseDTO response = service.generateSchedule(PrenatalScheduleRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).lastMenstrualPeriodDate(lmp).build(), Locale.US, SCHEDULER_USERNAME);

        assertThat(response.getExistingAppointments())
            .extracting(com.example.hms.payload.dto.prenatal.PrenatalAppointmentSummaryDTO::getAppointmentId)
            .containsExactly(prenatal.getId());
        assertThat(response.getRecommendations())
            .filteredOn(PrenatalVisitRecommendationDTO::isScheduled)
            .extracting(PrenatalVisitRecommendationDTO::getAppointmentId)
            .containsExactly(prenatal.getId());
    }

    /** QA D519: a midwife's appointment counts even with no reason or maternity department. */
    @Test
    void aMidwifeAppointmentCountsAsAPrenatalVisit() {
        com.example.hms.model.Staff midwife = new com.example.hms.model.Staff();
        midwife.setJobTitle(com.example.hms.enums.JobTitle.MIDWIFE);
        Appointment appt = Appointment.builder().staff(midwife)
            .appointmentDate(LocalDate.of(2024, 4, 22)).status(AppointmentStatus.CONFIRMED).build();
        assertThat(PrenatalSchedulingServiceImpl.countsAsPrenatalVisit(appt)).isTrue();
        appt.setStatus(AppointmentStatus.NO_SHOW);
        assertThat(PrenatalSchedulingServiceImpl.countsAsPrenatalVisit(appt)).isFalse();
    }

    @Test
    void generateScheduleNeverRecommendsAPastTargetOrAnInvertedWindow() {
        // QA D47: on a Sunday, LMP + whole weeks lands on the previous Monday;
        // the first visit's target was six days ago and its window ran from
        // today back to three days ago.
        LocalDate sunday = LocalDate.of(2024, 4, 7);
        PrenatalSchedulingServiceImpl sundayService = new PrenatalSchedulingServiceImpl(
            appointmentRepository, patientRepository, hospitalRepository, staffRepository,
            appointmentService, notificationService,
            Clock.fixed(Instant.parse("2024-04-07T10:00:00Z"), ZoneId.of("UTC")),
            TestMessageSources.bundles(), deliveryRecordRepository, laborEpisodeRepository);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(appointmentRepository.findByHospital_IdAndPatient_Id(hospitalId, patientId)).thenReturn(List.of());

        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LocalDate.of(2024, 1, 1))
            .supplementalVisitWeeks(List.of(6, 14))
            .build();

        PrenatalScheduleResponseDTO response = sundayService.generateSchedule(request, Locale.US, SCHEDULER_USERNAME);

        assertThat(response.getRecommendations()).isNotEmpty().allSatisfy(rec -> {
            assertThat(rec.getTargetDate()).isAfterOrEqualTo(sunday);
            assertThat(rec.getWindowStart()).isAfterOrEqualTo(sunday);
            assertThat(rec.getWindowStart()).isBeforeOrEqualTo(rec.getTargetDate());
            assertThat(rec.getWindowEnd()).isAfterOrEqualTo(rec.getTargetDate());
        });
        PrenatalVisitRecommendationDTO first = response.getRecommendations().get(0);
        assertThat(first.getTargetDate()).isEqualTo(LocalDate.of(2024, 4, 8));
        // Week 6 is behind this pregnancy; week 14 is still ahead.
        assertThat(response.getRecommendations())
            .extracting(PrenatalVisitRecommendationDTO::getGestationalWeek)
            .doesNotContain(6)
            .contains(14);
    }

    @Test
    void generateScheduleRefusesAPatientRecordedAsMale() {
        // QA D46: obstetric records were accepted for a MALE patient.
        patient.setGender("MALE");
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LocalDate.of(2024, 1, 1))
            .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.generateSchedule(request, Locale.US, SCHEDULER_USERNAME))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(appointmentRepository);
    }

    @Test
    void reschedulePrenatalAppointmentDelegatesToAppointmentService() {
        UUID appointmentId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.builder()
            .hospital(hospital)
            .build();
        staff.setId(staffId);

        Department department = Department.builder()
            .name("OB")
            .hospital(hospital)
            .build();

        Appointment appointment = Appointment.builder()
            .patient(patient)
            .staff(staff)
            .hospital(hospital)
            .department(department)
            .appointmentDate(LocalDate.of(2024, 4, 2))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(9, 30))
            .status(AppointmentStatus.SCHEDULED)
            .reason(PRENATAL_REASON)
            .build();
        appointment.setId(appointmentId);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        AppointmentResponseDTO mockedResponse = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentService.updateAppointment(any(UUID.class), any(AppointmentRequestDTO.class), any(Locale.class), any(String.class)))
            .thenReturn(mockedResponse);

        PrenatalRescheduleRequestDTO request = PrenatalRescheduleRequestDTO.builder()
            .appointmentId(appointmentId)
            .newAppointmentDate(LocalDate.of(2024, 4, 10))
            .newStartTime(LocalTime.of(11, 0))
            .durationMinutes(20)
            .newStaffId(staffId)
            .notes("Patient requested later morning slot")
            .build();

    AppointmentResponseDTO response = service.reschedulePrenatalAppointment(request, Locale.US, SCHEDULER_USERNAME);
        assertThat(response).isSameAs(mockedResponse);

    ArgumentCaptor<AppointmentRequestDTO> captor = ArgumentCaptor.forClass(AppointmentRequestDTO.class);
    verify(appointmentService).updateAppointment(eq(appointmentId), captor.capture(), any(Locale.class), any(String.class));

        AppointmentRequestDTO dto = captor.getValue();
        assertThat(dto.getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalTime.of(11, 20));
        // QA D977 class: a moved visit is booked at its new time (SCHEDULED), as the
        // desk and portal reschedules land it; RESCHEDULED is a superseded booking.
        assertThat(dto.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(dto.getNotes()).contains("Patient requested later morning slot");
    }

    @Test
    void d977_reschedulePrenatalAppointmentDelegatesToAppointmentService_fromAMissedVisit() {
        UUID appointmentId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.builder()
            .hospital(hospital)
            .build();
        staff.setId(staffId);

        Department department = Department.builder()
            .name("OB")
            .hospital(hospital)
            .build();

        Appointment appointment = Appointment.builder()
            .patient(patient)
            .staff(staff)
            .hospital(hospital)
            .department(department)
            .appointmentDate(LocalDate.of(2024, 4, 2))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(9, 30))
            .status(AppointmentStatus.NO_SHOW)
            .reason(PRENATAL_REASON)
            .build();
        appointment.setId(appointmentId);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        AppointmentResponseDTO mockedResponse = AppointmentResponseDTO.builder().id(appointmentId).build();
        when(appointmentService.updateAppointment(any(UUID.class), any(AppointmentRequestDTO.class), any(Locale.class), any(String.class)))
            .thenReturn(mockedResponse);

        PrenatalRescheduleRequestDTO request = PrenatalRescheduleRequestDTO.builder()
            .appointmentId(appointmentId)
            .newAppointmentDate(LocalDate.of(2024, 4, 10))
            .newStartTime(LocalTime.of(11, 0))
            .durationMinutes(20)
            .newStaffId(staffId)
            .notes("Patient requested later morning slot")
            .build();

    AppointmentResponseDTO response = service.reschedulePrenatalAppointment(request, Locale.US, SCHEDULER_USERNAME);
        assertThat(response).isSameAs(mockedResponse);

    ArgumentCaptor<AppointmentRequestDTO> captor = ArgumentCaptor.forClass(AppointmentRequestDTO.class);
    verify(appointmentService).updateAppointment(eq(appointmentId), captor.capture(), any(Locale.class), any(String.class));

        AppointmentRequestDTO dto = captor.getValue();
        assertThat(dto.getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalTime.of(11, 20));
        // QA D977 class: a moved visit is booked at its new time (SCHEDULED), as the
        // desk and portal reschedules land it; RESCHEDULED is a superseded booking.
        assertThat(dto.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(dto.getNotes()).contains("Patient requested later morning slot");
    }

    @Test
    void createReminderEnqueuesNotification() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
            .patient(patient)
            .hospital(hospital)
            .appointmentDate(LocalDate.of(2024, 4, 15))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(9, 30))
            .status(AppointmentStatus.SCHEDULED)
            .reason(PRENATAL_REASON)
            .build();
        appointment.setId(appointmentId);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        PrenatalReminderRequestDTO request = PrenatalReminderRequestDTO.builder()
            .appointmentId(appointmentId)
            .daysBefore(3)
            .build();

    service.createReminder(request, Locale.US, SCHEDULER_USERNAME);

    verify(notificationService).created(any(String.class), eq(patient.getUser().getUsername()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createReminderRejectsPastAppointments() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = Appointment.builder()
            .patient(patient)
            .hospital(hospital)
            .appointmentDate(LocalDate.of(2024, 3, 25))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(9, 30))
            .status(AppointmentStatus.SCHEDULED)
            .reason(PRENATAL_REASON)
            .build();
        appointment.setId(appointmentId);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        PrenatalReminderRequestDTO request = PrenatalReminderRequestDTO.builder()
            .appointmentId(appointmentId)
            .daysBefore(1)
            .build();

        assertThatThrownBy(() -> service.createReminder(request, Locale.US, SCHEDULER_USERNAME))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Cannot create reminders for appointments in the past");
    }

    // ── QA D164 ───────────────────────────────────────────────────────────

    @Test
    void anLmpNinetyThreeWeeksAgoIsRefused() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LocalDate.of(2024, 4, 1).minusWeeks(93))
            .build();

        assertThatThrownBy(() -> service.generateSchedule(request, Locale.FRENCH, SCHEDULER_USERNAME))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .satisfies(e -> assertThat(((com.example.hms.exception.BusinessException) e).getMessageKey())
                .isEqualTo("prenatal.lmp.implausible"));
    }

    @Test
    void noAntenatalPlanAfterTheDeliveryOfThatPregnancy() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        LocalDate lmp = LocalDate.of(2024, 4, 1).minusWeeks(41);
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(patientId, lmp.atStartOfDay()))
            .thenReturn(true);
        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(lmp)
            .build();

        assertThatThrownBy(() -> service.generateSchedule(request, Locale.FRENCH, SCHEDULER_USERNAME))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .satisfies(e -> assertThat(((com.example.hms.exception.BusinessException) e).getMessageKey())
                .isEqualTo("prenatal.pregnancy.delivered"));
    }

    /**
     * QA D796, the exact round-12 step: A3 is in labour (an episode is open)
     * and the Prénatal tab asks for a calendar with an LMP 14 weeks ago. It
     * was planned at week 14 with no alert; it is refused now.
     */
    @Test
    void noAntenatalPlanWhileThePatientIsInLabour() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(laborEpisodeRepository.existsByPatient_IdAndStatus(patientId, com.example.hms.enums.LaborStatus.ACTIVE))
            .thenReturn(true);
        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LocalDate.of(2024, 4, 1).minusWeeks(14))
            .build();

        assertThatThrownBy(() -> service.generateSchedule(request, Locale.FRENCH, SCHEDULER_USERNAME))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .satisfies(e -> assertThat(((com.example.hms.exception.BusinessException) e).getMessageKey())
                .isEqualTo("prenatal.pregnancy.inLabour"));
        verify(appointmentRepository, never()).findByHospital_IdAndPatient_Id(any(), any());
    }

    /* ── QA D249: schedule recommendations in the caller's language ─────── */

    @Test
    void generateScheduleRendersRecommendationsAndAlertsInFrench() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(appointmentRepository.findByHospital_IdAndPatient_Id(hospitalId, patientId)).thenReturn(List.of());

        PrenatalScheduleRequestDTO request = PrenatalScheduleRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LocalDate.of(2023, 9, 4))
            .highRisk(true)
            .build();

        PrenatalScheduleResponseDTO response = service.generateSchedule(request, Locale.FRENCH, SCHEDULER_USERNAME);

        assertThat(response.getRecommendations()).isNotEmpty().allSatisfy(rec -> {
            assertThat(rec.getRecommendation()).contains("semaine").doesNotContain("week");
            assertThat(rec.getNotes()).doesNotContain("Monitor").doesNotContain("Standard");
        });
        assertThat(response.getAlerts()).anySatisfy(alert -> assertThat(alert).startsWith("Grossesse à haut risque"));
    }
}
