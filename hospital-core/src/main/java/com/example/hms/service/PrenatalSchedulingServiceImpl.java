package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.PrenatalVisitType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalAppointmentSummaryDTO;
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
import com.example.hms.service.support.HospitalScopeUtils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PrenatalSchedulingServiceImpl implements PrenatalSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(PrenatalSchedulingServiceImpl.class);
    private static final LocalTime DEFAULT_START_TIME = LocalTime.of(9, 0);
    private static final int INITIAL_VISIT_DURATION_MINUTES = 45;
    private static final int ROUTINE_VISIT_DURATION_MINUTES = 15;
    private static final int ULTRASOUND_VISIT_DURATION_MINUTES = 30;
    private static final int MAX_STANDARD_GESTATIONAL_WEEK = 40;

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final AppointmentService appointmentService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Override
    public PrenatalScheduleResponseDTO generateSchedule(PrenatalScheduleRequestDTO request, Locale locale, String username) {
        Objects.requireNonNull(request, "Prenatal scheduling request is required");
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Staff staff = resolveStaffIfPresent(request.getStaffId(), hospital.getId());
        validateHospitalScope(hospital.getId(), username);
        ensurePatientBelongsToHospital(patient, hospital.getId());

        LocalDate lmp = request.getLastMenstrualPeriodDate();
        LocalDate dueDate = resolveDueDate(request, lmp);
        LocalDate today = LocalDate.now(clock);
        int currentWeek = Math.max(0, (int) ChronoUnit.WEEKS.between(lmp, today));

        List<Appointment> futureAppointments = appointmentRepository.findByHospital_IdAndPatient_Id(hospital.getId(), patient.getId()).stream()
            .filter(appt -> !appt.getAppointmentDate().isBefore(today.minusWeeks(1)))
            .sorted(Comparator.comparing(Appointment::getAppointmentDate).thenComparing(Appointment::getStartTime))
            .toList();

        boolean highRisk = Boolean.TRUE.equals(request.getHighRisk());
    List<Integer> supplementalWeeks = Optional.ofNullable(request.getSupplementalVisitWeeks()).orElse(List.of());

    List<PrenatalVisitRecommendationDTO> recommendations = buildRecommendations(lmp, today, currentWeek, highRisk, supplementalWeeks, futureAppointments);
        List<PrenatalAppointmentSummaryDTO> existingAppointments = futureAppointments.stream()
            .map(appt -> toAppointmentSummary(appt, lmp))
            .toList();

        List<String> alerts = new ArrayList<>();
        if (dueDate.isBefore(today)) {
            alerts.add("Estimated due date is in the past – verify patient details.");
        }
        if (highRisk) {
            alerts.add("High-risk pregnancy flagged – planner switched to accelerated monitoring.");
        }

        detectAppointmentCollisions(existingAppointments, alerts);

        return PrenatalScheduleResponseDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospital.getId())
            .staffId(staff != null ? staff.getId() : null)
            .estimatedDueDate(dueDate)
            .currentGestationalWeek(currentWeek)
            .highRisk(highRisk)
            .recommendations(recommendations)
            .existingAppointments(existingAppointments)
            .alerts(alerts)
            .build();
    }

    @Override
    public AppointmentResponseDTO reschedulePrenatalAppointment(PrenatalRescheduleRequestDTO request, Locale locale, String username) {
        Objects.requireNonNull(request, "Reschedule request is required");
        Appointment appointment = appointmentRepository.findById(request.getAppointmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        validateHospitalScope(appointment.getHospital().getId(), username);

        int durationMinutes = resolveDuration(request.getDurationMinutes(), appointment.getReason());
        LocalTime endTime = request.getNewStartTime().plusMinutes(durationMinutes);

        AppointmentRequestDTO updateDto = AppointmentRequestDTO.builder()
            .appointmentDate(request.getNewAppointmentDate())
            .startTime(request.getNewStartTime())
            .endTime(endTime)
            .status(AppointmentStatus.RESCHEDULED)
            .patientId(appointment.getPatient().getId())
            .staffId(resolveStaffForReschedule(request, appointment))
            .hospitalId(appointment.getHospital().getId())
            .departmentId(appointment.getDepartment() != null ? appointment.getDepartment().getId() : null)
            .reason(appointment.getReason())
            .notes(Optional.ofNullable(request.getNotes()).orElse(appointment.getNotes()))
            .build();

        return appointmentService.updateAppointment(appointment.getId(), updateDto, locale, username);
    }

    @Override
    public void createReminder(PrenatalReminderRequestDTO request, Locale locale, String username) {
        Objects.requireNonNull(request, "Reminder request is required");
        Appointment appointment = appointmentRepository.findById(request.getAppointmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        validateHospitalScope(appointment.getHospital().getId(), username);

        LocalDate today = LocalDate.now(clock);
        if (appointment.getAppointmentDate().isBefore(today)) {
            throw new BusinessException("Cannot create reminders for appointments in the past");
        }

        String patientUsername = Optional.ofNullable(appointment.getPatient())
            .map(Patient::getUser)
            .map(User::getUsername)
            .orElseThrow(() -> new BusinessException("Appointment missing patient user"));

        LocalDate reminderDate = appointment.getAppointmentDate().minusDays(request.getDaysBefore());
        if (reminderDate.isBefore(today)) {
            log.debug("Reminder date {} already passed for appointment {} – sending immediately", reminderDate, appointment.getId());
        }

        String message = Optional.ofNullable(request.getCustomMessage())
            .filter(msg -> !msg.isBlank())
            .orElseGet(() -> buildDefaultReminderMessage(appointment, request.getDaysBefore()));

        notificationService.createNotification(message, patientUsername);
    }

    private void validateHospitalScope(UUID hospitalId, String username) {
        HospitalContext context = HospitalContextHolder.getContext()
            .orElseGet(HospitalContext::empty);
        if (context.isSuperAdmin()) {
            return;
        }
        Set<UUID> scope = HospitalScopeUtils.resolveScope(context);
        if (!scope.contains(hospitalId)) {
            if (log.isWarnEnabled()) {
                log.warn("User {} attempted to access hospital {} outside their scope {}", username, hospitalId, scope);
            }
            throw new AccessDeniedException("You do not have access to this hospital");
        }
    }

    private Staff resolveStaffIfPresent(UUID staffId, UUID hospitalId) {
        if (staffId == null) {
            return null;
        }
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));
        if (!staff.getHospital().getId().equals(hospitalId)) {
            throw new BusinessException("Selected staff member does not belong to the requested hospital");
        }
        return staff;
    }

    private void ensurePatientBelongsToHospital(Patient patient, UUID hospitalId) {
        boolean registered = patient.getHospitalRegistrations().stream()
            .filter(Objects::nonNull)
            .anyMatch(reg -> reg.getHospital() != null && hospitalId.equals(reg.getHospital().getId()) && reg.isActive());
        if (!registered) {
            throw new BusinessException("Patient is not registered in the requested hospital");
        }
    }

    private LocalDate resolveDueDate(PrenatalScheduleRequestDTO request, LocalDate lmp) {
        if (request.getEstimatedDueDate() != null) {
            return request.getEstimatedDueDate();
        }
        return lmp.plusWeeks(40);
    }

    private List<PrenatalVisitRecommendationDTO> buildRecommendations(
        LocalDate lmp,
        LocalDate today,
        int currentWeek,
        boolean highRisk,
        List<Integer> supplementalWeeks,
        List<Appointment> futureAppointments
    ) {
        List<PrenatalVisitRecommendationDTO> plan = new ArrayList<>();
        Set<Integer> supplemental = supplementalWeeks == null ? Set.of() : supplementalWeeks.stream()
            .filter(week -> week > 0 && week <= 44)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        int weekPointer = Math.max(8, currentWeek);
        if (weekPointer > MAX_STANDARD_GESTATIONAL_WEEK) {
            weekPointer = MAX_STANDARD_GESTATIONAL_WEEK;
        }

        List<Appointment> unmatched = new ArrayList<>(futureAppointments);
        boolean firstVisitAdded = false;

        while (weekPointer <= MAX_STANDARD_GESTATIONAL_WEEK) {
            PrenatalVisitType visitType = determineVisitType(weekPointer, firstVisitAdded);
            int duration = resolveDurationForType(visitType);
            LocalDate targetDate = alignToPreferredWeekday(lmp.plusWeeks(weekPointer));
            LocalDate windowStart = targetDate.minusDays(3);
            LocalDate windowEnd = targetDate.plusDays(3);
            if (windowStart.isBefore(today)) {
                windowStart = today;
            }

            PrenatalVisitRecommendationDTO recommendation = PrenatalVisitRecommendationDTO.builder()
                .targetDate(targetDate)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .suggestedStartTime(DEFAULT_START_TIME)
                .suggestedEndTime(DEFAULT_START_TIME.plusMinutes(duration))
                .gestationalWeek(weekPointer)
                .durationMinutes(duration)
                .visitType(visitType)
                .scheduled(false)
                .notes(buildNotesForVisit(visitType))
                .recommendation(buildRecommendationText(visitType, weekPointer))
                .build();

            matchExistingAppointment(lmp, recommendation, unmatched);
            plan.add(recommendation);

            firstVisitAdded = true;
            int frequency = determineFrequency(weekPointer, highRisk);
            weekPointer += frequency;
        }

        supplemental.stream()
            .filter(week -> plan.stream().noneMatch(rec -> rec.getGestationalWeek() == week))
            .map(week -> createSupplementalRecommendation(lmp, week, today))
            .forEach(plan::add);

        plan.sort(Comparator.comparing(PrenatalVisitRecommendationDTO::getGestationalWeek));
        matchRemainingAppointmentsAsAlerts(lmp, plan, unmatched);
        return plan;
    }

    private PrenatalVisitRecommendationDTO createSupplementalRecommendation(LocalDate lmp, int week, LocalDate today) {
        LocalDate targetDate = alignToPreferredWeekday(lmp.plusWeeks(week));
        int duration = ROUTINE_VISIT_DURATION_MINUTES;
        return PrenatalVisitRecommendationDTO.builder()
            .targetDate(targetDate)
            .windowStart(targetDate.minusDays(3).isBefore(today) ? today : targetDate.minusDays(3))
            .windowEnd(targetDate.plusDays(3))
            .suggestedStartTime(DEFAULT_START_TIME)
            .suggestedEndTime(DEFAULT_START_TIME.plusMinutes(duration))
            .gestationalWeek(week)
            .durationMinutes(duration)
            .visitType(PrenatalVisitType.ROUTINE_CHECK)
            .scheduled(false)
            .notes("Supplemental monitoring visit per care plan")
            .recommendation("Supplemental prenatal check (week " + week + ")")
            .build();
    }

    private PrenatalVisitType determineVisitType(int weekPointer, boolean firstVisitAdded) {
        if (!firstVisitAdded) {
            return PrenatalVisitType.INITIAL_INTAKE;
        }
        if (weekPointer >= 37) {
            return PrenatalVisitType.LATE_PREGNANCY;
        }
        if (weekPointer == 12 || weekPointer == 20 || weekPointer == 32) {
            return PrenatalVisitType.ULTRASOUND;
        }
        return PrenatalVisitType.ROUTINE_CHECK;
    }

    private int determineFrequency(int weekPointer, boolean highRisk) {
        if (highRisk && weekPointer >= 28) {
            return 1;
        }
        if (weekPointer < 32) {
            return 4;
        }
        if (weekPointer < 36) {
            return 2;
        }
        return 1;
    }

    private int resolveDurationForType(PrenatalVisitType visitType) {
        return switch (visitType) {
            case INITIAL_INTAKE -> INITIAL_VISIT_DURATION_MINUTES;
            case ULTRASOUND -> ULTRASOUND_VISIT_DURATION_MINUTES;
            default -> ROUTINE_VISIT_DURATION_MINUTES;
        };
    }

    private void matchExistingAppointment(LocalDate lmp, PrenatalVisitRecommendationDTO recommendation, List<Appointment> unmatched) {
        if (unmatched.isEmpty()) {
            return;
        }
        int targetWeek = recommendation.getGestationalWeek();
        Optional<Appointment> match = unmatched.stream()
            .filter(appt -> appt.getAppointmentDate() != null)
            .filter(appt -> {
                int apptWeek = Math.max(0, (int) ChronoUnit.WEEKS.between(lmp, appt.getAppointmentDate()));
                int tolerance = recommendation.getVisitType() == PrenatalVisitType.LATE_PREGNANCY ? 0 : 1;
                return Math.abs(apptWeek - targetWeek) <= tolerance;
            })
            .findFirst();

        match.ifPresent(appointment -> {
            recommendation.setScheduled(true);
            recommendation.setAppointmentId(appointment.getId());
            recommendation.setSuggestedStartTime(appointment.getStartTime());
            recommendation.setSuggestedEndTime(appointment.getEndTime());
            if (appointment.getStartTime() != null && appointment.getEndTime() != null) {
                recommendation.setDurationMinutes((int) Duration.between(appointment.getStartTime(), appointment.getEndTime()).toMinutes());
            }
            recommendation.setNotes(mergeNotes(recommendation.getNotes(), appointment.getNotes()));
            unmatched.remove(appointment);
        });
    }

    private void matchRemainingAppointmentsAsAlerts(LocalDate lmp, List<PrenatalVisitRecommendationDTO> plan, List<Appointment> unmatched) {
        if (unmatched.isEmpty()) {
            return;
        }
        for (Appointment appointment : unmatched) {
            int apptWeek = Math.max(0, (int) ChronoUnit.WEEKS.between(lmp, appointment.getAppointmentDate()));
            int durationMinutes = appointment.getStartTime() != null && appointment.getEndTime() != null
                ? (int) Duration.between(appointment.getStartTime(), appointment.getEndTime()).toMinutes()
                : ROUTINE_VISIT_DURATION_MINUTES;
            plan.add(PrenatalVisitRecommendationDTO.builder()
                .appointmentId(appointment.getId())
                .targetDate(appointment.getAppointmentDate())
                .windowStart(appointment.getAppointmentDate())
                .windowEnd(appointment.getAppointmentDate())
                .suggestedStartTime(appointment.getStartTime())
                .suggestedEndTime(appointment.getEndTime())
                .gestationalWeek(apptWeek)
                .durationMinutes(durationMinutes)
                .visitType(PrenatalVisitType.ROUTINE_CHECK)
                .scheduled(true)
                .recommendation("Existing prenatal appointment without matching guideline")
                .notes(appointment.getNotes())
                .build());
        }
        plan.sort(Comparator.comparing(PrenatalVisitRecommendationDTO::getGestationalWeek));
    }

    private String mergeNotes(String generated, String existing) {
        if (existing == null || existing.isBlank()) {
            return generated;
        }
        if (generated == null || generated.isBlank()) {
            return existing;
        }
        return generated + " | Notes: " + existing;
    }

    private PrenatalAppointmentSummaryDTO toAppointmentSummary(Appointment appointment, LocalDate lmp) {
        int gestationalWeek = Math.max(0, (int) ChronoUnit.WEEKS.between(lmp, appointment.getAppointmentDate()));
        return PrenatalAppointmentSummaryDTO.builder()
            .appointmentId(appointment.getId())
            .staffId(appointment.getStaff() != null ? appointment.getStaff().getId() : null)
            .departmentId(appointment.getDepartment() != null ? appointment.getDepartment().getId() : null)
            .appointmentDate(appointment.getAppointmentDate())
            .startTime(appointment.getStartTime())
            .endTime(appointment.getEndTime())
            .status(appointment.getStatus())
            .reason(appointment.getReason())
            .gestationalWeek(gestationalWeek)
            .build();
    }

    private void detectAppointmentCollisions(List<PrenatalAppointmentSummaryDTO> appointments, List<String> alerts) {
        Set<Integer> seenWeeks = appointments.stream()
            .collect(Collectors.groupingBy(PrenatalAppointmentSummaryDTO::getGestationalWeek, Collectors.counting()))
            .entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        if (!seenWeeks.isEmpty()) {
            alerts.add("Multiple appointments scheduled for weeks: " + seenWeeks);
        }
    }

    private LocalDate alignToPreferredWeekday(LocalDate reference) {
        return reference.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String buildRecommendationText(PrenatalVisitType type, int week) {
        return switch (type) {
            case INITIAL_INTAKE -> "Initial prenatal intake visit (week " + week + ")";
            case ULTRASOUND -> "Targeted ultrasound and screening (week " + week + ")";
            case LATE_PREGNANCY -> "Weekly prenatal monitoring (week " + week + ")";
            default -> "Routine prenatal check (week " + week + ")";
        };
    }

    private String buildNotesForVisit(PrenatalVisitType type) {
        return switch (type) {
            case INITIAL_INTAKE -> "Collect baseline labs, vitals, and education";
            case ULTRASOUND -> "Schedule ultrasound block and review fetal growth";
            case LATE_PREGNANCY -> "Monitor blood pressure, fetal movement, and labor readiness";
            default -> "Standard vitals, fundal height, and symptom review";
        };
    }

    private int resolveDuration(Integer override, String reason) {
        if (override != null && override > 0) {
            return override;
        }
        if (reason != null && reason.toLowerCase(Locale.ROOT).contains("ultrasound")) {
            return ULTRASOUND_VISIT_DURATION_MINUTES;
        }
        if (reason != null && reason.toLowerCase(Locale.ROOT).contains("intake")) {
            return INITIAL_VISIT_DURATION_MINUTES;
        }
        return ROUTINE_VISIT_DURATION_MINUTES;
    }

    private UUID resolveStaffForReschedule(PrenatalRescheduleRequestDTO request, Appointment appointment) {
        if (request.getNewStaffId() != null) {
            Staff staff = staffRepository.findById(request.getNewStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));
            if (!appointment.getHospital().getId().equals(staff.getHospital().getId())) {
                throw new BusinessException("Selected staff member does not belong to appointment hospital");
            }
            return staff.getId();
        }
        return appointment.getStaff() != null ? appointment.getStaff().getId() : null;
    }

    private String buildDefaultReminderMessage(Appointment appointment, int daysBefore) {
        return String.format(
            "Reminder: Prenatal appointment on %s at %s. %d day(s) remaining.",
            appointment.getAppointmentDate(),
            appointment.getStartTime(),
            daysBefore
        );
    }
}
