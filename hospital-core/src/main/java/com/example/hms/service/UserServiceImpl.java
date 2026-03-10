package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.utility.UserDisplayUtil;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.payload.dto.UpdateUserRequestDTO;
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.JwtTokenHolder;
import com.example.hms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
@Slf4j
public class UserServiceImpl implements UserService {
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String HOSPITAL_NOT_FOUND_PREFIX = "Hospital not found with ID: ";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_PHARMACIST = "ROLE_PHARMACIST";
    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";
    private static final String USER_NOT_FOUND_PREFIX = "User not found with ID: ";


    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleHospitalAssignmentService assignmentService;
    private final EmailService emailService;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditEventLogService;
    private final StaffRepository staffRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    /*
     * --------------------------------
     * Public Registration (Patient)
     * --------------------------------
     */
    @Override
    @Transactional
    public UserResponseDTO createUser(UserRequestDTO dto) {
        long existingUsers = userRepository.count();

        String encodedPwd = passwordEncoder.encode(dto.getPassword());
        User user = userMapper.toEntity(dto, encodedPwd);
        user.setActivationToken(UUID.randomUUID().toString());
        user.setActivationTokenExpiresAt(LocalDateTime.now().plusDays(1));
        user.setActive(false);

        User saved = userRepository.save(user);

        if (existingUsers == 0) {
            Role superAdmin = getRoleByCode(ROLE_SUPER_ADMIN);
            addUserRoleIfAbsent(saved.getId(), superAdmin.getId());
            createAssignmentIfAbsent(saved.getId(), superAdmin.getId(), null);
            log.info("🎉 First user auto-assigned ROLE_SUPER_ADMIN (global).");
        } else {
            Role patient = getRoleByCode(ROLE_PATIENT);
            addUserRoleIfAbsent(saved.getId(), patient.getId());

            UUID targetHospitalId = dto.getHospitalId();
            if (targetHospitalId == null) {
                try {
                    Hospital defaultHospital = getDefaultHospital();
                    targetHospitalId = defaultHospital.getId();
                } catch (RuntimeException e) {
                    log.warn("⚠️ Default hospital not found. Creating PATIENT without hospital assignment.");
                    targetHospitalId = null;
                }
            }
            createAssignmentIfAbsent(saved.getId(), patient.getId(), targetHospitalId);
        }

        final String activationLink = String.format(
                "%s/verify?email=%s&token=%s",
                frontendBaseUrl, saved.getEmail(), saved.getActivationToken());
        try {
            emailService.sendActivationEmail(saved.getEmail(), activationLink);
        } catch (Exception e) {
            log.warn("⚠️ Failed to send verification email to '{}': {}. "
                    + "Patient can request a new verification email later.",
                    saved.getEmail(), e.getMessage());
        }

        User reloaded = userRepository.findByIdWithRolesAndProfiles(saved.getId())
                .orElseThrow(() -> new IllegalStateException("User disappeared after save"));
        Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(reloaded);

        return userMapper.toResponseDTO(reloaded, assignments);
    }

    /*
     * --------------------------------
     * Bootstrap First Super Admin
     * --------------------------------
     */
    @Override
    @Transactional
    public BootstrapSignupResponse bootstrapFirstUser(BootstrapSignupRequest request) {
        long existing = userRepository.count();
        if (existing > 0) {
            return BootstrapSignupResponse.builder()
                    .success(false)
                    .message("Bootstrap not allowed; users already exist")
                    .username(null)
                    .email(null)
                    .build();
        }

        // No token check — the endpoint is self-protecting (only works when 0 users exist)

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail().toLowerCase(Locale.ROOT));
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        LocalDateTime passwordSetAt = LocalDateTime.now();
        user.setPasswordHash(encodedPassword);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setActive(true); // immediate activation for bootstrap
        user.setCreatedAt(passwordSetAt);
        user.setPasswordChangedAt(passwordSetAt);
        user.setPasswordRotationWarningAt(null);
        user.setPasswordRotationForcedAt(null);
        user = userRepository.save(user);

        Role superAdmin = getRoleByCode(ROLE_SUPER_ADMIN);
        addUserRoleIfAbsent(user.getId(), superAdmin.getId());
        createAssignmentIfAbsent(user.getId(), superAdmin.getId(), null);

