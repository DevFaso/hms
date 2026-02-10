package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import com.example.hms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import com.example.hms.config.BootstrapProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

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
            Role superAdmin = getRoleByCode("ROLE_SUPER_ADMIN");
            addUserRoleIfAbsent(saved.getId(), superAdmin.getId());
            createAssignmentIfAbsent(saved.getId(), superAdmin.getId(), null);
            log.info("🎉 First user auto-assigned ROLE_SUPER_ADMIN (global).");
        } else {
            Role patient = getRoleByCode("ROLE_PATIENT");
            addUserRoleIfAbsent(saved.getId(), patient.getId());

            UUID targetHospitalId = dto.getHospitalId();
            if (targetHospitalId == null) {
                try {
                    Hospital defaultHospital = getDefaultHospital();
                    targetHospitalId = defaultHospital.getId();
                } catch (Exception e) {
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
        if (expectedToken != null && !expectedToken.isBlank()) {
            if (request.getBootstrapToken() == null || !expectedToken.equals(request.getBootstrapToken())) {
                return BootstrapSignupResponse.builder()
                        .success(false)
                        .message("Invalid bootstrap token")
                        .username(null)
                        .email(null)
                        .build();
            }
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

        Role superAdmin = getRoleByCode("ROLE_SUPER_ADMIN");
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
                .anyMatch(r -> r.equals("PATIENT") || r.equals("ROLE_PATIENT"));

        // ---- 1) Resolve hospital for this registration ----
        UUID staffContextHospitalId = null;
        if (isPatient) {
            staffContextHospitalId = extractHospitalIdFromJwt();
            if (staffContextHospitalId == null) {
                staffContextHospitalId = request.getHospitalId();
            }
            log.info("[RECEPTION/ADMIN] Resolved hospitalId for patient registration: {}", staffContextHospitalId);
            if (staffContextHospitalId == null) {
                throw new BusinessException("Hospital must be provided when staff registers a patient.");
            }
            final UUID resolvedHospitalId = staffContextHospitalId;
            hospitalRepository.findById(resolvedHospitalId)
                    .orElseThrow(
                            () -> new ResourceNotFoundException("Hospital not found with ID: " + resolvedHospitalId));
        } else {
            final boolean isSuperAdmin = roleNames.stream()
                    .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                    .anyMatch(r -> r.equals("SUPER_ADMIN") || r.equals("ROLE_SUPER_ADMIN"));

            if (!isSuperAdmin) {
                UUID provided = request.getHospitalId();
                if (provided == null) {
                    throw new BusinessException("Hospital must be provided for non-SUPER_ADMIN staff/admin roles.");
                }
                final UUID resolvedHospitalId = provided;
                staffContextHospitalId = hospitalRepository.findById(resolvedHospitalId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Hospital not found with ID: " + resolvedHospitalId))
                        .getId();
            } else {
                staffContextHospitalId = null; // global assignment allowed for SUPER_ADMIN
            }
        }

        // ---- 2) Resolve Roles ----
        final Set<Role> roles = roleNames.stream()
                .map(name -> {
                    final String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
                    final String code = normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
                    return getRoleByCode(code);
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final boolean requiresStaff = roles.stream().anyMatch(role -> switch (role.getCode()) {
            case "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_LAB_SCIENTIST", "ROLE_PHARMACIST", "ROLE_HOSPITAL_ADMIN" -> true;
            default -> false;
        });

        // ---- 3) Resolve/Create User ----
        final String lic = requiresStaff
                ? Optional.ofNullable(request.getLicenseNumber()).map(String::trim).orElse("")
                : "";
        if (requiresStaff && lic.isEmpty()) {
            throw new IllegalArgumentException("licenseNumber is required for medical/admin roles.");
        }

    Optional<User> existingByIdentity = userRepository
        .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(username, email, phone);

        Optional<User> existingByLicense = requiresStaff
                ? staffRepository.findUserIdByLicense(lic).flatMap(userRepository::findById)
                : Optional.empty();

    final boolean newUserCreated = existingByIdentity.isEmpty() && existingByLicense.isEmpty();

    final User user = existingByIdentity.or(() -> existingByLicense).orElseGet(() -> {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        LocalDateTime passwordSetAt = LocalDateTime.now();
        u.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        u.setFirstName(request.getFirstName());
        u.setLastName(request.getLastName());
        u.setPhoneNumber(phone);
        u.setActive(true);
        if (roles.stream().anyMatch(r -> "ROLE_PATIENT".equalsIgnoreCase(r.getCode()))
                || Boolean.TRUE.equals(request.getForcePasswordChange())) {
            u.setForcePasswordChange(true);
        }
        u.setCreatedAt(passwordSetAt);
        u.setPasswordChangedAt(passwordSetAt);
        u.setPasswordRotationWarningAt(null);
        u.setPasswordRotationForcedAt(Boolean.TRUE.equals(request.getForcePasswordChange()) ? passwordSetAt : null);
        return userRepository.save(u);
    });

    if ((existingByIdentity.isPresent() || existingByLicense.isPresent())
            && Boolean.TRUE.equals(request.getForcePasswordChange())) {
        user.setForcePasswordChange(true);
        user.setPasswordRotationForcedAt(LocalDateTime.now());
    }

    // ---- 4) Ensure roles + hospital-scoped assignments ----
    final List<UserRoleHospitalAssignment> ensuredAssignments = new ArrayList<>();
    Optional<User> actorOpt = resolveCurrentActor();
    User actor = actorOpt.orElse(user);
    for (Role r : roles) {
    // always ensure legacy user_roles link exists
    addUserRoleIfAbsent(user.getId(), r.getId());

    // PATIENT assignments must not be active
    boolean isPatientRole = "ROLE_PATIENT".equalsIgnoreCase(r.getCode()) ||
        "PATIENT".equalsIgnoreCase(r.getName());

    log.info("[ASSIGN] {} -> user={} hospitalId={} active={}",
        r.getCode(), user.getUsername(), staffContextHospitalId, !isPatientRole);

    ensuredAssignments.add(
        ensureAssignmentSmart(user.getId(), r, staffContextHospitalId, !isPatientRole));
    }

    if (newUserCreated) {
    recordUserCreationAudit(actor, user, ensuredAssignments);
    }

    // ---- 5) Upsert Staff when needed ----
    if (requiresStaff && staffContextHospitalId != null) {
    final UUID resolvedHospitalId = staffContextHospitalId;
    final Hospital hospital = hospitalRepository.findById(resolvedHospitalId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Hospital not found with ID: " + resolvedHospitalId));

    staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
        .map(Staff::getLicenseNumber)
        .ifPresent(existingLicForHospital -> {
            if (!existingLicForHospital.equalsIgnoreCase(lic)) {
            throw new ConflictException(
                "License number mismatch for this user in this hospital; expected "
                    + existingLicForHospital);
            }
        });

    final UserRoleHospitalAssignment staffAssignment = ensuredAssignments.stream()
        .filter(a -> "ROLE_HOSPITAL_ADMIN".equalsIgnoreCase(a.getRole().getCode()))
        .findFirst()
        .orElse(ensuredAssignments.get(0));

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
    if (staff.getStartDate() == null)
        staff.setStartDate(LocalDate.now());

    final String fn = user.getFirstName() == null ? "" : user.getFirstName().trim();
    final String ln = user.getLastName() == null ? "" : user.getLastName().trim();
    final String fullName = (fn + " " + ln).trim();
    if (!fullName.isEmpty())
        staff.setName(fullName);

    staffRepository.save(staff);
    }

    // ---- 6) Reload + map ----
    final User reloaded = userRepository.findByIdWithRolesAndProfiles(user.getId())
        .orElseThrow(() -> new IllegalStateException("User disappeared after save"));
    final Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(reloaded);

    final long roleCount = getUserDistinctRoleCount(reloaded.getId());
    final long activeRoleCount = getUserDistinctActiveRoleCount(reloaded.getId());
    log.info("Registered user {} with {} distinct roles ({} active).", reloaded.getId(), roleCount,
        activeRoleCount);

    final UserResponseDTO dto = userMapper.toResponseDTO(reloaded, assignments);
    dto.setRoleCount(Math.toIntExact(roleCount));
    return dto;
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
        } catch (Exception ex) {
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
        } catch (Exception ex) {
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

    private void fillHospitalOnPatientDto(Patient entity, PatientResponseDTO dto) {
        // If your Patient entity has no hospital relation, derive from URHA
        UUID userId = entity.getUser() != null ? entity.getUser().getId() : null;
        if (userId == null)
            return;

        assignmentRepository.findLatestPatientAssignment(userId).ifPresent(a -> {
            var h = a.getHospital();
            if (h != null) {
                dto.setPrimaryHospitalId(h.getId());
                dto.setPrimaryHospitalName(h.getName());
                dto.setPrimaryHospitalAddress(h.getAddress());
                // for backward-compat if you keep both fields
                dto.setHospitalId(h.getId());
                dto.setHospitalName(h.getName());
            }
        });
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

    private UUID extractHospitalIdFromCurrentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null)
            return null;

        // principal may be a Spring Security User or a String
        String principal = auth.getName();
        var userOpt = userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                principal, principal, null);
        var user = userOpt.orElse(null);
        if (user == null)
            return null;

        // Prefer staff-ish roles, then fall back to any active hospital
        Set<String> staffCodes = Set.of(
                "ROLE_RECEPTIONIST", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR",
                "ROLE_NURSE", "ROLE_PHARMACIST", "ROLE_LAB_SCIENTIST");

        return assignmentRepository.findByUser_IdAndActiveTrue(user.getId()).stream()
                .filter(a -> a.getHospital() != null)
                .sorted(java.util.Comparator.comparing(UserRoleHospitalAssignment::getCreatedAt).reversed())
                .filter(a -> a.getRole() != null && staffCodes.contains(a.getRole().getCode()))
                .map(a -> a.getHospital().getId())
                .findFirst()
                .orElseGet(() -> assignmentRepository.findByUser_IdAndActiveTrue(user.getId()).stream()
                        .filter(a -> a.getHospital() != null)
                        .findFirst()
                        .map(a -> a.getHospital().getId())
                        .orElse(null));
    }

    private UUID resolveHospitalForPatient(AdminSignupRequest request) {
        // 1) Try to infer from the current (staff) user’s active hospital assignment
        UUID fromContext = extractHospitalIdFromCurrentUser();
        if (fromContext != null)
            return fromContext;

        // 2) Otherwise honor an explicit ID if provided
        if (request.getHospitalId() != null)
            return request.getHospitalId();

        // 3) Or look up by name if provided
        if (request.getHospitalName() != null && !request.getHospitalName().isBlank()) {
            return hospitalRepository.findByName(request.getHospitalName())
                    .map(Hospital::getId)
                    .orElse(null);
        }

        // 4) Give up (caller will throw BusinessException)
        return null;
    }

    private UUID extractHospitalIdFromJwt() {
        try {
            String jwt = resolveCurrentJwt();
            if (jwt == null || jwt.isBlank())
                return null;

            io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parserBuilder()
                    .setSigningKey(jwtTokenProvider.getSecretKey())
                    .build()
                    .parseClaimsJws(jwt)
                    .getBody();

            Object hId = claims.get("hospitalId");
            if (hId instanceof String s && !s.isBlank()) {
                return UUID.fromString(s);
            }
        } catch (Exception e) {
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
            case "ROLE_HOSPITAL_ADMIN":
                return JobTitle.HOSPITAL_ADMIN;
            case "ROLE_DOCTOR":
                return JobTitle.DOCTOR;
            case "ROLE_NURSE":
                return JobTitle.NURSE;
            case "ROLE_LAB_SCIENTIST":
                return JobTitle.LAB_TECHNICIAN;
            case "ROLE_PHARMACIST":
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
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));
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
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

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
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + id));

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
