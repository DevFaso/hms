package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.Hospital;
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
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import com.example.hms.config.BootstrapProperties;
import lombok.extern.slf4j.Slf4j;
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
    private final BootstrapProperties bootstrapProperties;
    private final JwtTokenProvider jwtTokenProvider;

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
                "https://bitnesttechs.com/verify?email=%s&token=%s",
                saved.getEmail(), saved.getActivationToken());
        emailService.sendActivationEmail(saved.getEmail(), activationLink);

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

        // Optional shared secret enforcement (property-based for testability)
        String expectedToken = bootstrapProperties.getToken();
        if (expectedToken != null && !expectedToken.isBlank()
                && (request.getBootstrapToken() == null || !expectedToken.equals(request.getBootstrapToken()))) {
            return BootstrapSignupResponse.builder()
                    .success(false)
                    .message("Invalid bootstrap token")
                    .username(null)
                    .email(null)
                    .build();
        }

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

        // Audit event
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .assignmentId(null)
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("First system user bootstrap (Super Admin)")
                .details("Bootstrap user created: " + user.getUsername())
                .resourceId(user.getId().toString())
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .ipAddress(null)
                .build());

        return BootstrapSignupResponse.builder()
                .success(true)
                .message("Bootstrap Super Admin created")
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

        final Set<String> roleNames = Optional.ofNullable(request.getRoleNames())
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("At least one role must be provided."));

        final boolean isPatient = roleNames.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(r -> r.equals("PATIENT") || r.equals(ROLE_PATIENT));

        // ---- 1) Resolve hospital for this registration ----
        UUID staffContextHospitalId = resolveHospitalForRegistration(request, roleNames, isPatient);

        // ---- 2) Resolve Roles ----
        final Set<Role> roles = roleNames.stream()
                .map(this::resolveRoleByName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final boolean requiresStaff = roles.stream().anyMatch(role -> switch (role.getCode()) {
            case ROLE_DOCTOR, ROLE_NURSE, ROLE_LAB_SCIENTIST, ROLE_PHARMACIST, ROLE_HOSPITAL_ADMIN -> true;
            default -> false;
        });

        // ---- 3) Resolve/Create User ----
        final String lic = resolveLicenseNumber(request, requiresStaff);

    Optional<User> existingByIdentity = userRepository
        .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(username, email, phone);

        Optional<User> existingByLicense = requiresStaff
                ? staffRepository.findUserIdByLicense(lic).flatMap(userRepository::findById)
                : Optional.empty();

    final boolean newUserCreated = existingByIdentity.isEmpty() && existingByLicense.isEmpty();

    final User user = existingByIdentity.or(() -> existingByLicense)
        .orElseGet(() -> createNewUser(request, username, email, phone, roles));

    if ((existingByIdentity.isPresent() || existingByLicense.isPresent())
            && Boolean.TRUE.equals(request.getForcePasswordChange())) {
        user.setForcePasswordChange(true);
        user.setPasswordRotationForcedAt(LocalDateTime.now());
    }

    // ---- 4) Ensure roles + hospital-scoped assignments ----
    final List<UserRoleHospitalAssignment> ensuredAssignments =
        ensureRolesAndAssignments(user, roles, staffContextHospitalId);

    if (newUserCreated) {
    User actor = resolveCurrentActor().orElse(user);
    recordUserCreationAudit(actor, user, ensuredAssignments);
    }

    // ---- 5) Upsert Staff when needed ----
    if (requiresStaff && staffContextHospitalId != null) {
        upsertStaff(user, staffContextHospitalId, lic, ensuredAssignments, request, roles);
    }

    // ---- 6) Reload + map ----
    final User reloaded = userRepository.findByIdWithRolesAndProfiles(user.getId())
        .orElseThrow(() -> new IllegalStateException("User disappeared after save"));
    final Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(reloaded);

    final long roleCount = assignmentRepository.countDistinctRolesByUserId(reloaded.getId());
    final long activeRoleCount = assignmentRepository.countDistinctActiveRolesByUserId(reloaded.getId());
    log.info("Registered user {} with {} distinct roles ({} active).", reloaded.getId(), roleCount,
        activeRoleCount);

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
        u.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        u.setFirstName(request.getFirstName());
        u.setLastName(request.getLastName());
        u.setPhoneNumber(phone);
        u.setActive(true);
        if (roles.stream().anyMatch(r -> ROLE_PATIENT.equalsIgnoreCase(r.getCode()))
                || Boolean.TRUE.equals(request.getForcePasswordChange())) {
            u.setForcePasswordChange(true);
        }
        u.setCreatedAt(passwordSetAt);
        u.setPasswordChangedAt(passwordSetAt);
        u.setPasswordRotationWarningAt(null);
        u.setPasswordRotationForcedAt(Boolean.TRUE.equals(request.getForcePasswordChange()) ? passwordSetAt : null);
        return userRepository.save(u);
    }

        private UUID resolveHospitalForRegistration(AdminSignupRequest request, Set<String> roleNames, boolean isPatient) {
        if (isPatient) {
            return resolveHospitalForPatient(request);
        }
        return resolveHospitalForStaff(request, roleNames);
    }

    private UUID resolveHospitalForPatient(AdminSignupRequest request) {
        UUID hospitalId = extractHospitalIdFromJwt();
        if (hospitalId == null) {
            hospitalId = request.getHospitalId();
        }
        log.info("[RECEPTION/ADMIN] Resolved hospitalId for patient registration: {}", hospitalId);
        if (hospitalId == null) {
            throw new BusinessException("Hospital must be provided when staff registers a patient.");
        }
        final UUID resolvedHospitalId = hospitalId;
        hospitalRepository.findById(resolvedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_PREFIX + resolvedHospitalId));
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
        return assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(userId, hospitalId, roleId)
                .orElseThrow(() -> new IllegalStateException("Assignment was not persisted as expected"));
    }

    private UUID extractHospitalIdFromJwt() {
        try {
            String jwt = resolveCurrentJwt();
            if (jwt == null || jwt.isBlank())
                return null;

            io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                    .verifyWith(jwtTokenProvider.getSecretKey())
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();

            Object hId = claims.get("hospitalId");
            if (hId instanceof String s && !s.isBlank()) {
                return UUID.fromString(s);
            }
        } catch (RuntimeException e) {
            log.warn("[JWT] Failed to extract hospitalId from JWT claims", e);
        }
        return null;
    }

    private String resolveCurrentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object credentials = authentication.getCredentials();
        if (credentials instanceof String token && !token.isBlank()) {
            return token;
        }

        if (credentials != null) {
            String value = credentials.toString();
            return value.isBlank() ? null : value;
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
    public Page<UserResponseDTO> getAllUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return userRepository.findAllActive(pageable).map(userMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponseDTO> searchUsers(String name, String role, String email, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return userRepository.searchUsers(name, role, email, pageable).map(userMapper::toDto);
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

        log.info("🗑️ User soft-deleted with ID: {}", id);
        recordUserDeletionAudit(user);
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID id, UserRequestDTO dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_PREFIX + id));

        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setPhoneNumber(dto.getPhoneNumber());
        user.setActive(dto.getActive());

        if (dto.getPassword() != null && !dto.getPassword().isBlank()
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

        return userMapper.toDto(updated);
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
    public void updateProfileImage(UUID userId, String imageUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        String oldImageUrl = user.getProfileImageUrl();
        user.setProfileImageUrl(imageUrl);
        userRepository.save(user);

        log.info("Profile image updated for user {}: {} -> {}", userId, oldImageUrl, imageUrl);
    }

}