        // Audit event — best-effort, never fails the bootstrap operation
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(null)           // no JWT actor during bootstrap; audit supports null (SYSTEM flow)
                .userName("SYSTEM")     // denormalized label for audit trail
                .assignmentId(null)
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("First system user bootstrap (Super Admin)")
                .details("Bootstrap user created: " + user.getUsername())
                .resourceId(user.getId().toString())
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .ipAddress(null)
                .build());

        // Send welcome email (fire-and-forget — don't block bootstrap on mail failure)
        try {
            String htmlBody = "<h2>Welcome to HMS, " + user.getFirstName() + "!</h2>"
                    + "<p>Your <strong>Super Admin</strong> account has been created and is <strong>active immediately</strong>.</p>"
                    + "<table style='border-collapse:collapse'>"
                    + "<tr><td style='padding:4px 12px;font-weight:bold'>Username:</td><td>" + user.getUsername() + "</td></tr>"
                    + "<tr><td style='padding:4px 12px;font-weight:bold'>Email:</td><td>" + user.getEmail() + "</td></tr>"
                    + "<tr><td style='padding:4px 12px;font-weight:bold'>Role:</td><td>Super Admin</td></tr>"
                    + "</table>"
                    + "<p style='margin-top:16px'>You can sign in now and begin configuring the system.</p>"
                    + "<p style='color:#888;font-size:12px'>This is an automated message from HMS.</p>";
            emailService.sendHtml(
                    java.util.List.of(user.getEmail()),
                    java.util.List.of(), java.util.List.of(),
                    "HMS — Your Super Admin Account Is Ready",
                    htmlBody);
            log.info("[BOOTSTRAP] Welcome email sent to {}", user.getEmail());
        } catch (Exception e) {
            log.warn("[BOOTSTRAP] Failed to send welcome email to {}: {}", user.getEmail(), e.getMessage());
        }

        return BootstrapSignupResponse.builder()
                .success(true)
                .message("Bootstrap Super Admin created — account is active, you can sign in now")
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    /*
     * --------------------------------
     * Admin/Staff Registration
     * --------------------------------
     */
    @Override
    @Transactional(timeout = 20)
    public UserResponseDTO createUserWithRolesAndHospital(AdminSignupRequest request) {
        log.info("[SERVICE] Starting createUserWithRolesAndHospital for username={}, hospitalId={}, roles={}",
                request.getUsername(), request.getHospitalId(), request.getRoleNames());

        // ---- 0) Normalize/validate inputs ----
        final String email = request.getEmail() == null ? null : request.getEmail().toLowerCase(Locale.ROOT);
        final String phone = request.getPhoneNumber();
        final String username = request.getUsername();

        // Determine roles early so patient-specific logic can relax duplicate checks.
        final Set<String> roleNames = Optional.ofNullable(request.getRoleNames())
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("At least one role must be provided."));

        final boolean isPatient = roleNames.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(r -> r.equals("PATIENT") || r.equals(ROLE_PATIENT));

        // ---- 0a) Duplicate checks ----
        if (isPatient) {
            // Epic-style: patients can span multiple hospitals.
            // Only reject if the same patient is already at the *target* hospital.
            checkPatientAlreadyAtHospital(username, email, phone, request);
        } else {
            // Non-patient roles: strict global uniqueness.
            if (username != null && Boolean.TRUE.equals(userRepository.existsByUsername(username))) {
                throw new ConflictException("username:Username '" + username + "' is already taken.");
            }
            if (email != null && Boolean.TRUE.equals(userRepository.existsByEmail(email))) {
                throw new ConflictException("email:Email '" + email + "' is already registered.");
            }
            if (phone != null && !phone.isBlank() && Boolean.TRUE.equals(userRepository.existsByPhoneNumber(phone))) {
                throw new ConflictException("phone:Phone number '" + phone + "' is already registered.");
            }
        }

        // ---- 1) Resolve hospital for this registration ----
        UUID staffContextHospitalId = resolveHospitalForRegistration(request, roleNames, isPatient);

        // ---- 2) Resolve Roles ----
        final Set<Role> roles = roleNames.stream()
                .map(this::resolveRoleByName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final boolean requiresStaff = roles.stream().anyMatch(role -> switch (role.getCode()) {
            case ROLE_DOCTOR, ROLE_NURSE, ROLE_LAB_SCIENTIST, ROLE_PHARMACIST, ROLE_HOSPITAL_ADMIN,
                 "ROLE_RECEPTIONIST", "ROLE_MIDWIFE", "ROLE_RADIOLOGIST", "ROLE_SURGEON", "ROLE_PHYSICIAN" -> true;
            default -> false;
        });

        // Clinical roles require a medical licence; admin/management roles do not.
        final boolean requiresLicense = roles.stream().anyMatch(role -> switch (role.getCode()) {
            case ROLE_DOCTOR, ROLE_NURSE, ROLE_LAB_SCIENTIST, ROLE_PHARMACIST -> true;
            default -> false;
        });

        // ---- 3) Resolve/Create User ----
        final String lic = resolveLicenseNumber(request, requiresLicense);

        final Optional<User> existingByIdentity = userRepository
                .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(username, email, phone);
        final Optional<User> existingByLicense = requiresLicense
                ? staffRepository.findUserIdByLicense(lic).flatMap(userRepository::findById)
                : Optional.empty();

        final boolean newUserCreated = existingByIdentity.isEmpty() && existingByLicense.isEmpty();
        final User user = existingByIdentity.or(() -> existingByLicense)
                .orElseGet(() -> createNewUser(request, username, email, phone, roles));

        applyForcePasswordChangeIfReturning(user, newUserCreated, request);

        // ---- 4) Ensure roles + hospital-scoped assignments ----
        final List<UserRoleHospitalAssignment> ensuredAssignments =
                ensureRolesAndAssignments(user, roles, staffContextHospitalId);

        // ---- 4a) Forward temp password to the PATIENT assignment ----
        // The assignment notification email (AFTER_COMMIT) will expose these
        // credentials once, then verifyAssignmentByCode() clears them.
        if (newUserCreated && isPatient && request.getPassword() != null) {
            ensuredAssignments.stream()
                    .filter(a -> a.getRole() != null
                            && ROLE_PATIENT.equalsIgnoreCase(a.getRole().getCode()))
                    .findFirst()
                    .ifPresent(patientAssignment -> {
                        patientAssignment.setTempPlainPassword(request.getPassword());
                        assignmentRepository.save(patientAssignment);
                    });
        }

        auditIfNewUser(newUserCreated, user, ensuredAssignments);

        // ---- 5) Upsert Staff when needed ----
        if (requiresStaff && staffContextHospitalId != null) {
            upsertStaff(user, staffContextHospitalId, lic, ensuredAssignments, request, roles);
        }

        // ---- 6) Reload + map ----
        final UserResponseDTO result = reloadAndMap(user.getId());

        // ---- 7) Welcome email for brand-new non-patient users (fire-and-forget) ----
        if (newUserCreated && !isPatient) {
            final String displayName = UserDisplayUtil.resolveDisplayName(user);
            final String roleName    = roles.stream()
                    .map(r -> formatRoleLabel(r.getCode()))
                    .findFirst().orElse("User");
            final String hospitalName = staffContextHospitalId != null
                    ? hospitalRepository.findById(staffContextHospitalId)
                          .map(Hospital::getName).orElse(null)
                    : null;
            try {
                emailService.sendAdminWelcomeEmail(
                    user.getEmail(), displayName,
                    user.getUsername(), request.getPassword(),
                    roleName, hospitalName);
                log.info("📧 Welcome email dispatched to new user '{}'", user.getUsername());
            } catch (Exception e) {
                log.warn("⚠️ Failed to send welcome email to '{}': {}", user.getUsername(), e.getMessage());
            }
        }

        return result;
    }

    /** Apply force-password-change flags only when re-registering an existing user. */
    private void applyForcePasswordChangeIfReturning(User user, boolean newUserCreated,
                                                      AdminSignupRequest request) {
        if (!newUserCreated && Boolean.TRUE.equals(request.getForcePasswordChange())) {
            user.setForcePasswordChange(true);
            user.setPasswordRotationForcedAt(LocalDateTime.now());
        }
    }

    /** Record audit + emit creation event only for brand-new users. */
    private void auditIfNewUser(boolean newUserCreated, User user,
                                List<UserRoleHospitalAssignment> assignments) {
        if (newUserCreated) {
            User actor = resolveCurrentActor().orElse(user);
            recordUserCreationAudit(actor, user, assignments);
        }
    }

    /** Reload user from DB, map to DTO, and attach role counts. */
    private UserResponseDTO reloadAndMap(UUID userId) {
        final User reloaded = userRepository.findByIdWithRolesAndProfiles(userId)
                .orElseThrow(() -> new IllegalStateException("User disappeared after save"));
        final Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(reloaded);

        final long roleCount = assignmentRepository.countDistinctRolesByUserId(reloaded.getId());
        final long activeRoleCount = assignmentRepository.countDistinctActiveRolesByUserId(reloaded.getId());
        log.info("Registered user {} with {} distinct roles ({} active).",
                reloaded.getId(), roleCount, activeRoleCount);

        final UserResponseDTO dto = userMapper.toResponseDTO(reloaded, assignments);
        dto.setRoleCount(Math.toIntExact(roleCount));
        return dto;
    }

    private List<UserRoleHospitalAssignment> ensureRolesAndAssignments(
            User user, Set<Role> roles, UUID staffContextHospitalId) {
        final List<UserRoleHospitalAssignment> result = new ArrayList<>();
        for (Role r : roles) {
            addUserRoleIfAbsent(user.getId(), r.getId());

            boolean isPatientRole = ROLE_PATIENT.equalsIgnoreCase(r.getCode())
                    || "PATIENT".equalsIgnoreCase(r.getName());

            log.info("[ASSIGN] {} -> user={} hospitalId={} active={}",
                    r.getCode(), user.getUsername(), staffContextHospitalId, !isPatientRole);

            result.add(ensureAssignmentSmart(user.getId(), r, staffContextHospitalId, !isPatientRole));
        }
        return result;
    }

    private Role resolveRoleByName(String name) {
        final String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        final String code = normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
        return getRoleByCode(code);
    }

    private void upsertStaff(User user, UUID hospitalId, String lic,
                         List<UserRoleHospitalAssignment> assignments, AdminSignupRequest request, Set<Role> roles) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_PREFIX + hospitalId));

        staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
            .map(Staff::getLicenseNumber)
            .ifPresent(existingLic -> {
                if (!existingLic.equalsIgnoreCase(lic)) {
                    throw new ConflictException(
                        "License number mismatch for this user in this hospital; expected " + existingLic);
                }
            });

        final UserRoleHospitalAssignment staffAssignment = assignments.stream()
            .filter(a -> ROLE_HOSPITAL_ADMIN.equalsIgnoreCase(a.getRole().getCode()))
            .findFirst()
            .orElse(assignments.get(0));

        final Staff staff = staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
            .orElseGet(() -> {
                Staff s = new Staff();
                s.setUser(user);
                s.setHospital(hospital);
                return s;
            });

        staff.setAssignment(staffAssignment);
        staff.setLicenseNumber(lic);
        staff.setEmploymentType(
            request.getEmploymentType() != null ? request.getEmploymentType() : EmploymentType.FULL_TIME);
        staff.setJobTitle(coerceJobTitle(request.getJobTitle(), roles));
        if (staff.getStartDate() == null) {
            staff.setStartDate(LocalDate.now());
        }

        final String fn = user.getFirstName() == null ? "" : user.getFirstName().trim();
        final String ln = user.getLastName() == null ? "" : user.getLastName().trim();
        final String fullName = (fn + " " + ln).trim();
        if (!fullName.isEmpty()) {
            staff.setName(fullName);
        }

        staffRepository.save(staff);
    }

    /** Converts ROLE_HOSPITAL_ADMIN → "Hospital Admin". */
    private static String formatRoleLabel(String roleCode) {
        if (roleCode == null) return "User";
        String stripped = roleCode.startsWith("ROLE_") ? roleCode.substring(5) : roleCode;
        return java.util.Arrays.stream(stripped.split("_"))
            .filter(w -> !w.isEmpty())
            .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private String resolveLicenseNumber(AdminSignupRequest request, boolean requiresStaff) {
        String lic = requiresStaff
                ? Optional.ofNullable(request.getLicenseNumber()).map(String::trim).orElse("")
                : "";
        if (requiresStaff && lic.isEmpty()) {
            throw new IllegalArgumentException("licenseNumber is required for medical/admin roles.");
        }
        return lic;
    }

    private User createNewUser(AdminSignupRequest request, String username, String email, String phone, Set<Role> roles) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        LocalDateTime passwordSetAt = LocalDateTime.now();

        // Auto-generate a temporary password when none is provided (e.g. patient
        // registration by receptionist).  The user will be forced to change it on
        // first login via forcePasswordChange flag.
        String rawPassword = request.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = UUID.randomUUID().toString();
            request.setForcePasswordChange(true);
        }
        // Persist the raw password back onto the request so that callers
        // (e.g. createUserWithRolesAndHospital) can forward it to the
        // assignment's tempPlainPassword field for one-time email exposure.
        request.setPassword(rawPassword);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));

        u.setFirstName(request.getFirstName());
        u.setLastName(request.getLastName());
        u.setPhoneNumber(phone);

        boolean isPatient = roles.stream().anyMatch(r -> ROLE_PATIENT.equalsIgnoreCase(r.getCode()));

        if (isPatient) {
            // Patient accounts start inactive — must verify email first
            u.setActive(false);
            u.setActivationToken(UUID.randomUUID().toString());
            u.setActivationTokenExpiresAt(LocalDateTime.now().plusDays(1));
        } else {
            // Admin-registered staff/admin accounts are immediately active.
            // The admin vouches for them and sends credentials via welcome email.
            u.setActive(true);
        }

        if (isPatient || Boolean.TRUE.equals(request.getForcePasswordChange())) {
            u.setForcePasswordChange(true);
        }
        u.setCreatedAt(passwordSetAt);
        u.setPasswordChangedAt(passwordSetAt);
        u.setPasswordRotationWarningAt(null);
        u.setPasswordRotationForcedAt(Boolean.TRUE.equals(request.getForcePasswordChange()) ? passwordSetAt : null);

        // Patient verification email is now handled by the assignment notification
        // flow (AssignmentCreatedEvent → sendRoleAssignmentConfirmationEmail) which
        // includes the 6-digit confirmation code, temp credentials, and a link to
        // the RoleWelcomeComponent verification page.

        return userRepository.save(u);
    }

        private UUID resolveHospitalForRegistration(AdminSignupRequest request, Set<String> roleNames, boolean isPatient) {
        if (isPatient) {
            return resolveHospitalForPatient(request);
        }
        return resolveHospitalForStaff(request, roleNames);
    }

    /**
     * For patient registrations, allow reuse of existing users across hospitals.
     * Only reject if the patient already has an active assignment at the target hospital.
     */
    private void checkPatientAlreadyAtHospital(String username, String email, String phone,
                                                AdminSignupRequest request) {
        Optional<User> existing = userRepository
                .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(username, email, phone);
        if (existing.isEmpty()) {
            return;
        }
        UUID targetHospitalId = resolveHospitalForPatient(request);
        if (targetHospitalId == null) {
            return;
        }
        Role patientRole = getRoleByCode(ROLE_PATIENT);
        if (assignmentService.isRoleAlreadyAssigned(existing.get().getId(), targetHospitalId, patientRole.getId())) {
            throw new ConflictException("patient:Patient is already registered at this hospital.");
        }
    }

    private UUID resolveHospitalForPatient(AdminSignupRequest request) {
        // Try JWT first (receptionist registering at their facility)
        UUID hospitalId = extractHospitalIdFromJwt();
        if (hospitalId == null) {
            hospitalId = request.getHospitalId();
        }
        log.info("[RECEPTION/ADMIN] Resolved hospitalId for patient registration: {}", hospitalId);

        // Patients can exist system-wide without a hospital — like in Epic/Cerner
        // models where patients visit multiple facilities. If a hospital IS provided,
        // we validate it exists; otherwise the patient gets a global (null-hospital) assignment.
        if (hospitalId != null) {
            final UUID resolvedHospitalId = hospitalId;
            hospitalRepository.findById(resolvedHospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_PREFIX + resolvedHospitalId));
        }
        return hospitalId;
    }

    private UUID resolveHospitalForStaff(AdminSignupRequest request, Set<String> roleNames) {
        final boolean isSuperAdmin = roleNames.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(r -> r.equals("SUPER_ADMIN") || r.equals(ROLE_SUPER_ADMIN));

        if (isSuperAdmin) {
            return null;
        }

        UUID provided = request.getHospitalId();
        if (provided == null) {
            throw new BusinessException("Hospital must be provided for non-SUPER_ADMIN staff/admin roles.");
        }
        return hospitalRepository.findById(provided)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_PREFIX + provided))
                .getId();
    }

        private void recordUserCreationAudit(User actor, User created, List<UserRoleHospitalAssignment> assignments) {
        if (created == null) {
            return;
        }
        try {
            UUID actorId = actor != null ? actor.getId() : created.getId();
            UUID assignmentId = Optional.ofNullable(actor)
                .map(this::resolveActorAssignmentId)
                .orElseGet(() -> assignments.stream()
                    .filter(Objects::nonNull)
                    .map(UserRoleHospitalAssignment::getId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null));

            Map<String, Object> details = new HashMap<>();
            details.put("createdUserId", created.getId());
            details.put("actorId", actorId);
            details.put("roles", assignments.stream()
                .filter(Objects::nonNull)
                .map(UserRoleHospitalAssignment::getRole)
                .filter(Objects::nonNull)
                .map(Role::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList());

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(actorId)
                .assignmentId(assignmentId)
                .userName(resolveUserDisplayName(actor != null ? actor : created))
                .eventType(AuditEventType.USER_CREATE)
                .eventDescription("New user account created")
                .resourceId(created.getId() != null ? created.getId().toString() : null)
                .resourceName(resolveUserDisplayName(created))
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build();

            auditEventLogService.logEvent(auditEvent);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to record user creation audit for user '{}': {}", created.getId(), ex.getMessage());
        }
    }

    private void recordUserDeletionAudit(User deletedUser) {
        if (deletedUser == null) {
            return;
        }
        try {
            Optional<User> actorOpt = resolveCurrentActor();
            User actor = actorOpt.orElse(deletedUser);
            UUID assignmentId = actorOpt
                .map(this::resolveActorAssignmentId)
                .orElseGet(() -> assignmentRepository
                    .findFirstByUserIdAndActiveTrue(deletedUser.getId())
                    .map(UserRoleHospitalAssignment::getId)
                    .orElse(null));

            Map<String, Object> details = new HashMap<>();
            details.put("softDelete", true);
            details.put("targetUserId", deletedUser.getId());

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(actor.getId())
                .assignmentId(assignmentId)
                .userName(resolveUserDisplayName(actor))
                .eventType(AuditEventType.USER_DELETE)
                .eventDescription("User account soft-deleted")
                .resourceId(deletedUser.getId().toString())
                .resourceName(resolveUserDisplayName(deletedUser))
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build();

            auditEventLogService.logEvent(auditEvent);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to record user deletion audit for '{}': {}", deletedUser.getId(), ex.getMessage());
        }
    }

    private Optional<User> resolveCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        String principal = authentication.getName();
        if (principal == null || principal.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(principal, principal, null);
    }

    private UUID resolveActorAssignmentId(User actor) {
        if (actor == null) {
            return null;
        }
        return assignmentRepository.findFirstByUserIdAndActiveTrue(actor.getId())
            .map(UserRoleHospitalAssignment::getId)
            .orElse(null);
    }

    private String resolveUserDisplayName(User user) {
        if (user == null) {
            return "Unknown User";
        }
        String first = Optional.ofNullable(user.getFirstName()).map(String::trim).orElse("");
        String last = Optional.ofNullable(user.getLastName()).map(String::trim).orElse("");
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) {
            return full;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Unknown User";
    }

    private UserRoleHospitalAssignment ensureAssignmentSmart(UUID userId, Role role, UUID hospitalId, boolean active) {
        UUID roleId = role.getId();

        // avoid duplicates
        if (!assignmentService.isRoleAlreadyAssigned(userId, hospitalId, roleId)) {
            assignmentService.assignRole(UserRoleHospitalAssignmentRequestDTO.builder()
                    .userId(userId)
                    .roleId(roleId)
                    .hospitalId(hospitalId) // may be null for global
                    .active(active) // 👈 PATIENT => false
                    .build());
        }

        // fetch the assignment we now expect to exist
        UserRoleHospitalAssignment assignment = assignmentRepository
                .findFirstByUserIdAndHospitalIdAndRoleId(userId, hospitalId, roleId)
                .orElseThrow(() -> new IllegalStateException("Assignment was not persisted as expected"));

        // Admin-register creates assignments that should be immediately active.
        // enforceRoleScopeConstraints may have forced active=false for the
        // email-confirmation workflow, but admin-created users are pre-approved.
        if (active && !Boolean.TRUE.equals(assignment.getActive())) {
            assignment.setActive(true);
            assignmentRepository.save(assignment);
        }

        return assignment;
    }

    private UUID extractHospitalIdFromJwt() {
        try {
            String jwt = JwtTokenHolder.getToken();
            if (jwt == null || jwt.isBlank())
                return null;

            io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                    .verifyWith(jwtTokenProvider.getSecretKey())
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();

            Object hId = claims.get("primaryHospitalId");
            if (hId == null) {
                hId = claims.get("hospitalId");
            }
            if (hId instanceof String s && !s.isBlank()) {
                return UUID.fromString(s);
            }
        } catch (RuntimeException e) {
            log.warn("[JWT] Failed to extract hospitalId from JWT claims", e);
        }
        return null;
    }


    @Transactional(readOnly = true)
    public long getUserDistinctRoleCount(UUID userId) {
        return assignmentRepository.countDistinctRolesByUserId(userId);
    }

    @Transactional(readOnly = true)
    public long getUserDistinctActiveRoleCount(UUID userId) {
        return assignmentRepository.countDistinctActiveRolesByUserId(userId);
    }

    private JobTitle mapRoleCodeToJobTitle(String roleCode) {
        if (roleCode == null)
            return null;
        String code = roleCode.toUpperCase(Locale.ROOT);

        switch (code) {
            case ROLE_HOSPITAL_ADMIN:
                return JobTitle.HOSPITAL_ADMIN;
            case ROLE_DOCTOR:
                return JobTitle.DOCTOR;
            case ROLE_NURSE:
                return JobTitle.NURSE;
            case ROLE_LAB_SCIENTIST:
                return JobTitle.LAB_TECHNICIAN;
            case ROLE_PHARMACIST:
                return JobTitle.PHARMACIST;
            default:
                return null;
        }
    }

    /*
     * --------------------------------
     * Queries
     * --------------------------------
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponseDTO getUserById(UUID id) {
        User user = userRepository.findByIdWithRolesAndProfiles(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + id));
        Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(user);
        return userMapper.toResponseDTO(user, assignments);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryDTO> getAllUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<User> users = userRepository.findAllPaged(pageable);
        return users.map(userMapper::toSummaryDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryDTO> searchUsers(String name, String role, String email, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<User> users = userRepository.searchUsers(name, role, email, pageable);
        return users.map(userMapper::toSummaryDTO);
    }

    /*
     * --------------------------------
     * Mutations
     * --------------------------------
     */
    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + id));

        user.setDeleted(true);
        user.setActive(false);
        userRepository.save(user);
        assignmentService.deleteAllAssignmentsForUser(id);

        // Deactivate all Staff records linked to the deleted user
        List<Staff> staffRecords = staffRepository.findByUserId(id);
        for (Staff staff : staffRecords) {
            if (staff.isActive()) {
                staff.setActive(false);
                staffRepository.save(staff);
                log.debug("🔒 Deactivated staff record {} for deleted user {}", staff.getId(), id);
            }
        }

        log.info("🗑️ User soft-deleted with ID: {}", id);
        recordUserDeletionAudit(user);
    }

    @Override
    @Transactional
    public void restoreUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + id));

        user.setDeleted(false);
        user.setActive(true);
        userRepository.save(user);

        // Reactivate Staff records that were deactivated when the user was deleted
        List<Staff> staffRecords = staffRepository.findByUserId(id);
        for (Staff staff : staffRecords) {
            if (!staff.isActive()) {
                staff.setActive(true);
                staffRepository.save(staff);
                log.debug("♻️ Reactivated staff record {} for restored user {}", staff.getId(), id);
            }
        }

        log.info("♻️ User restored with ID: {}", id);

        String displayName = UserDisplayUtil.resolveDisplayName(user);
        try {
            emailService.sendAccountRestoredEmail(user.getEmail(), displayName);
        } catch (Exception e) {
            log.warn("⚠️ Failed to send account-restored notification to '{}': {}",
                    user.getEmail(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID id, UpdateUserRequestDTO dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + id));

        // ── Merge-preserve: only overwrite fields that are explicitly provided ──

        if (hasText(dto.getUsername())) {
            user.setUsername(dto.getUsername());
        }
        if (hasText(dto.getEmail())) {
            user.setEmail(dto.getEmail());
        }
        if (hasText(dto.getFirstName())) {
            user.setFirstName(dto.getFirstName());
        }
        if (hasText(dto.getLastName())) {
            user.setLastName(dto.getLastName());
        }
        if (hasText(dto.getPhoneNumber())) {
            user.setPhoneNumber(dto.getPhoneNumber());
        }
        if (dto.getActive() != null) {
            user.setActive(dto.getActive());
        }

        // Password: only update if a new non-blank password is explicitly provided
        // and it differs from the current hash.
        if (hasText(dto.getPassword())
                && !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            String encoded = passwordEncoder.encode(dto.getPassword());
            LocalDateTime passwordSetAt = LocalDateTime.now();
            user.setPasswordHash(encoded);
            user.setPasswordChangedAt(passwordSetAt);
            user.setPasswordRotationWarningAt(null);
            user.setPasswordRotationForcedAt(null);
            user.setForcePasswordChange(false);
        }

        User updated = userRepository.save(user);

        UserRoleHospitalAssignment assignment = assignmentRepository
                .findFirstByUserIdAndActiveTrue(updated.getId())
                .orElse(null);

        Optional<User> actorOpt = resolveCurrentActor();
        User actor = actorOpt.orElse(updated);
        UUID actorAssignmentId = actorOpt
                .map(this::resolveActorAssignmentId)
                .orElse(assignment != null ? assignment.getId() : null);

        AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(actor.getId())
                .assignmentId(actorAssignmentId)
                .userName(resolveUserDisplayName(actor))
                .eventType(AuditEventType.USER_UPDATE)
                .eventDescription("User profile updated")
                .details("Profile updated for user: " + updated.getUsername())
                .resourceId(updated.getId().toString())
                .resourceName(resolveUserDisplayName(updated))
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .ipAddress(null)
                .build();

        auditEventLogService.logEvent(auditEvent);

        Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(updated);
        return userMapper.toResponseDTO(updated, assignments);
    }

    /** True when the string is non-null and non-blank. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /*
     * --------------------------------
     * Email Verification
     * --------------------------------
     */
    @Override
    @Transactional
    public boolean verifyEmail(String email, String token) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        if (user.getActivationToken() == null
                || !token.equals(user.getActivationToken())
                || user.getActivationTokenExpiresAt() == null
                || user.getActivationTokenExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("⚠️ Invalid or expired activation token for: {}", email);
            return false;
        }

        user.setActive(true);
        user.setActivationToken(null);
        user.setActivationTokenExpiresAt(null);
        userRepository.save(user);

        // Activate the Patient entity to match the now-verified User
        patientRepository.findByUserId(user.getId()).ifPresent(patient -> {
            if (!patient.isActive()) {
                patient.setActive(true);
                patientRepository.save(patient);
            }
        });

        log.info("✅ Email verified: {}", user.getEmail());
        return true;
    }

    /*
     * --------------------------------
     * Helpers
     * --------------------------------
     */
    private Role getRoleByCode(String code) {
        return roleRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + code));
    }

    /** Create a global user-role link if it doesn't already exist. */
    private void addUserRoleIfAbsent(UUID userId, UUID roleId) {
        UserRoleId key = new UserRoleId(userId, roleId);
        if (!userRoleRepository.existsById(key)) {
            UserRole link = UserRole.builder()
                    .id(key)
                    .user(userRepository.getReferenceById(userId))
                    .role(roleRepository.getReferenceById(roleId))
                    .build();
            userRoleRepository.save(link);
        }
    }

    private void createAssignmentIfAbsent(UUID userId, UUID roleId, UUID hospitalId) {
        if (!assignmentService.isRoleAlreadyAssigned(userId, hospitalId, roleId)) {
            assignmentService.assignRole(UserRoleHospitalAssignmentRequestDTO.builder()
                    .userId(userId)
                    .roleId(roleId)
                    .hospitalId(hospitalId)
                    .active(true)
                    .build());
        }
    }

    private Hospital getDefaultHospital() {
        return hospitalRepository.findByCodeIgnoreCase("Hospital Yalgado Ouedraogo")
                .orElseThrow(() -> new ResourceNotFoundException("Default hospital not found"));
    }

    /** Map job title from request or role codes */
    private JobTitle coerceJobTitle(JobTitle requestedJobTitle, Set<Role> roles) {
        // 1) If caller provided an enum, use it
        if (requestedJobTitle != null) {
            return requestedJobTitle;
        }
        // 2) Derive from role codes
        for (Role role : roles) {
            JobTitle jt = mapRoleCodeToJobTitle(role.getCode());
            if (jt != null) {
                return jt;
            }
        }
        return null;
    }

    /*
     * --------------------------------
     * Profile Image Management
     * --------------------------------
     */

    @Override
    public UUID getUserIdByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));
        return user.getId();
    }

    @Override
    @Transactional
    public void changeOwnPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + userId));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setForcePasswordChange(false);
        user.setPasswordRotationWarningAt(null);
        user.setPasswordRotationForcedAt(null);
        userRepository.save(user);
        log.info("🔑 [CHANGE-PWD] Password updated and forcePasswordChange cleared for user={}", userId);
    }

    @Override
    @Transactional
    public void updateProfileImage(UUID userId, String imageUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String oldImageUrl = user.getProfileImageUrl();
        user.setProfileImageUrl(imageUrl);
        userRepository.save(user);

        log.info("Profile image updated for user {}: {} -> {}", userId, oldImageUrl, imageUrl);
    }

}
