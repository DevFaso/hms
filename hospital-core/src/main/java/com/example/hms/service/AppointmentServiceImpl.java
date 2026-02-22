package com.example.hms.service;


import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AppointmentMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
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

import java.time.LocalDateTime;
import java.util.Collections;
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
    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getAppointmentsByPatientUsername(String patientUsername, Locale locale, String username) {
        User patientUser = userRepository.findByUsername(patientUsername)
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + patientUsername));
        Patient patient = patientRepository.findByUserId(patientUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found for username: " + patientUsername));

        User currentUser = getUserOrThrow(username);
        return getAppointmentsByPatientScoped(patient.getId(), currentUser);
    }
    private final EmailService emailService;
    private boolean isSuperAdmin(User user) {
        return hasRole(user, ROLE_SUPER_ADMIN_CODE);
    }

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final MessageSource messageSource;
    private final UserRepository userRepository;
    private final StaffAvailabilityService staffAvailabilityService;
    private final DepartmentRepository departmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);
    private static final String USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String APPOINTMENT_NOT_FOUND_MESSAGE = "Appointment not found";
    private static final String ROLE_SUPER_ADMIN_CODE = "ROLE_SUPER_ADMIN";
    private static final String ROLE_ADMIN_CODE = "ROLE_ADMIN";
    private static final String ROLE_PATIENT_CODE = "ROLE_PATIENT";
    private static final String ROLE_DOCTOR_CODE = "ROLE_DOCTOR";
    private static final String ROLE_NURSE_CODE = "ROLE_NURSE";
    private static final String ROLE_RECEPTIONIST_CODE = "ROLE_RECEPTIONIST";

    /* =======================
       Helpers
       ======================= */

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + username));
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

    private boolean hasRole(User user, String roleCode) {
        if (user == null) {
            log.warn("hasRole() check failed: user is null");
            return false;
        }

        List<String> userRoleCodes = user.getUserRoles() == null ? List.of() : user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(Objects::nonNull)
            .map(Role::getCode)
            .filter(Objects::nonNull)
            .toList();

        boolean directMatch = userRoleCodes.stream().anyMatch(code -> code.equalsIgnoreCase(roleCode));
        if (directMatch) {
            log.debug("hasRole() direct match: user={}, roleCode={}, userRoleCodes={}", user.getUsername(), roleCode, userRoleCodes);
            return true;
        }

        var assignmentCodes = assignmentRepository.findAllByUserId(user.getId()).stream()
            .filter(assignment -> Boolean.TRUE.equals(assignment.getActive()))
            .map(UserRoleHospitalAssignment::getRole)
            .filter(Objects::nonNull)
            .map(Role::getCode)
            .filter(Objects::nonNull)
            .toList();

        boolean assignmentMatch = assignmentCodes.stream().anyMatch(code -> code.equalsIgnoreCase(roleCode));
        log.debug("hasRole() checking assignments: user={}, roleCode={}, userRoleCodes={}, assignmentCodes={}",
            user.getUsername(), roleCode, userRoleCodes, assignmentCodes);
        return assignmentMatch;
    }

    /* =======================
       CREATE
       ======================= */

    @Override
    @Transactional
    public AppointmentSummaryDTO createAppointment(AppointmentRequestDTO request, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);

        // --- Patient resolution ---
        final Patient patient = resolvePatient(request, locale);

        // Authorization: Staff/admins can book for any patient; pure patients can only book for themselves
        boolean isStaffOrAdmin = isSuperAdmin(currentUser)
            || hasRole(currentUser, "ROLE_HOSPITAL_ADMIN")
            || hasRole(currentUser, ROLE_ADMIN_CODE)
            || hasRole(currentUser, ROLE_DOCTOR_CODE)
            || hasRole(currentUser, ROLE_NURSE_CODE)
            || hasRole(currentUser, ROLE_RECEPTIONIST_CODE);

        log.debug("🔐 Authorization check: user={}, isStaffOrAdmin={}, isSuperAdmin={}, hasAdmin={}, hasDoctor={}, hasNurse={}, hasReceptionist={}",
            currentUser.getUsername(),
            isStaffOrAdmin,
            isSuperAdmin(currentUser),
            hasRole(currentUser, ROLE_ADMIN_CODE),
            hasRole(currentUser, ROLE_DOCTOR_CODE),
            hasRole(currentUser, ROLE_NURSE_CODE),
            hasRole(currentUser, ROLE_RECEPTIONIST_CODE));

        // If user is NOT staff/admin (i.e., they're a pure patient role), enforce self-booking
        if (!isStaffOrAdmin) {
            log.warn("❌ User {} is NOT staff/admin, enforcing self-booking restriction", currentUser.getUsername());
            if (patient.getUser() == null || !patient.getUser().getId().equals(currentUser.getId())) {
                throw new AccessDeniedException("Patients can only book appointments for themselves.");
            }
        } else {
            log.info("✅ User {} is staff/admin, allowing booking for any patient", currentUser.getUsername());
        }

        // --- Hospital resolution (single block) ---
        final Hospital hospital = resolveHospital(request, locale);

        // auth scope
        requireHospitalScope(currentUser, hospital.getId(), locale);

        // --- Staff resolution ---
        final Staff staff = resolveStaff(request, locale);

        // staff must belong to hospital
        if (!staff.getHospital().getId().equals(hospital.getId())) {
            throw new AccessDeniedException("Staff does not belong to selected hospital");
        }

        // --- Department resolution ---
        final Department department = resolveDepartment(request, hospital, staff);

        // --- Staff role assignment in this hospital ---
        final UserRoleHospitalAssignment assignment = assignmentRepository
            .findByUserIdAndHospitalId(staff.getUser().getId(), hospital.getId())
            .stream().findFirst()
            .orElseThrow(() -> new BusinessException("Staff role assignment not found"));

        // --- Time validations ---
        LocalDateTime requestedStart = LocalDateTime.of(request.getAppointmentDate(), request.getStartTime());
        LocalDateTime requestedEnd = LocalDateTime.of(request.getAppointmentDate(), request.getEndTime());
        if (!requestedEnd.isAfter(requestedStart)) {
            throw new BusinessException("Appointment end time must be after start time");
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
        boolean hasConflict = appointmentRepository
            .findByStaff_IdAndAppointmentDate(staff.getId(), request.getAppointmentDate())
            .stream()
            .anyMatch(existing -> {
                LocalDateTime es = LocalDateTime.of(existing.getAppointmentDate(), existing.getStartTime());
                LocalDateTime ee = LocalDateTime.of(existing.getAppointmentDate(), existing.getEndTime());
                return requestedStart.isBefore(ee) && es.isBefore(requestedEnd);
            });
        if (hasConflict) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime(), request.getEndTime()},
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
        appointment.setEndTime(request.getEndTime());
    appointment.setNotes(request.getNotes());
    AppointmentStatus status = Optional.ofNullable(request.getStatus()).orElse(AppointmentStatus.SCHEDULED);
    appointment.setStatus(status);
        appointment.setCreatedBy(currentUser);
        appointment.setAssignment(assignment);

        Appointment saved = appointmentRepository.save(appointment);

        // Build reschedule/cancel links (example, adjust as needed)
        String rescheduleLink = "https://your-app.com/appointments/reschedule/" + saved.getId();
        String cancelLink = "https://your-app.com/appointments/cancel/" + saved.getId();
        // Send confirmation email to patient
        emailService.sendAppointmentConfirmationEmail(
            patient.getEmail(),
            patient.getFirstName() + " " + patient.getLastName(),
            hospital.getName(),
            staff.getUser().getFirstName() + " " + staff.getUser().getLastName(),
            saved.getAppointmentDate().toString(),
            saved.getStartTime().toString() + " - " + saved.getEndTime().toString(),
            hospital.getEmail(),
            hospital.getPhoneNumber(),
            rescheduleLink,
            cancelLink
        );

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
            .patientName(p.getFirstName() + " " + p.getLastName())
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

    private Patient resolvePatient(AppointmentRequestDTO request, Locale locale) {
        if (request.getPatientId() != null) {
            return patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage("patient.notfound", new Object[]{request.getPatientId()}, locale)));
        } else if (request.getPatientUsername() != null) {
            UUID userId = userRepository.findByUsername(request.getPatientUsername())
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + request.getPatientUsername()))
                .getId();
            return patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found for username: " + request.getPatientUsername()));
        } else if (request.getPatientEmail() != null) {
            return patientRepository.findByEmailContainingIgnoreCase(request.getPatientEmail())
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found for email: " + request.getPatientEmail()));
        }
        throw new BusinessException("Patient identifier required");
    }

    private Hospital resolveHospital(AppointmentRequestDTO request, Locale locale) {
        if (request.getHospitalId() != null) {
            return hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage("hospital.notfound", new Object[]{request.getHospitalId()}, locale)));
        } else if (request.getHospitalCode() != null) {
            return hospitalRepository.findByCodeIgnoreCase(request.getHospitalCode())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found for code: " + request.getHospitalCode()));
        } else if (request.getHospitalName() != null) {
            return hospitalRepository.findByNameIgnoreCase(request.getHospitalName())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found for name: " + request.getHospitalName()));
        }
        throw new BusinessException("Hospital identifier required");
    }

    private Staff resolveStaff(AppointmentRequestDTO request, Locale locale) {
        if (request.getStaffId() != null) {
            return staffRepository.findById(request.getStaffId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage("staff.notfound", new Object[]{request.getStaffId()}, locale)));
        } else if (request.getStaffEmail() != null) {
            UUID userId = userRepository.findByEmail(request.getStaffEmail())
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + request.getStaffEmail()))
                .getId();
            return staffRepository.findByUserId(userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found for email: " + request.getStaffEmail()));
        } else if (request.getStaffUsername() != null) {
            UUID userId = userRepository.findByUsername(request.getStaffUsername())
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + request.getStaffUsername()))
                .getId();
            return staffRepository.findByUserId(userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found for username: " + request.getStaffUsername()));
        }
        throw new BusinessException("Staff identifier required");
    }

    private Department resolveDepartment(AppointmentRequestDTO request, Hospital hospital, Staff staff) {
        // 1. Explicit departmentId in the request
        if (request.getDepartmentId() != null) {
            return departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.getDepartmentId()));
        }
        // 2. departmentCode in the request
        if (request.getDepartmentCode() != null) {
            return departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), request.getDepartmentCode())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found for code: " + request.getDepartmentCode()));
        }
        // 3. departmentName in the request
        if (request.getDepartmentName() != null) {
            return departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), request.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found for name: " + request.getDepartmentName()));
        }
        // 4. Staff's own department (doctors/nurses usually have one)
        if (staff.getDepartment() != null) {
            return staff.getDepartment();
        }
        // 5. Fallback: first available department in the hospital
        //    Covers roles like RECEPTIONIST that are not tied to a specific department.
        List<Department> hospitalDepts = departmentRepository.findByHospitalId(hospital.getId());
        if (!hospitalDepts.isEmpty()) {
            log.debug("resolveDepartment: staff {} has no department; using first hospital department '{}'",
                staff.getId(), hospitalDepts.get(0).getName());
            return hospitalDepts.get(0);
        }
        throw new BusinessException("No departments found for hospital " + hospital.getId()
            + ". Please add a department or specify departmentId in the request.");
    }


    /* =======================
       UPDATE
       ======================= */

    @Override
    @Transactional
    public AppointmentResponseDTO updateAppointment(UUID id, AppointmentRequestDTO request, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);

        Appointment existing = appointmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_MESSAGE));

        // Null checks for required IDs
        if (request.getPatientId() == null) {
            throw new BusinessException("Patient ID must not be null");
        }
        if (request.getStaffId() == null) {
            throw new BusinessException("Staff ID must not be null");
        }
        if (request.getHospitalId() == null) {
            throw new BusinessException("Hospital ID must not be null");
        }
        if (request.getAppointmentDate() == null) {
            throw new BusinessException("Appointment date must not be null");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BusinessException("Appointment start and end time must not be null");
        }

        requireHospitalScope(currentUser, existing.getHospital().getId(), locale);

        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        Staff staff = staffRepository.findById(request.getStaffId())
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        if (!staff.getHospital().getId().equals(hospital.getId())) {
            throw new AccessDeniedException("Staff does not belong to selected hospital");
        }

        UserRoleHospitalAssignment assignment = assignmentRepository.findByUserIdAndHospitalId(staff.getUser().getId(), hospital.getId())
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

        boolean hasConflict = appointmentRepository
            .findByStaff_IdAndAppointmentDate(staff.getId(), request.getAppointmentDate()).stream()
            .filter(a -> !a.getId().equals(id))
            .anyMatch(existingAppointment -> {
                LocalDateTime es = LocalDateTime.of(existingAppointment.getAppointmentDate(), existingAppointment.getStartTime());
                LocalDateTime ee = LocalDateTime.of(existingAppointment.getAppointmentDate(), existingAppointment.getEndTime());
                return requestedStart.isBefore(ee) && es.isBefore(requestedEnd);
            });

        if (hasConflict) {
            throw new BusinessException(messageSource.getMessage(
                "appointment.staff.unavailable",
                new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime(), request.getEndTime()},
                locale
            ));
        }

        appointmentMapper.updateAppointmentFromDto(request, existing, patient, staff, hospital);
        existing.setAssignment(assignment);
        existing.setUpdatedAt(LocalDateTime.now());

        Appointment saved = appointmentRepository.save(existing);
        entityManager.refresh(saved);

        return appointmentMapper.toAppointmentResponseDTO(saved);
    }

    /* =======================
       STATUS CHANGE
       ======================= */

    @Override
    @Transactional
    public AppointmentResponseDTO confirmOrCancelAppointment(UUID appointmentId, String action, Locale locale, String username) {
        User currentUser = getUserOrThrow(username);
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_MESSAGE));

        requireHospitalScope(currentUser, appointment.getHospital().getId(), locale);

        AppointmentStatus newStatus;
        switch (action.toLowerCase()) {
            case "confirm"    -> newStatus = AppointmentStatus.CONFIRMED;
            case "cancel"     -> newStatus = AppointmentStatus.CANCELLED;
            case "complete"   -> newStatus = AppointmentStatus.COMPLETED;
            case "no_show"    -> newStatus = AppointmentStatus.NO_SHOW;
            case "pending"    -> newStatus = AppointmentStatus.PENDING;
            case "reschedule" -> newStatus = AppointmentStatus.RESCHEDULED;
            case "in_progress"-> newStatus = AppointmentStatus.IN_PROGRESS;
            case "fail"       -> newStatus = AppointmentStatus.FAILED;
            default -> throw new BusinessException("Invalid action: " + action);
        }

        appointment.setStatus(newStatus);
        appointmentRepository.save(appointment);

        // Email notification triggers
        Patient patient = appointment.getPatient();
        Staff staff = appointment.getStaff();
        Hospital hospital = appointment.getHospital();
        String patientName = patient.getFirstName() + " " + patient.getLastName();
        String staffName = staff.getUser().getFirstName() + " " + staff.getUser().getLastName();
        String appointmentDate = appointment.getAppointmentDate().toString();
        String appointmentTime = appointment.getStartTime().toString() + " - " + appointment.getEndTime().toString();
        String hospitalEmail = hospital.getEmail();
        String hospitalPhone = hospital.getPhoneNumber();
        String rescheduleLink = "https://your-app.com/appointments/reschedule/" + appointment.getId();
        String cancelLink = "https://your-app.com/appointments/cancel/" + appointment.getId();

        switch (newStatus) {
            case RESCHEDULED -> emailService.sendAppointmentRescheduledEmail(
                patient.getEmail(), patientName, hospital.getName(), staffName,
                appointmentDate, appointmentTime, hospitalEmail, hospitalPhone, rescheduleLink, cancelLink);
            case CANCELLED -> emailService.sendAppointmentCancelledEmail(
                patient.getEmail(), patientName, hospital.getName(), staffName,
                appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
            case COMPLETED -> emailService.sendAppointmentCompletedEmail(
                patient.getEmail(), patientName, hospital.getName(), staffName,
                appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
            case NO_SHOW -> emailService.sendAppointmentNoShowEmail(
                patient.getEmail(), patientName, hospital.getName(), staffName,
                appointmentDate, appointmentTime, hospitalEmail, hospitalPhone);
            default -> {
                // No notification for remaining statuses
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
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_MESSAGE));

        if (!isSuperAdmin(currentUser)) {
            boolean isPatient = appointment.getPatient().getUser().getId().equals(currentUser.getId());
            if (!isPatient && !hasHospitalAccess(currentUser, appointment.getHospital().getId())) {
                throw new AccessDeniedException(messageSource.getMessage("access.denied", null, "Access denied", locale));
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
            return appointmentRepository.findAll().stream()
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
            .orElseThrow(() -> new ResourceNotFoundException(APPOINTMENT_NOT_FOUND_MESSAGE));

        requireHospitalScope(user, appointment.getHospital().getId(), locale);

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
        return getAppointmentsByPatientScoped(patientId, user);
    }

    private List<AppointmentResponseDTO> getAppointmentsByPatientScoped(UUID patientId, User user) {
        if (isSuperAdmin(user)) {
            return appointmentRepository.findByPatient_Id(patientId).stream()
                .map(appointmentMapper::toAppointmentResponseDTO)
                .toList();
        }

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        if (user.getId().equals(patient.getUser().getId())) {
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
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));

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
        return appointmentRepository.findByStaff_Id(staffId).stream()
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
        if (context.isSuperAdmin()) {
            LinkedHashSet<UUID> superAdminScope = hospitalRepository.findAll().stream()
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
                context.getActiveHospitalId(), context.getPermittedHospitalIds(), actualHospitalIds);
            logHospitalLookup("assignments", actualHospitalIds);
        }
        UUID activeCandidate = resolveActiveHospitalCandidate(context, actualHospitalIds);
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

    private UUID resolveActiveHospitalCandidate(HospitalContext context, Set<UUID> actualHospitalIds) {
        UUID activeHospitalId = context.getActiveHospitalId();
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

    private void requireHospitalScope(User user, UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(user, hospitalId)) {
            throw new AccessDeniedException(messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }

    private void requireHospitalScope(User user, UUID hospitalId) {
    requireHospitalScope(user, hospitalId, LocaleContextHolder.getLocale());
    }
}
