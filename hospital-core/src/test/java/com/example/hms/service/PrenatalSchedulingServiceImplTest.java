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
    @Mock
    private NotificationService notificationService;

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
            fixedClock
        );

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
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
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

    verify(notificationService).createNotification(any(String.class), eq(patient.getUser().getUsername()));
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
}
