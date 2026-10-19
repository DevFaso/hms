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
import com.example.hms.repository.DeliveryRecordRepository;
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
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import com.example.hms.utility.GestationalDating;
import com.example.hms.utility.ObstetricEligibility;

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
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;
    private final DeliveryRecordRepository deliveryRecordRepository;

    @Override
    public PrenatalScheduleResponseDTO generateSchedule(PrenatalScheduleRequestDTO request, Locale locale, String username) {
        Objects.requireNonNull(request, "Prenatal scheduling request is required");
        Locale textLocale = locale != null ? locale : Locale.FRENCH;
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));
        ObstetricEligibility.requireObstetricEligible(patient);

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));

        Staff staff = resolveStaffIfPresent(request.getStaffId(), hospital.getId());
        validateHospitalScope(hospital.getId(), username);
        ensurePatientBelongsToHospital(patient, hospital.getId());

        LocalDate lmp = request.getLastMenstrualPeriodDate();
        LocalDate today = LocalDate.now(clock);
        // QA D164: an LMP 93 weeks ago (or in the future) cannot date a current
        // pregnancy, and a pregnancy already delivered needs no antenatal visits.
        if (!GestationalDating.isPlausibleCurrentLmp(lmp, today)) {
            throw new BusinessException("prenatal.lmp.implausible");
        }
        if (deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
                patient.getId(), lmp.atStartOfDay())) {
            throw new BusinessException("prenatal.pregnancy.delivered");
        }
        LocalDate dueDate = resolveDueDate(request, lmp);
        int currentWeek = Math.max(0, (int) ChronoUnit.WEEKS.between(lmp, today));

        List<Appointment> futureAppointments = appointmentRepository.findByHospital_IdAndPatient_Id(hospital.getId(), patient.getId()).stream()
            .filter(appt -> !appt.getAppointmentDate().isBefore(today.minusWeeks(1)))
            .sorted(Comparator.comparing(Appointment::getAppointmentDate).thenComparing(Appointment::getStartTime))
            .toList();

        boolean highRisk = Boolean.TRUE.equals(request.getHighRisk());
    List<Integer> supplementalWeeks = Optional.ofNullable(request.getSupplementalVisitWeeks()).orElse(List.of());

    List<PrenatalVisitRecommendationDTO> recommendations = buildRecommendations(lmp, today, currentWeek, highRisk, supplementalWeeks, futureAppointments, textLocale);
        List<PrenatalAppointmentSummaryDTO> existingAppointments = futureAppointments.stream()
            .map(appt -> toAppointmentSummary(appt, lmp))
            .toList();

        List<String> alerts = new ArrayList<>();
        if (dueDate.isBefore(today)) {
            alerts.add(text(textLocale, "prenatal.schedule.alert.dueDatePast"));
        }
        if (highRisk) {
            alerts.add(text(textLocale, "prenatal.schedule.alert.highRisk"));
        }

        detectAppointmentCollisions(existingAppointments, alerts, textLocale);

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
            .orElseThrow(() -> new ResourceNotFoundException("appointment.notFound", request.getAppointmentId()));

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
            .orElseThrow(() -> new ResourceNotFoundException("appointment.notFound", request.getAppointmentId()));

        validateHospitalScope(appointment.getHospital().getId(), username);

        LocalDate today = LocalDate.now(clock);
        if (appointment.getAppointmentDate().isBefore(today)) {
            throw new BusinessException("maternity.reminder.appointmentInPast");
        }

        String patientUsername = Optional.ofNullable(appointment.getPatient())
            .map(Patient::getUser)
            .map(User::getUsername)
            .orElseThrow(() -> new BusinessException("maternity.reminder.appointmentNoPatientUser"));

        LocalDate reminderDate = appointment.getAppointmentDate().minusDays(request.getDaysBefore());
        if (reminderDate.isBefore(today)) {
            log.debug("Reminder date {} already passed for appointment {} – sending immediately", reminderDate, appointment.getId());
        }

        // The default body is read by the PATIENT, so it is rendered in the
        // patient's stated language — the `locale` parameter is the
        // scheduler's request locale and only governs what the caller sees.
        // A custom message is the scheduler's own words and is stored as given.
        String message = Optional.ofNullable(request.getCustomMessage())
            .filter(msg -> !msg.isBlank())
            .orElseGet(() -> buildDefaultReminderMessage(
                appointment, request.getDaysBefore(),
                patientLocaleResolver.resolve(appointment.getPatient(), NotificationLocales.PATIENT_FALLBACK)));

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
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
        if (!staff.getHospital().getId().equals(hospitalId)) {
            throw new BusinessException("maternity.schedule.staffNotInHospital");
        }
        return staff;
    }

    private void ensurePatientBelongsToHospital(Patient patient, UUID hospitalId) {
        boolean registered = patient.getHospitalRegistrations().stream()
            .filter(Objects::nonNull)
            .anyMatch(reg -> reg.getHospital() != null && hospitalId.equals(reg.getHospital().getId()) && reg.isActive());
        if (!registered) {
            throw new BusinessException("maternity.schedule.patientNotRegistered");
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
        List<Appointment> futureAppointments,
        Locale textLocale
    ) {
        List<PrenatalVisitRecommendationDTO> plan = new ArrayList<>();
        Set<Integer> supplemental = supplementalWeeks == null ? Set.of() : supplementalWeeks.stream()
            .filter(week -> week > 0 && week <= 44 && week >= currentWeek)
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
            LocalDate targetDate = upcomingTargetDate(lmp, weekPointer, today);
            LocalDate windowStart = windowStartFor(targetDate, today);
            LocalDate windowEnd = targetDate.plusDays(3);

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
                .notes(buildNotesForVisit(visitType, textLocale))
                .recommendation(buildRecommendationText(visitType, weekPointer, textLocale))
                .build();

            matchExistingAppointment(lmp, recommendation, unmatched, textLocale);
            plan.add(recommendation);

            firstVisitAdded = true;
            int frequency = determineFrequency(weekPointer, highRisk);
            weekPointer += frequency;
        }

        supplemental.stream()
            .filter(week -> plan.stream().noneMatch(rec -> rec.getGestationalWeek() == week))
            .map(week -> createSupplementalRecommendation(lmp, week, today, textLocale))
            .forEach(plan::add);

        plan.sort(Comparator.comparing(PrenatalVisitRecommendationDTO::getGestationalWeek));
        matchRemainingAppointmentsAsAlerts(lmp, plan, unmatched, textLocale);
        return plan;
    }

    private PrenatalVisitRecommendationDTO createSupplementalRecommendation(LocalDate lmp, int week, LocalDate today,
                                                                           Locale textLocale) {
        LocalDate targetDate = upcomingTargetDate(lmp, week, today);
        int duration = ROUTINE_VISIT_DURATION_MINUTES;
        return PrenatalVisitRecommendationDTO.builder()
            .targetDate(targetDate)
            .windowStart(windowStartFor(targetDate, today))
            .windowEnd(targetDate.plusDays(3))
            .suggestedStartTime(DEFAULT_START_TIME)
            .suggestedEndTime(DEFAULT_START_TIME.plusMinutes(duration))
            .gestationalWeek(week)
            .durationMinutes(duration)
            .visitType(PrenatalVisitType.ROUTINE_CHECK)
            .scheduled(false)
            .notes(text(textLocale, "prenatal.schedule.notes.SUPPLEMENTAL"))
            .recommendation(text(textLocale, "prenatal.schedule.recommendation.SUPPLEMENTAL", week))
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

    private void matchExistingAppointment(LocalDate lmp, PrenatalVisitRecommendationDTO recommendation, List<Appointment> unmatched,
                                          Locale textLocale) {
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
            recommendation.setNotes(mergeNotes(recommendation.getNotes(), appointment.getNotes(), textLocale));
            unmatched.remove(appointment);
        });
    }

    private void matchRemainingAppointmentsAsAlerts(LocalDate lmp, List<PrenatalVisitRecommendationDTO> plan, List<Appointment> unmatched,
                                                    Locale textLocale) {
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
                .recommendation(text(textLocale, "prenatal.schedule.recommendation.UNMATCHED"))
                .notes(appointment.getNotes())
                .build());
        }
        plan.sort(Comparator.comparing(PrenatalVisitRecommendationDTO::getGestationalWeek));
    }

    private String mergeNotes(String generated, String existing, Locale textLocale) {
        if (existing == null || existing.isBlank()) {
            return generated;
        }
        if (generated == null || generated.isBlank()) {
            return existing;
        }
        return text(textLocale, "prenatal.schedule.notes.merged", generated, existing);
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

    private void detectAppointmentCollisions(List<PrenatalAppointmentSummaryDTO> appointments, List<String> alerts, Locale textLocale) {
        Set<Integer> seenWeeks = appointments.stream()
            .collect(Collectors.groupingBy(PrenatalAppointmentSummaryDTO::getGestationalWeek, Collectors.counting()))
            .entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        if (!seenWeeks.isEmpty()) {
            alerts.add(text(textLocale, "prenatal.schedule.alert.collision", seenWeeks.stream().sorted()
                .map(String::valueOf).collect(Collectors.joining(", "))));
        }
    }

    /**
     * The visit's target date, never in the past. The planner starts at the
     * current gestational week, whose calendar date (LMP + whole weeks, moved
     * to a Monday) is usually a few days behind today; that put the first
     * visit's target in the past, and clamping only the window start to today
     * left the window inverted, start after end (QA D47). A past target moves
     * to the next preferred weekday from today.
     */
    private LocalDate upcomingTargetDate(LocalDate lmp, int gestationalWeek, LocalDate today) {
        LocalDate target = alignToPreferredWeekday(lmp.plusWeeks(gestationalWeek));
        return target.isBefore(today) ? alignToPreferredWeekday(today) : target;
    }

    /** Three days before the target, but not before today; never after the target. */
    private static LocalDate windowStartFor(LocalDate targetDate, LocalDate today) {
        LocalDate start = targetDate.minusDays(3);
        return start.isBefore(today) ? today : start;
    }

    private LocalDate alignToPreferredWeekday(LocalDate reference) {
        return reference.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String buildRecommendationText(PrenatalVisitType type, int week, Locale textLocale) {
        return text(textLocale, "prenatal.schedule.recommendation." + recommendationKey(type), week);
    }

    private String buildNotesForVisit(PrenatalVisitType type, Locale textLocale) {
        return text(textLocale, "prenatal.schedule.notes." + recommendationKey(type));
    }

    private static String recommendationKey(PrenatalVisitType type) {
        return switch (type) {
            case INITIAL_INTAKE, ULTRASOUND, LATE_PREGNANCY -> type.name();
            default -> "ROUTINE_CHECK";
        };
    }

    /**
     * QA D249: the schedule is read by the clinician who asked for it, so its
     * recommendations, notes and alerts are rendered in the request locale (the controller passes it)
     * from the bundle, not composed in English in code.
     */
    private String text(Locale textLocale, String key, Object... args) {
        return messageSource.getMessage(key, args, textLocale);
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
                .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getNewStaffId()));
            if (!appointment.getHospital().getId().equals(staff.getHospital().getId())) {
                throw new BusinessException("maternity.schedule.staffNotInAppointmentHospital");
            }
            return staff.getId();
        }
        return appointment.getStaff() != null ? appointment.getStaff().getId() : null;
    }

    private String buildDefaultReminderMessage(Appointment appointment, int daysBefore, Locale patientLocale) {
        return messageSource.getMessage(
            "prenatal.reminder.default",
            new Object[]{
                String.valueOf(appointment.getAppointmentDate()),
                String.valueOf(appointment.getStartTime()),
                String.valueOf(daysBefore)
            },
            patientLocale);
    }
}
