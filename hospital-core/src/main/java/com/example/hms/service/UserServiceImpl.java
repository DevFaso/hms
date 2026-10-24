package com.example.hms.service;

import static com.example.hms.config.SecurityConstants.ROLE_PREFIX;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.utility.TransactionCallbacks;
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
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.service.support.UserAccountAccess.DirectoryScope;
import com.example.hms.utility.MessageUtil;
import org.springframework.security.access.AccessDeniedException;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private static final String HOSPITAL_NOT_FOUND_KEY = "hospital.notFound";
    private static final String USER_NOT_FOUND_KEY = "user.notFound";

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_PHARMACIST = "ROLE_PHARMACIST";
    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";


    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleHospitalAssignmentService assignmentService;
    private final EmailService emailService;
    private final AssignmentLinkService assignmentLinkService;
    private final com.example.hms.security.LoginAttemptService loginAttemptService;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditEventLogService;
    private final PasswordHistoryService passwordHistoryService;
    private final StaffRepository staffRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    private final UserAccountAccess accountAccess;
    /** Role/facility compatibility (provider plan §3.2), checked before anything is saved. */
    private final com.example.hms.security.provider.FacilityAssignmentGuard facilityAssignmentGuard;
    /** Tells whether generated credentials can reach an e-mail-less patient by SMS. */
    private final SmsService smsService;

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

        // saveAndFlush forces the INSERT to hit the DB before any follow-up
        // writes — see createNewUser() for the full rationale.
        User saved = userRepository.saveAndFlush(user);

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

        final String activationLink = com.example.hms.utility.ActivationLinks.build(
                frontendBaseUrl, saved.getEmail(), saved.getActivationToken());
        try {
            emailService.sendActivationEmail(saved.getEmail(), activationLink);
        } catch (Exception e) {
            log.warn("⚠️ Failed to send verification email to '{}': {}. "
                    + "Patient can request a new verification email later.",
                    saved.getEmail(), e.getMessage());
        }

        User reloaded = userRepository.findByIdWithRolesAndProfiles(saved.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "User disappeared after save (id=" + saved.getId()
                                + ", username=" + saved.getUsername()
                                + "). With saveAndFlush in place this should be unreachable; "
                                + "if you see it, check for a concurrent delete or a "
                                + "@Transactional propagation mistake."));
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
                    .message(com.example.hms.utility.MessageUtil.resolveOrRaw("auth.r7.bootstrapNotAllowed"))
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
        user.setPhoneNumber(normalizePhone(request.getPhoneNumber(), null));
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
                .message(com.example.hms.utility.MessageUtil.resolveOrRaw("auth.r7.bootstrapCreated"))
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
        final String email = request.getEmail() == null || request.getEmail().isBlank()
                ? null : request.getEmail().trim().toLowerCase(Locale.ROOT);
        request.setEmail(email);
        // Username is optional: blank means "generate one" (createNewUser). A
        // blank username must not take part in the identity lookups below.
        final String username = request.getUsername() == null || request.getUsername().isBlank()
                ? null : request.getUsername().trim();
        request.setUsername(username);

        // Determine roles early so patient-specific logic can relax duplicate checks.
        final Set<String> roleNames = Optional.ofNullable(request.getRoleNames())
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.roles.required")));

        final boolean isPatient = roleNames.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(r -> r.equals("PATIENT") || r.equals(ROLE_PATIENT));
        // QA D329: staff onboarding accepted the patient role next to staff
        // roles. The account was then handled as a patient: no welcome mail,
        // no staff record. A patient is registered on its own from the
        // patient form; a staff member who is also a patient gets a separate
        // patient registration.
        if (isPatient && roleNames.stream()
                .map(r -> r == null ? "" : r.trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", ""))
                .filter(r -> !r.isEmpty())
                .distinct()
                .anyMatch(r -> !r.equals("PATIENT"))) {
            throw new BusinessException("users.roles.patientWithStaffRoles");
        }

        // ---- 0) Who may grant these roles, before anything about the request
        // is looked up, so a refused caller learns nothing from a duplicate
        // check either. The hospital half of the check is at step 1.
        final UserAccountAccess.Grant grant;
        try {
            grant = accountAccess.requireMayGrant(roleNames);
        } catch (AccessDeniedException denied) {
            throw refusedGrant(roleNames, denied);
        }

        // Normalised only once the caller may grant these roles: a refused
        // caller gets the 403, never a phone-format 400.
        final String phone = normalizeRegistrationPhone(request);
        request.setPhoneNumber(phone);

        // ---- 0a) Duplicate checks ----
        if (isPatient) {
            // Epic-style: patients can span multiple hospitals.
            // Only reject if the same patient is already at the *target* hospital.
            checkPatientAlreadyAtHospital(username, email, phone, request);
        } else {
            // Phone-first relaxation applies to PATIENTS only — staff and admin
            // accounts still need an email (welcome mail, credential delivery).
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.email.requiredForStaff"));
            }
            // Non-patient roles: strict global uniqueness.
            // Each identifier can be held by a SOFT-DELETED account: the row
            // keeps its unique email/username/phone, so "already registered"
            // used to point at a user nobody could see - the list filtered
            // deleted rows out while these checks counted them. The message
            // now says which case it is, and the deleted view + Restore
            // button in the user list are the way out.
            if (username != null) {
                userRepository.findByUsername(username).ifPresent(existing -> {
                    throw conflictFor("username", username, existing);
                });
            }
            if (email != null) {
                userRepository.findByEmail(email).ifPresent(existing -> {
                    throw conflictFor("email", email, existing);
                });
            }
            if (phone != null && !phone.isBlank()) {
                findByPhoneInAnyForm(phone).ifPresent(existing -> {
                    throw conflictFor("phone", phone, existing);
                });
            }
        }

        // ---- 1) Resolve hospital for this registration, and hold the grant to it ----
        final UUID staffContextHospitalId;
        try {
            staffContextHospitalId = grant.requireAt(resolveHospitalForRegistration(request, roleNames, isPatient));
        } catch (AccessDeniedException denied) {
            throw refusedGrant(roleNames, denied);
        }
        // Allowed to grant here, and the roles must also be ones this kind of
        // facility takes: a 400 before the account or any assignment exists.
        // The one-kind-of-facility rule needs the account, so it runs where
        // each assignment is created (UserRoleHospitalAssignmentServiceImpl).
        facilityAssignmentGuard.requireCompatible(roleNames, staffContextHospitalId);

        // ---- 2) Resolve Roles ----
        final Set<Role> roles = roleNames.stream()
                .map(this::resolveRoleByName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final boolean requiresStaff = roles.stream().anyMatch(role -> switch (role.getCode()) {
            case ROLE_DOCTOR, ROLE_NURSE, ROLE_LAB_SCIENTIST, ROLE_PHARMACIST, ROLE_HOSPITAL_ADMIN,
                 "ROLE_RECEPTIONIST", "ROLE_MIDWIFE", "ROLE_RADIOLOGIST", "ROLE_SURGEON", "ROLE_PHYSICIAN",
                 // QA D435 (and the round-4 note): lab and billing staff onboarded
                 // through the same form got no staff row, so they were missing
                 // from every staff and department picker.
                 "ROLE_LAB_TECHNICIAN", "ROLE_LAB_DIRECTOR", "ROLE_LAB_MANAGER", "ROLE_BILLING_SPECIALIST",
                 "ROLE_ACCOUNTANT", "ROLE_CLAIMS_REVIEWER", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST",
                 "ROLE_PHARMACY_VERIFIER", "ROLE_QUALITY_MANAGER", "ROLE_INVENTORY_CLERK",
                 "ROLE_STORE_MANAGER" -> true;
            default -> false;
        });

        // ---- 3) Resolve/Create User ----
        // The licence number is optional for every role: clinicians are
        // credentialed on diplomas, so it is recorded when known, never required.
        // Only a staff registration may resolve an existing account by licence:
        // a patient registration carrying a clinician's licence must not reach it.
        final String lic = resolveLicenseNumber(request);

        // Staff identifiers were refused at 0a, so only a patient registration
        // can land on an existing account here, and 0a has already refused a
        // staff one.
        final Optional<User> existingByIdentity = isPatient
                ? findExistingByIdentity(email, phone)
                : Optional.empty();
        final Optional<User> existingByLicense = lic != null && requiresStaff
                ? staffRepository.findUserIdByLicense(lic).flatMap(userRepository::findById)
                : Optional.empty();

        final boolean newUserCreated = existingByIdentity.isEmpty() && existingByLicense.isEmpty();
        final boolean passwordSupplied = request.getPassword() != null && !request.getPassword().isBlank();
        final boolean reclaimed = existingByIdentity.map(this::isDiscardedRegistration).orElse(false);
        final User user = existingByIdentity.or(() -> existingByLicense)
                .orElseGet(() -> createNewUser(request, request.getUsername(), email, phone, roles));
        if (reclaimed) {
            reclaimDiscardedRegistration(user, request);
        }
        // Only a password the SERVER generated is ever delivered to the user
        // (mail, SMS or the one-time response). An admin-typed password is
        // never sent anywhere: the admin already knows it.
        final String generatedPassword = (newUserCreated || reclaimed) && !passwordSupplied
                ? request.getPassword() : null;

        // Re-registration after a frontend compensation delete: the previous
        // attempt soft-deleted the user (FE rolls back when /api/patients
        // fails), so the lookup above returns a row with isDeleted=true.
        // Without restoring it, reloadAndMap()'s `where u.isDeleted = false`
        // filter would later report the misleading "User disappeared after
        // save" diagnostic.
        restoreIfSoftDeleted(user);

        // A returning patient's credentials are theirs: a registrar linking
        // them at another hospital never rotates them.
        if (!isPatient) {
            applyForcePasswordChangeIfReturning(user, newUserCreated, request);
        }

        // ---- 4) Ensure roles + hospital-scoped assignments ----
        final List<UserRoleHospitalAssignment> ensuredAssignments =
                ensureRolesAndAssignments(user, roles, staffContextHospitalId,
                        welcomeMailCarriesCode(newUserCreated, isPatient, roles));
        if (reclaimed) {
            // The discard revoked the invitation's code: a fresh one, sent
            // with the new credentials set below (step 4a).
            assignmentService.reissuePendingInvitationsForRestoredUser(user.getId());
        }

        // ---- 4a) Forward temp password to the PATIENT assignment ----
        // The assignment notification email (AFTER_COMMIT) will expose these
        // credentials once, then verifyAssignmentByCode() clears them.
        if (isPatient && generatedPassword != null) {
            ensuredAssignments.stream()
                    .filter(a -> a.getRole() != null
                            && ROLE_PATIENT.equalsIgnoreCase(a.getRole().getCode()))
                    .findFirst()
                    .ifPresent(patientAssignment -> {
                        patientAssignment.setTempPlainPassword(generatedPassword);
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
        // The portal's compensation delete after a failed patient step may
        // only discard an account THIS call created (QA D24).
        result.setNewAccount(newUserCreated || reclaimed);

        // ---- 6a) Generated credentials nobody can deliver ----
        // No e-mail on file and no real SMS transport: the staff UI shows them
        // once, from this response only. Never logged, never persisted in clear.
        if (generatedPassword != null && (email == null || email.isBlank())
                && (smsService == null || !smsService.deliversRealSms())) {
            result.setTemporaryUsername(user.getUsername());
            result.setTemporaryPassword(generatedPassword);
        }

        // ---- 7) Welcome email for brand-new non-patient users (fire-and-forget) ----
        if (newUserCreated && !isPatient) {
            final String displayName = UserDisplayUtil.resolveDisplayName(user);
            // The raw code: the mail renders the label in its own language.
            final String roleName    = roles.stream()
                    .map(Role::getCode)
                    .findFirst().orElse(null);
            final String hospitalName = staffContextHospitalId != null
                    ? hospitalRepository.findById(staffContextHospitalId)
                          .map(Hospital::getName).orElse(null)
                    : null;
            // The account is inactive until its assignment codes are confirmed,
            // so the welcome mail is the new user's ONLY mail (see
            // welcomeMailCarriesCode): it lists every role with that
            // assignment's own 6-digit code and its own confirmation link,
            // since RoleWelcomeComponent checks the typed code against the
            // assignment named in the URL. Only assignments that have both
            // codes are listed — exactly those whose separate mail
            // UserRoleHospitalAssignmentServiceImpl.sendNotifications skips.
            final List<EmailService.WelcomeRole> welcomeRoles = welcomeMailCarriesCode(newUserCreated, isPatient, roles)
                    ? welcomeRoles(ensuredAssignments, assignmentLinkService::buildProfileCompletionUrl)
                    : List.of();
            // Deferred to AFTER_COMMIT for the same reason
            // AssignmentCreatedEventListener exists: this mail now carries an
            // assignment code in its button, so a rollback after the send
            // (the 20s transaction timeout is reachable on a slow SMTP hop)
            // would deliver a link to a code that never existed. The
            // synchronisation runs on this thread before the controller
            // closes ActivationDeliveryTracker, so the registrar still sees
            // the outcome.
            // QA D522 (D311/D418): when the welcome mail carries the role codes
            // it IS the e-mailed activation - the per-assignment mails are
            // suppressed - so it is reported as one. Reported as WELCOME, the
            // registration said only that the SMS had failed, and the
            // e-mailed activation looked as if it had never been sent.
            final String welcomePurpose = welcomeMailPurpose(welcomeRoles);
            TransactionCallbacks.afterCommit(() -> {
                boolean sent = com.example.hms.utility.ActivationDeliveryTracker.sendEmailAndReport(
                    welcomePurpose,
                    user.getEmail(),
                    () -> {
                        if (welcomeRoles.isEmpty()) {
                            // No assignment with a code: the step is named and
                            // each assignment mail carries its own code.
                            emailService.sendAdminWelcomeEmail(
                                user.getEmail(), displayName,
                                user.getUsername(), generatedPassword,
                                roleName, hospitalName, null, null);
                        } else {
                            emailService.sendAdminWelcomeEmail(
                                user.getEmail(), displayName,
                                user.getUsername(), generatedPassword, welcomeRoles);
                        }
                    },
                    emailService::deliversRealEmail);
                if (sent) {
                    log.info("📧 Welcome email queued for new user '{}'", user.getUsername());
                } else {
                    log.warn("⚠️ Failed to queue welcome email for '{}'", user.getUsername());
                }
            });
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

    /**
     * Clear the soft-delete flag on a previously deleted user found during
     * admin-register so downstream reloads (which filter `isDeleted = false`)
     * see a live row. Also reactivates any soft-deactivated Staff records
     * tied to the same user to mirror {@link #restoreUser(UUID)}.
     */
    private void restoreIfSoftDeleted(User user) {
        if (user == null || !user.isDeleted()) {
            return;
        }
        log.info("♻️ Re-registering soft-deleted user {} ({}); restoring account.",
                user.getId(), user.getUsername());
        user.setDeleted(false);
        // Registering the person again undoes the delete, the disable it
        // recorded included (V184): otherwise a patient re-registered after
        // a failed first attempt (the registrar's clean-up delete) could
        // never activate.
        user.setDeactivatedByAdminAt(null);
        userRepository.save(user);

        for (Staff staff : staffRepository.findByUserId(user.getId())) {
            if (!staff.isActive()) {
                staff.setActive(true);
                staffRepository.save(staff);
            }
        }
    }

    /**
     * The identifier is taken either by a live account or by a soft-deleted
     * one. Only one of those is visible in the default user list, so the
     * message must say which - "already registered" against an invisible
     * row is a dead end the administrator cannot act on.
     */
    private static ConflictException conflictFor(String field, String identifier, User existing) {
        if (existing.isDeleted()) {
            return new ConflictException(field + ":" + com.example.hms.utility.MessageUtil.resolveOrRaw(
                "users.identifier.deletedAccount." + field, identifier));
        }
        // "taken" for usernames, "registered" for contact identifiers - the
        // wording each field always had.
        return new ConflictException(field + ":" + com.example.hms.utility.MessageUtil.resolveOrRaw(
            "users.identifier.taken." + field, identifier));
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
                .orElseThrow(() -> new IllegalStateException(
                        "User disappeared after save (id=" + userId
                                + "). With saveAndFlush in createNewUser this should be unreachable; "
                                + "if you see it, check for a concurrent delete or a "
                                + "@Transactional propagation mistake on the calling method."));
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
            User user, Set<Role> roles, UUID staffContextHospitalId, boolean welcomeMailCarriesCode) {
        final List<UserRoleHospitalAssignment> result = new ArrayList<>();
        for (Role r : roles) {
            addUserRoleIfAbsent(user.getId(), r.getId());

            log.info("[ASSIGN] {} -> user={} hospitalId={} (inactive until code verification)",
                    r.getCode(), user.getUsername(), staffContextHospitalId);

            result.add(ensureAssignmentSmart(user.getId(), r, staffContextHospitalId, welcomeMailCarriesCode));
        }
        return result;
    }

    private Role resolveRoleByName(String name) {
        final String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        final String code = normalized.startsWith(ROLE_PREFIX) ? normalized : ROLE_PREFIX + normalized;
        return getRoleByCode(code);
    }

    private void upsertStaff(User user, UUID hospitalId, String lic,
                         List<UserRoleHospitalAssignment> assignments, AdminSignupRequest request, Set<Role> roles) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, hospitalId));

        staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
            .map(Staff::getLicenseNumber)
            .ifPresent(existingLic -> {
                // Only two KNOWN numbers can disagree: a licence is optional.
                if (lic != null && !existingLic.isBlank() && !existingLic.equalsIgnoreCase(lic)) {
                    throw new ConflictException(
                        com.example.hms.utility.MessageUtil.resolveOrRaw("users.license.mismatch", existingLic));
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
        if (lic != null) {
            // Never erase a known licence because this registration omitted it.
            staff.setLicenseNumber(lic);
        }
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

    /** The trimmed licence number, or null when none was given. Never required. */
    private static String resolveLicenseNumber(AdminSignupRequest request) {
        return Optional.ofNullable(request.getLicenseNumber())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }

    /**
     * The registration phone in E.164, a local number taking the calling code
     * of the target hospital's country (Burkina Faso when unknown). A value
     * that cannot be a phone number is refused with a translated message.
     */
    private String normalizeRegistrationPhone(AdminSignupRequest request) {
        UUID hospitalId = activeHospitalFromContext();
        if (hospitalId == null) {
            hospitalId = request.getHospitalId();
        }
        return normalizePhone(request.getPhoneNumber(), hospitalId);
    }

    /**
     * Any stored phone in E.164: a local number takes the calling code of the
     * hospital's country (Burkina Faso when unknown); a value that cannot be a
     * phone number is refused with a translated message. Blank stays null.
     */
    private String normalizePhone(String raw, UUID hospitalId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String country = hospitalId == null ? null
                : hospitalRepository.findById(hospitalId).map(Hospital::getCountry).orElse(null);
        String e164 = com.example.hms.utility.PhoneNumbers.toE164(
                raw, com.example.hms.utility.PhoneNumbers.callingCodeForCountry(country));
        if (e164 == null) {
            throw new BusinessException(MessageUtil.resolve("adminSignup.phoneNumber.invalid"));
        }
        return e164;
    }

    /**
     * A temporary username nobody holds yet: {@code first.last} folded to
     * ASCII letters, then four random digits, retried on a collision. The user
     * is made to choose their own at first login (forceUsernameChange).
     */
    private String generateTemporaryUsername(String firstName, String lastName) {
        String base = trimDots(asciiLetters(firstName) + "." + asciiLetters(lastName));
        if (base.isEmpty()) {
            base = "user";
        }
        if (base.length() > 30) {
            base = base.substring(0, 30);
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = base + String.format(Locale.ROOT, "%04d", SECURE_RANDOM.nextInt(10_000));
            if (!Boolean.TRUE.equals(userRepository.existsByUsername(candidate))) {
                return candidate;
            }
        }
        // Twenty collisions on one name is pathological; a random suffix is still unique.
        return base + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Leading and trailing dots removed, without a regex (CodeQL java/polynomial-redos). */
    private static String trimDots(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '.') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '.') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String asciiLetters(String value) {
        if (value == null) {
            return "";
        }
        String folded = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private User createNewUser(AdminSignupRequest request, String username, String email, String phone, Set<Role> roles) {
        User u = new User();
        boolean usernameGenerated = username == null || username.isBlank();
        u.setUsername(usernameGenerated
                ? generateTemporaryUsername(request.getFirstName(), request.getLastName())
                : username);
        u.setEmail(email);
        LocalDateTime passwordSetAt = LocalDateTime.now();

        // Auto-generate a temporary password when none is provided (e.g. patient
        // registration by receptionist).  The user will be forced to change it on
        // first login via forcePasswordChange flag.
        String rawPassword = request.getPassword();
        boolean passwordChosenByAdmin = rawPassword != null && !rawPassword.isBlank();
        if (!passwordChosenByAdmin) {
            rawPassword = generateTempPassword();
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

        // No throttle clear here. The lockout is keyed on the account id
        // (LoginAttemptService), and a new account's id has no history:
        // failures a prober recorded against this name before it existed stay
        // on the name's own key, which the name stops using the moment an
        // account holds it. The holder's first login with the credentials just
        // mailed to them is never refused for someone else's probing.

        boolean isPatient = roles.stream().anyMatch(r -> ROLE_PATIENT.equalsIgnoreCase(r.getCode()));

        // EVERY admin-registered account starts inactive - staff exactly like
        // patients. Product decision 2026-09-02 (option A): the emailed
        // confirmation code must gate something real. Until now staff were
        // activated up front while the system still mailed them a
        // verification code that gated nothing - activation theater. Login
        // is refused while inactive (CustomUserDetails.isEnabled), and
        // verifyAssignmentByCode() (assignee) and confirmAssignment()
        // (registrar presenting the same code) both activate the assignment
        // AND the user once someone proves possession of the emailed code.
        // The bootstrap first-super-admin path is separate and untouched.
        u.setActive(false);
        if (isPatient) {
            // Patients additionally keep the legacy email-link token.
            u.setActivationToken(UUID.randomUUID().toString());
            u.setActivationTokenExpiresAt(LocalDateTime.now().plusDays(1));
        }

        if (isPatient || usernameGenerated || Boolean.TRUE.equals(request.getForcePasswordChange())) {
            u.setForcePasswordChange(true);
            u.setForceUsernameChange(true);
        }
        // Every caller is an administrator registering someone else: a password
        // they typed is known to them, so the holder replaces it at first sign-in
        // (D38). The username they chose stays.
        if (passwordChosenByAdmin) {
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

        // saveAndFlush (vs save) forces the INSERT to hit the DB synchronously
        // here. If a unique constraint or NOT NULL fails, the caller gets a
        // DataIntegrityViolationException with the actual constraint name —
        // mapped to a 400 with that cause by GlobalExceptionHandler. With the
        // previous deferred-flush save(), the violation only surfaced later
        // (during a flush triggered by a follow-up read), the transaction
        // rolled back silently, and reloadAndMap() reported the meaningless
        // "User disappeared after save".
        return userRepository.saveAndFlush(u);
    }

        private UUID resolveHospitalForRegistration(AdminSignupRequest request, Set<String> roleNames, boolean isPatient) {
        if (isPatient) {
            return resolveHospitalForPatient(request);
        }
        return resolveHospitalForStaff(request, roleNames);
    }

    /**
     * Finds an existing account by the identifiers the request actually carries
     * (email first, then phone in any stored form). A null or blank identifier is
     * never looked up, so it cannot match every account lacking that field.
     */
    private Optional<User> findExistingByIdentity(String email, String phone) {
        // Only an identifier the request actually carries is ever looked up.
        // The derived OR queries this used to call turned a null phone into a
        // `phone_number IS NULL` predicate, so a patient registered with an
        // email and no phone matched the first phone-less account on the
        // platform - a staff member (QA D24). The username is not an identity
        // at all: the portal generates a random one per registration, and a
        // collision with someone else's must never merge two people.
        if (email != null && !email.isBlank()) {
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        return findByPhoneInAnyForm(phone);
    }

    /**
     * An account a registration created and then discarded itself: a
     * soft-deleted patient account that was never activated and never got a
     * patient record (the portal's compensation delete after a failed patient
     * step). Anything else soft-deleted stays an administrator's to restore.
     */
    private boolean isDiscardedRegistration(User existing) {
        return existing != null
                && existing.isDeleted()
                && !Boolean.TRUE.equals(existing.getActivatedBeforeDelete())
                && existing.getLastLoginAt() == null
                && !patientRepository.existsByUserId(existing.getId());
    }

    /**
     * Fresh one-time credentials for a reclaimed registration: the discarded
     * attempt's temporary password was revoked with its invitation and was
     * never delivered with a working code.
     */
    private void reclaimDiscardedRegistration(User user, AdminSignupRequest request) {
        String rawPassword = request.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = generateTempPassword();
        }
        request.setPassword(rawPassword);
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordChangedAt(now);
        user.setForcePasswordChange(true);
        user.setForceUsernameChange(true);
        user.setActive(false);
        userRepository.save(user);
    }

    /**
     * A patient registration may link an existing PATIENT account (Epic-style,
     * one person across hospitals), never a staff one: no ROLE_PATIENT is ever
     * added to, no password change forced on, and no activation message sent
     * to an account that holds a staff role or a staff record (QA D24). The
     * registrar gets a conflict naming the field; the person is registered
     * with a different email or phone.
     */
    private void refuseStaffAccountForPatient(User existing, String email) {
        if (existing == null) {
            return;
        }
        boolean staffAccount = staffRepository.existsByUserId(existing.getId())
                || assignmentRepository.existsByUserIdAndRole_CodeNot(existing.getId(), ROLE_PATIENT);
        if (!staffAccount) {
            return;
        }
        boolean byEmail = email != null && email.equalsIgnoreCase(existing.getEmail());
        throw new ConflictException(byEmail
                ? "email:users.staffEmail.notForPatient"
                : "phone:users.staffPhone.notForPatient");
    }

    /**
     * A user holding this E.164 phone, or one of the legacy shapes it was
     * stored in before registration normalised it: the digits without the
     * plus, and the national number without the calling code. Exact matches
     * only, so the lookup stays index-friendly; the backfill of legacy rows
     * is tracked in tasklist.md.
     */
    private Optional<User> findByPhoneInAnyForm(String e164) {
        if (e164 == null || e164.isBlank()) {
            return Optional.empty();
        }
        Set<String> forms = new LinkedHashSet<>();
        forms.add(e164);
        if (e164.startsWith("+")) {
            String digits = e164.substring(1);
            forms.add(digits);
            UUID hospitalId = activeHospitalFromContext();
            String country = hospitalId == null ? null
                    : hospitalRepository.findById(hospitalId).map(Hospital::getCountry).orElse(null);
            for (String code : new LinkedHashSet<>(List.of(com.example.hms.utility.PhoneNumbers.DEFAULT_CALLING_CODE,
                    com.example.hms.utility.PhoneNumbers.callingCodeForCountry(country)))) {
                if (digits.startsWith(code) && digits.length() > code.length()) {
                    forms.add(digits.substring(code.length()));
                }
            }
        }
        for (String form : forms) {
            Optional<User> hit = userRepository.findByPhoneNumber(form);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private void checkPatientAlreadyAtHospital(String username, String email, String phone,
                                                AdminSignupRequest request) {
        Optional<User> existing = findExistingByIdentity(email, phone);
        if (existing.isEmpty()) {
            // A new person. A requested username someone else already holds
            // is dropped so the server generates one, instead of the
            // registration either failing or merging into that account.
            if (username != null && userRepository.findByUsernameIgnoreCase(username).isPresent()) {
                request.setUsername(null);
            }
            return;
        }
        refuseStaffAccountForPatient(existing.get(), email);
        if (isDiscardedRegistration(existing.get())) {
            // The registrar's own earlier attempt, discarded when its patient
            // step failed: the retry reclaims it (QA D40), instead of a 409
            // "already registered" against an account nobody can see.
            return;
        }
        UUID targetHospitalId = resolveHospitalForPatient(request);
        if (targetHospitalId == null) {
            return;
        }
        Role patientRole = getRoleByCode(ROLE_PATIENT);
        if (assignmentService.isRoleAlreadyAssigned(existing.get().getId(), targetHospitalId, patientRole.getId())) {
            throw new ConflictException("patient:" + com.example.hms.utility.MessageUtil.resolveOrRaw("users.patient.alreadyRegistered"));
        }
    }

    private UUID resolveHospitalForPatient(AdminSignupRequest request) {
        // The request context first (receptionist registering at their facility)
        UUID hospitalId = activeHospitalFromContext();
        if (hospitalId == null) {
            hospitalId = request.getHospitalId();
        }
        log.info("[RECEPTION/ADMIN] Resolved hospitalId for patient registration: {}", hospitalId);

        // Patients can exist system-wide without a hospital — like in Epic/Cerner
        // models where patients visit multiple facilities. If a hospital IS provided,
        // we validate it exists; otherwise the patient gets a global (null-hospital) assignment.
        if (hospitalId != null) {
            final UUID resolvedHospitalId = hospitalId;
            hospitalRepository.findClinicalById(resolvedHospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, resolvedHospitalId));
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
            throw new BusinessException("users.hospital.requiredForStaff");
        }
        return hospitalRepository.findById(provided)
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, provided))
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

    /**
     * QA D51: a restore is its own event (USER_RESTORE), not the generic
     * DATA_UPDATE the write interceptor would record. Ids and flags only in
     * the details: no name, email or phone of the restored account.
     */
    private void recordUserRestoreAudit(User restoredUser, boolean reactivated) {
        try {
            Optional<User> actorOpt = resolveCurrentActor();
            User actor = actorOpt.orElse(restoredUser);
            UUID assignmentId = actorOpt.map(this::resolveActorAssignmentId).orElse(null);

            Map<String, Object> details = new HashMap<>();
            details.put("targetUserId", restoredUser.getId());
            details.put("reactivated", reactivated);

            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actor.getId())
                .assignmentId(assignmentId)
                .userName(resolveUserDisplayName(actor))
                .eventType(AuditEventType.USER_RESTORE)
                .eventDescription("User account restored")
                .resourceId(restoredUser.getId().toString())
                .resourceName(resolveUserDisplayName(restoredUser))
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to record user restore audit for '{}': {}", restoredUser.getId(), ex.getMessage());
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
        return userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(principal, principal, principal);
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

    /**
     * A brand-new staff account gets ONE e-mail: the welcome mail lists every
     * role it was registered with, each with its own confirmation code and
     * link, so the separate role-assignment mails are suppressed. An existing
     * user given another assignment, or a patient, keeps the role-assignment
     * mail.
     * QA D522: a welcome mail that lists the role codes is the activation mail.
     */
    static String welcomeMailPurpose(List<EmailService.WelcomeRole> welcomeRoles) {
        return welcomeRoles == null || welcomeRoles.isEmpty()
            ? com.example.hms.payload.dto.NotificationDeliveryStatusDTO.PURPOSE_WELCOME
            : com.example.hms.payload.dto.NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION;
    }

    static boolean welcomeMailCarriesCode(boolean newUserCreated, boolean isPatient, Set<Role> roles) {
        return newUserCreated && !isPatient && roles != null && !roles.isEmpty();
    }

    private UserRoleHospitalAssignment ensureAssignmentSmart(UUID userId, Role role, UUID hospitalId,
                                                             boolean welcomeMailCarriesCode) {
        UUID roleId = role.getId();

        // avoid duplicates
        if (!assignmentService.isRoleAlreadyAssigned(userId, hospitalId, roleId)) {
            // active=false for everyone: enforceRoleScopeConstraints holds
            // staff inactive until the emailed code is verified, and the
            // pre-approval override that used to force staff assignments
            // active here was exactly what made the verification email
            // theater (option A decision, 2026-09-02).
            assignmentService.assignRoleOnAccountCreation(UserRoleHospitalAssignmentRequestDTO.builder()
                    .userId(userId)
                    .roleId(roleId)
                    .hospitalId(hospitalId) // may be null for global
                    .active(false)
                    .welcomeMailCarriesCode(welcomeMailCarriesCode)
                    .build());
        }

        return assignmentRepository
                .findFirstByUserIdAndHospitalIdAndRoleId(userId, hospitalId, roleId)
                .orElseThrow(() -> new IllegalStateException("Assignment was not persisted as expected"));
    }

    /**
     * The hospital the request is pinned to (live permitted set +
     * {@code X-Hospital-Id}); {@code null} for a super-admin in global view.
     * Replaces a helper that re-parsed the raw bearer token for its
     * {@code primaryHospitalId} claim — a value frozen at login, wrong after
     * any assignment change (E9 #55).
     */
    private static UUID activeHospitalFromContext() {
        return HospitalContextHolder.getContextOrEmpty().pinnedHospitalId();
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
            // QA D137: every role that gets a staff row needs a title -
            // staff.job_title is NOT NULL, and a receptionist registered with
            // no title used to fail the whole onboarding with a 400.
            case "ROLE_RECEPTIONIST":
                return JobTitle.RECEPTIONIST;
            case "ROLE_MIDWIFE":
                return JobTitle.MIDWIFE;
            case "ROLE_RADIOLOGIST":
                return JobTitle.RADIOLOGIST;
            case "ROLE_SURGEON":
                return JobTitle.SURGEON;
            case "ROLE_PHYSICIAN":
                return JobTitle.PHYSICIAN;
            case "ROLE_LAB_TECHNICIAN":
                return JobTitle.LAB_TECHNICIAN;
            case "ROLE_LAB_DIRECTOR":
                return JobTitle.LAB_DIRECTOR;
            case "ROLE_BILLING_SPECIALIST":
                return JobTitle.BILLING_SPECIALIST;
            case "ROLE_ANESTHESIOLOGIST":
                return JobTitle.ANESTHESIOLOGIST;
            case "ROLE_PHYSIOTHERAPIST":
                return JobTitle.PHYSIOTHERAPIST;
            case "ROLE_QUALITY_MANAGER":
                return JobTitle.QUALITY_MANAGER;
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
        // Refused exactly like a missing id: no existence oracle.
        User user = userRepository.findByIdWithRolesAndProfiles(id)
                .filter(accountAccess::canView)
                .orElseThrow(() -> userNotFound(id));
        Set<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser(user);
        return userMapper.toResponseDTO(user, assignments);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryDTO> getAllUsers(int page, int size, boolean includeDeleted,
                                            boolean onlyDeleted) {
        DirectoryScope scope = accountAccess.requireDirectoryAccess();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (!scope.everyone() && scope.hospitalIds().isEmpty()) {
            return Page.empty(pageable);
        }
        // The deleted view is the super-admin's: a scoped call never asks for it.
        boolean scoped = !scope.everyone();
        // QA D430: a non-administrator lists colleagues only, never patient accounts.
        Page<User> users = scope.seesPatientContacts()
            ? userRepository.findAllPaged(
                !scoped && includeDeleted, !scoped && onlyDeleted, scoped, directoryHospitals(scope), pageable)
            : userRepository.findStaffDirectoryPaged(
                scoped, directoryHospitals(scope), !scoped && includeDeleted, !scoped && onlyDeleted, pageable);
        return users.map(u -> directoryRow(u, scope));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryDTO> searchUsers(String name, String role, String email, int page, int size,
                                            boolean includeDeleted, boolean onlyDeleted) {
        DirectoryScope scope = accountAccess.requireDirectoryAccess();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (!scope.everyone() && scope.hospitalIds().isEmpty()) {
            return Page.empty(pageable);
        }
        boolean scoped = !scope.everyone();
        // The free-text box also finds an account by e-mail or phone (D119);
        // phones are stored in E.164, so "70 20 20 20" is matched on its digits.
        // QA D198: a caller who cannot read contact details cannot search on them either.
        // QA D430: a non-administrator searches colleagues only, never patient accounts.
        Page<User> users = scope.seesPatientContacts()
            ? userRepository.searchUsers(name, phoneDigits(name), role, email, true,
                !scoped && includeDeleted, !scoped && onlyDeleted, scoped, directoryHospitals(scope), pageable)
            : userRepository.searchStaffDirectory(name, phoneDigits(name), role, email, false,
                !scoped && includeDeleted, !scoped && onlyDeleted, scoped, directoryHospitals(scope), pageable);
        return users.map(u -> directoryRow(u, scope));
    }

    /**
     * QA D125/D198/D347: a non-administrator gets the minimal directory row (chat
     * needs the id, the name and the role) and never an e-mail or a username, on any
     * account: the earlier patient-only mask keyed on the profile links, which an
     * account without a loaded profile slipped past, and staff addresses are no
     * business of a nurse's directory either. Administrators keep the full row.
     */
    private UserSummaryDTO directoryRow(User user, DirectoryScope scope) {
        UserSummaryDTO dto = userMapper.toSummaryDTO(user);
        if (dto != null && !scope.seesPatientContacts()) {
            dto.setEmail(null);
            dto.setUsername(null);
        }
        return dto;
    }

    /** The digits of a search term that looks like a phone number, else null. */
    static String phoneDigits(String term) {
        if (term == null) {
            return null;
        }
        String digits = term.replaceAll("\\D", "");
        boolean phoneShaped = term.strip().matches("[+\\d][\\d\\s().-]*");
        return phoneShaped && digits.length() >= 4 ? digits : null;
    }

    /** The caller's hospitals, or the repository's sentinel when the directory is unscoped. */
    private static java.util.Collection<UUID> directoryHospitals(DirectoryScope scope) {
        return scope.everyone() ? UserRepository.DIRECTORY_UNSCOPED : scope.hospitalIds();
    }

    /*
     * --------------------------------
     * Mutations
     * --------------------------------
     */
    @Override
    @Transactional
    public void deleteUser(UUID id) {
        // An administrator of the account, or a registrar discarding the
        // unclaimed patient account its own failed registration just created
        // (patient-form's compensation). Anyone else: the missing-user answer,
        // and a FAILURE row.
        User user = userRepository.findById(id)
                .orElseThrow(() -> userNotFound(id));
        if (!accountAccess.canDelete(user)) {
            throw refused(AuditEventType.USER_DELETE, "delete", id);
        }
        // QA D327: a second delete of a deleted account changed nothing yet
        // answered success and wrote a second USER_DELETE audit row.
        if (user.isDeleted()) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve("user.alreadyDeleted"));
        }

        // Read before anything is switched off: the restore puts back this
        // answer, since afterwards nothing on the account or its rows can
        // tell a verified account from a pending invitee (D21).
        // A repeat delete is refused above (QA D327), so the first answer is
        // never overwritten by an already switched-off account.
        user.setActivatedBeforeDelete(user.isActive() || hasSignedInOrConfirmed(user));
        user.setDeleted(true);
        user.setActive(false);
        // An administrator's disable: no invitation code or provider re-invite undoes it.
        user.setDeactivatedByAdminAt(LocalDateTime.now(ZoneId.systemDefault()));
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
        // The same tenant rule as editing: a hospital admin restores only an
        // account whose assignments are all at hospitals they administer. A
        // soft delete now DEACTIVATES the assignments rather than deleting
        // them, so they are still there to decide that by; they come back
        // inactive, and an administrator reactivates the ones still wanted.
        User user = userRepository.findById(id)
                .orElseThrow(() -> userNotFound(id));
        if (!accountAccess.canAdminister(user)) {
            throw refused(AuditEventType.USER_RESTORE, "restore", id);
        }
        // QA D327: restoring an account that is not deleted re-ran the
        // restore (and could switch a never-activated account's state) and
        // wrote another USER_RESTORE audit row.
        if (!user.isDeleted()) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve("user.notDeleted"));
        }

        // Restore the state before the delete, not "active". A pending invitee
        // (never signed in, no confirmed assignment) has never proved they
        // hold the address the invitation went to: switching them on here let
        // them sign in with the mailed temporary password and no code (D21).
        // They stay inactive and get a fresh code for each invitation the
        // delete revoked; the marker is cleared so that code can activate the
        // account, exactly as a first invitation does (V184: an inactive
        // account without the marker is simply not activated yet).
        Boolean recorded = user.getActivatedBeforeDelete();
        boolean previouslyActivated = recorded != null ? recorded : wasEverActivated(user);
        user.setActivatedBeforeDelete(null);
        user.setDeleted(false);
        user.setActive(previouslyActivated);
        user.setDeactivatedByAdminAt(null);
        userRepository.save(user);
        if (!previouslyActivated) {
            assignmentService.reissuePendingInvitationsForRestoredUser(user.getId());
        }
        // A lockout collected while the account was switched off must not
        // survive it being switched on. After commit: a rollback must not
        // leave the counter cleared for an account that stayed deactivated.
        final UUID restoredId = user.getId();
        TransactionCallbacks.afterCommit(() -> loginAttemptService.resetAttempts(restoredId));

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
        recordUserRestoreAudit(user, previouslyActivated);

        // After commit, like the reset above: a failure in the staff loop or at
        // commit would otherwise deliver "your account has been restored" for a
        // restore that never happened.
        //
        // The try/catch lives INSIDE the callback, not around the restore.
        // Spring propagates after-commit exceptions to whoever called commit(),
        // so a catch around the registration guards nothing once the send moves
        // into the callback — and restoring a phone-first patient (email is
        // nullable since V107) throws on a null recipient, which would turn a
        // committed restore into a 500.
        // Residual, and no worse than before: an after-commit callback runs
        // before the connection is released, so a stalled SMTP host pins a
        // pool connection for the send timeout. The previous inline send held
        // the connection AND the transaction, so this is strictly better —
        // moving mail off the request thread entirely is in tasklist.md.
        final UUID restoredUserId = user.getId();
        final String restoredTo = user.getEmail();
        final String restoredName = UserDisplayUtil.resolveDisplayName(user);
        TransactionCallbacks.afterCommit(() -> {
            try {
                emailService.sendAccountRestoredEmail(restoredTo, restoredName);
            } catch (Exception e) {
                // Id and exception type only. getMessage() is not safe here:
                // validateAddresses formats "Invalid email format: <addr>",
                // and a MailSendException carries the recipient too — so
                // logging the message would put a contact detail in the log
                // for exactly the malformed-address case this catches.
                log.warn("⚠️ Failed to send account-restored notification for user {}: {}",
                        restoredUserId, e.getClass().getSimpleName());
            }
        });
    }

    /** Merge-preserve of the identity fields: only a provided value overwrites. */
    private static void applyNameAndEmail(User user, UpdateUserRequestDTO dto) {
        if (hasText(dto.getUsername())) {
            // An administrator choosing the name settles the forced rename:
            // the holder is no longer asked to pick one at next sign-in (D127).
            if (!dto.getUsername().equals(user.getUsername())) {
                user.setForceUsernameChange(false);
            }
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
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID id, UpdateUserRequestDTO dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> userNotFound(id));

        // Editing your own account is the profile page: contact fields only,
        // whoever you are. Anyone else's account needs an administrator of it
        // (UserAccountAccess.canAdminister) and is otherwise refused exactly
        // like a missing id, with a FAILURE row.
        if (accountAccess.isSelf(user)) {
            requireSelfServiceChangesOnly(user, dto);
        } else if (!accountAccess.canAdminister(user)) {
            throw refused(AuditEventType.USER_UPDATE, "update", id);
        }
        // A deleted account is restored first, then edited: an edit would
        // otherwise rewrite a tombstone nobody can see (D126).
        if (user.isDeleted()) {
            throw new BusinessException(MessageUtil.resolve("user.update.deleted"));
        }
        requireIdentifiersFree(user, dto);
        requireRolesUnchanged(user, dto);

        // ── Merge-preserve: only overwrite fields that are explicitly provided ──

        boolean reactivated = false;
        applyNameAndEmail(user, dto);
        // Normalised only when it changed: the edit dialog sends the stored
        // phone back, and a legacy value toE164 refuses must not block an edit.
        if (hasText(dto.getPhoneNumber()) && !dto.getPhoneNumber().trim().equals(user.getPhoneNumber())) {
            user.setPhoneNumber(normalizePhone(dto.getPhoneNumber(), activeHospitalFromContext()));
        }
        if (dto.getActive() != null) {
            reactivated = applyActive(user, dto.getActive());
        }

        // Reactivation clears the login lockout, so the holder is not refused
        // at the moment their account is switched on. The throttle is keyed on
        // the account id, so the clear reaches the lockout whatever name it was
        // collected under, a combined rename + reactivate included, and never
        // touches another account's. After commit, so a failed save cannot
        // free an account that stayed inactive.
        //
        // The transition guard is about not clearing on an ordinary edit; it
        // is NOT an authorization control. Authorization is the caller check
        // at the top of this method: only an administrator of the account
        // reaches here with a status change, since a self-edit cannot make one.
        final UUID reactivatedId = user.getId();
        if (reactivated) {
            TransactionCallbacks.afterCommit(
                () -> loginAttemptService.resetAttempts(reactivatedId));
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
            // Only an administrator reaches here (a self-edit cannot carry a
            // password), and a password someone else chose is a shared secret:
            // the holder must replace it at the next sign-in (D38).
            user.setForcePasswordChange(true);
            // An administrator handing out a new password means the holder
            // should sign in with it now: the lock collected on the old one
            // must not refuse it (D18). After commit, as above; a reactivation
            // in the same update already registered the same clear.
            if (!reactivated) {
                TransactionCallbacks.afterCommit(
                    () -> loginAttemptService.resetAttempts(reactivatedId));
            }
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

    /**
     * The fields an account holder may change on their own account through
     * {@code PUT /users/{id}}: names and phone. The rest have their own
     * endpoints, which apply rules this one does not: the password needs the
     * current one and the history check ({@code POST /auth/me/change-password}),
     * the username the character and uniqueness rules
     * ({@code POST /auth/me/change-username}), the email the current password
     * and a code sent to the new address ({@code POST /auth/me/change-email},
     * then {@code /confirm}: it is where a password reset is sent, so a
     * session changing it with no re-authentication would turn a stolen
     * short-lived token into a permanent takeover), and nobody switches their
     * own account on or off. An administrator of another account still sets
     * its email here (the {@code canAdminister} path).
     * Sending the current value back unchanged is not a change, so the
     * profile form, which always sends the username and email, still works.
     */
    private static void requireSelfServiceChangesOnly(User user, UpdateUserRequestDTO dto) {
        if (dto.getActive() != null && !dto.getActive().equals(user.isActive())) {
            throw new BusinessException(MessageUtil.resolve("user.update.self.active"));
        }
        if (hasText(dto.getPassword())) {
            throw new BusinessException(MessageUtil.resolve("user.update.self.password"));
        }
        if (hasText(dto.getUsername()) && !dto.getUsername().equals(user.getUsername())) {
            throw new BusinessException(MessageUtil.resolve("user.update.self.username"));
        }
        if (hasText(dto.getEmail()) && !dto.getEmail().equals(user.getEmail())) {
            throw new BusinessException(MessageUtil.resolve("user.update.self.email"));
        }
    }

    /**
     * Roles are granted and retired per hospital through the role-assignment
     * endpoints, which apply the grant rules (who may grant what, where).
     * This endpoint never changed them, yet it accepted {@code roleNames} and
     * answered 200, so the edit dialog reported a role change that never
     * happened (QA D765). Sending the current role set back is not a change
     * and is accepted; anything else is refused instead of silently dropped.
     */
    private void requireRolesUnchanged(User user, UpdateUserRequestDTO dto) {
        if (dto.getRoleNames() == null || dto.getRoleNames().isEmpty()) {
            return;
        }
        Set<String> requested = dto.getRoleNames().stream()
                .filter(Objects::nonNull)
                .map(UserServiceImpl::normalizeRoleCode)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toSet());
        Set<String> current = assignmentRepository.findByUser(user).stream()
                .filter(a -> a != null && Boolean.TRUE.equals(a.getActive()) && a.getRole() != null)
                .map(a -> a.getRole().getCode() != null ? a.getRole().getCode() : a.getRole().getName())
                .filter(Objects::nonNull)
                .map(UserServiceImpl::normalizeRoleCode)
                .collect(Collectors.toSet());
        if (!requested.equals(current)) {
            throw new BusinessException(MessageUtil.resolve("user.update.roles.unsupported"));
        }
    }

    private static String normalizeRoleCode(String code) {
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper.substring(5) : upper;
    }

    /**
     * A changed username or email must not be held by ANOTHER account in any
     * letter case, deleted accounts included. uq_user_username is case
     * sensitive but login resolves usernames case-insensitively, so renaming
     * a nurse to a case variant of the super-admin's username would leave the
     * super-admin's login unresolvable: a tenant admin locking out the
     * platform admin. The same query shape as {@code changeOwnUsername}'s
     * check, excluding the account itself; the message never names the other
     * account.
     */
    private void requireIdentifiersFree(User user, UpdateUserRequestDTO dto) {
        if (hasText(dto.getUsername()) && !dto.getUsername().equals(user.getUsername())
                && userRepository.existsUsernameOnOtherAccount(dto.getUsername(), user.getId())) {
            throw new BusinessException(MessageUtil.resolve("user.update.username.taken"));
        }
        if (hasText(dto.getEmail()) && !dto.getEmail().equals(user.getEmail())
                && userRepository.existsEmailOnOtherAccount(dto.getEmail(), user.getId())) {
            throw new BusinessException(MessageUtil.resolve("user.update.email.taken"));
        }
    }

    /**
     * A refused cross-account write: recorded as a FAILURE (actor id and
     * target id only: no names, never the submitted values), then answered
     * exactly like a missing account. The audit service writes in its own
     * transaction, so the row survives this one's rollback.
     */
    private ResourceNotFoundException refused(AuditEventType type, String action, UUID targetId) {
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(accountAccess.currentUserId().orElse(null))
                .eventType(type)
                .eventDescription("User " + action + " refused: the caller may not administer this account")
                .resourceId(targetId.toString())
                .entityType("USER")
                .status(AuditStatus.FAILURE)
                .build());
        return userNotFound(targetId);
    }

    /**
     * A registration the caller may not grant, recorded: one FAILURE row with
     * the actor id and the requested role codes only (no username, email,
     * phone or password from the request). Returned for the caller to throw,
     * a 403: unlike an id-based refusal this is not an existence question.
     */
    private AccessDeniedException refusedGrant(Set<String> roleNames, AccessDeniedException denied) {
        List<String> requested = roleNames.stream()
                .filter(Objects::nonNull)
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .sorted()
                .toList();
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(accountAccess.currentUserId().orElse(null))
                .eventType(AuditEventType.USER_CREATE)
                .eventDescription("User registration refused: the caller may not grant these roles")
                .details(Map.of("requestedRoles", requested))
                .entityType("USER")
                .status(AuditStatus.FAILURE)
                .build());
        return denied;
    }

    /** The account has signed in, or one of its assignments was confirmed with its code. */
    private boolean hasSignedInOrConfirmed(User user) {
        return user.getLastLoginAt() != null
                || assignmentRepository.findByUserId(user.getId()).stream()
                        .anyMatch(a -> a.getConfirmationVerifiedAt() != null);
    }

    /**
     * Fallback for an account deleted before V187 recorded
     * {@code activated_before_delete}: whether it was ever switched on, guessed
     * from what the delete left behind. A deleted account's active flag no
     * longer says it, since the delete cleared it. Inexact for one case only:
     * a patient verified by e-mail who never signed in still shows a sent,
     * unconfirmed sign-up row and is restored inactive.
     */
    private boolean wasEverActivated(User user) {
        if (hasSignedInOrConfirmed(user)) {
            return true;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(user.getId());
        // Only an account still waiting on something counts as never activated:
        // an unused e-mail verification link, or an invitation code that was
        // sent and never entered. An account verified by e-mail (the link
        // clears the token but stamps no assignment) or created active by the
        // platform waits on nothing, so it was activated.
        if (user.getActivationToken() != null) {
            return false;
        }
        return assignments.stream()
                .noneMatch(a -> a.getConfirmationSentAt() != null && a.getConfirmationVerifiedAt() == null);
    }

    /** The one answer for a missing account and for one the caller may not touch. */
    private static ResourceNotFoundException userNotFound(UUID id) {
        return new ResourceNotFoundException(USER_NOT_FOUND_KEY, id);
    }

    /**
     * Sets the account's active flag from an administrator's update and
     * keeps V184's marker in step. An explicit active=false is an
     * administrator's disable, also on a pending invitee (whose emailed code
     * would otherwise switch the account on): the portal's user edit never
     * sends `active`, so its presence is intent, not an echo. The marker is
     * cleared only on a real re-enable; an echoed active=true on a live
     * account changes nothing. Returns whether the account was re-enabled.
     */
    private static boolean applyActive(User user, Boolean active) {
        boolean reactivated = Boolean.TRUE.equals(active) && !Boolean.TRUE.equals(user.isActive());
        user.setActive(active);
        if (Boolean.FALSE.equals(active) && user.getDeactivatedByAdminAt() == null) {
            user.setDeactivatedByAdminAt(LocalDateTime.now(ZoneId.systemDefault()));
        } else if (reactivated) {
            user.setDeactivatedByAdminAt(null);
        }
        return reactivated;
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
                .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByEmail", email));

        if (user.getActivationToken() == null
                || !token.equals(user.getActivationToken())
                || user.getActivationTokenExpiresAt() == null
                || user.getActivationTokenExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("⚠️ Invalid or expired activation token for: {}", email);
            return false;
        }

        // An account an administrator switched off stays off (V184): only an
        // administrator re-enables it, never an activation link.
        if (user.getDeactivatedByAdminAt() != null) {
            log.warn("⚠️ Activation refused for user {}: disabled by an administrator", user.getId());
            return false;
        }

        user.setActive(true);
        user.setActivationToken(null);
        user.setActivationTokenExpiresAt(null);
        userRepository.save(user);
        // NOTE: this method has no caller in main — AuthController#verifyEmail
        // implements verification itself and is the path that runs. The reset
        // is kept, and deferred like every other one, so wiring this method up
        // later cannot reintroduce the bug the rest of this class just fixed:
        // the patient activation below writes after this point, and a rollback
        // there would otherwise leave the counter cleared for an account that
        // stayed inactive. (Note for whoever wires it up: unlike
        // AuthController#verifyEmail this method does NOT activate the user's
        // ROLE_PATIENT assignments — it only flips the Patient row.)
        final UUID verifiedId = user.getId();
        TransactionCallbacks.afterCommit(() -> loginAttemptService.resetAttempts(verifiedId));

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
                .orElseThrow(() -> new ResourceNotFoundException("role.notfound", code));
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
        if (assignmentService.isRoleAlreadyAssigned(userId, hospitalId, roleId)) {
            return;
        }
        // Only SUPER_ADMIN bootstraps active=true. Every other role (PATIENT, staff, admin)
        // must remain inactive at creation; activation is gated on email verification
        // (see verifyEmail / verifyAssignmentByCode). enforceRoleScopeConstraints rejects
        // active=true for PATIENT, so passing null lets the downstream guards apply the
        // correct default per role.
        Role role = roleRepository.getReferenceById(roleId);
        Boolean active = ROLE_SUPER_ADMIN.equalsIgnoreCase(role.getCode()) ? Boolean.TRUE : null;
        assignmentService.assignRoleOnAccountCreation(UserRoleHospitalAssignmentRequestDTO.builder()
                .userId(userId)
                .roleId(roleId)
                .hospitalId(hospitalId)
                .active(active)
                .build());
    }

    private Hospital getDefaultHospital() {
        return hospitalRepository.findByCodeIgnoreCase("Hospital Yalgado Ouedraogo")
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFoundByIdentifier", "Hospital Yalgado Ouedraogo"));
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
        // A staff row always carries a title (NOT NULL column, QA D137).
        return JobTitle.ADMINISTRATIVE_STAFF;
    }

    /*
     * --------------------------------
     * Profile Image Management
     * --------------------------------
     */

    @Override
    public UUID getUserIdByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByUsername", username));
        return user.getId();
    }

    @Override
    @Transactional
    public void changeOwnPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_KEY, userId));
        String encodedPassword = passwordEncoder.encode(newPassword);
        user.setPasswordHash(encodedPassword);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setForcePasswordChange(false);
        user.setPasswordRotationWarningAt(null);
        user.setPasswordRotationForcedAt(null);
        userRepository.save(user);

        // Record in password history for reuse prevention
        passwordHistoryService.recordPassword(userId, encodedPassword);

        log.info("🔑 [CHANGE-PWD] Password updated and forcePasswordChange cleared for user={}", userId);
    }

    @Override
    @Transactional
    public void changeOwnUsername(UUID userId, String newUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_KEY, userId));
        if (userRepository.findByUsername(newUsername).filter(u -> !u.getId().equals(userId)).isPresent()) {
            throw new IllegalArgumentException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.username.taken", newUsername));
        }
        user.setUsername(newUsername);
        user.setForceUsernameChange(false);
        userRepository.save(user);
        log.info("🔑 [CHANGE-USR] Username updated and forceUsernameChange cleared for user={}", userId);
    }

    @Override
    @Transactional
    public void completeAccountSetup(UUID userId, String newUsername, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_KEY, userId));
        // The holder may keep the name they were given (QA D52): that clears
        // the flag without a rename.
        final boolean renamed = newUsername != null && !newUsername.equals(user.getUsername());
        if (newUsername != null) {
            // Any letter case, deleted accounts included: the same rule as an
            // administrator's rename (requireIdentifiersFree).
            if (userRepository.existsUsernameOnOtherAccount(newUsername, userId)) {
                throw new BusinessException(MessageUtil.resolve("account.setup.username.taken"));
            }
            user.setUsername(newUsername);
            user.setForceUsernameChange(false);
        }
        String encodedPassword = null;
        if (newPassword != null) {
            encodedPassword = passwordEncoder.encode(newPassword);
            user.setPasswordHash(encodedPassword);
            user.setPasswordChangedAt(LocalDateTime.now());
            user.setForcePasswordChange(false);
            user.setPasswordRotationWarningAt(null);
            user.setPasswordRotationForcedAt(null);
        }
        userRepository.save(user);
        if (encodedPassword != null) {
            passwordHistoryService.recordPassword(userId, encodedPassword);
        }
        // Ids only: never the username typed, never the password.
        final boolean passwordChanged = newPassword != null;
        TransactionCallbacks.afterCommit(() -> auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(userId)
                .eventType(passwordChanged ? AuditEventType.PASSWORD_CHANGED : AuditEventType.USER_UPDATE)
                .eventDescription("First sign-in setup completed")
                .details(Map.of("usernameChanged", renamed, "passwordChanged", passwordChanged))
                .resourceId(userId.toString())
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .build()));
        log.info("🔑 [ACCOUNT-SETUP] Setup completed for user={} (username={}, password={})",
                userId, renamed, passwordChanged);
    }

    @Override
    @Transactional
    public void updateProfileImage(UUID userId, String imageUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_KEY, userId));

        String oldImageUrl = user.getProfileImageUrl();
        user.setProfileImageUrl(imageUrl);
        userRepository.save(user);

        log.info("Profile image updated for user {}: {} -> {}", userId, oldImageUrl, imageUrl);
    }

    /**
     * Generate a temporary password: 14 chars mixing uppercase, lowercase, digits, and specials.
     * Guarantees at least one character from each category.
     */
    private String generateTempPassword() {
        String upper   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower   = "abcdefghijklmnopqrstuvwxyz";
        String digits  = "0123456789";
        String special = "!@#$%&*";
        String all     = upper + lower + digits + special;

        // Guarantee one from each category
        StringBuilder sb = new StringBuilder(14);
        sb.append(upper.charAt(SECURE_RANDOM.nextInt(upper.length())));
        sb.append(lower.charAt(SECURE_RANDOM.nextInt(lower.length())));
        sb.append(digits.charAt(SECURE_RANDOM.nextInt(digits.length())));
        sb.append(special.charAt(SECURE_RANDOM.nextInt(special.length())));

        // Fill remaining 10 chars
        for (int i = 4; i < 14; i++) {
            sb.append(all.charAt(SECURE_RANDOM.nextInt(all.length())));
        }

        // Shuffle to avoid predictable positions
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }


    /**
     * The roles the welcome mail lists: every assignment that has both its
     * confirmation code and its assignment code, each with the link to its
     * own confirmation screen. An assignment missing either is left out, and
     * its own role-assignment mail still goes out
     * (UserRoleHospitalAssignmentServiceImpl.sendNotifications applies the
     * same test).
     *
     * <p>Visible for testing.
     */
    static List<EmailService.WelcomeRole> welcomeRoles(List<UserRoleHospitalAssignment> assignments,
                                                       java.util.function.UnaryOperator<String> linkFor) {
        if (assignments == null) {
            return List.of();
        }
        List<EmailService.WelcomeRole> out = new ArrayList<>();
        for (UserRoleHospitalAssignment a : assignments) {
            String confirmation = a.getConfirmationCode();
            String code = a.getAssignmentCode();
            if (confirmation == null || confirmation.isBlank() || code == null || code.isBlank()) {
                continue;
            }
            out.add(new EmailService.WelcomeRole(
                    a.getRole() != null ? a.getRole().getCode() : null,
                    a.getHospital() != null ? a.getHospital().getName() : null,
                    confirmation,
                    linkFor.apply(code)));
        }
        return out;
    }

}
