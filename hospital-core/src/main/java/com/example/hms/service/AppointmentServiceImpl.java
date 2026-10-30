package com.example.hms.service;


import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.security.EffectiveRoles;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AppointmentFilterDTO;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.support.HospitalScopeUtils;
import com.example.hms.specification.AppointmentSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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
public class AppointmentServiceImpl implements AppointmentService {
    private static final String USER_NOT_FOUND_BY_USERNAME_KEY = "user.notFoundByUsername";
    private static final String PATIENT_NOT_FOUND_FOR_USER_KEY = "patient.notFoundForUser";
    private static final String APPOINTMENT_NOT_FOUND_KEY = "appointment.notFound";
    private static final String INVALID_TRANSITION_KEY = "appointment.status.invalidTransition";

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByPatientUsername(String patientUsername, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);
        User patientUser = userRepository.findByUsername(patientUsername)
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_BY_USERNAME_KEY, patientUsername));
        // A patient caller may name only themselves: another account answers
        // as an unknown username does, before the patient lookup below, which
        // would otherwise tell an account with a patient row from one without.
        // Compared as user ids, not strings -- usernames resolve
        // case-insensitively (lower(u.username) = lower(:username)).
        if (subjectReadGuard.isPatientOnly(PatientSubjectReaderRoles.APPOINTMENT_READS)
                && !patientUser.getId().equals(currentUser.getId())) {
            throw new ResourceNotFoundException(USER_NOT_FOUND_BY_USERNAME_KEY, patientUsername);
        }
        Patient patient = patientRepository.findByUserId(patientUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_FOR_USER_KEY, patientUsername));

        return getAppointmentsByPatientScoped(patient.getId(), currentUser);
    }
    private final EmailService emailService;
    private boolean isSuperAdmin(User user) {
        return hasRole(user, ROLE_SUPER_ADMIN_CODE);
    }

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final MessageSource messageSource;
    private final UserRepository userRepository;
    private final StaffAvailabilityService staffAvailabilityService;
    private final DepartmentRepository departmentRepository;
    private final com.example.hms.service.scheduling.SlotInventoryService slotInventoryService;
    private final com.example.hms.service.webhook.WebhookPublisher webhookPublisher;

    /** MessageSource varargs: an empty array, never null (Sonar S4449). */
    private static final Object[] NO_ARGS = new Object[0];

    /** The resourceType every appointment webhook payload names (item 45). */
    private static final String WEBHOOK_RESOURCE_APPOINTMENT = "Appointment";
    private final com.example.hms.config.AppointmentLinkProperties appointmentLinks;
    /**
     * The language the patient asked to be written to in. A mail is read by
     * the patient, so the request's {@code locale} parameter (the registrar's
     * browser) is never the one used for it.
     */
    private final com.example.hms.service.i18n.PatientLocaleResolver patientLocaleResolver;
    /**
     * The three reads by id, patient id and patient username admit
     * ROLE_PATIENT. Owning the record was one road in and hospital scope the
     * other, and a patient holds a ROLE_PATIENT assignment at the hospitals
     * that registered them — so hospital scope handed a patient every other
     * patient's appointments there. A patient-only caller now reads their own.
     */
    private final PatientSubjectReadGuard subjectReadGuard;
    /** QA D788: an appointment is COMPLETED by its visit's check-out, never ahead of it. */
    private final com.example.hms.repository.EncounterRepository encounterRepository;

    @org.springframework.beans.factory.annotation.Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * QA D985: booking or cancelling the visit a recall asks for moves the
     * recall. Setter-injected so the many unit tests that build this service
     * by constructor keep compiling; null there, and then nothing is linked.
     */
    private com.example.hms.service.scheduling.PatientRecallService recallService;

    @org.springframework.beans.factory.annotation.Autowired
    void setRecallService(com.example.hms.service.scheduling.PatientRecallService recallService) {
        this.recallService = recallService;
    }

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);
    private static final String USER_NOT_FOUND_PREFIX = "User not found: ";
    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): this
    // error-message prefix appears 3x in this file. Naming follows the
    // existing USER_NOT_FOUND_PREFIX sibling above.
    /** What an unknown patient id answers on the by-patient read, and so what a refused one answers too. */
    private static final String ROLE_SUPER_ADMIN_CODE = "ROLE_SUPER_ADMIN";
    private static final String ROLE_ADMIN_CODE = "ROLE_ADMIN";
    private static final String ROLE_PATIENT_CODE = "ROLE_PATIENT";
    private static final String ROLE_DOCTOR_CODE = "ROLE_DOCTOR";
    private static final String ROLE_NURSE_CODE = "ROLE_NURSE";
    private static final String ROLE_RECEPTIONIST_CODE = "ROLE_RECEPTIONIST";

    /**
     * QA D823: every role that books FOR a patient, i.e. every role
     * {@code AppointmentController.APPOINTMENT_BOOK_ROLES} admits except
     * PATIENT. This list used to name only admin, doctor, nurse and
     * receptionist, so a midwife (or a STAFF account) the controller let in was
     * treated as a patient booking for herself, and her booking for a patient
     * she can read answered 404 "patient not found". Guarded by
     * {@code AppointmentBookingRolesGuardTest}: a role added to the controller
     * without being added here fails the build.
     */
    static final List<String> STAFF_BOOKING_ROLES = List.of(
        ROLE_SUPER_ADMIN_CODE, "ROLE_HOSPITAL_ADMIN", ROLE_ADMIN_CODE, "ROLE_STAFF",
        ROLE_RECEPTIONIST_CODE, ROLE_DOCTOR_CODE, ROLE_NURSE_CODE, "ROLE_MIDWIFE");

    /** Allowed status transitions: current → set of valid next statuses. */
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> VALID_TRANSITIONS;
    static {
        Map<AppointmentStatus, Set<AppointmentStatus>> m = new EnumMap<>(AppointmentStatus.class);
        m.put(AppointmentStatus.PENDING,     EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED));
        // SCHEDULED is reachable from CONFIRMED because moving a confirmed
        // appointment to a new time invalidates the confirmation — the patient
        // agreed to a DIFFERENT slot, and being asked again is the point.
        // QA D946: a SCHEDULED appointment whose time has passed is marked
        // NO_SHOW directly (the temporal guard still applies); the desk used to
        // record a confirmation the patient never gave just to reach it.
        m.put(AppointmentStatus.SCHEDULED,   EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.RESCHEDULED, AppointmentStatus.NO_SHOW));
        m.put(AppointmentStatus.CONFIRMED,   EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_PROGRESS, AppointmentStatus.CANCELLED, AppointmentStatus.RESCHEDULED, AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW));
        // CHECKED_IN had NO entry at all. ReceptionServiceImpl.checkInPatient
        // sets it directly on the entity, bypassing this map, so check-in
        // worked — but getOrDefault then returned an empty set for every
        // subsequent action, and a checked-in appointment could never be
        // completed, marked no-show or cancelled. It sat CHECKED_IN forever
        // while the encounter carried the clinical workflow on alone.
        m.put(AppointmentStatus.CHECKED_IN,  EnumSet.of(AppointmentStatus.IN_PROGRESS, AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW, AppointmentStatus.CANCELLED));
        m.put(AppointmentStatus.IN_PROGRESS, EnumSet.of(AppointmentStatus.COMPLETED, AppointmentStatus.FAILED, AppointmentStatus.NO_SHOW));
        m.put(AppointmentStatus.RESCHEDULED, EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED));
        m.put(AppointmentStatus.COMPLETED,   EnumSet.noneOf(AppointmentStatus.class));
        m.put(AppointmentStatus.CANCELLED,   EnumSet.noneOf(AppointmentStatus.class));
        m.put(AppointmentStatus.NO_SHOW,     EnumSet.of(AppointmentStatus.RESCHEDULED));
        m.put(AppointmentStatus.FAILED,      EnumSet.of(AppointmentStatus.RESCHEDULED));
        m.put(AppointmentStatus.UNKNOWN,     EnumSet.noneOf(AppointmentStatus.class));
        VALID_TRANSITIONS = Collections.unmodifiableMap(m);
    }

    /** Statuses that require the appointment time window to have started. */
    private static final Set<AppointmentStatus> REQUIRES_APPOINTMENT_STARTED =
        EnumSet.of(AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW);

    /**
     * COMPLETED and NO_SHOW only once the visit has started, on every write
     * path (status action and PUT alike; review round 8: the PUT skipped it),
     * judged on the hospital's clock as the portal booking is (review round 15).
     */
    private static void requireStartedFor(AppointmentStatus status, LocalDateTime appointmentStart,
                                          Hospital hospital) {
        if (REQUIRES_APPOINTMENT_STARTED.contains(status)
                && com.example.hms.utility.HospitalCalendar.now(hospital).isBefore(appointmentStart)) {
            throw new BusinessException("appointment.status.notStartedYet",
                AppointmentStatus.labelOf(status),
                appointmentStart.format(java.time.format.DateTimeFormatter
                    .ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                    .withLocale(LocaleContextHolder.getLocale())));
        }
    }

    /** A cancelled, missed or being-moved visit gives its inventory slot back (P3 #22, QA D937). */
    private static boolean releasesSlotInventory(AppointmentStatus status) {
        return status == AppointmentStatus.CANCELLED || status == AppointmentStatus.RESCHEDULED
            || status == AppointmentStatus.NO_SHOW;
    }

    /** The ids, date and times a staff edit must carry. */
    private static void requireCompleteEdit(AppointmentRequestDTO request) {
        if (request.getPatientId() == null) {
            throw new BusinessException("appointment.patient.required");
        }
        if (request.getStaffId() == null) {
            throw new BusinessException("appointment.staff.required");
        }
        if (request.getHospitalId() == null) {
            throw new BusinessException("appointment.hospital.required");
        }
        if (request.getAppointmentDate() == null) {
            throw new BusinessException("Appointment date must not be null");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BusinessException("Appointment start and end time must not be null");
        }
    }

    /**
     * QA D937 (review round 8): a freed booking (NO_SHOW) that a status action
     * puts back into a slot-holding status (RESCHEDULED) holds its own time
     * again. That time may have been rebooked since the slot was freed:
     * refused then, so the clinician is never double-booked; the desk moves
     * the visit to a new time instead (the PUT checks that time).
     */
    private void requireFreedSlotStillFree(Appointment appointment, AppointmentStatus currentStatus,
                                           AppointmentStatus newStatus) {
        if (AppointmentStatus.holdsSlot(currentStatus) || !newStatus.holdsSlot() || appointment.getStaff() == null) {
            return;
        }
        LocalDate day = appointment.getAppointmentDate();
        boolean taken = AppointmentSlotRules.overlapsHeldSlot(
            appointmentRepository.findByStaff_IdAndAppointmentDate(appointment.getStaff().getId(), day),
            appointment.getId(),
            LocalDateTime.of(day, appointment.getStartTime()),
            LocalDateTime.of(day, appointment.getEndTime()));
        if (taken) {
            throw new BusinessException("appointment.status.slotRebooked");
        }
    }

    /**
     * What an edit (PUT) that changed the status sets off, as the status
     * action does: a cancelled, missed or parked visit frees its inventory slot
     * (review round 8), and the recall it answered is reopened or closed (QA
     * D985). Only a status change: a note on a completed visit must not
     * re-close its recall (review round 15).
     */
    private void afterEditMovedStatus(Appointment saved) {
        if (releasesSlotInventory(saved.getStatus())) {
            slotInventoryService.releaseForAppointment(saved.getId());
        }
        followUpRecalls(saved.getId(), saved.getStatus());
    }

    /**
     * QA D985: a visit that will not happen reopens the recall it answered
     * (cancelled, missed, or started and FAILED: review round 15); one that
     * took place fulfils it. The same rule on the status action and the edit form.
     */
    private void followUpRecalls(UUID appointmentId, AppointmentStatus status) {
        if (recallService == null) {
            return;
        }
        if (!AppointmentStatus.holdsSlot(status) || status == AppointmentStatus.FAILED) {
            recallService.reopenRecallsOf(appointmentId);
        } else if (status == AppointmentStatus.COMPLETED) {
            recallService.completeRecallsOf(appointmentId);
        } else if (status == AppointmentStatus.RESCHEDULED) {
            // The only road out of NO_SHOW/FAILED: the visit is back, and so
            // are the recalls it answered (review round 18).
            recallService.rescheduleRecallsOf(appointmentId);
        }
    }

    /**
     * Reject a status change on the update path that the state machine would
     * not allow.
     *
     * <p>Permissive in two places on purpose. A null requested status means
     * the caller is editing something else and not touching status; an
     * unchanged status is a no-op, not a self-transition. Only an actual move
     * is checked, so ordinary edits — reason, notes, the date and time of a
     * reschedule — pass through untouched.
     */
    private void requireValidStatusChange(AppointmentStatus current, AppointmentStatus requested) {
        if (requested == null || requested == current) {
            return;
        }
        Set<AppointmentStatus> allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(requested)) {
            throw new BusinessException(INVALID_TRANSITION_KEY,
                AppointmentStatus.labelOf(current), AppointmentStatus.labelOf(requested));
        }
    }

    /**
     * QA D788: an appointment whose patient was checked in is COMPLETED by the
     * check-out of that visit ({@code EncounterServiceImpl.checkOut}), never by
     * hand ahead of it. The desk marked the appointment of a never-seen visit
     * COMPLETED from CHECKED_IN, so the day's figures counted a consultation
     * that did not happen. Refused while no visit linked to the appointment is
     * COMPLETED; an appointment with no visit at all (never checked in) keeps
     * the manual completion the state machine allows.
     */
    private void requireVisitCompletedBeforeAppointment(Appointment appointment, AppointmentStatus requested) {
        if (requested != AppointmentStatus.COMPLETED
                || appointment.getStatus() == AppointmentStatus.COMPLETED
                || appointment.getId() == null) {
            return;
        }
        List<com.example.hms.model.Encounter> visits =
            encounterRepository.findByAppointmentIdIn(List.of(appointment.getId()));
        if (visits.isEmpty()) {
            return;
        }
        boolean seen = visits.stream()
            .anyMatch(v -> v.getStatus() == com.example.hms.enums.EncounterStatus.COMPLETED);
        if (!seen) {
            com.example.hms.enums.EncounterStatus open = visits.get(0).getStatus();
            throw new BusinessException("appointment.complete.visitNotCompleted", com.example.hms.enums.EncounterStatus.labelOf(open));
        }
    }

    /* =======================
       Helpers
       ======================= */

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_BY_USERNAME_KEY, username));
    }

    /**
     * Active hospital IDs the user is assigned to (global roles return empty set and are handled by bypass checks).
     */
    private Set<UUID> getUserHospitalIds(User user) {
        Set<UUID> hospitals = assignmentRepository.findAllByUserId(user.getId()).stream()
            .filter(a -> Boolean.TRUE.equals(a.getActive()))
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .map(Hospital::getId)
            .collect(Collectors.toSet());

        if (log.isDebugEnabled()) {
            log.debug("resolveScope → active hospital assignments for {}: {}", user.getUsername(), hospitals);
        }

        return hospitals;
    }

    /** Holds any of {@link #STAFF_BOOKING_ROLES}, by the same active-assignment rule as {@link #hasRole}. */
    private boolean isStaffBooker(User user) {
        if (user == null) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findAllByUserId(user.getId());
        return STAFF_BOOKING_ROLES.stream().anyMatch(code -> EffectiveRoles.holds(user, assignments, code));
    }

    private boolean hasRole(User user, String roleCode) {
        if (user == null) {
            log.warn("hasRole() check failed: user is null");
            return false;
        }

        // Same rule as sign-in (EffectiveRoles): only an ACTIVE assignment
        // grants a governed role, and a leftover user-level ROLE_PATIENT on a
        // staff account never narrows a receptionist's search to their own
        // patient appointments (QA D74, D132).
        return EffectiveRoles.holds(user, assignmentRepository.findAllByUserId(user.getId()), roleCode);
    }

    /**
     * QA D351: the booked reason and notes. The reason was never stored on a
     * staff booking, so the reception queue and the doctor's worklist fell
     * back to the chief complaint triage recorded.
     */
    static void applyBookingText(Appointment appointment, AppointmentRequestDTO request) {
        appointment.setNotes(request.getNotes());
        appointment.setReason(request.getReason());
    }

    /* =======================
       CREATE
       ======================= */

    @Override
    @Transactional
    public AppointmentSummaryDTO createAppointment(AppointmentRequestDTO request, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);

        // Authorization: staff book for any patient registered at the booking
        // hospital; a pure patient books only for themselves (QA D823: one list
        // for every staff booking role, so a midwife is not taken for a patient).
        boolean isStaffOrAdmin = isStaffBooker(currentUser);

        // --- Patient resolution (falls back to authenticated user) ---
        // A pure patient books only for themselves. Naming anyone else answers
        // exactly as naming an unknown patient does — the same 404 and message
        // for that identifier — where it used to be a 403 for a real patient
        // beside a 404 for a made-up one, which told a patient which ids,
        // usernames and emails exist.
        // QA D824: a staff booking names its patient by id. It used to fall
        // back to the CALLER's own patient record when patientId was absent,
        // so a receptionist whose account was (wrongly) linked to a patient
        // row booked that patient without choosing anyone.
        final Patient patient = isStaffOrAdmin
            ? resolvePatientForStaffBooking(request)
            : resolveOwnPatientForBooking(request, currentUser);

        // --- Hospital resolution (single block) ---
        final Hospital hospital = resolveHospital(request);

        // auth scope
        requireHospitalScope(currentUser, hospital.getId(), locale);

        // QA D401: staff book into the hospital's own schedule, so the patient
        // must be registered there; otherwise the same 404 as an unknown id
        // (the booking response used to hand back the patient's name and phone).
        if (isStaffOrAdmin) {
            patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());
        }

        // --- Staff resolution ---
        final Staff staff = resolveStaff(request);

        // staff must belong to hospital
        if (!staff.getHospital().getId().equals(hospital.getId())) {
            throw new AccessDeniedException("Staff does not belong to selected hospital");
        }

        // --- Department resolution ---
        final Department department = resolveDepartment(request, hospital, staff);

        // --- Staff role assignment in this hospital ---
        final UserRoleHospitalAssignment assignment = assignmentRepository
            .findActiveStaffAssignment(staff.getUser().getId(), hospital.getId())
            .stream().findFirst()
            .orElseThrow(() -> new BusinessException("Staff role assignment not found"));

        // --- Time validations (default endTime = startTime + 30 min) ---
        LocalTime endTime = request.getEndTime() != null
                ? request.getEndTime()
                : request.getStartTime().plusMinutes(30);
        LocalDateTime requestedStart = LocalDateTime.of(request.getAppointmentDate(), request.getStartTime());
        LocalDateTime requestedEnd = LocalDateTime.of(request.getAppointmentDate(), endTime);
        if (!requestedEnd.isAfter(requestedStart)) {
            throw new BusinessException("appointment.timeRange.invalid");
        }

        // schedule check
        if (!staffAvailabilityService.isStaffAvailable(staff.getId(), requestedStart)) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable.schedule",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime()},
                locale
            ));
        }

        // overlap check
        // QA D937: a cancelled booking or a no-show frees its slot.
        boolean hasConflict = AppointmentSlotRules.overlapsHeldSlot(
            appointmentRepository.findByStaff_IdAndAppointmentDate(staff.getId(), request.getAppointmentDate()),
            null, requestedStart, requestedEnd);
        if (hasConflict) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime(), endTime},
                locale
            ));
        }

        // --- Create only scheduling data ---
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setStaff(staff);
        appointment.setHospital(hospital);
        appointment.setDepartment(department);
        appointment.setAppointmentDate(request.getAppointmentDate());
        appointment.setStartTime(request.getStartTime());
        appointment.setEndTime(endTime);
        applyBookingText(appointment, request);
    AppointmentStatus status = Optional.ofNullable(request.getStatus()).orElse(AppointmentStatus.SCHEDULED);
    appointment.setStatus(status);
        appointment.setCreatedBy(currentUser);
        appointment.setAssignment(assignment);

        Appointment saved = appointmentRepository.save(appointment);
        // QA D985: the recall this visit answers is now scheduled.
        if (recallService != null && AppointmentStatus.holdsSlot(saved.getStatus())) {
            recallService.scheduleOpenRecallFor(saved, request.getRecallId());
        }

        // Outbound webhooks (Tier 2 item 45): thin id-reference only. The
        // publisher defers to AFTER COMMIT and enqueues in its own
        // transaction - it can never fail or roll back this booking, and
        // never emits for a booking that rolled back.
        webhookPublisher.publish(hospital.getId(), WebhookEventType.APPOINTMENT_BOOKED,
            WEBHOOK_RESOURCE_APPOINTMENT, saved.getId());

        // Build reschedule/cancel links
        String rescheduleLink = frontendBaseUrl + appointmentLinks.getReschedulePath() + saved.getId();
        String cancelLink = frontendBaseUrl + appointmentLinks.getCancelPath() + saved.getId();
        // Send confirmation email to patient. Phone-first patients have no email,
        // and a notification failure must never roll back the booking itself.
        if (patient.getEmail() != null && !patient.getEmail().isBlank()) {
            try {
                Locale mailLocale = patientLocaleResolver.resolve(patient, EmailService.DEFAULT_RECIPIENT_LOCALE);
                emailService.sendAppointmentConfirmationEmail(
                    patient.getEmail(),
                    patient.getFullName(),
                    hospital.getName(),
                    staff.getUser().getFirstName() + " " + staff.getUser().getLastName(),
                    saved.getAppointmentDate().toString(),
                    saved.getStartTime().toString() + " - " + saved.getEndTime().toString(),
                    hospital.getEmail(),
                    hospital.getPhoneNumber(),
                    rescheduleLink,
                    cancelLink,
                    mailLocale
                );
            } catch (RuntimeException ex) {
                log.warn("Failed to send appointment confirmation email for {}: {}", saved.getId(), ex.getMessage());
            }
        }

        // --- Return lean summary (no null bloat) ---
        Patient p = saved.getPatient();
        Staff s = saved.getStaff();
        Department d = saved.getDepartment();
        Hospital h = saved.getHospital();
        return AppointmentSummaryDTO.builder()
            .id(saved.getId())
            .status(saved.getStatus())
            .appointmentDate(saved.getAppointmentDate())
            .startTime(saved.getStartTime())
            .endTime(saved.getEndTime())
            .patientId(p.getId())
            .patientName(p.getFullName())
            .patientEmail(p.getEmail())
            .patientPhone(p.getPhoneNumberPrimary())
            .staffId(s.getId())
            .staffName(s.getUser().getFirstName() + " " + s.getUser().getLastName())
            .staffEmail(s.getUser().getEmail())
            .departmentId(d.getId())
            .departmentName(d.getName())
            .departmentPhone(d.getPhoneNumber())
            .departmentEmail(d.getEmail())
            .hospitalId(h.getId())
            .hospitalName(h.getName())
            .hospitalAddress(h.getAddress())
            .hospitalPhone(h.getPhoneNumber())
            .hospitalEmail(h.getEmail())
            .notes(saved.getNotes())
            .build();
    }

    /* ------- small private helpers to keep createAppointment tidy ------- */

    /**
     * The patient a staff member books for: the one named by {@code patientId},
     * and nobody else. Never the caller's own patient record (QA D824), and
     * never a username or an e-mail fragment, whose "contains" match picked
     * whichever patient came first.
     */
    private Patient resolvePatientForStaffBooking(AppointmentRequestDTO request) {
        if (request.getPatientId() == null) {
            throw new BusinessException("appointment.patient.required");
        }
        return resolvePatient(request.getPatientId());
    }

    /**
     * Unscoped on purpose: multi-hospital patients have Patient.hospitalId set
     * to the FIRST hospital, so the tenant-scoped findById misses them when
     * accessed from a different hospital context. The registration guard then
     * scopes a staff booking to the booking hospital.
     */
    private Patient resolvePatient(UUID patientId) {
        return patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", patientId));
    }

    /**
     * The patient a pure patient books for: themselves. Each way of naming a
     * patient answers a foreign one exactly as {@link #resolvePatient} answers
     * one that does not exist, and asks ownership before loading anything a
     * made-up identifier would not also reach.
     */
    private Patient resolveOwnPatientForBooking(AppointmentRequestDTO request, User currentUser) {
        if (request.getPatientId() != null) {
            if (!patientRepository.existsByIdAndUserId(request.getPatientId(), currentUser.getId())) {
                throw new ResourceNotFoundException("patient.notfound", request.getPatientId());
            }
            return resolvePatient(request.getPatientId());
        }
        if (request.getPatientUsername() != null) {
            if (!request.getPatientUsername().equalsIgnoreCase(currentUser.getUsername())) {
                throw new ResourceNotFoundException(USER_NOT_FOUND_BY_USERNAME_KEY, request.getPatientUsername());
            }
            return ownPatientRecord(currentUser, request.getPatientUsername());
        }
        if (request.getPatientEmail() != null) {
            // QA round 16 residual #13 (class): a family can share an email, so the
            // first match may be a relative's record; only the caller's own counts.
            Patient named = patientRepository.findByEmailContainingIgnoreCase(request.getPatientEmail())
                .stream()
                .filter(p -> p.getUser() != null && currentUser.getId().equals(p.getUser().getId()))
                .findFirst().orElse(null);
            if (named == null) {
                throw new ResourceNotFoundException("patient.notFoundByIdentifier", request.getPatientEmail());
            }
            return named;
        }
        // No patient named (the mobile and patient-portal clients): the
        // caller's own record -- for a patient only. Any other caller that
        // reaches here (a role outside the staff list) names its patient.
        if (!hasRole(currentUser, ROLE_PATIENT_CODE)) {
            throw new BusinessException("appointment.patient.required");
        }
        return ownPatientRecord(currentUser, currentUser.getUsername());
    }

    /** The patient row linked to the caller's own account. */
    private Patient ownPatientRecord(User currentUser, String named) {
        return patientRepository.findByUserId(currentUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_FOR_USER_KEY, named));
    }

    /**
     * The booking hospital, by id, code or name. A provider facility answers
     * exactly as an unknown one: nobody books an appointment at a pharmacy or
     * laboratory (provider plan AC-11).
     */
    private Hospital resolveHospital(AppointmentRequestDTO request) {
        if (request.getHospitalId() != null) {
            return hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", request.getHospitalId()));
        } else if (request.getHospitalCode() != null) {
            return hospitalRepository.findClinicalByCodeIgnoreCase(request.getHospitalCode())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFoundByIdentifier", request.getHospitalCode()));
        } else if (request.getHospitalName() != null) {
            return hospitalRepository.findClinicalByNameIgnoreCase(request.getHospitalName())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFoundByIdentifier", request.getHospitalName()));
        }
        throw new BusinessException("appointment.hospital.required");
    }

    private Staff resolveStaff(AppointmentRequestDTO request) {
        if (request.getStaffId() != null) {
            return staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("staff.notfound", request.getStaffId()));
        } else if (request.getStaffEmail() != null) {
            UUID userId = userRepository.findByEmail(request.getStaffEmail())
                .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByEmail", request.getStaffEmail()))
                .getId();
            return staffRepository.findByUserId(userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("staff.notFoundByIdentifier", request.getStaffEmail()));
        } else if (request.getStaffUsername() != null) {
            UUID userId = userRepository.findByUsername(request.getStaffUsername())
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_BY_USERNAME_KEY, request.getStaffUsername()))
                .getId();
            return staffRepository.findByUserId(userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("staff.notFoundByIdentifier", request.getStaffUsername()));
        }
        throw new BusinessException("appointment.staff.required");
    }

    private Department resolveDepartment(AppointmentRequestDTO request, Hospital hospital, Staff staff) {
        // 1. Explicit departmentId in the request
        if (request.getDepartmentId() != null) {
            // Only a department of the appointment's hospital: a caller-supplied
            // id from another hospital is "not found", not a cross-tenant link.
            return departmentRepository.findById(request.getDepartmentId())
                .filter(d -> d.getHospital() != null && hospital.getId().equals(d.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("department.notFound", request.getDepartmentId()));
        }
        // 2. departmentCode in the request
        if (request.getDepartmentCode() != null) {
            return departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), request.getDepartmentCode())
                .orElseThrow(() -> new ResourceNotFoundException("department.notFoundByIdentifier", request.getDepartmentCode()));
        }
        // 3. departmentName in the request
        if (request.getDepartmentName() != null) {
            return departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), request.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException("department.notFoundByIdentifier", request.getDepartmentName()));
        }
        // 4. Staff's own department (doctors/nurses usually have one)
        if (staff.getDepartment() != null) {
            return staff.getDepartment();
        }
        // 5. No department named and the clinician has none: refuse (QA D99).
        //    This used to file the booking under the hospital's FIRST
        //    department (alphabetically, e.g. the laboratory), a silent and
        //    wrong placement the patient's chart then carried.
        throw new BusinessException("appointment.department.required");
    }


    /**
     * QA D1018: the department an edit files the visit under. A department the
     * request names wins (same lookup as a booking, scoped to the hospital);
     * a visit moved to another hospital is filed as a new booking would be; a
     * visit moved to another clinician who has a department follows them;
     * otherwise it stays where it was.
     */
    private Department resolveDepartmentForEdit(AppointmentRequestDTO request, Hospital hospital, Staff staff,
                                                Appointment existing) {
        boolean named = request.getDepartmentId() != null || request.getDepartmentCode() != null
            || request.getDepartmentName() != null;
        UUID oldStaffId = existing.getStaff() != null ? existing.getStaff().getId() : null;
        UUID oldHospitalId = existing.getHospital() != null ? existing.getHospital().getId() : null;
        boolean staffChanged = oldStaffId != null && !oldStaffId.equals(staff.getId());
        boolean hospitalChanged = oldHospitalId != null && !oldHospitalId.equals(hospital.getId());
        // A visit moved to another hospital never keeps the old hospital's department.
        if (named || hospitalChanged) {
            return resolveDepartment(request, hospital, staff);
        }
        if (staffChanged && staff.getDepartment() != null) {
            return staff.getDepartment();
        }
        return existing.getDepartment();
    }

    /* =======================
       UPDATE
       ======================= */

    @Override
    @Transactional
    public AppointmentResponseDTO updateAppointment(UUID id, AppointmentRequestDTO request, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);

        Appointment existing = appointmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, id));

        requireCompleteEdit(request);

        requireAppointmentVisible(currentUser, existing);
        // QA D727: a staff edit refuses an appointment that ends before it starts,
        // exactly as create and the portal reschedule do.
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException("appointment.timeRange.invalid");
        }
        // QA D788: the edit form is the other road to COMPLETED; refused as the
        // status action refuses it, before anything else is looked up.
        requireVisitCompletedBeforeAppointment(existing, request.getStatus());

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Staff staff = staffRepository.findById(request.getStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getStaffId()));

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));

        if (!staff.getHospital().getId().equals(hospital.getId())) {
            throw new AccessDeniedException("Staff does not belong to selected hospital");
        }
        // QA D401: an update may move the appointment to another hospital or
        // patient; the patient must be registered where it lands.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        UserRoleHospitalAssignment assignment = assignmentRepository
            .findActiveStaffAssignment(staff.getUser().getId(), hospital.getId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException("Staff role assignment not found"));

        LocalDateTime requestedStart = LocalDateTime.of(request.getAppointmentDate(), request.getStartTime());
        LocalDateTime requestedEnd = LocalDateTime.of(request.getAppointmentDate(), request.getEndTime());

        // --- Staff Schedule Check ---
        boolean staffAvailable = staffAvailabilityService.isStaffAvailable(staff.getId(), requestedStart);
        if (!staffAvailable) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable.schedule",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime()},
                locale
            ));
        }

        // QA D937: the appointment being moved, a cancelled booking and a
        // no-show never conflict. Nor does the edit itself when the visit
        // ends up not holding its slot (review round 9): a missed or cancelled
        // visit whose freed time was rebooked can still take a note or a cancel.
        AppointmentStatus resultingStatus = request.getStatus() != null ? request.getStatus() : existing.getStatus();
        boolean hasConflict = AppointmentStatus.holdsSlot(resultingStatus)
            && AppointmentSlotRules.overlapsHeldSlot(
                appointmentRepository.findByStaff_IdAndAppointmentDate(staff.getId(), request.getAppointmentDate()),
                id, requestedStart, requestedEnd);

        if (hasConflict) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime(), request.getEndTime()},
                locale
            ));
        }

        // Capture old schedule before mapper overwrites fields
        LocalDate   oldDate  = existing.getAppointmentDate();
        LocalTime   oldStart = existing.getStartTime();
        LocalTime   oldEnd   = existing.getEndTime();
        UUID        oldStaffId = existing.getStaff() != null ? existing.getStaff().getId() : null;

        // The update path used to write request.getStatus() straight onto the
        // entity with no transition check at all, while
        // confirmOrCancelAppointment validated every move. That asymmetry is
        // how the reschedule modal parked appointments in RESCHEDULED — a
        // status the state machine says must be passed through, reached by a
        // route that never consulted the state machine. A rule enforced on one
        // of two write paths is not a rule.
        AppointmentStatus statusBefore = existing.getStatus();
        requireValidStatusChange(statusBefore, request.getStatus());
        boolean statusMoved = request.getStatus() != null && request.getStatus() != statusBefore;
        if (statusMoved) {
            // The same temporal guard as the status action (review round 8).
            // Both the visit's own time and the time it is moved to must have
            // started: one PUT cannot move a future visit into the past and mark
            // it a no-show in the same breath (review round 15).
            requireStartedFor(request.getStatus(),
                LocalDateTime.of(existing.getAppointmentDate(), existing.getStartTime()), hospital);
            requireStartedFor(request.getStatus(), requestedStart, hospital);
        }

        // QA D1018 / D923: the department is part of the edit. The PUT used to
        // answer 200 and keep the old one, so a visit moved to a paediatrician
        // stayed filed under general medicine. Resolved after the status
        // checks, so their refusals come first as before.
        Department department = resolveDepartmentForEdit(request, hospital, staff, existing);
        appointmentMapper.updateAppointmentFromDto(request, existing, patient, staff, hospital);
        existing.setDepartment(department);
        existing.setAssignment(assignment);
        existing.setUpdatedAt(LocalDateTime.now());

        // QA D923: flush BEFORE the refresh. refresh() reloads the row from the
        // database, so refreshing an entity whose edits were still only in the
        // persistence context threw every edit away: the PUT answered 200 with
        // the old date, time and notes, and nothing was written.
        Appointment saved = appointmentRepository.saveAndFlush(existing);
        entityManager.refresh(saved);
        if (statusMoved) {
            afterEditMovedStatus(saved);
        }

        // QA D1013 (sibling): a visit moved off the inventory slot it was
        // booked from, or to another clinician, frees that slot; it used to
        // stay BOOKED for an appointment that was no longer there.
        boolean leftItsSlot = !saved.getAppointmentDate().equals(oldDate)
            || !saved.getStartTime().equals(oldStart)
            || !saved.getEndTime().equals(oldEnd)
            || !Objects.equals(oldStaffId, staff.getId());
        if (leftItsSlot) {
            slotInventoryService.releaseForAppointment(saved.getId());
        }

        // Outbound webhooks (Tier 2 item 45): a moved visit is a reschedule
        // regardless of which write path moved it.
        publishRescheduleIfMoved(saved, oldDate, oldStart, oldEnd);

        // Notify patient when date/time changes (treat as reschedule)
        boolean dateChanged  = !saved.getAppointmentDate().equals(oldDate);
        boolean startChanged = !saved.getStartTime().equals(oldStart);
        boolean endChanged   = !saved.getEndTime().equals(oldEnd);
        if (dateChanged || startChanged || endChanged) {
            try {
                Patient pt = saved.getPatient();
                Staff st   = saved.getStaff();
                Hospital h = saved.getHospital();
                Locale mailLocale = patientLocaleResolver.resolve(pt, EmailService.DEFAULT_RECIPIENT_LOCALE);
                emailService.sendAppointmentRescheduledEmail(
                    pt.getEmail(),
                    pt.getFirstName() + " " + pt.getLastName(),
                    h.getName(),
                    st.getUser().getFirstName() + " " + st.getUser().getLastName(),
                    saved.getAppointmentDate().toString(),
                    saved.getStartTime().toString() + " - " + saved.getEndTime().toString(),
                    h.getEmail(), h.getPhoneNumber(),
                    frontendBaseUrl + appointmentLinks.getReschedulePath() + saved.getId(),
                    frontendBaseUrl + appointmentLinks.getCancelPath() + saved.getId(),
                    mailLocale);
            } catch (Exception e) {
                log.warn("Failed to send reschedule email for appointment {}: {}", saved.getId(), e.getMessage());
            }
        }

        return appointmentMapper.toAppointmentResponseDTO(saved);
    }

    /** Emits the item-45 reschedule event iff the schedule actually moved. */
    private void publishRescheduleIfMoved(Appointment saved, LocalDate oldDate,
                                          LocalTime oldStart, LocalTime oldEnd) {
        boolean moved = !saved.getAppointmentDate().equals(oldDate)
            || !saved.getStartTime().equals(oldStart)
            || !saved.getEndTime().equals(oldEnd);
        if (moved) {
            webhookPublisher.publish(saved.getHospital().getId(),
                WebhookEventType.APPOINTMENT_RESCHEDULED, WEBHOOK_RESOURCE_APPOINTMENT,
                saved.getId());
        }
    }

    /* =======================
       STATUS CHANGE
       ======================= */

    @Override
    @Transactional
    public AppointmentResponseDTO confirmOrCancelAppointment(UUID appointmentId, String action, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, appointmentId));

        requireAppointmentVisible(currentUser, appointment);

        AppointmentStatus newStatus;
        switch (action.toLowerCase()) {
            case "confirm"    -> newStatus = AppointmentStatus.CONFIRMED;
            // Puts a RESCHEDULED appointment back into play. Reschedule now
            // lands on SCHEDULED directly, so this is for rows written before
            // that fix — without it they have a date, a time, and no action
            // anywhere in the product that can move them on.
            case "schedule"   -> newStatus = AppointmentStatus.SCHEDULED;
            case "cancel"     -> newStatus = AppointmentStatus.CANCELLED;
            case "complete"   -> newStatus = AppointmentStatus.COMPLETED;
            case "no_show"    -> newStatus = AppointmentStatus.NO_SHOW;
            case "pending"    -> newStatus = AppointmentStatus.PENDING;
            case "reschedule" -> newStatus = AppointmentStatus.RESCHEDULED;
            case "in_progress"-> newStatus = AppointmentStatus.IN_PROGRESS;
            case "fail"       -> newStatus = AppointmentStatus.FAILED;
            default -> throw new BusinessException("appointment.invalidAction", action);
        }

        // ── Validate status transition ──
        AppointmentStatus currentStatus = appointment.getStatus();
        Set<AppointmentStatus> allowed = VALID_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new BusinessException(INVALID_TRANSITION_KEY,
                AppointmentStatus.labelOf(currentStatus), AppointmentStatus.labelOf(newStatus));
        }
        requireVisitCompletedBeforeAppointment(appointment, newStatus);

        // ── Temporal guard: COMPLETED / NO_SHOW only after appointment start time ──
        requireStartedFor(newStatus, LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime()),
            appointment.getHospital());

        requireFreedSlotStillFree(appointment, currentStatus, newStatus);

        appointment.setStatus(newStatus);
        appointmentRepository.save(appointment);

        // The appointment owns its slot (P3 #22): once the visit is cancelled
        // or moved, the slot goes back into circulation.
        // QA D937: a no-show frees its time like a cancel, in the inventory too.
        if (releasesSlotInventory(newStatus)) {
            slotInventoryService.releaseForAppointment(appointment.getId());
        }
        // QA D985: a recall scheduled with a visit that will not happen is open
        // again; one whose visit took place is fulfilled.
        followUpRecalls(appointment.getId(), newStatus);

        // Outbound webhooks (Tier 2 item 45).
        if (newStatus == AppointmentStatus.CANCELLED) {
            webhookPublisher.publish(appointment.getHospital().getId(),
                WebhookEventType.APPOINTMENT_CANCELLED, WEBHOOK_RESOURCE_APPOINTMENT, appointment.getId());
        } else if (newStatus == AppointmentStatus.RESCHEDULED) {
            webhookPublisher.publish(appointment.getHospital().getId(),
                WebhookEventType.APPOINTMENT_RESCHEDULED, WEBHOOK_RESOURCE_APPOINTMENT, appointment.getId());
        }

        // Email notification triggers
        Patient patient = appointment.getPatient();
        Staff staff = appointment.getStaff();
        Hospital hospital = appointment.getHospital();
        String patientName = patient.getFullName();
        String staffName = staff.getUser().getFirstName() + " " + staff.getUser().getLastName();
        String appointmentDate = appointment.getAppointmentDate().toString();
        String appointmentTime = appointment.getStartTime().toString() + " - " + appointment.getEndTime().toString();
        String hospitalEmail = hospital.getEmail();
        String hospitalPhone = hospital.getPhoneNumber();
        String rescheduleLink = frontendBaseUrl + appointmentLinks.getReschedulePath() + appointment.getId();
        String cancelLink = frontendBaseUrl + appointmentLinks.getCancelPath() + appointment.getId();

        // Phone-first patients have no email, and a notification failure must
        // never roll back the status change itself.
        if (patient.getEmail() != null && !patient.getEmail().isBlank()) {
            try {
                Locale mailLocale = patientLocaleResolver.resolve(patient, EmailService.DEFAULT_RECIPIENT_LOCALE);
                switch (newStatus) {
                    case CONFIRMED -> emailService.sendAppointmentConfirmationEmail(
                        patient.getEmail(), patientName, hospital.getName(), staffName,
                        appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, rescheduleLink, cancelLink,
                        mailLocale);
                    case RESCHEDULED -> emailService.sendAppointmentRescheduledEmail(
                        patient.getEmail(), patientName, hospital.getName(), staffName,
                        appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, rescheduleLink, cancelLink,
                        mailLocale);
                    case CANCELLED -> emailService.sendAppointmentCancelledEmail(
                        patient.getEmail(), patientName, hospital.getName(), staffName,
                        appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, mailLocale);
                    case COMPLETED -> emailService.sendAppointmentCompletedEmail(
                        patient.getEmail(), patientName, hospital.getName(), staffName,
                        appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, mailLocale);
                    case NO_SHOW -> emailService.sendAppointmentNoShowEmail(
                        patient.getEmail(), patientName, hospital.getName(), staffName,
                        appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, mailLocale);
                    default -> {
                        // No notification for remaining statuses (PENDING, IN_PROGRESS, FAILED)
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to send appointment status email for {}: {}", appointment.getId(), ex.getMessage());
            }
        }

        return appointmentMapper.toAppointmentResponseDTO(appointment);
    }

    /* =======================
       GET BY ID
       ======================= */

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponseDTO getAppointmentById(UUID id, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);
        Appointment appointment = appointmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, id));

        // A patient caller reads only their own: another patient's appointment
        // answers exactly as a missing id does. Without this, the hospital
        // scope below admitted any appointment at a hospital where the patient
        // is registered.
        if (!subjectReadGuard.mayRead(PatientSubjectReaderRoles.APPOINTMENT_READS, appointment.getPatient())) {
            throw new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, appointment.getId());
        }

        if (!isSuperAdmin(currentUser)) {
            boolean isPatient = appointment.getPatient().getUser().getId().equals(currentUser.getId());
            if (!isPatient && !hasHospitalAccess(currentUser, appointment.getHospital().getId())) {
                throw new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, appointment.getId());
            }
        }
    // On GET, return full details (existing mapping)
    return appointmentMapper.toAppointmentResponseDTO(appointment);
    }

    /* =======================
       LIST: scoped to user
       ======================= */

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsForUser(String username, Locale locale) {
        User user = getUserOrThrow(username);

        if (isSuperAdmin(user)) {
            return appointmentRepository.findAllDistinct().stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }

        if (hasRole(user, ROLE_PATIENT_CODE)) {
            return patientRepository.findByUserId(user.getId())
                .map(patient -> appointmentRepository.findByPatient_Id(patient.getId()).stream()
                    .map(appointmentMapper::toAppointmentResponseDTO)
                    .toList())
                .orElse(List.of());
        }

        Set<UUID> hospitalScope = resolveHospitalScope(user);
        if (hospitalScope.isEmpty()) {
            return List.of();
        }

        return appointmentRepository.findAllByHospitalIdIn(hospitalScope).stream()
            .map(appointmentMapper::toAppointmentResponseDTO)
            .toList();
    }

    /* =======================
       DELETE
       ======================= */

    @Override
    @Transactional
    public void deleteAppointment(UUID id, Locale locale, String username) {
        User user = getUserOrThrow(username);
        Appointment appointment = appointmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, id));

        requireAppointmentVisible(user, appointment);

        appointmentRepository.delete(appointment);
        log.info("Deleted appointment with ID: {}", id);
    }

    /* =======================
       QUERIES
       ======================= */

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByPatientId(UUID patientId, Locale locale, String username) {
        User user = getUserOrThrow(username);
        // A patient caller reads only their own. Another patient's id answers
        // exactly as an unknown one does, before the patient lookup.
        if (!subjectReadGuard.mayRead(PatientSubjectReaderRoles.APPOINTMENT_READS, patientId)) {
            throw new ResourceNotFoundException("patient.notFound", patientId);
        }
        return getAppointmentsByPatientScoped(patientId, user);
    }

    /**
     * No authorization here on purpose — see the interface javadoc. The one
     * caller is the proxy-access path, which has already verified a live,
     * unexpired grant covering VIEW_APPOINTMENTS for exactly this patient.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsForVerifiedPatient(UUID patientId) {
        return appointmentRepository.findByPatient_Id(patientId).stream()
            .map(appointmentMapper::toAppointmentResponseDTO)
            .toList();
    }

    private List<AppointmentResponseDTO> getAppointmentsByPatientScoped(UUID patientId, User user) {
        // Both callers resolve the user via getUserOrThrow, so this never trips
        // today. It is stated because isSuperAdmin -> hasRole handles a null
        // user by returning false, which declares the parameter nullable — and
        // the self-access check below then dereferences it. Rather than leave
        // the two halves disagreeing, pin the contract the callers already
        // honour: no user, no scoped read.
        Objects.requireNonNull(user, "A resolved user is required to scope patient appointments.");
        if (isSuperAdmin(user)) {
            return appointmentRepository.findByPatient_Id(patientId).stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));

        // patient.getUser() is nullable in practice — the same dangling-FK class
        // that produced the registrations 500 (a patient row whose user was
        // deleted). A self-access check must fail closed on a broken link, not
        // throw a 500 at a caller who may well be entitled by hospital scope
        // below.
        boolean isOwnRecord = patient.getUser() != null
            && user.getId().equals(patient.getUser().getId());
        if (isOwnRecord) {
            return appointmentRepository.findByPatient_Id(patientId).stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }

        Set<UUID> hospitalScope = resolveHospitalScope(user);
        if (hospitalScope.isEmpty()) {
            return List.of();
        }

        List<Appointment> scoped = appointmentRepository.findByPatient_Id(patientId).stream()
            .filter(a -> hospitalScope.contains(a.getHospital().getId()))
            .toList();

        return scoped.stream()
            .map(appointmentMapper::toAppointmentResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByStaffId(UUID staffId, Locale locale, String username) {
        User user = getUserOrThrow(username);
        return getAppointmentsByStaffScoped(staffId, user);
    }

    private List<AppointmentResponseDTO> getAppointmentsByStaffScoped(UUID staffId, User user) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));

        if (isSuperAdmin(user)) {
            return appointmentRepository.findByStaff_Id(staffId).stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }

        requireHospitalScope(user, staff.getHospital().getId());
        return appointmentRepository.findByStaff_Id(staffId).stream()
            .map(appointmentMapper::toAppointmentResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByDoctorId(UUID staffId, Locale locale) {
        // ── Hospital scope enforcement: filter results to the caller's hospital scope ──
        // Global view only for a verified super-admin who named no hospital; a
        // super-admin who named one reads it like anyone else.
        ActingScope scope = ActingScopeResolver.currentScope();
        if (scope instanceof ActingScope.Global) {
            return appointmentRepository.findByStaff_Id(staffId).stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }
        UUID activeHospitalId = scope instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
        if (activeHospitalId == null) {
            log.warn("getAppointmentsByDoctorId: no active hospital context, returning empty");
            return List.of();
        }
        return appointmentRepository.findByHospital_IdAndStaff_Id(activeHospitalId, staffId).stream()
            .map(appointmentMapper::toAppointmentResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByDoctorId(UUID staffId, Locale locale, String username) {
        User user = getUserOrThrow(username);
        if (!hasRole(user, ROLE_DOCTOR_CODE) && !isSuperAdmin(user)) {
            throw new AccessDeniedException("Only doctors or super administrators can access doctor appointment details");
        }
        return getAppointmentsByStaffScoped(staffId, user);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppointmentResponseDTO> searchAppointments(AppointmentFilterDTO filter, Pageable pageable, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);
        AppointmentFilterDTO effectiveFilter = filter != null ? filter : AppointmentFilterDTO.builder().build();

        Specification<Appointment> specification = Specification.where(AppointmentSpecification.withFilter(effectiveFilter));

        if (isSuperAdmin(currentUser)) {
            Page<Appointment> result = appointmentRepository.findAll(specification, pageable);
            return result.map(appointmentMapper::toAppointmentResponseDTO);
        }

    if (hasRole(currentUser, ROLE_PATIENT_CODE)) {
            specification = specification.and(AppointmentSpecification.forPatientUser(currentUser.getId()));
        } else {
            Set<UUID> hospitalScope = resolveHospitalScope(currentUser);
            logScopeDecision(currentUser, hospitalScope, effectiveFilter);
            if (hospitalScope.isEmpty()) {
                return Page.empty(pageable);
            }
            specification = specification.and(AppointmentSpecification.inHospitals(hospitalScope));
        }

        Page<Appointment> result = appointmentRepository.findAll(specification, pageable);
        return result.map(appointmentMapper::toAppointmentResponseDTO);
    }

    private Set<UUID> resolveHospitalScope(User user) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        UUID pinnedHospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        if (context.isSuperAdmin() && pinnedHospitalId != null) {
            // A super-admin who named a hospital reads that one (D1).
            return Set.of(pinnedHospitalId);
        }
        if (context.isGlobalView()) {
            LinkedHashSet<UUID> superAdminScope = hospitalRepository.findAllHospitals().stream()
                .map(Hospital::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            if (log.isDebugEnabled()) {
                log.debug("resolveScope → super admin access, hospitals={}", superAdminScope);
            }
            return superAdminScope;
        }

        Set<UUID> actualHospitalIds = getUserHospitalIds(user);

        if (log.isDebugEnabled()) {
            log.debug("resolveScope → context snapshot for {}: activeHospital={} permitted={} actual={}", user.getUsername(),
                pinnedHospitalId, context.getPermittedHospitalIds(), actualHospitalIds);
            logHospitalLookup("assignments", actualHospitalIds);
        }
        UUID activeCandidate = resolveActiveHospitalCandidate(actualHospitalIds);
        if (activeCandidate != null) {
            return Set.of(activeCandidate);
        }

        LinkedHashSet<UUID> permitted = filterPermittedHospitals(context, actualHospitalIds);
        if (!permitted.isEmpty()) {
            return permitted;
        }

        if (!actualHospitalIds.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("resolveScope → falling back to assignment hospitals {}", actualHospitalIds);
                logHospitalLookup("fallback", actualHospitalIds);
            }
            return actualHospitalIds;
        }

        if (log.isDebugEnabled()) {
            log.debug("resolveScope → no hospitals resolved for {}", user.getUsername());
        }

        return Collections.emptySet();
    }

    private void logScopeDecision(User user, Set<UUID> hospitalScope, AppointmentFilterDTO filter) {
        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("searchAppointments → user={} scope={} filterHospitalId={} filterHospitalName={}",
            user.getUsername(), hospitalScope, filter.getHospitalId(), filter.getHospitalName());
        logHospitalLookup("scope", hospitalScope);
    }

    private UUID resolveActiveHospitalCandidate(Set<UUID> actualHospitalIds) {
        UUID activeHospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        if (activeHospitalId == null) {
            return null;
        }

        if (actualHospitalIds.contains(activeHospitalId)) {
            if (log.isDebugEnabled()) {
                log.debug("resolveScope → using active hospital {}", activeHospitalId);
            }
            return activeHospitalId;
        }

        if (log.isDebugEnabled()) {
            log.debug("resolveScope → active hospital {} not in assignments {}", activeHospitalId, actualHospitalIds);
        }
        return null;
    }

    private LinkedHashSet<UUID> filterPermittedHospitals(HospitalContext context, Set<UUID> actualHospitalIds) {
        Set<UUID> permitted = HospitalScopeUtils.resolveScope(context);
        if (permitted.isEmpty()) {
            return new LinkedHashSet<>();
        }

        LinkedHashSet<UUID> filtered = permitted.stream()
            .filter(actualHospitalIds::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (log.isDebugEnabled()) {
            log.debug("resolveScope → permitted hospitals {} filtered to {}", context.getPermittedHospitalIds(), filtered);
            logHospitalLookup("permitted", filtered);
        }

        return filtered;
    }

    private void logHospitalLookup(String label, Set<UUID> ids) {
        if (!log.isDebugEnabled() || ids == null || ids.isEmpty()) {
            return;
        }

        Map<UUID, String> names = hospitalRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Hospital::getId, Hospital::getName));

        log.debug("resolveScope → hospital lookup [{}]: {}", label, names);
    }

    private boolean hasHospitalAccess(User user, UUID hospitalId) {
        if (hospitalId == null || isSuperAdmin(user)) {
            return true;
        }
        return resolveHospitalScope(user).contains(hospitalId);
    }

    /**
     * QA D259: an appointment at a hospital outside the caller's scope answers exactly
     * as a missing id does (404), like every other tenant-scoped resource — a 403 told
     * a prober the id exists.
     */
    private void requireAppointmentVisible(User user, Appointment appointment) {
        if (!hasHospitalAccess(user, appointment.getHospital().getId())) {
            throw new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_KEY, appointment.getId());
        }
    }

    private void requireHospitalScope(User user, UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(user, hospitalId)) {
            throw new AccessDeniedException(messageSource.getMessage("access.denied", NO_ARGS, locale));
        }
    }

    private void requireHospitalScope(User user, UUID hospitalId) {
    requireHospitalScope(user, hospitalId, LocaleContextHolder.getLocale());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<com.example.hms.payload.dto.appointment.AppointmentCalendarEventDTO> getCalendarEvents(
        java.util.UUID hospitalId, java.time.LocalDate from, java.time.LocalDate to,
        java.util.UUID staffId, String username, java.util.Locale locale) {
        if (hospitalId == null || from == null || to == null || from.isAfter(to)) {
            return java.util.List.of();
        }
        // Authorisation: the calendar must not let an authenticated staff role
        // enumerate appointments for arbitrary hospitalIds. Reuse the same
        // hospital-scope gate as the rest of AppointmentServiceImpl so global
        // roles (SUPER_ADMIN, etc.) still pass through.
        java.util.Locale loc = locale != null ? locale
            : org.springframework.context.i18n.LocaleContextHolder.getLocale();
        if (username != null) {
            User currentUser = getUserOrThrow(username);
            requireHospitalScope(currentUser, hospitalId, loc);
        }
        java.util.List<com.example.hms.model.Appointment> rows = staffId == null
            ? appointmentRepository.findByHospital_IdAndAppointmentDateBetween(hospitalId, from, to)
            : appointmentRepository.findByHospital_IdAndStaff_IdAndAppointmentDateBetween(
                hospitalId, staffId, from, to);
        return rows.stream().map(AppointmentServiceImpl::toCalendarEvent).toList();
    }

    private static com.example.hms.payload.dto.appointment.AppointmentCalendarEventDTO toCalendarEvent(com.example.hms.model.Appointment a) {
        // Sonar S3358 (Pattern 8 of docs/SonarQubeInstructions.md):
        // the original implementation packed three nested ternaries
        // across L1002/1005/1007. Extracted into named helpers so each
        // null-fallback step is independently readable.
        java.util.UUID providerId = a.getStaff() != null ? a.getStaff().getId() : null;
        String providerName = resolveProviderName(a.getStaff());
        java.util.UUID patientId = a.getPatient() != null ? a.getPatient().getId() : null;
        String patientName = resolvePatientName(a.getPatient());
        java.time.LocalDateTime start = a.getAppointmentDate() == null || a.getStartTime() == null
            ? null : a.getAppointmentDate().atTime(a.getStartTime());
        java.time.LocalDateTime end = a.getAppointmentDate() == null || a.getEndTime() == null
            ? null : a.getAppointmentDate().atTime(a.getEndTime());
        String title = (patientName == null || patientName.isBlank() ? "Appointment" : patientName);
        return new com.example.hms.payload.dto.appointment.AppointmentCalendarEventDTO(
            a.getId(),
            patientId,
            patientName,
            providerId,
            providerName,
            title,
            start,
            end,
            a.getStatus() == null ? null : a.getStatus().name(),
            a.getReason()
        );
    }

    /**
     * Prefer the staff's full name; fall back to plain name; null if no
     * staff at all. Extracted from {@link #toCalendarEvent} to satisfy
     * Sonar S3358 (Pattern 8). Behaviour equivalent to the original
     * nested ternary.
     */
    private static String resolveProviderName(com.example.hms.model.Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getFullName() != null) {
            return staff.getFullName();
        }
        return staff.getName();
    }

    /**
     * Compose a patient's display name from first + last with explicit
     * empty-string fallbacks. Returns null when there is no patient.
     * Extracted from {@link #toCalendarEvent} to satisfy Sonar S3358
     * (Pattern 8). Behaviour equivalent to the original nested
     * ternaries.
     */
    private static String resolvePatientName(com.example.hms.model.Patient patient) {
        if (patient == null) {
            return null;
        }
        String first = patient.getFirstName() == null ? "" : patient.getFirstName();
        String last = patient.getLastName() == null ? "" : patient.getLastName();
        return (first + " " + last).trim();
    }
}
