package com.example.hms.service;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.NotificationTransportUnavailableException;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.utility.TransactionCallbacks;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.event.AssignmentCreatedEvent;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.AssignmentMinimalDTO;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import com.example.hms.payload.dto.assignment.AssignmentSearchCriteria;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResultDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentFailureDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentPublicViewDTO;
import com.example.hms.specification.UserRoleHospitalAssignmentSpecification;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.service.support.AssignmentReferences;
import com.example.hms.service.support.UserAccountAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;
import com.example.hms.security.provider.ClinicalHospitals;
import com.example.hms.security.provider.FacilityAssignmentGuard;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserRoleHospitalAssignmentServiceImpl implements UserRoleHospitalAssignmentService {
    private static final String UNKNOWN_ROLE = "UNKNOWN_ROLE";
    private static final String AUDIT_ENTITY_ASSIGNMENT = "USER_ROLE_ASSIGNMENT";


    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String MSG_REMOVE_OWN = "users.assignment.removeOwn";
    private static final String MSG_STAFF_ACCESS_OUT_OF_REACH = "staff.deactivate.accessOutOfReach";
    /** QA D902: the codes the super-admin role is stored under. */
    private static final Set<String> SUPER_ADMIN_CODES = Set.of(ROLE_SUPER_ADMIN, "SUPER_ADMIN");
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String MSG_PATIENT_FROM_PATIENT_FORM = "assignments.roles.patientFromPatientForm";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String MSG_ASSIGNMENT_NOT_FOUND = "roleAssignment.notFound";
    private static final String MSG_ASSIGNMENT_CONFLICT = "assignment.conflict";
    private static final String MSG_ASSIGNMENT_CONFLICT_INACTIVE = "assignment.conflict.inactive";
    private static final String MSG_ASSIGNMENT_DOCTOR_CONFLICT = "assignment.doctor.conflict";
    private static final String MSG_ROLE_DELETE_CONFLICT = "role.delete.conflict";
    private static final String MSG_ROLE_NOT_FOUND = "role.notfound";
    private static final String MSG_ROLE_NOT_FOUND_BY_NAME = "role.notfound.byname";
    private static final String MSG_ROLE_REQUIRED = "role.required";
    private static final String MSG_USER_NOT_FOUND = "user.notfound";
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";
    private static final String MSG_ORGANIZATION_NOT_FOUND = "organization.notfound";
    private static final String DEFAULT_ROLE_ALREADY_ASSIGNED = "Role already assigned to this user for this hospital.";
    private static final String DEFAULT_ROLE_ASSIGNED_INACTIVE =
        "This role was assigned to this user at this hospital before and is now inactive. Reactivate that assignment instead.";
    private static final String DEFAULT_ROLE_DELETE_CONFLICT = "Cannot delete role. It is assigned to one or more users.";
    private static final String DEFAULT_USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String DEFAULT_ROLE_REQUIRED_MESSAGE = "Role must be specified by either ID or name.";
    private static final String DEFAULT_DOCTOR_CONFLICT_MESSAGE = "An active DOCTOR assignment already exists for this user in the given hospital.";
    private static final String DEFAULT_SUPER_ADMIN_SCOPE_MESSAGE = "assignment.superAdmin.noHospital";
    private static final String DEFAULT_HOSPITAL_REQUIRED_MESSAGE = "Hospital must be provided for non-SUPER_ADMIN roles.";
    private static final String DEFAULT_INVALID_UUID_MESSAGE = "Invalid UUID value: ";
    private static final String DEFAULT_INVALID_BOOLEAN_MESSAGE = "Invalid boolean value: ";
    private static final String DEFAULT_INVALID_DATE_MESSAGE = "Invalid date value: ";
    private static final String DEFAULT_ROW_MISSING_USER = "assignment.csv.rowMissingUser";
    private static final String DEFAULT_ROW_MISSING_ROLE = "assignment.csv.rowMissingRole";
    private static final String DEFAULT_PROCESSING_ERROR = "assignment.csv.unprocessable";
    private static final String MSG_ASSIGNMENT_INVALID_CODE = "assignment.confirmation.invalid";
    private static final String MSG_ASSIGNMENT_ALREADY_CONFIRMED = "assignment.confirmation.already";
    private static final String MSG_ASSIGNMENT_ACTOR_MISMATCH = "assignment.confirmation.actor-mismatch";
    private static final String MSG_ASSIGNMENT_NOT_FOUND_BY_CODE = "assignment.notfound.bycode";
    private static final String DEFAULT_CONFIRMATION_CODE_INVALID = "Invalid confirmation code.";
    private static final String DEFAULT_ASSIGNMENT_ALREADY_CONFIRMED = "This assignment has already been confirmed.";
    private static final String MSG_ACCOUNT_DISABLED_BY_ADMIN = "assignment.confirmation.accountDisabled";
    private static final String DEFAULT_ACCOUNT_DISABLED_BY_ADMIN =
        "This account was disabled by an administrator. Ask them to re-enable it.";
    private static final String DEFAULT_CONFIRMATION_ACTOR_MISMATCH = "Only the assigner who created this assignment can confirm it.";
    private static final String DEFAULT_ACTOR_RESOLUTION_FAILURE = "Unable to resolve the current user.";
    private static final String DEFAULT_HOLDER_CHANGE = "assignment.holderChange.superAdminOnly";
    private static final String DEFAULT_ACTIVATION_THROUGH_CODE =
        "Only a super-admin can activate an assignment directly. Send the holder a new code "
            + "(regenerate the code); the assignment activates when they confirm it.";
    private static final long CONFIRMATION_CODE_EXPIRY_HOURS = 48;
    private static final List<String> DEFAULT_PROFILE_CHECKLIST = List.of(
        "Review and confirm your personal information",
        "Add at least one emergency contact",
        "Verify your preferred communication details"
    );
    private static final Map<String, List<String>> ROLE_PROFILE_CHECKLISTS = Map.ofEntries(
        Map.entry(ROLE_DOCTOR, List.of(
            "Upload your medical license and credentials",
            "Set your primary specialties and departments",
            "Publish your consultation availability"
        )),
        Map.entry("ROLE_NURSE", List.of(
            "Provide nursing license details",
            "Confirm shift preferences and availability",
            "Review assigned wards or units"
        )),
        Map.entry("ROLE_RECEPTIONIST", List.of(
            "Verify front-desk contact information",
            "Review visitor intake checklist",
            "Confirm escalation procedures"
        )),
        Map.entry("ROLE_HOSPITAL_ADMIN", List.of(
            "Classify the sensitive departments (psychiatry, addiction, HIV, family planning) so their records stay behind break-the-glass across hospitals",
            "Review the hospital's record-access posture and opt-out register",
            "Verify critical incident escalation contacts"
        )),
        Map.entry(ROLE_SUPER_ADMIN, List.of(
            "Enable multi-factor authentication",
            "Review organization level notification settings",
            "Verify critical incident escalation contacts"
        ))
    );
    private static final DateTimeFormatter DATE_FORMAT_DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FORMAT_MM_DD_YYYY = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<DateTimeFormatter> SUPPORTED_DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DATE_FORMAT_DD_MM_YYYY,
        DATE_FORMAT_MM_DD_YYYY
    );

    private final SmsService smsService;
    private final EmailService emailService;
    private final AuditEventLogService auditEventLogService;
    private final AssignmentLinkService assignmentLinkService;

    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;
    private final RoleRepository roleRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final OrganizationRepository organizationRepository;
    private final StaffRepository staffRepository;
    private final com.example.hms.repository.EncounterRepository encounterRepository;
    private final PatientRepository patientRepository;
    private final UserRoleHospitalAssignmentMapper mapper;
    private final MessageSource messageSource;
    private final com.example.hms.utility.RoleValidator roleValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final UserAccountAccess accountAccess;
    /** Role/facility compatibility and one kind of facility per user (provider plan §3.2, §3.2a). */
    private final FacilityAssignmentGuard facilityAssignmentGuard;
    /** Every other kind of record that still points at an assignment (QA round 14). */
    private final AssignmentReferences assignmentReferences;

    /* ===================== Create ===================== */

    private UserRoleHospitalAssignmentResponseDTO toDtoWithLinks(UserRoleHospitalAssignment assignment) {
        UserRoleHospitalAssignmentResponseDTO dto = mapper.toResponseDTO(assignment);
        if (dto == null || assignment == null) {
            return dto;
        }
        String assignmentCode = assignment.getAssignmentCode();
        if (assignmentCode != null && !assignmentCode.isBlank()) {
            dto.setProfileCompletionUrl(profileCompletionUrl(assignment));
            dto.setAssignerConfirmationUrl(assignerConfirmationUrl(assignment));
        }
        dto.setProfileChecklist(buildRoleProfileChecklist(assignment.getRole()));
        dto.setConfirmationVerified(assignment.getConfirmationVerifiedAt() != null);
        return dto;
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO assignRole(UserRoleHospitalAssignmentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        UserRoleHospitalAssignment assignment = createAssignment(dto, locale, true, true);
        return toDtoWithLinks(assignment);
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO assignRoleOnAccountCreation(UserRoleHospitalAssignmentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        UserRoleHospitalAssignment assignment = createAssignment(dto, locale, true, false);
        return toDtoWithLinks(assignment);
    }

    /**
     * @param adminRequest true for the {@code /assignments} writes (single,
     *                     multi-scope, bulk): the caller may grant only what
     *                     {@link UserAccountAccess#requireMayGrant} allows, at
     *                     a hospital it allows, checked before anything is
     *                     saved. False only for {@link #assignRoleOnAccountCreation},
     *                     whose caller has already decided the grant.
     */
    private UserRoleHospitalAssignment createAssignment(UserRoleHospitalAssignmentRequestDTO dto,
                                                        Locale locale,
                                                        boolean sendNotifications,
                                                        boolean adminRequest) {
        Role role = resolveRole(dto, locale);
        String roleCode = getRoleCode(role);
        // The role first, before anything about the assignee is looked up, so
        // a refused caller learns nothing from the user lookup either; then
        // the hospital once it is resolved. The same two steps as admin-register.
        UserAccountAccess.Grant grant = adminRequest ? requireMayGrant(roleCode) : null;
        if (adminRequest) {
            requireNotPatientGrant(roleCode);
        }

        enforceRoleScopeConstraints(dto, roleCode, adminRequest);

        Hospital hospital = resolveHospitalHumanAware(dto, role, locale);
        if (grant != null) {
            requireGrantAt(grant, hospital, roleCode);
        }
        facilityAssignmentGuard.requireCompatible(roleCode, hospital);
        // QA D789: an archived hospital takes no new role (a 409 before anything is saved).
        FacilityAssignmentGuard.requireOpenForAssignment(hospital);
        User user = resolveUser(dto, locale);
        facilityAssignmentGuard.requireSingleFacilityKind(user, roleCode, hospital, null);
        checkActiveDoctorConflict(dto, user, role, hospital, locale);
        checkExistingAssignment(user, role, hospital, locale);

        UserRoleHospitalAssignment assignment = mapper.toEntity(dto, user, hospital, role);
        assignment.setStartDate(dto.getStartDate() != null ? dto.getStartDate() : LocalDate.now());
        assignment.setAssignmentCode(generateAssignCode(user, hospital));
        assignment.setConfirmationCode(generateConfirmationCode());
        assignment.setConfirmationSentAt(LocalDateTime.now());
        assignment.setConfirmationVerifiedAt(null);

        // The registrar is the caller, by id, never a request field: it is the
        // one person /confirm accepts. An /assignments write needs one; account
        // creation has one only when an admin registers (none on
        // self-registration or bootstrap).
        assignment.setRegisteredBy(adminRequest
            ? requireCallerAccount(locale)
            : accountAccess.currentUserId().flatMap(userRepository::findById).orElse(null));

        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);

        log.info("✅ Assigned role '{}' to user {}{}",
            roleCode,
            user.getId(),
            hospital != null ? (" in hospital " + hospital.getId()) : " (global)");

        syncLegacyRole(user, role);
        if (sendNotifications) {
            // Publish an after-commit event so that email + SMS are only dispatched
            // once the transaction has committed and the assignment row is visible.
            // This prevents ghost links where the email is sent for a rolled-back assignment.
            eventPublisher.publishEvent(new AssignmentCreatedEvent(saved.getId(),
                !adminRequest && dto.isWelcomeMailCarriesCode()));
        }
        recordAssignmentAudit(saved);

        return saved;
    }

    private User resolveUser(UserRoleHospitalAssignmentRequestDTO dto, Locale locale) {
        if (dto.getUserId() != null) {
            return userRepository.findById(dto.getUserId()).orElseThrow(() ->
                new ResourceNotFoundException(MSG_USER_NOT_FOUND, dto.getUserId()));
        }

        String identifier = dto.getUserIdentifier();
        if (identifier != null && !identifier.isBlank()) {
            String trimmed = identifier.trim();
            return userRepository.findByUsername(trimmed)
                .or(() -> userRepository.findByEmail(trimmed))
                .or(() -> userRepository.findByPhoneNumber(trimmed))
                .orElseThrow(() -> new ResourceNotFoundException(MSG_USER_NOT_FOUND, trimmed));
        }

        throw new BusinessException(messageSource.getMessage(
            MSG_USER_NOT_FOUND,
            new Object[]{null},
            DEFAULT_USER_NOT_FOUND_PREFIX + "<unknown>",
            locale));
    }

    /**
     * QA D626/D664: no {@code /assignments} write (single, multi-scope, bulk,
     * or an update that changes the role) grants PATIENT, whoever asks. A
     * patient is registered from the patient form, which creates the patient
     * record with the grant; a bare PATIENT row here left an account with a
     * patient role and no patient. The same 400 as admin-register. An update
     * that keeps an existing PATIENT row a PATIENT row is not a grant.
     */
    private void requireNotPatientGrant(String roleCode) {
        if (isRoleCode(roleCode, ROLE_PATIENT)) {
            throw new BusinessException(MSG_PATIENT_FROM_PATIENT_FORM);
        }
    }

    private void enforceRoleScopeConstraints(UserRoleHospitalAssignmentRequestDTO dto, String roleCode,
                                             boolean adminRequest) {
        if (isRoleCode(roleCode, ROLE_SUPER_ADMIN)) {
            enforceSuperAdminScope(dto, adminRequest);
            return;
        }

        if (isRoleCode(roleCode, ROLE_PATIENT)) {
            // Patient assignments start inactive — activated after email verification
            if (Boolean.TRUE.equals(dto.getActive())) {
                throw new BusinessException("assignment.patient.mustStartInactive");
            }
            if (dto.getActive() == null) {
                dto.setActive(Boolean.FALSE);
            }
            return;
        }

        // All other roles (staff / admin): assignments start INACTIVE until the
        // assignee verifies the confirmation code sent by email.  The assignment
        // is activated in verifyAssignmentByCode() after successful verification.
        dto.setActive(Boolean.FALSE);
    }

    /**
     * A SUPER_ADMIN row is global, and active at once only when a verified
     * super-admin grants it, or when account creation asks for it explicitly
     * (the first-user bootstrap). Never by default: an admin request from
     * anyone else starts inactive whatever it asks for, and the grant check
     * has already refused it before this point.
     */
    private void enforceSuperAdminScope(UserRoleHospitalAssignmentRequestDTO dto, boolean adminRequest) {
        if (dto.getHospitalId() != null
            || (dto.getHospitalCode() != null && !dto.getHospitalCode().isBlank())
            || (dto.getHospitalName() != null && !dto.getHospitalName().isBlank())) {
            throw new BusinessException(DEFAULT_SUPER_ADMIN_SCOPE_MESSAGE);
        }
        boolean verifiedSuperAdmin = adminRequest && roleValidator.isSuperAdminFromJwtClaim();
        if (dto.getActive() == null || (adminRequest && !verifiedSuperAdmin)) {
            dto.setActive(verifiedSuperAdmin);
        }
    }

    /* ===================== Update ===================== */

    @Override
    public UserRoleHospitalAssignmentResponseDTO updateAssignment(UUID id, UserRoleHospitalAssignmentRequestDTO dto) {
        final Locale locale = LocaleContextHolder.getLocale();

        // The row as it stands must be one the caller may change, and the row
        // after the change one the caller could have granted (the create rule,
        // on the new role at the new hospital).
        UserRoleHospitalAssignment target = findChangeable(id);
        boolean verifiedSuperAdmin = roleValidator.isSuperAdminFromJwtClaim();
        // Before any user lookup: a 404 for an unknown id against a 400 for a
        // known one would tell the caller which user ids exist platform-wide.
        requireSameHolder(dto.getUserId(), target, verifiedSuperAdmin);

        User newUser = resolveUserForUpdate(dto, target);
        Role newRole = resolveRoleForUpdate(dto, target, locale);
        Hospital newHospital = resolveHospitalForUpdate(dto, target, newRole, locale);

        String newRoleCode = getRoleCode(newRole);
        requireGrantAt(requireMayGrant(newRoleCode), newHospital, newRoleCode);
        if (!isRoleCode(getRoleCode(target.getRole()), ROLE_PATIENT)) {
            requireNotPatientGrant(newRoleCode);
        }
        facilityAssignmentGuard.requireCompatible(newRoleCode, newHospital);
        requireActivationThroughCode(dto, target);
        // Decided before the mapper overwrites the row's current role and hospital.
        boolean reinvite = !verifiedSuperAdmin && isNewGrant(target, newRole, newHospital);
        // QA D789: a row that stays or comes on, or is re-invited, at an archived
        // hospital is refused; switching one off there is still allowed.
        boolean activeAfter = dto.getActive() != null ? dto.getActive() : Boolean.TRUE.equals(target.getActive());
        if (activeAfter || reinvite) {
            FacilityAssignmentGuard.requireOpenForAssignment(newHospital);
        }

        enforcePatientInactiveConstraint(dto, newRole, target);
        checkTupleDuplicateOnUpdate(id, target, newUser, newRole, newHospital, locale);
        // QA D902: a new role, a new holder or a switch-off takes the grant
        // away from whoever held it - never the caller's own, never the last
        // live super-admin's. Decided before the mapper overwrites the row.
        if (takesGrantAway(target, newUser, newRole, activeAfter)) {
            requireNotCallersOwn(target);
            requireSuperAdminRemains(List.of(target));
        }

        if (Boolean.TRUE.equals(dto.getActive())) {
            checkActiveDoctorConflict(dto, newUser, newRole, newHospital, locale);
        }
        // Resolved before anything on the row changes: a re-invite with no
        // registrar would leave a row nobody can confirm.
        User newRegistrar = reinvite ? requireCallerAccount(locale) : null;
        boolean wasActive = Boolean.TRUE.equals(target.getActive());

        mapper.updateEntity(target, dto, newHospital, newRole);
        target.setUser(newUser);
        if (reinvite) {
            restartInvitation(target, newUser, newHospital, newRegistrar);
        }

        if (Boolean.TRUE.equals(target.getActive())) {
            facilityAssignmentGuard.requireSingleFacilityKind(newUser, newRoleCode, newHospital, target.getId());
        }
        UserRoleHospitalAssignment saved = assignmentRepository.save(target);
        if (!wasActive && Boolean.TRUE.equals(saved.getActive())) {
            restoreLinkedStaff(saved);
        }
        if (reinvite) {
            eventPublisher.publishEvent(new AssignmentCreatedEvent(saved.getId()));
            recordAssignmentAudit(saved);
        }
        log.info("🔄 Updated assignment ID '{}' for user '{}'", id, newUser.getEmail());
        return toDtoWithLinks(saved);
    }

    /**
     * A new role or hospital is a new grant: for anyone but a verified
     * super-admin it starts over as a fresh POST would (see {@link #restartInvitation}).
     */
    private boolean isNewGrant(UserRoleHospitalAssignment target, Role newRole, Hospital newHospital) {
        return !newRole.getId().equals(target.getRole().getId())
            || hasDifferentHospital(target.getHospital(), newHospital);
    }

    /**
     * The row starts over as a fresh POST: inactive, new codes, verification
     * cleared, and the caller as its registrar. The registrar receives the new
     * code and is the one {@link #confirmAssignment} accepts; keeping the
     * original one would hand the new grant to someone the caller chose
     * nothing about, who may hold no rights at the new hospital.
     */
    private void restartInvitation(UserRoleHospitalAssignment target, User holder, Hospital hospital,
                                   User registrar) {
        target.setRegisteredBy(registrar);
        target.setActive(false);
        target.setAssignmentCode(generateAssignCode(holder, hospital));
        target.setConfirmationCode(generateConfirmationCode());
        target.setConfirmationSentAt(LocalDateTime.now(ZoneId.systemDefault()));
        target.setConfirmationVerifiedAt(null);
    }

    /**
     * The caller's own account, by the same id {@link UserAccountAccess#assignmentScope}
     * decided the caller's scope with, not by the principal's name (an OIDC
     * token without a username, or a phone-keyed account, would not resolve).
     *
     * @throws BusinessException when the caller has no resolvable account
     */
    private User requireCallerAccount(Locale locale) {
        return accountAccess.currentUserId()
            .flatMap(userRepository::findById)
            .orElseThrow(() -> new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ACTOR_MISMATCH,
                    null,
                    DEFAULT_ACTOR_RESOLUTION_FAILURE,
                    locale)));
    }

    /**
     * Only a super-admin hands an existing row to another person. For anyone
     * else that is a new grant to someone who never confirmed a code, so it
     * goes through a new assignment and its own verification.
     */
    private static void requireSameHolder(UUID requestedUserId, UserRoleHospitalAssignment target,
                                          boolean verifiedSuperAdmin) {
        User current = target.getUser();
        boolean holderChanged = requestedUserId != null
            && (current == null || !requestedUserId.equals(current.getId()));
        if (holderChanged && !verifiedSuperAdmin) {
            throw new BusinessException(DEFAULT_HOLDER_CHANGE);
        }
    }

    /**
     * Only a super-admin switches an assignment on by hand. Anyone else's
     * inactive row is activated by its holder's confirmation code (or the
     * registrar's confirm): the verification that ticking "active" would
     * otherwise skip. A fresh code is the regenerate path.
     */
    private void requireActivationThroughCode(UserRoleHospitalAssignmentRequestDTO dto,
                                              UserRoleHospitalAssignment target) {
        if (Boolean.TRUE.equals(dto.getActive()) && !Boolean.TRUE.equals(target.getActive())
                && !roleValidator.isSuperAdminFromJwtClaim()) {
            throw new BusinessException(DEFAULT_ACTIVATION_THROUGH_CODE);
        }
    }

    private User resolveUserForUpdate(UserRoleHospitalAssignmentRequestDTO dto,
                                      UserRoleHospitalAssignment target) {
        if (dto.getUserId() == null) {
            return target.getUser();
        }
        return userRepository.findById(dto.getUserId()).orElseThrow(() ->
            new ResourceNotFoundException(MSG_USER_NOT_FOUND, dto.getUserId()));
    }

    private Role resolveRoleForUpdate(UserRoleHospitalAssignmentRequestDTO dto,
                                      UserRoleHospitalAssignment target, Locale locale) {
        if (dto.getRoleId() != null || (dto.getRoleName() != null && !dto.getRoleName().isBlank())) {
            return resolveRole(dto, locale);
        }
        return target.getRole();
    }

    private Hospital resolveHospitalForUpdate(UserRoleHospitalAssignmentRequestDTO dto,
                                              UserRoleHospitalAssignment target, Role newRole, Locale locale) {
        if (dto.getHospitalId() != null) {
            return resolveHospital(dto, newRole, locale);
        }
        return target.getHospital();
    }

    private void enforcePatientInactiveConstraint(UserRoleHospitalAssignmentRequestDTO dto,
                                                  Role newRole, UserRoleHospitalAssignment target) {
        final String newRoleCode = getRoleCode(newRole);
        if (!isRoleCode(newRoleCode, ROLE_PATIENT)) {
            return;
        }
        // Patient assignments must remain inactive until email verification activates them
        if (Boolean.TRUE.equals(dto.getActive())) {
            throw new BusinessException("assignment.patient.cannotActivate");
        }
        if (dto.getActive() == null) {
            dto.setActive(target.getActive());
        }
    }

    private void checkTupleDuplicateOnUpdate(UUID id, UserRoleHospitalAssignment target,
                                             User newUser, Role newRole, Hospital newHospital, Locale locale) {
        boolean tupleChanged =
            !newUser.getId().equals(target.getUser().getId())
            || !newRole.getId().equals(target.getRole().getId())
            || hasDifferentHospital(target.getHospital(), newHospital);

        if (!tupleChanged) {
            return;
        }

        if (newHospital == null) {
            assignmentRepository.findByUserIdAndRoleIdAndHospitalIsNull(newUser.getId(), newRole.getId())
                .ifPresent(existing -> throwIfDifferentId(existing, id, locale));
        } else {
            assignmentRepository.findByUserIdAndHospitalIdAndRoleId(newUser.getId(), newHospital.getId(), newRole.getId())
                .ifPresent(existing -> throwIfDifferentId(existing, id, locale));
        }
    }

    private boolean hasDifferentHospital(Hospital current, Hospital proposed) {
        if (proposed == null) {
            return current != null;
        }
        return current == null || !proposed.getId().equals(current.getId());
    }

    private void throwIfDifferentId(UserRoleHospitalAssignment existing, UUID expectedId, Locale locale) {
        if (!existing.getId().equals(expectedId)) {
            throw new ConflictException(
                messageSource.getMessage(MSG_ASSIGNMENT_CONFLICT, null,
                    DEFAULT_ROLE_ALREADY_ASSIGNED, locale));
        }
    }

    /* ===================== Read ===================== */

    @Override
    @Transactional(readOnly = true)
    public UserRoleHospitalAssignmentResponseDTO getAssignmentById(UUID id) {
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(id)
            .filter(found -> accountAccess.assignmentScope().covers(found))
            .orElseThrow(() -> assignmentNotFound(id));
        return toDtoWithLinks(assignment);
    }

    /**
     * The row, when the caller may change it ({@link UserAccountAccess#assignmentScope}).
     * A row outside the caller's scope answers exactly as a missing id does,
     * so the refusal is not an existence oracle.
     */
    private UserRoleHospitalAssignment findChangeable(UUID id) {
        UserAccountAccess.AssignmentScope scope = accountAccess.assignmentScope();
        return assignmentRepository.findById(id)
            .filter(scope::mayChange)
            .filter(found -> !holderShielded(scope, found))
            .orElseThrow(() -> assignmentNotFound(id));
    }

    /**
     * {@link #findChangeable} for several rows under the given scope, each
     * holder's shield decided once. Every row is checked before the caller
     * changes any: one out of scope (or missing) answers exactly as a missing id.
     */
    private List<UserRoleHospitalAssignment> findAllChangeable(Collection<UUID> ids,
                                                               UserAccountAccess.AssignmentScope scope) {
        Map<UUID, Boolean> shieldedByHolder = new HashMap<>();
        // toList() is terminal: every row is checked (or the first refusal
        // thrown) before the caller changes anything.
        return new LinkedHashSet<>(ids).stream()
            .map(id -> assignmentRepository.findById(id)
                .filter(scope::mayChange)
                .filter(found -> !shieldedByHolder.computeIfAbsent(
                    found.getUser() == null ? null : found.getUser().getId(),
                    holderId -> holderShielded(scope, found)))
                .orElseThrow(() -> assignmentNotFound(id)))
            .toList();
    }

    /**
     * A super-admin's account is out of a hospital admin's reach row by row
     * too, not only through {@code DELETE /user}: any trace of the role,
     * active or not, by assignment or global role ({@link UserAccountAccess.AssignmentScope#shields}).
     */
    private boolean holderShielded(UserAccountAccess.AssignmentScope scope, UserRoleHospitalAssignment row) {
        User holder = row.getUser();
        return !scope.everywhere() && holder != null
            && scope.shields(holder, assignmentRepository.findByUserId(holder.getId()));
    }

    private static ResourceNotFoundException assignmentNotFound(UUID id) {
        return new ResourceNotFoundException(MSG_ASSIGNMENT_NOT_FOUND, id);
    }

    private UserAccountAccess.Grant requireMayGrant(String roleCode) {
        try {
            return accountAccess.requireMayGrant(Set.of(Objects.toString(roleCode, UNKNOWN_ROLE)));
        } catch (AccessDeniedException denied) {
            throw refusedGrant(roleCode, denied);
        }
    }

    /** A {@code null} hospital is a global row: the super-admin's only. */
    private void requireGrantAt(UserAccountAccess.Grant grant, Hospital hospital, String roleCode) {
        try {
            grant.requireAt(hospital == null ? null : hospital.getId());
        } catch (AccessDeniedException denied) {
            throw refusedGrant(roleCode, denied);
        }
    }

    /**
     * A grant the caller may not make, recorded: one FAILURE row with the
     * actor id and the requested role code only. Returned for the caller to
     * throw, a 403: what may be granted is not an existence question.
     */
    private AccessDeniedException refusedGrant(String roleCode, AccessDeniedException denied) {
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
            .userId(accountAccess.currentUserId().orElse(null))
            // QA D521: anchored at the hospital the caller acts at, so the
            // refusal reaches that hospital admin's audit log.
            .hospitalId(com.example.hms.security.tenant.ActingScopeResolver.pinnedHospitalIdOrNull())
            .eventType(AuditEventType.ROLE_ASSIGNED)
            .eventDescription("Role assignment refused: the caller may not grant this role there")
            .details(Map.of("requestedRole", Objects.toString(roleCode, UNKNOWN_ROLE)))
            .entityType(AUDIT_ENTITY_ASSIGNMENT)
            .status(AuditStatus.FAILURE)
            .build());
        return denied;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable) {
        return getAllAssignments(pageable, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable,
                                                                         AssignmentSearchCriteria criteria) {
        // ── Tenant isolation: non-super-admin callers only see their own hospital's
        //    assignments; the caller-supplied hospitalId filter is honoured only for
        //    super-admin (requireActiveHospitalId() == null) ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID requestedHospitalId = criteria != null ? parseUuid(criteria.getHospitalId()) : null;
        UUID hospitalId = activeHospitalId != null ? activeHospitalId : requestedHospitalId;
        Boolean active = criteria != null ? criteria.getActive() : null;
        String search = criteria != null ? criteria.getSearch() : null;
        String assignmentCode = criteria != null ? criteria.getAssignmentCode() : null;

        Specification<UserRoleHospitalAssignment> specification = buildAssignmentSpec(
            hospitalId, active, search, assignmentCode);

        if (specification == null) {
            log.info("📄 Fetching assignments without filters (page {}, size {})", pageable.getPageNumber(),
                pageable.getPageSize());
            return assignmentRepository.findAll(pageable).map(this::toDtoWithLinks);
        }

        log.info("📄 Fetching assignments with filters hospitalId={}, active={}, search='{}', assignmentCode='{}' (page {}, size {})",
            hospitalId, active, search, assignmentCode, pageable.getPageNumber(), pageable.getPageSize());
        return assignmentRepository.findAll(specification, pageable).map(this::toDtoWithLinks);
    }

    private Specification<UserRoleHospitalAssignment> buildAssignmentSpec(
            UUID hospitalId, Boolean active, String search, String assignmentCode) {
        Specification<UserRoleHospitalAssignment> spec = null;
        spec = combineSpec(spec, UserRoleHospitalAssignmentSpecification.belongsToHospital(hospitalId));
        spec = combineSpec(spec, UserRoleHospitalAssignmentSpecification.hasActive(active));

        String trimmedSearch = search == null ? null : search.trim();
        if (StringUtils.hasText(trimmedSearch)) {
            spec = combineSpec(spec, UserRoleHospitalAssignmentSpecification.matchesSearch(trimmedSearch));
        }

        String trimmedCode = assignmentCode == null ? null : assignmentCode.trim();
        if (StringUtils.hasText(trimmedCode)) {
            spec = combineSpec(spec, UserRoleHospitalAssignmentSpecification.hasAssignmentCode(trimmedCode));
        }
        return spec;
    }

    private Specification<UserRoleHospitalAssignment> combineSpec(
            Specification<UserRoleHospitalAssignment> existing,
            Specification<UserRoleHospitalAssignment> addition) {
        if (addition == null) {
            return existing;
        }
        return existing == null ? addition : existing.and(addition);
    }

    @Override
    public UserRoleAssignmentBatchResponseDTO assignRoleToMultipleScopes(UserRoleAssignmentMultiRequestDTO requestDTO) {
        Objects.requireNonNull(requestDTO, "Request must not be null");

        Locale locale = LocaleContextHolder.getLocale();
        Set<UUID> hospitalIds = collectTargetHospitalIds(requestDTO);
        List<UserRoleHospitalAssignmentResponseDTO> successes = new ArrayList<>();
        List<UserRoleAssignmentFailureDTO> failures = new ArrayList<>();

        if (hospitalIds.isEmpty()) {
            UserRoleHospitalAssignment assignment = createAssignment(
                buildSingleAssignmentRequest(requestDTO, null),
                locale,
                requestDTO.isSendNotifications(),
                true);
            successes.add(toDtoWithLinks(assignment));
            return UserRoleAssignmentBatchResponseDTO.builder()
                .requestedAssignments(1)
                .createdAssignments(1)
                .assignments(successes)
                .failures(failures)
                .build();
        }

        requireMayGrantAtEveryScope(requestDTO, hospitalIds, locale);

        for (UUID hospitalId : hospitalIds) {
            UserRoleHospitalAssignmentRequestDTO singleRequest = buildSingleAssignmentRequest(requestDTO, hospitalId);
            try {
                UserRoleHospitalAssignment assignment = createAssignment(singleRequest, locale, requestDTO.isSendNotifications(), true);
                successes.add(toDtoWithLinks(assignment));
            } catch (ConflictException conflict) {
                if (!requestDTO.isSkipConflicts()) {
                    throw conflict;
                }
                failures.add(UserRoleAssignmentFailureDTO.builder()
                    .hospitalId(hospitalId)
                    .scopeLabel("hospital:" + hospitalId)
                    .message(conflict.getMessage())
                    .build());
            } catch (BusinessException | ResourceNotFoundException | AccessDeniedException ex) {
                // A scope the caller may not grant at is reported like any
                // other refused scope: nothing was saved for it (the grant is
                // checked before the save), and the scopes the caller does
                // administer still go through.
                failures.add(UserRoleAssignmentFailureDTO.builder()
                    .hospitalId(hospitalId)
                    .scopeLabel("hospital:" + hospitalId)
                    .message(ex.getMessage())
                    .build());
            }
        }

        return UserRoleAssignmentBatchResponseDTO.builder()
            .requestedAssignments(hospitalIds.size())
            .createdAssignments(successes.size())
            .skippedAssignments(failures.size())
            .assignments(successes)
            .failures(failures)
            .build();
    }

    /**
     * QA D541 — a scope the caller may not grant at is refused like the single
     * POST /assignments refuses it: a 403 for the whole request, audited, before
     * anything is saved. It used to come back 200 with hospital B quietly moved
     * to {@code failures} while hospital A's row was created. Scopes that fail
     * for any other reason (unknown hospital, conflict) are still reported per
     * scope by the loop.
     */
    private void requireMayGrantAtEveryScope(UserRoleAssignmentMultiRequestDTO requestDTO,
                                             Set<UUID> hospitalIds, Locale locale) {
        UserRoleHospitalAssignmentRequestDTO first =
            buildSingleAssignmentRequest(requestDTO, hospitalIds.iterator().next());
        Role role = resolveRole(first, locale);
        String roleCode = getRoleCode(role);
        UserAccountAccess.Grant grant = requireMayGrant(roleCode);
        for (UUID hospitalId : hospitalIds) {
            Hospital hospital;
            try {
                hospital = resolveHospitalHumanAware(buildSingleAssignmentRequest(requestDTO, hospitalId), role, locale);
            } catch (BusinessException | ResourceNotFoundException unresolved) {
                continue;
            }
            requireGrantAt(grant, hospital, roleCode);
        }
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO regenerateAssignmentCode(UUID assignmentId, boolean resendNotifications) {
        return toDtoWithLinks(reissueCode(findChangeable(assignmentId), resendNotifications));
    }

    @Override
    public void regenerateProviderStaffAssignmentCodes(Collection<UUID> ids, boolean resendNotifications) {
        for (UserRoleHospitalAssignment assignment : findAllChangeable(ids, accountAccess.providerStaffScope())) {
            reissueCode(assignment, resendNotifications);
        }
    }

    /** A new assignment code and confirmation code for a row already found changeable. */
    private UserRoleHospitalAssignment reissueCode(UserRoleHospitalAssignment assignment, boolean resendNotifications) {
        // QA D789: no fresh invitation into an archived hospital.
        FacilityAssignmentGuard.requireOpenForAssignment(assignment.getHospital());
        assignment.setAssignmentCode(generateAssignCode(assignment.getUser(), assignment.getHospital()));
        assignment.setConfirmationCode(generateConfirmationCode());
        assignment.setConfirmationSentAt(LocalDateTime.now());
        assignment.setConfirmationVerifiedAt(null);

        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);
        if (resendNotifications) {
            eventPublisher.publishEvent(new AssignmentCreatedEvent(saved.getId()));
        }
        recordAssignmentAudit(saved);
        log.info("🔁 Regenerated assignment code for assignment '{}'", saved.getId());
        return saved;
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO confirmAssignment(UUID assignmentId, String confirmationCode) {
        Locale locale = LocaleContextHolder.getLocale();
        String sanitizedCode = confirmationCode != null ? confirmationCode.trim() : null;
        if (sanitizedCode == null || sanitizedCode.isBlank()) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_INVALID_CODE,
                    null,
                    DEFAULT_CONFIRMATION_CODE_INVALID,
                    locale));
        }

        // Confirming activates the row, so it takes the scope every other
        // change by id takes: a row the caller may not change answers exactly
        // as a missing id, before anything else about the row is checked.
        UserRoleHospitalAssignment assignment = findChangeable(assignmentId);

        if (assignment.getConfirmationVerifiedAt() != null) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ALREADY_CONFIRMED,
                    null,
                    DEFAULT_ASSIGNMENT_ALREADY_CONFIRMED,
                    locale));
        }

        // Only the registrar confirms, and that is decided BEFORE the code is
        // compared: anyone else gets the same refusal whatever code they send,
        // so the endpoint is no oracle for guessing the holder's code.
        User registrar = assignment.getRegisteredBy();
        // By id, as a re-invite records the registrar (requireCallerAccount):
        // a phone-login registrar's principal name resolves no account.
        User actor = requireCallerAccount(locale);

        if (registrar == null || registrar.getId() == null || !registrar.getId().equals(actor.getId())) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ACTOR_MISMATCH,
                    null,
                    DEFAULT_CONFIRMATION_ACTOR_MISMATCH,
                    locale));
        }

        String expectedCode = assignment.getConfirmationCode();
        if (expectedCode == null || !expectedCode.equalsIgnoreCase(sanitizedCode)) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_INVALID_CODE,
                    null,
                    DEFAULT_CONFIRMATION_CODE_INVALID,
                    locale));
        }

        // Registrar confirmation presents the SAME confirmation code the
        // assignee received, so it completes verification outright. Since
        // option A every account starts inactive; a confirm that only stamped
        // confirmationVerifiedAt would trip verifyAssignmentByCode's
        // already-verified branch and the assignee could never activate
        // through the advertised path.
        activateVerifiedAssignment(assignment, "registrar confirmation");

        recordAssignmentConfirmationAudit(assignment, actor);
        log.info("✅ Assignment '{}' confirmed by actor '{}'", assignment.getId(), actor.getId());
        return toDtoWithLinks(assignment);
    }

    @Override
    public UserRoleAssignmentPublicViewDTO getAssignmentPublicView(String assignmentCode) {
        String sanitized = assignmentCode != null ? assignmentCode.trim() : null;
        if (sanitized == null || sanitized.isBlank()) {
            throw new ResourceNotFoundException(MSG_ASSIGNMENT_NOT_FOUND_BY_CODE, assignmentCode);
        }

        UserRoleHospitalAssignment assignment = assignmentRepository.findByAssignmentCode(sanitized)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ASSIGNMENT_NOT_FOUND_BY_CODE, sanitized));

        Role role = assignment.getRole();
        Hospital hospital = assignment.getHospital();
        User assignee = assignment.getUser();

        return UserRoleAssignmentPublicViewDTO.builder()
            .assignmentId(assignment.getId())
            .assignmentCode(assignment.getAssignmentCode())
            .roleName(role != null ? role.getName() : null)
            .roleCode(role != null ? getRoleCode(role) : null)
            .roleDescription(role != null ? role.getDescription() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .hospitalCode(hospital != null ? hospital.getCode() : null)
            .hospitalAddress(hospital != null ? hospital.getAddress() : null)
            .assigneeName(resolveDisplayName(assignee, null))
            .confirmationVerified(assignment.getConfirmationVerifiedAt() != null)
            .confirmationVerifiedAt(assignment.getConfirmationVerifiedAt())
            .profileCompletionUrl(profileCompletionUrl(assignment))
            .profileChecklist(buildRoleProfileChecklist(role))
            .build();
    }

    @Override
    public UserRoleAssignmentPublicViewDTO verifyAssignmentByCode(String assignmentCode, String confirmationCode) {
        String sanitizedCode = sanitizeRequiredInput(assignmentCode, MSG_ASSIGNMENT_NOT_FOUND_BY_CODE);
        String sanitizedConfirmation = sanitizeConfirmationInput(confirmationCode);

        UserRoleHospitalAssignment assignment = assignmentRepository.findByAssignmentCode(sanitizedCode)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ASSIGNMENT_NOT_FOUND_BY_CODE, sanitizedCode));

        // Idempotent: already verified AND active → existing state (no save).
        // A verified-but-INACTIVE row is the wedged state a pre-fix registrar
        // confirm left behind (timestamp stamped, nothing activated); it falls
        // through so the code check below still gates and the activation
        // finishes what that confirm intended.
        if (assignment.getConfirmationVerifiedAt() != null
                && Boolean.TRUE.equals(assignment.getActive())) {
            log.info("⏭️ Assignment '{}' is already verified — returning current public view.", sanitizedCode);
            return buildPublicViewDTO(assignment, null, null);
        }

        verifyCodeNotExpiredAndMatches(assignment, sanitizedConfirmation);

        // Capture one-time temp credentials BEFORE clearing them
        String tempUsername = assignment.getUser() != null ? assignment.getUser().getUsername() : null;
        String tempPassword = assignment.getTempPlainPassword();

        activateVerifiedAssignment(assignment, "assignee code entry");

        // Include credentials if this was a new-user assignment (temp creds were set)
        String exposedUsername = tempPassword != null ? tempUsername : null;
        return buildPublicViewDTO(assignment, exposedUsername, tempPassword);
    }

    private String sanitizeRequiredInput(String value, String messageKey) {
        String sanitized = value != null ? value.trim() : null;
        if (sanitized == null || sanitized.isBlank()) {
            throw new ResourceNotFoundException(messageKey, value);
        }
        return sanitized;
    }

    private String sanitizeConfirmationInput(String confirmationCode) {
        String sanitized = confirmationCode != null ? confirmationCode.trim() : null;
        if (sanitized == null || sanitized.isBlank()) {
            throw new BusinessException(
                messageSource.getMessage(MSG_ASSIGNMENT_INVALID_CODE, null,
                    DEFAULT_CONFIRMATION_CODE_INVALID, LocaleContextHolder.getLocale()));
        }
        return sanitized;
    }

    private void verifyCodeNotExpiredAndMatches(UserRoleHospitalAssignment assignment, String sanitizedConfirmation) {
        if (assignment.getConfirmationSentAt() != null
                && assignment.getConfirmationSentAt().plusHours(CONFIRMATION_CODE_EXPIRY_HOURS)
                    .isBefore(LocalDateTime.now())) {
            throw new BusinessException(
                "users.verification.expiredContactAdmin");
        }
        String expectedCode = assignment.getConfirmationCode();
        if (expectedCode == null || !expectedCode.equalsIgnoreCase(sanitizedConfirmation)) {
            throw new BusinessException(
                messageSource.getMessage(MSG_ASSIGNMENT_INVALID_CODE, null,
                    DEFAULT_CONFIRMATION_CODE_INVALID, LocaleContextHolder.getLocale()));
        }
    }

    private void activateVerifiedAssignment(UserRoleHospitalAssignment assignment, String source) {
        // An account an administrator switched off (V184) is not turned on
        // from below, and neither is its assignment: the code is refused
        // outright, with nothing confirmed and no credentials handed out.
        if (assignment.getUser() != null && assignment.getUser().getDeactivatedByAdminAt() != null) {
            throw new BusinessException(messageSource.getMessage(
                MSG_ACCOUNT_DISABLED_BY_ADMIN, null, DEFAULT_ACCOUNT_DISABLED_BY_ADMIN,
                org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
        // QA D789: an invitation to a hospital archived since is not switched on.
        FacilityAssignmentGuard.requireOpenForAssignment(assignment.getHospital());
        // One kind of facility per user: two pending rows, one at a hospital
        // and one at a provider, cannot both come on.
        facilityAssignmentGuard.requireSingleFacilityKind(assignment.getUser(), getRoleCode(assignment.getRole()),
            assignment.getHospital(), assignment.getId());
        // Mark the assignment as verified AND activate it. An existing
        // timestamp is preserved: the healing path re-runs activation for
        // rows an older registrar confirm stamped without activating.
        if (assignment.getConfirmationVerifiedAt() == null) {
            assignment.setConfirmationVerifiedAt(LocalDateTime.now());
        }
        assignment.setActive(true);
        assignment.setTempPlainPassword(null);
        assignmentRepository.save(assignment);
        restoreLinkedStaff(assignment);
        log.info("✅ Assignment '{}' verified and activated via {}.", assignment.getId(), source);

        // Activate the user account if it was pending assignment verification
        User user = assignment.getUser();
        if (user != null && !Boolean.TRUE.equals(user.isActive())) {
            user.setActive(true);
            userRepository.save(user);
            // Clear any login lockout accumulated while the account was
            // inactive. The welcome mail hands out temp credentials, so trying
            // them before confirming the code is the expected mistake, and
            // those refusals count toward the lockout; without this reset the
            // holder is locked out at the exact moment activation succeeds.
            // After commit: confirmAssignment is @Transactional and records an
            // audit event after this point, so a rollback would otherwise
            // leave the user inactive with the counter already cleared.
            final UUID activatedId = user.getId();
            TransactionCallbacks.afterCommit(
                () -> loginAttemptService.resetAttempts(activatedId));
            log.info("✅ User '{}' activated after first assignment verification.", user.getUsername());
        }

        // Activate the Patient entity if this was a PATIENT role assignment
        if (user != null && assignment.getRole() != null
                && ROLE_PATIENT.equalsIgnoreCase(assignment.getRole().getCode())) {
            patientRepository.findByUserId(user.getId()).ifPresent(patient -> {
                if (!patient.isActive()) {
                    patient.setActive(true);
                    patientRepository.save(patient);
                    log.info("✅ Patient record activated for user '{}'.", user.getUsername());
                }
            });
        }
    }

    /**
     * Builds a {@link UserRoleAssignmentPublicViewDTO} directly from the given assignment entity,
     * optionally injecting one-time temp credentials.
     */
    private UserRoleAssignmentPublicViewDTO buildPublicViewDTO(
            UserRoleHospitalAssignment assignment, String tempUsername, String tempPassword) {
        Role role = assignment.getRole();
        Hospital hospital = assignment.getHospital();
        User assignee = assignment.getUser();
        return UserRoleAssignmentPublicViewDTO.builder()
            .assignmentId(assignment.getId())
            .assignmentCode(assignment.getAssignmentCode())
            .roleName(role != null ? role.getName() : null)
            .roleCode(role != null ? getRoleCode(role) : null)
            .roleDescription(role != null ? role.getDescription() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .hospitalCode(hospital != null ? hospital.getCode() : null)
            .hospitalAddress(hospital != null ? hospital.getAddress() : null)
            .assigneeName(resolveDisplayName(assignee, null))
            .confirmationVerified(assignment.getConfirmationVerifiedAt() != null)
            .confirmationVerifiedAt(assignment.getConfirmationVerifiedAt())
            .profileCompletionUrl(profileCompletionUrl(assignment))
            .profileChecklist(buildRoleProfileChecklist(role))
            .tempUsername(tempUsername)
            .tempPassword(tempPassword)
            .build();
    }

    @Override
    public UserRoleAssignmentBulkImportResponseDTO bulkImportAssignments(UserRoleAssignmentBulkImportRequestDTO requestDTO) {
        Objects.requireNonNull(requestDTO, "Request must not be null");
        String csvContent = requestDTO.getCsvContent();
        if (csvContent == null || csvContent.isBlank()) {
            throw new BusinessException(DEFAULT_PROCESSING_ERROR);
        }

        String delimiter = (requestDTO.getDelimiter() != null && !requestDTO.getDelimiter().isBlank())
            ? requestDTO.getDelimiter()
            : ",";

        List<UserRoleAssignmentBulkImportResultDTO> results = new ArrayList<>();
        BulkImportAccumulator accumulator = new BulkImportAccumulator();

        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new BusinessException(DEFAULT_PROCESSING_ERROR);
            }

            Map<String, Integer> headerIndex = buildHeaderIndex(splitCsvLine(headerLine, delimiter));
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                accumulator.incrementProcessed();

                String[] tokens = splitCsvLine(line, delimiter);
                BulkImportOutcome outcome = handleBulkImportRow(rowNumber, tokens, headerIndex, requestDTO);
                accumulator.applyOutcome(outcome.status());
                results.add(outcome.result());
            }
        } catch (IOException ex) {
            throw new BusinessException(DEFAULT_PROCESSING_ERROR, ex);
        }

        return accumulator.toResponse(results);
    }

    /* ===================== Delete ===================== */

    @Override
    public void deleteAssignment(UUID id) {
        UserRoleHospitalAssignment doomed = findChangeable(id);
        // QA D902: a super-admin deleted their own global assignment, the
        // platform's only one, and lost the role. Never your own row, and
        // never the last live super-admin's.
        requireNotCallersOwn(doomed);
        requireSuperAdminRemains(List.of(doomed));
        // Prevent deletion if a Staff record still references this assignment.
        // Deleting it would leave the staff row with a dangling FK and cause a 500
        // on every /api/staff call that touches that row.
        if (staffRepository.existsByAssignment_Id(id)) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.assignment.deleteStaffLinked"));
        }
        // The same for the encounters it attended: they are clinical history,
        // and an encounter pointing at a deleted assignment could not be edited
        // by anyone (dev, 2026-09-13). V175 makes the database refuse it too;
        // this says why in words. Deactivate instead - it keeps the history.
        if (encounterRepository.existsByAssignment_Id(id)) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolveOrRaw("users.assignment.deleteHasEncounters"));
        }
        // QA round 14: a department (and twenty-odd other records) can name
        // the assignment too, and the delete reached the foreign key and came
        // back as a 500. Every kind of record that still points at it is named.
        List<String> stillUsedBy = assignmentReferences.referencingEntities(id);
        if (!stillUsedBy.isEmpty()) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve(
                "users.assignment.deleteStillReferenced", referenceLabels(stillUsedBy)));
        }
        try {
            assignmentRepository.deleteById(id);
            // Flushed here so a key the metamodel cannot see (a row hidden by a
            // soft-delete restriction, a column only the database knows) is
            // refused in words too, not at commit as a 500.
            assignmentRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            log.warn("Assignment {} is still referenced by a database key the pre-check did not see", id);
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve(
                "users.assignment.deleteStillReferenced",
                com.example.hms.utility.MessageUtil.resolve("users.assignment.reference.other")));
        }
        dropUserRoleWhenLastAssignment(doomed);
        log.info("🗑️ Deleted assignment ID '{}'", id);
    }

    /** The referencing entities in the caller's language, e.g. "departments, prescriptions". */
    private static String referenceLabels(List<String> entities) {
        return entities.stream()
            .map(entity -> com.example.hms.utility.MessageUtil.resolve("users.assignment.reference." + entity))
            .distinct()
            .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Deleting the last assignment of a role also drops the user-level
     * {@code user_roles} link that {@code assignRole} created with it. Left
     * behind, that link had no assignment to govern it and was honoured as a
     * legacy global role: deleting a stray patient assignment on a
     * receptionist brought ROLE_PATIENT back at sign-in (QA D74, D132).
     */
    private void dropUserRoleWhenLastAssignment(UserRoleHospitalAssignment doomed) {
        User user = doomed.getUser();
        Role role = doomed.getRole();
        if (user == null || role == null || user.getId() == null || role.getId() == null) {
            return;
        }
        boolean roleStillAssigned = assignmentRepository.findAllByUserId(user.getId()).stream()
            .filter(other -> !doomed.getId().equals(other.getId()))
            .map(UserRoleHospitalAssignment::getRole)
            .filter(Objects::nonNull)
            .anyMatch(other -> role.getId().equals(other.getId()));
        if (roleStillAssigned) {
            return;
        }
        user.removeRole(role);
        userRepository.save(user);
    }

    @Override
    public void deactivateProviderStaffAssignments(Collection<UUID> ids) {
        List<UserRoleHospitalAssignment> rows = findAllChangeable(ids, accountAccess.providerStaffScope());
        // QA D902: checked on every row before any is switched off.
        rows.forEach(this::requireNotCallersOwn);
        requireSuperAdminRemains(rows);
        List<UserRoleHospitalAssignment> retired = rows.stream()
            .filter(UserRoleHospitalAssignmentServiceImpl::retire)
            .toList();
        if (!retired.isEmpty()) {
            assignmentRepository.saveAll(retired);
        }
        log.info("🔒 Deactivated {} of {} assignment(s) (soft — history preserved).", retired.size(), ids.size());
    }

    @Override
    public void deactivateAssignment(UUID id) {
        UserRoleHospitalAssignment assignment = findChangeable(id);
        requireNotCallersOwn(assignment);
        requireSuperAdminRemains(List.of(assignment));
        if (!retire(assignment)) {
            log.info("⏭️ Assignment ID '{}' is already inactive with no live invitation — no change.", id);
            return;
        }
        assignmentRepository.save(assignment);
        log.info("🔒 Deactivated assignment ID '{}' (soft — history preserved).", id);
    }

    /**
     * Takes an assignment out of use for good: inactive, and its invitation
     * revoked. Returns whether anything changed.
     *
     * <p>Inactive alone is not enough. The public code-entry endpoint
     * ({@link #verifyAssignmentByCode}) goes on to check the code for ANY row
     * that is not both verified and active, and a matching code runs
     * {@code activateVerifiedAssignment}, which switches the assignment AND the
     * user account back on. A row that is only flagged inactive therefore came
     * back on its old invitation code for as long as that code was valid - a
     * deactivated invitation, or a removed user's assignment, re-enabled by
     * whoever held the code. Clearing the code (and the one-time temporary
     * password the verification would hand out) makes the refusal final;
     * re-inviting goes through the resend path, which issues a new code.
     */
    private static boolean retire(UserRoleHospitalAssignment assignment) {
        boolean changed = stillGrantsAccess(assignment);
        assignment.setActive(false);
        assignment.setConfirmationCode(null);
        assignment.setTempPlainPassword(null);
        return changed;
    }

    /** Active, or still able to come on through its code: what {@link #retire} takes away. */
    private static boolean stillGrantsAccess(UserRoleHospitalAssignment assignment) {
        return !Boolean.FALSE.equals(assignment.getActive())
            || assignment.getConfirmationCode() != null
            || assignment.getTempPlainPassword() != null;
    }

    /**
     * Retires every assignment a user holds - by deactivating it, not by
     * deleting the row.
     *
     * <p>Removing a user is a soft delete, and this used to hard-delete their
     * assignments underneath it. Encounters, staff rows and a dozen other
     * clinical tables keep the assignment id they were recorded under, so a
     * departed clinician's history was left pointing at nothing: on dev four
     * encounters could no longer be edited by anyone (2026-09-13). Deactivated,
     * the row still says who acted in which role at which hospital, the user's
     * restore finds it, and V175's foreign keys can hold. Each row's invitation
     * is revoked with it, so no old code can switch it (or the account) back on.
     */
    @Override
    public void deleteAllAssignmentsForUser(UUID userId) {
        requireNotCallersOwnAccount(userId);
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(userId);
        requireSuperAdminRemains(assignments);
        // Every row, including a pending invitation that was never active:
        // its code must stop working too (see retire).
        List<UserRoleHospitalAssignment> deactivated = assignments.stream()
            .filter(UserRoleHospitalAssignmentServiceImpl::retire)
            .toList();
        assignmentRepository.saveAll(deactivated);
        log.info("🔒 Deactivated {} of {} assignment(s) for user ID '{}' (soft — history preserved).",
            deactivated.size(), assignments.size(), userId);
    }

    @Override
    public int reissuePendingInvitationsForRestoredUser(UUID userId) {
        List<UserRoleHospitalAssignment> pending = assignmentRepository.findByUserId(userId).stream()
            .filter(a -> a.getConfirmationVerifiedAt() == null)
            // Only invitations that went out as a code: a patient row activated
            // by the e-mail link never had one and must not get staff mail.
            .filter(a -> a.getConfirmationSentAt() != null)
            // QA D789: an invitation to a hospital closed since is not
            // re-issued. Skipped, not refused: restoring the account must not
            // fail because one of its old invitations can no longer be used.
            .filter(a -> !atClosedHospital(a))
            .toList();
        for (UserRoleHospitalAssignment assignment : pending) {
            reissueCode(assignment, true);
        }
        log.info("🔁 Reissued {} pending invitation(s) for restored user ID '{}'.", pending.size(), userId);
        return pending.size();
    }

    /**
     * {@code DELETE /assignments/user/{userId}}: retires the user's rows the
     * caller may change ({@link UserAccountAccess#assignmentScope}). A
     * super-admin retires every row, as {@link #deleteAllAssignmentsForUser}
     * does; a hospital admin only the rows at hospitals they administer, never
     * an admin role's row, and none at all of a super-admin's account. Rows
     * outside the scope are left untouched and unreported, the same answer as
     * for a user with none.
     */
    @Override
    public void retireAssignmentsForUserWithinCallerScope(UUID userId) {
        // QA D902: never the caller's own account, whatever their scope.
        requireNotCallersOwnAccount(userId);
        UserAccountAccess.AssignmentScope scope = accountAccess.assignmentScope();
        if (scope.everywhere()) {
            deleteAllAssignmentsForUser(userId);
            return;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(userId);
        boolean shielded = userRepository.findById(userId)
            .map(user -> scope.shields(user, assignments))
            .orElse(false);
        List<UserRoleHospitalAssignment> candidates = shielded
            ? List.of()
            : assignments.stream().filter(scope::mayChange).toList();
        // QA D902: checked on the rows as they are now - once retire() has
        // switched them off, none reads as a live super-admin and the check
        // could never refuse. scope.shields already keeps a super-admin's
        // account out of a hospital admin's reach; this holds even if it
        // did not.
        requireSuperAdminRemains(candidates);
        List<UserRoleHospitalAssignment> deactivated = candidates.stream()
            .filter(UserRoleHospitalAssignmentServiceImpl::retire)
            .toList();
        if (!deactivated.isEmpty()) {
            assignmentRepository.saveAll(deactivated);
        }
        log.info("🔒 Deactivated {} assignment(s) for user ID '{}' within the caller's scope.",
            deactivated.size(), userId);
    }

    @Override
    public int retireStaffAccessAtHospital(UUID userId, UUID hospitalId, UUID staffAssignmentId) {
        if (userId == null) {
            return 0;
        }
        // QA D902: never the caller's own access, whatever their scope.
        requireNotCallersOwnAccount(userId);
        // Only an administrator of that hospital switches its staff off: holding
        // any other role there (a nurse at B who administers A) is not enough.
        UserAccountAccess.AssignmentScope scope = accountAccess.assignmentScope();
        if (!scope.everywhere() && (hospitalId == null || !scope.hospitalIds().contains(hospitalId))) {
            throw new AccessDeniedException("Access denied");
        }
        List<UserRoleHospitalAssignment> held = assignmentRepository.findByUserId(userId);
        List<UserRoleHospitalAssignment> doomed = held.stream()
            .filter(row -> isStaffAccessAt(row, hospitalId, staffAssignmentId))
            .filter(UserRoleHospitalAssignmentServiceImpl::stillGrantsAccess)
            .toList();
        if (doomed.isEmpty()) {
            return 0;
        }
        // Every row or none: a staff record switched off while an
        // administrator role at the same hospital stays on would again say
        // "no longer has access" about someone who still has it.
        boolean outOfReach = !scope.everywhere()
            && (scope.shields(doomed.get(0).getUser(), held) || !doomed.stream().allMatch(scope::mayChange));
        if (outOfReach) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve(MSG_STAFF_ACCESS_OUT_OF_REACH));
        }
        // QA D902: on the rows as they are now, before retire() switches them off.
        requireSuperAdminRemains(doomed);
        User actor = accountAccess.currentUserId().flatMap(userRepository::findById).orElse(null);
        // Built while the rows are still attached; written only once the
        // switch-off has committed, so a rollback leaves no revocation behind.
        List<AuditEventRequestDTO> events = doomed.stream()
            .map(row -> staffAccessRevokedEvent(row, actor))
            .toList();
        doomed.forEach(UserRoleHospitalAssignmentServiceImpl::retire);
        assignmentRepository.saveAll(doomed);
        // logEvents never throws: a failed audit must not turn a committed
        // deactivation into a 500.
        TransactionCallbacks.afterCommit(() -> auditEventLogService.logEvents(events));
        log.info("🔒 Staff record deactivated: retired {} assignment(s) for user ID '{}' at hospital '{}'.",
            doomed.size(), userId, hospitalId);
        return doomed.size();
    }

    /**
     * QA D907: a staff role row at this hospital, or the staff record's own
     * row when it is a platform-wide one. A patient row is the holder's own
     * care, never staff access; a row at another hospital is that hospital's.
     */
    private boolean isStaffAccessAt(UserRoleHospitalAssignment row, UUID hospitalId, UUID staffAssignmentId) {
        if (row.getRole() == null || isRoleCode(getRoleCode(row.getRole()), ROLE_PATIENT)) {
            return false;
        }
        if (row.getHospital() == null) {
            return staffAssignmentId != null && staffAssignmentId.equals(row.getId());
        }
        return hospitalId != null && hospitalId.equals(row.getHospital().getId());
    }

    private AuditEventRequestDTO staffAccessRevokedEvent(UserRoleHospitalAssignment row, User actor) {
        User assignee = row.getUser();
        String roleCode = getRoleCode(row.getRole());
        String assigneeDisplay = resolveDisplayName(assignee, "unknown user");
        Map<String, Object> details = new HashMap<>();
        details.put("revokedRole", roleCode);
        details.put("reason", "STAFF_DEACTIVATED");
        if (assignee != null) {
            details.put("assigneeId", assignee.getId());
        }
        return AuditEventRequestDTO.builder()
            .userId(actor != null ? actor.getId() : null)
            .userName(resolveDisplayName(actor, null))
            .assignmentId(row.getId())
            .hospitalName(resolveHospitalName(row.getHospital()))
            .hospitalId(row.getHospital() != null ? row.getHospital().getId() : null)
            .resourceName(assigneeDisplay)
            .resourceId(row.getId() != null ? row.getId().toString() : null)
            .entityType(AUDIT_ENTITY_ASSIGNMENT)
            .eventType(AuditEventType.ROLE_REVOKED)
            .eventDescription(String.format("Revoked role '%s' from %s: staff record deactivated",
                roleCode, assigneeDisplay))
            .status(AuditStatus.SUCCESS)
            .details(details)
            .build();
    }

    /**
     * QA D907: the staff record a staff deactivation switched off with this
     * assignment comes back with it, so /staff does not go on reading
     * "inactive" for someone who works here again.
     */
    private void restoreLinkedStaff(UserRoleHospitalAssignment assignment) {
        if (assignment.getId() == null) {
            return;
        }
        UUID hospitalId = assignment.getHospital() != null ? assignment.getHospital().getId() : null;
        // Only a record at the assignment's own hospital: a row a super-admin
        // moved to another hospital must not revive the old hospital's staff
        // record when the new hospital's admin re-invites it (#968 review).
        List<Staff> retired = staffRepository.findByAssignment_Id(assignment.getId()).stream()
            .filter(staff -> !staff.isActive())
            .filter(staff -> hospitalId != null && staff.getHospital() != null
                && hospitalId.equals(staff.getHospital().getId()))
            .toList();
        retired.forEach(staff -> staff.setActive(true));
        if (!retired.isEmpty()) {
            staffRepository.saveAll(retired);
            log.info("✅ Restored {} staff record(s) with assignment '{}'.", retired.size(), assignment.getId());
        }
    }

    /* ===================== Platform continuity (QA D902) ===================== */


    /** Whether this update leaves the row's current holder without its current grant. */
    private static boolean takesGrantAway(UserRoleHospitalAssignment target, User newUser, Role newRole,
                                          boolean activeAfter) {
        boolean switchedOff = Boolean.TRUE.equals(target.getActive()) && !activeAfter;
        boolean roleChanges = target.getRole() == null || !Objects.equals(newRole.getId(), target.getRole().getId());
        boolean holderChanges = target.getUser() == null || newUser == null
            || !Objects.equals(newUser.getId(), target.getUser().getId());
        return switchedOff || roleChanges || holderChanges;
    }

    /**
     * QA D902: nobody removes, deactivates or re-roles their own assignment,
     * whatever the role. A hospital admin's own admin row is already out of
     * their reach ({@link #findChangeable} answers 404); a super-admin's was
     * not, and deleting it took the platform's only super-admin away. Another
     * administrator does it.
     */
    private void requireNotCallersOwn(UserRoleHospitalAssignment row) {
        if (row.getUser() != null && accountAccess.isSelf(row.getUser())) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve(MSG_REMOVE_OWN));
        }
    }

    /** {@link #requireNotCallersOwn} for every row of an account at once. */
    private void requireNotCallersOwnAccount(UUID userId) {
        if (userId != null && accountAccess.currentUserId().map(userId::equals).orElse(false)) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve(MSG_REMOVE_OWN));
        }
    }

    /** A super-admin row that signs someone in today: active, on an active, undeleted account. */
    private boolean isLiveSuperAdmin(UserRoleHospitalAssignment row) {
        User holder = row.getUser();
        return Boolean.TRUE.equals(row.getActive())
            && row.getRole() != null && isRoleCode(getRoleCode(row.getRole()), ROLE_SUPER_ADMIN)
            && holder != null && holder.isActive() && !holder.isDeleted();
    }

    /**
     * QA D902: refuses a change that would take away the last live super-admin
     * assignment. Before counting the others it locks the super-admin role
     * rows ({@link RoleRepository#lockRolesWithCodes}), the one lock every
     * such removal takes - an assignment's, or its account's switch-off or
     * deletion. Two removals racing each other therefore run one after the
     * other: the second waits, and its count (a new statement, so a new
     * READ COMMITTED snapshot) sees what the first committed.
     *
     * <p>{@code losing} must be read before the change marks those rows or
     * their account as no longer live: a row that already reads inactive is
     * not counted as lost.
     */
    private void requireSuperAdminRemains(Collection<UserRoleHospitalAssignment> losing) {
        Set<UUID> lost = losing.stream()
            .filter(this::isLiveSuperAdmin)
            .map(UserRoleHospitalAssignment::getId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (lost.isEmpty()) {
            return;
        }
        roleRepository.lockRolesWithCodes(SUPER_ADMIN_CODES);
        boolean anotherRemains = assignmentRepository.findLiveAssignmentsWithRoleCodes(SUPER_ADMIN_CODES).stream()
            .anyMatch(row -> !lost.contains(row.getId()));
        if (!anotherRemains) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve("users.assignment.lastSuperAdmin"));
        }
    }

    @Override
    public void requireSuperAdminRemainsWithoutUser(UUID userId) {
        requireSuperAdminRemains(assignmentRepository.findByUserId(userId));
    }

    /** Roles are platform-wide: deleting one is the super-admin's alone. */
    @Override
    public void deleteRole(UUID roleId) {
        if (!roleValidator.isSuperAdminFromJwtClaim()) {
            throw new AccessDeniedException("Access denied");
        }
        final Locale locale = LocaleContextHolder.getLocale();
        // QA D902 (#968 review): the same rule as DELETE /roles/{id} - a
        // built-in role is never deleted, assigned or not.
        boolean builtIn = roleRepository.findById(roleId)
            .map(Role::getCode)
            .filter(SecurityConstants::isBuiltInRoleCode)
            .isPresent();
        if (builtIn) {
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolve("role.delete.builtIn"));
        }
        if (assignmentRepository.existsByRoleId(roleId)) {
            throw new ConflictException(
                messageSource.getMessage(MSG_ROLE_DELETE_CONFLICT,
                    null,
                    DEFAULT_ROLE_DELETE_CONFLICT,
                    locale));
        }
        roleRepository.deleteById(roleId);
        log.info("🗑️ Deleted role ID '{}'", roleId);
    }

    /* ===================== Utility ===================== */

    @Override
    @Transactional(readOnly = true)
    public boolean isRoleAlreadyAssigned(UUID userId, UUID hospitalId, UUID roleId) {
        return (hospitalId == null)
            ? assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(userId, roleId)
            : assignmentRepository.existsByUserIdAndHospitalIdAndRoleId(userId, hospitalId, roleId);
    }

    /**
     * Resolve role by ID first, otherwise by name (case-insensitive). All comparisons elsewhere use role CODE.
     */
    public Role resolveRole(UserRoleHospitalAssignmentRequestDTO dto, Locale locale) {
        if (dto.getRoleId() != null) {
            return roleRepository.findById(dto.getRoleId()).orElseThrow(() ->
                new ResourceNotFoundException(MSG_ROLE_NOT_FOUND, dto.getRoleId()));
        }
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            return roleRepository.findByNameIgnoreCase(dto.getRoleName()).orElseThrow(() ->
                new ResourceNotFoundException(MSG_ROLE_NOT_FOUND_BY_NAME, dto.getRoleName()));
        }
        throw new BusinessException(
            messageSource.getMessage(MSG_ROLE_REQUIRED, null,
                DEFAULT_ROLE_REQUIRED_MESSAGE, locale));
    }

    /**
     * Hospital is required unless role is global (ROLE_SUPER_ADMIN).
     */
    private Hospital resolveHospital(UserRoleHospitalAssignmentRequestDTO dto, Role role, Locale locale) {
        return resolveHospitalHumanAware(dto, role, locale);
    }

    private Hospital resolveHospitalHumanAware(UserRoleHospitalAssignmentRequestDTO dto, Role role, Locale locale) {
        if (dto.getHospitalId() != null) {
            return hospitalRepository.findById(dto.getHospitalId()).orElseThrow(() ->
                new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, dto.getHospitalId()));
        }
        // Try code then name
        if (dto.getHospitalCode() != null && !dto.getHospitalCode().isBlank()) {
            return hospitalRepository.findByCodeIgnoreCase(dto.getHospitalCode().trim())
                .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, dto.getHospitalCode()));
        }
        if (dto.getHospitalName() != null && !dto.getHospitalName().isBlank()) {
            return hospitalRepository.findByNameIgnoreCase(dto.getHospitalName().trim())
                .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, dto.getHospitalName()));
        }
        final String roleCode = getRoleCode(role);
        // Super Admin and Patient can have global (null-hospital) assignments
        if (isRoleCode(roleCode, ROLE_SUPER_ADMIN) || isRoleCode(roleCode, ROLE_PATIENT)) {
            return null; // global assignment — patients can exist system-wide
        }

        // All other staff/admin roles require a hospital
        throw new BusinessException(
            messageSource.getMessage("hospital.required", null,
                DEFAULT_HOSPITAL_REQUIRED_MESSAGE, locale));
    }

    private void checkActiveDoctorConflict(UserRoleHospitalAssignmentRequestDTO dto,
                                           User user, Role role, Hospital hospital, Locale locale) {
        final boolean requestedActive = Boolean.TRUE.equals(dto.getActive());
        final String roleCode = getRoleCode(role);

        if (requestedActive && isRoleCode(roleCode, ROLE_DOCTOR) && hospital != null) {
            boolean exists = assignmentRepository.existsByUserIdAndHospitalIdAndRoleIdAndActiveTrue(
                user.getId(), hospital.getId(), role.getId());
            if (exists) {
                throw new ConflictException(
                    messageSource.getMessage(MSG_ASSIGNMENT_DOCTOR_CONFLICT,
                        null,
                        DEFAULT_DOCTOR_CONFLICT_MESSAGE,
                        locale));
            }
        }
    }

    private void checkExistingAssignment(User user, Role role, Hospital hospital, Locale locale) {
        boolean alreadyAssigned = (hospital == null)
            ? assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(user.getId(), role.getId())
            : assignmentRepository.existsByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId());

        if (alreadyAssigned) {
            // Assignments are retired, not deleted, so the tuple can be taken by
            // an inactive row (a removed user's, or one deactivated). Say so:
            // the answer is to reactivate that row, not to create a second one.
            if (existingIsInactive(user, role, hospital)) {
                throw new ConflictException(
                    messageSource.getMessage(MSG_ASSIGNMENT_CONFLICT_INACTIVE, null,
                        DEFAULT_ROLE_ASSIGNED_INACTIVE, locale));
            }
            log.info("⚠️ Role '{}' already assigned to user '{}' for hospital '{}'.",
                getRoleCode(role),
                user.getEmail(),
                hospital != null ? hospital.getName() : GLOBAL_SCOPE);
            throw new ConflictException(
                messageSource.getMessage(MSG_ASSIGNMENT_CONFLICT, null,
                    DEFAULT_ROLE_ALREADY_ASSIGNED, locale));
        }
    }

    private boolean existingIsInactive(User user, Role role, Hospital hospital) {
        Optional<UserRoleHospitalAssignment> existing = (hospital == null)
            ? assignmentRepository.findByUserIdAndRoleIdAndHospitalIsNull(user.getId(), role.getId())
            : assignmentRepository.findByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId());
        return existing.map(a -> Boolean.FALSE.equals(a.getActive())).orElse(false);
    }

    private Set<UUID> collectTargetHospitalIds(UserRoleAssignmentMultiRequestDTO request) {
        Set<UUID> hospitalIds = new LinkedHashSet<>();
        if (request.getHospitalIds() != null) {
            hospitalIds.addAll(request.getHospitalIds());
        }
        if (request.getOrganizationIds() != null) {
            for (UUID organizationId : request.getOrganizationIds()) {
                Organization organization = organizationRepository.findByIdWithHospitals(organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException(MSG_ORGANIZATION_NOT_FOUND, organizationId));
                organization.getHospitals().stream()
                    // A role assigned "to an organisation" lands at its
                    // hospitals only, never at a provider facility (AC-11).
                    .filter(ClinicalHospitals::isClinical)
                    .map(Hospital::getId)
                    .filter(Objects::nonNull)
                    .forEach(hospitalIds::add);
            }
        }
        return hospitalIds;
    }

    private UserRoleHospitalAssignmentRequestDTO buildSingleAssignmentRequest(UserRoleAssignmentMultiRequestDTO request,
                                                                             UUID hospitalId) {
        return UserRoleHospitalAssignmentRequestDTO.builder()
            .userId(request.getUserId())
            .userIdentifier(request.getUserIdentifier())
            .roleId(request.getRoleId())
            .roleName(request.getRoleName())
            .hospitalId(hospitalId)
            .active(request.getActive())
            .startDate(request.getStartDate())
            .build();
    }

    private BulkImportOutcome handleBulkImportRow(int rowNumber,
                                                  String[] tokens,
                                                  Map<String, Integer> headerIndex,
                                                  UserRoleAssignmentBulkImportRequestDTO options) {
        try {
            UserRoleHospitalAssignmentRequestDTO request = buildRequestFromCsvRow(tokens, headerIndex, options);
            UserRoleHospitalAssignment assignment = createAssignment(request, LocaleContextHolder.getLocale(), options.isSendNotifications(), true);

            UserRoleAssignmentBulkImportResultDTO result = UserRoleAssignmentBulkImportResultDTO.builder()
                .rowNumber(rowNumber)
                .identifier(resolveRowIdentifier(request))
                .success(true)
                .message(messageSource.getMessage("assignment.import.created", null, LocaleContextHolder.getLocale()))
                .assignmentId(assignment.getId())
                .assignmentCode(assignment.getAssignmentCode())
                .hospitalId(assignment.getHospital() != null ? assignment.getHospital().getId() : null)
                .roleCode(assignment.getRole() != null ? getRoleCode(assignment.getRole()) : null)
                .profileCompletionUrl(profileCompletionUrl(assignment))
                .build();

            return BulkImportOutcome.created(result);
        } catch (ConflictException conflict) {
            if (!options.isSkipConflicts()) {
                throw conflict;
            }
            return BulkImportOutcome.skipped(buildFailureResult(rowNumber, tokens, headerIndex, conflict.getMessage()));
        } catch (AccessDeniedException denied) {
            // A row the caller may not grant fails on its own, nothing saved
            // for it; the rest of the file still imports. Only a
            // ClientSafeAccessDeniedException carries a sentence written for
            // the caller; any other refusal (UserAccountAccess's internal
            // "Access denied") is answered in the request's language, as
            // GlobalExceptionHandler does for a whole request (QA D671).
            String message = denied instanceof com.example.hms.exception.ClientSafeAccessDeniedException
                ? denied.getMessage()
                : messageSource.getMessage("error.access.denied", null, "Access denied", LocaleContextHolder.getLocale());
            return BulkImportOutcome.failed(buildFailureResult(rowNumber, tokens, headerIndex, message));
        } catch (BusinessException | ResourceNotFoundException ex) {
            return BulkImportOutcome.failed(buildFailureResult(rowNumber, tokens, headerIndex, ex.getMessage()));
        }
    }

    private UserRoleAssignmentBulkImportResultDTO buildFailureResult(int rowNumber,
                                                                     String[] tokens,
                                                                     Map<String, Integer> headerIndex,
                                                                     String message) {
        return UserRoleAssignmentBulkImportResultDTO.builder()
            .rowNumber(rowNumber)
            .identifier(extractIdentifier(tokens, headerIndex))
            .success(false)
            .message(message)
            .build();
    }

    private UserRoleHospitalAssignmentRequestDTO buildRequestFromCsvRow(String[] tokens,
                                                                        Map<String, Integer> headerIndex,
                                                                        UserRoleAssignmentBulkImportRequestDTO options) {
        UserRoleHospitalAssignmentRequestDTO.UserRoleHospitalAssignmentRequestDTOBuilder builder = UserRoleHospitalAssignmentRequestDTO.builder();
    applyUserColumns(builder, tokens, headerIndex);
        applyRoleColumns(builder, tokens, headerIndex, options);
        applyHospitalColumns(builder, tokens, headerIndex, options);
        applyOptionalColumns(builder, tokens, headerIndex, options);
        return builder.build();
    }

    private void applyUserColumns(UserRoleHospitalAssignmentRequestDTO.UserRoleHospitalAssignmentRequestDTOBuilder builder,
                                  String[] tokens,
                                  Map<String, Integer> headerIndex) {
        UUID userId = parseUuid(getColumnValue(tokens, headerIndex, "user_id", "userid"));
        String userIdentifier = getColumnValue(tokens, headerIndex, "user_identifier", "username", "email", "phone", "identifier");
        if (userId != null) {
            builder.userId(userId);
            return;
        }
        if (userIdentifier != null && !userIdentifier.isBlank()) {
            builder.userIdentifier(userIdentifier);
            return;
        }
        throw new BusinessException(DEFAULT_ROW_MISSING_USER);
    }

    private void applyRoleColumns(UserRoleHospitalAssignmentRequestDTO.UserRoleHospitalAssignmentRequestDTOBuilder builder,
                                  String[] tokens,
                                  Map<String, Integer> headerIndex,
                                  UserRoleAssignmentBulkImportRequestDTO options) {
        UUID roleId = parseUuid(getColumnValue(tokens, headerIndex, "role_id", "roleid"));
        String roleName = getColumnValue(tokens, headerIndex, "role_name", "rolename", "role_code");
        if (roleId == null && (roleName == null || roleName.isBlank())) {
            if (options.getDefaultRoleId() != null) {
                roleId = options.getDefaultRoleId();
            } else if (options.getDefaultRoleName() != null && !options.getDefaultRoleName().isBlank()) {
                roleName = options.getDefaultRoleName();
            }
        }
        if (roleId != null) {
            builder.roleId(roleId);
            return;
        }
        if (roleName != null && !roleName.isBlank()) {
            builder.roleName(roleName);
            return;
        }
        throw new BusinessException(DEFAULT_ROW_MISSING_ROLE);
    }

    private void applyHospitalColumns(UserRoleHospitalAssignmentRequestDTO.UserRoleHospitalAssignmentRequestDTOBuilder builder,
                                      String[] tokens,
                                      Map<String, Integer> headerIndex,
                                      UserRoleAssignmentBulkImportRequestDTO options) {
        UUID hospitalId = parseUuid(getColumnValue(tokens, headerIndex, "hospital_id", "hospitalid"));
        if (hospitalId == null) {
            hospitalId = options.getDefaultHospitalId();
        }
        if (hospitalId != null) {
            builder.hospitalId(hospitalId);
        }

        String hospitalCode = getColumnValue(tokens, headerIndex, "hospital_code", "hospitalcode", "facility_code");
        if (hospitalCode != null) {
            builder.hospitalCode(hospitalCode);
        }

        String hospitalName = getColumnValue(tokens, headerIndex, "hospital_name", "hospitalname", "facility_name");
        if (hospitalName != null) {
            builder.hospitalName(hospitalName);
        }
    }

    private void applyOptionalColumns(UserRoleHospitalAssignmentRequestDTO.UserRoleHospitalAssignmentRequestDTOBuilder builder,
                                      String[] tokens,
                                      Map<String, Integer> headerIndex,
                                      UserRoleAssignmentBulkImportRequestDTO options) {
        Boolean active = parseBoolean(getColumnValue(tokens, headerIndex, "active", "is_active"), options.getDefaultActive());
        if (active != null) {
            builder.active(active);
        }

        LocalDate startDate = parseStartDate(getColumnValue(tokens, headerIndex, "start_date", "startdate", "effective_date"));
        if (startDate != null) {
            builder.startDate(startDate);
        }

    }

    private Map<String, Integer> buildHeaderIndex(String[] headers) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i];
            if (header != null) {
                index.put(header.trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return index;
    }

    private String[] splitCsvLine(String line, String delimiter) {
        return line.split(Pattern.quote(delimiter), -1);
    }

    private String getColumnValue(String[] tokens, Map<String, Integer> headerIndex, String... keys) {
        for (String key : keys) {
            String value = extractColumnValue(tokens, headerIndex, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String extractColumnValue(String[] tokens, Map<String, Integer> headerIndex, String key) {
        if (key == null) {
            return null;
        }
        Integer columnIndex = headerIndex.get(key.toLowerCase(Locale.ROOT));
        if (columnIndex == null || columnIndex >= tokens.length) {
            return null;
        }
        String value = tokens[columnIndex];
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(DEFAULT_INVALID_UUID_MESSAGE + raw);
        }
    }

    private Boolean parseBoolean(String raw, Boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y").contains(normalized)) {
            return Boolean.TRUE;
        }
        if (List.of("false", "0", "no", "n").contains(normalized)) {
            return Boolean.FALSE;
        }
        throw new BusinessException(DEFAULT_INVALID_BOOLEAN_MESSAGE + raw);
    }

    private LocalDate parseStartDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        for (DateTimeFormatter formatter : SUPPORTED_DATE_FORMATS) {
            try {
                return LocalDate.parse(candidate, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new BusinessException(DEFAULT_INVALID_DATE_MESSAGE + raw);
    }

    private String resolveRowIdentifier(UserRoleHospitalAssignmentRequestDTO request) {
        if (request.getUserIdentifier() != null && !request.getUserIdentifier().isBlank()) {
            return request.getUserIdentifier();
        }
        if (request.getUserId() != null) {
            return request.getUserId().toString();
        }
        return "unknown";
    }

    private String extractIdentifier(String[] tokens, Map<String, Integer> headerIndex) {
        String identifier = getColumnValue(tokens, headerIndex,
            "user_identifier", "username", "email", "phone", "identifier", "user_id", "userid");
        return identifier != null ? identifier : "unknown";
    }

    private static final class BulkImportAccumulator {
        private int processed;
        private int created;
        private int skipped;
        private int failed;

        void incrementProcessed() {
            processed++;
        }

        void applyOutcome(BulkRowStatus status) {
            if (status == null) {
                return;
            }
            switch (status) {
                case CREATED -> created++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }

        UserRoleAssignmentBulkImportResponseDTO toResponse(List<UserRoleAssignmentBulkImportResultDTO> results) {
            return UserRoleAssignmentBulkImportResponseDTO.builder()
                .processed(processed)
                .created(created)
                .skipped(skipped)
                .failed(failed)
                .results(results)
                .build();
        }
    }

    private enum BulkRowStatus {
        CREATED,
        SKIPPED,
        FAILED
    }

    private record BulkImportOutcome(BulkRowStatus status, UserRoleAssignmentBulkImportResultDTO result) {
        static BulkImportOutcome created(UserRoleAssignmentBulkImportResultDTO result) {
            return new BulkImportOutcome(BulkRowStatus.CREATED, result);
        }

        static BulkImportOutcome skipped(UserRoleAssignmentBulkImportResultDTO result) {
            return new BulkImportOutcome(BulkRowStatus.SKIPPED, result);
        }

        static BulkImportOutcome failed(UserRoleAssignmentBulkImportResultDTO result) {
            return new BulkImportOutcome(BulkRowStatus.FAILED, result);
        }
    }

    private void syncLegacyRole(User user, Role role) {
        try {
            if (!userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) {
                UserRole userRole = new UserRole();
                userRole.setId(new UserRoleId(user.getId(), role.getId()));
                userRole.setUser(user);
                userRole.setRole(role);
                userRoleRepository.save(userRole);
                log.info("🔄 Synced legacy user_roles entry for user '{}' and role '{}'",
                    user.getEmail(), getRoleCode(role));
            }
        } catch (RuntimeException e) {
            // Best-effort: do not fail primary flow
            log.warn("⚠️ Legacy role sync failed for user '{}' and role '{}': {}",
                user.getEmail(), getRoleCode(role), e.getMessage());
        }
    }

    private String generateAssignCode(User user, Hospital hospital) {
        String hospitalCode = (hospital != null && hospital.getCode() != null && !hospital.getCode().isBlank())
            ? hospital.getCode().toUpperCase()
            : GLOBAL_SCOPE;

        String f = (user.getFirstName() != null && !user.getFirstName().isBlank())
            ? user.getFirstName().trim().substring(0, 1).toUpperCase()
            : "X";
        String l = (user.getLastName() != null && !user.getLastName().isBlank())
            ? user.getLastName().trim().substring(0, 1).toUpperCase()
            : "X";
        String initials = f + l;

        // add timestamp + small random to reduce collision probability
        String timestamp = TS.format(java.time.LocalDateTime.now());
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();

        // Example: HSP1-TN-20250818XXXX-ABCD
        return hospitalCode + "-" + initials + "-" + timestamp + "-" + rand;
    }

    private String generateConfirmationCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String resolveDisplayName(User user, String fallback) {
        if (user == null) {
            return fallback;
        }

        String first = user.getFirstName();
        String last = user.getLastName();
        String full = ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
        if (!full.isEmpty()) {
            return full;
        }

        String username = user.getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }

        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            return email;
        }

        return fallback;
    }

    private String resolveHospitalName(Hospital hospital) {
        if (hospital == null) {
            return GLOBAL_SCOPE;
        }

        if (hospital.getName() != null && !hospital.getName().isBlank()) {
            return hospital.getName();
        }

        if (hospital.getCode() != null && !hospital.getCode().isBlank()) {
            return hospital.getCode();
        }

    return GLOBAL_SCOPE;
    }

    private String getRoleCode(Role role) {
        return role.getCode() != null && !role.getCode().isBlank()
            ? role.getCode()
            : role.getName(); // fallback
    }

    private boolean isRoleCode(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }

        if (expected.equalsIgnoreCase(actual)) {
            return true;
        }

        String normalizedExpected = expected;
        String normalizedActual = actual;

        if (expected.startsWith(ROLE_PREFIX)) {
            normalizedExpected = expected.substring(ROLE_PREFIX.length());
        }
        if (actual.startsWith(ROLE_PREFIX)) {
            normalizedActual = actual.substring(ROLE_PREFIX.length());
        }

        return normalizedExpected.equalsIgnoreCase(normalizedActual);
    }

    private void sendAssignmentSmsNotifications(UserRoleHospitalAssignment assignment) {
        try {
            Role role = assignment.getRole();
            Hospital hospital = assignment.getHospital();
            User user = assignment.getUser();

            String confirmationCode = assignment.getConfirmationCode();
            String assignmentCode = assignment.getAssignmentCode();
            String roleDisplay = role != null ? getRoleCode(role) : UNKNOWN_ROLE;
            String hospitalDisplay = resolveHospitalName(hospital);
            String assigneeDisplay = resolveDisplayName(user, user != null ? user.getEmail() : "user");

            // The assignee's activation SMS goes FIRST and each FYI send is
            // isolated: a throwing registrar/hospital notification used to
            // abort the whole method before the one SMS that matters was even
            // attempted — and before any SMS outcome could be recorded.
            notifyAssigneeBySms(assignment, user, roleDisplay, hospitalDisplay, confirmationCode, assignmentCode);
            sendFyiSmsQuietly(assignment.getId(), "Registrar", () ->
                notifyRegistrarBySms(assignment.getRegisteredBy(), roleDisplay, assigneeDisplay, hospitalDisplay, assignmentCode, confirmationCode));
            sendFyiSmsQuietly(assignment.getId(), "Hospital", () ->
                notifyHospitalBySms(hospital, assigneeDisplay, roleDisplay, assignmentCode, confirmationCode));
        } catch (RuntimeException e) {
            log.warn("⚠️ Failed to send SMS notifications for assignment '{}': {}", assignment.getId(), e.getMessage());
        }
    }

    /**
     * FYI messages are couriers of convenience: a failure is logged, never
     * propagated, so it can neither abort the assignee's activation SMS nor
     * pollute the delivery report.
     */
    private void sendFyiSmsQuietly(UUID assignmentId, String audience, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.warn("⚠️ {} FYI SMS failed for assignment '{}': {}", audience, assignmentId, e.getMessage());
        }
    }

    private void notifyRegistrarBySms(User registrar,
                                      String roleDisplay,
                                      String assigneeDisplay,
                                      String hospitalDisplay,
                                      String assignmentCode,
                                      String confirmationCode) {
        if (registrar == null) {
            return;
        }
        String phone = registrar.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String message = String.format(
            "✅ You assigned role '%s' to %s in '%s'. Assignment: %s%s",
            roleDisplay,
            assigneeDisplay,
            hospitalDisplay,
            assignmentCode,
            buildConfirmationSuffix(confirmationCode)
        );
        sendSmsSilently(phone, message);
    }

    private void notifyHospitalBySms(Hospital hospital,
                                     String assigneeDisplay,
                                     String roleDisplay,
                                     String assignmentCode,
                                     String confirmationCode) {
        if (hospital == null) {
            return;
        }
        String phone = hospital.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String message = String.format(
            "📢 New assignment: %s -> role '%s'. Assignment ref: %s%s",
            assigneeDisplay,
            roleDisplay,
            assignmentCode,
            buildConfirmationSuffix(confirmationCode)
        );
        sendSmsSilently(phone, message);
    }

    private void notifyAssigneeBySms(UserRoleHospitalAssignment assignment,
                                     User user,
                                     String roleDisplay,
                                     String hospitalDisplay,
                                     String confirmationCode,
                                     String assignmentCode) {
        if (user == null) {
            return;
        }
        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                NotificationDeliveryStatusDTO.OUTCOME_NO_CONTACT, null, null);
            return;
        }
        if (confirmationCode == null || confirmationCode.isBlank()) {
            log.warn("⚠️ Confirmation code missing for assignment '{}'; skipping SMS confirmation message to user", assignment.getId());
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                NotificationDeliveryStatusDTO.OUTCOME_FAILED,
                ActivationDeliveryTracker.maskPhone(phone), "confirmation code missing");
            return;
        }
        String message = String.format(
            "👋 Confirm your '%s' role at '%s'. Use code: %s (reference: %s)",
            roleDisplay,
            hospitalDisplay,
            confirmationCode,
            assignmentCode
        );
        // Own try/catch: this is the one SMS the ACTIVATION depends on, so
        // its outcome must not be lumped in with the registrar/hospital FYI
        // messages the outer catch covers.
        try {
            sendSmsSilently(phone, message);
            boolean real = smsService.deliversRealSms();
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                real ? NotificationDeliveryStatusDTO.OUTCOME_SENT
                     : NotificationDeliveryStatusDTO.OUTCOME_MOCKED,
                ActivationDeliveryTracker.maskPhone(phone),
                real ? null : "SMS transport disabled — mock channel only logs");
        } catch (NotificationTransportUnavailableException ex) {
            // "No transport" is not "the send failed" — the registrar's next
            // move is to have an operator finish the SMS configuration, not
            // to retry the same registration.
            log.warn("⚠️ SMS transport unavailable for assignment '{}': {}", assignment.getId(), ex.getMessage());
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED,
                ActivationDeliveryTracker.maskPhone(phone), DETAIL_SEE_SERVER_LOGS);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to send confirmation SMS for assignment '{}': {}", assignment.getId(), ex.getMessage());
            // detail is a FIXED string: exception messages can embed the raw
            // recipient (validateAddresses does exactly that for email) and
            // this DTO leaves the server. The transport error stays in logs.
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                NotificationDeliveryStatusDTO.OUTCOME_FAILED,
                ActivationDeliveryTracker.maskPhone(phone), DETAIL_SEE_SERVER_LOGS);
        }
    }

    private String buildConfirmationSuffix(String confirmationCode) {
        return (confirmationCode != null && !confirmationCode.isBlank())
            ? ". Staff confirmation code: " + confirmationCode
            : "";
    }

    private void sendSmsSilently(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank() || message == null || message.isBlank()) {
            return;
        }
        smsService.send(phoneNumber, message);
    }

    /** One-time credentials for email-less (phone-first) accounts, sent by SMS. */
    private void sendCredentialsSmsFallback(UserRoleHospitalAssignment assignment, User user) {
        String tempPassword = assignment.getTempPlainPassword();
        if (tempPassword == null || tempPassword.isBlank()) {
            return;
        }
        // Credentials must NEVER reach the mock SMS channel — it logs full
        // message bodies, which would put the temp password into plaintext logs.
        if (!smsService.deliversRealSms()) {
            log.warn("⚠️ No real SMS channel configured — one-time credentials for assignment '{}' were NOT delivered; "
                + "the patient can still activate via the staff-read confirmation code.", assignment.getId());
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_CREDENTIALS,
                NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED,
                ActivationDeliveryTracker.maskPhone(user.getPhoneNumber()),
                "no real SMS channel — one-time credentials were not delivered");
            return;
        }
        String message = String.format(
            "🔐 e-Keneya account created. Username: %s Temp password: %s — change it at first login.",
            user.getUsername(),
            tempPassword
        );
        // Own try/catch: an SMS failure here must not be misfiled as an
        // EMAIL outcome by sendAssignmentEmailNotification's catch.
        try {
            sendSmsSilently(user.getPhoneNumber(), message);
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_CREDENTIALS,
                NotificationDeliveryStatusDTO.OUTCOME_SENT,
                ActivationDeliveryTracker.maskPhone(user.getPhoneNumber()), null);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to send credentials SMS for assignment '{}': {}", assignment.getId(), ex.getMessage());
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_SMS,
                NotificationDeliveryStatusDTO.PURPOSE_CREDENTIALS,
                NotificationDeliveryStatusDTO.OUTCOME_FAILED,
                ActivationDeliveryTracker.maskPhone(user.getPhoneNumber()), DETAIL_SEE_SERVER_LOGS);
        }
    }

    private void sendAssignmentEmailNotification(UserRoleHospitalAssignment assignment) {
        try {
            User user = assignment.getUser();
            if (user == null) {
                return;
            }

            String email = user.getEmail();
            if (email == null || email.isBlank()) {
                // Phone-first patients have no email: deliver the one-time
                // credentials over SMS instead. The confirmation code already
                // goes out separately via sendAssignmentSmsNotifications.
                // NO_CONTACT is informational, not a warning — the portal
                // only alerts on FAILED / NOT_CONFIGURED / MOCKED.
                recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_EMAIL,
                    NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                    NotificationDeliveryStatusDTO.OUTCOME_NO_CONTACT, null, null);
                sendCredentialsSmsFallback(assignment, user);
                return;
            }

            String confirmationCode = assignment.getConfirmationCode();
            if (confirmationCode == null || confirmationCode.isBlank()) {
                log.warn("⚠️ Confirmation code missing for assignment '{}'; skipping email notification", assignment.getId());
                recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_EMAIL,
                    NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                    NotificationDeliveryStatusDTO.OUTCOME_FAILED,
                    ActivationDeliveryTracker.maskEmail(email), "confirmation code missing");
                return;
            }

            String userDisplayName = resolveDisplayName(user, email);
            String roleDisplay = assignment.getRole() != null ? getRoleCode(assignment.getRole()) : null;
            String hospitalDisplay = resolveHospitalName(assignment.getHospital());
            String completionUrl = profileCompletionUrl(assignment);

            emailService.sendRoleAssignmentConfirmationEmail(
                email,
                userDisplayName,
                roleDisplay,
                hospitalDisplay,
                confirmationCode,
                assignment.getAssignmentCode(),
                completionUrl,
                assignment.getTempPlainPassword() != null ? user.getUsername() : null,
                assignment.getTempPlainPassword()
            );
            // QUEUED: the mail is in the outbox, not yet at the SMTP server.
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_EMAIL,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                NotificationDeliveryStatusDTO.OUTCOME_QUEUED,
                ActivationDeliveryTracker.maskEmail(email), null);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to send assignment confirmation email for assignment '{}': {}", assignment.getId(), ex.getMessage());
            User assignee = assignment.getUser();
            // Fixed detail — see the SMS catch above for why getMessage() is
            // banned from this DTO.
            boolean configured = emailService.deliversRealEmail();
            recordDelivery(NotificationDeliveryStatusDTO.CHANNEL_EMAIL,
                NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION,
                configured
                    ? NotificationDeliveryStatusDTO.OUTCOME_FAILED
                    : NotificationDeliveryStatusDTO.OUTCOME_NOT_CONFIGURED,
                ActivationDeliveryTracker.maskEmail(assignee != null ? assignee.getEmail() : null),
                configured ? DETAIL_SEE_SERVER_LOGS : DETAIL_MAIL_NOT_CONFIGURED);
        }
    }

    private static final String DETAIL_SEE_SERVER_LOGS =
        "send failed — transport error in server logs";
    private static final String DETAIL_MAIL_NOT_CONFIGURED =
        "mail transport not configured on this deployment";

    /** Shorthand for {@link ActivationDeliveryTracker#report} — no-op unless a controller armed collection. */
    private void recordDelivery(String channel, String purpose, String outcome, String target, String detail) {
        ActivationDeliveryTracker.report(NotificationDeliveryStatusDTO.builder()
            .channel(channel)
            .purpose(purpose)
            .outcome(outcome)
            .target(target)
            .detail(detail)
            .build());
    }

    private void recordAssignmentAudit(UserRoleHospitalAssignment assignment) {
        try {
            User registrar = assignment.getRegisteredBy();
            if (registrar == null) {
                log.debug("Skipping audit log for assignment '{}' because registrar is null", assignment.getId());
                return;
            }

            User assignee = assignment.getUser();
            Role role = assignment.getRole();
            Hospital hospital = assignment.getHospital();

            Map<String, Object> details = new HashMap<>();
            details.put("assignmentCode", assignment.getAssignmentCode());
            if (assignment.getConfirmationSentAt() != null) {
                details.put("confirmationSentAt", assignment.getConfirmationSentAt());
            }
            details.put("confirmationChannels", List.of("SMS", "EMAIL"));
            details.put("assignedRole", role != null ? getRoleCode(role) : UNKNOWN_ROLE);
            if (assignee != null) {
                details.put("assigneeId", assignee.getId());
                details.put("assigneeEmail", assignee.getEmail());
            }

            String roleDisplay = role != null ? getRoleCode(role) : UNKNOWN_ROLE;
            String assigneeDisplay = resolveDisplayName(assignee, "unknown user");

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(registrar.getId())
                .assignmentId(assignment.getId())
                .userName(resolveDisplayName(registrar, null))
                // QA D773: no roleName. It is the ACTOR's role column; the
                // granted role used to land there, so a hospital admin's grant
                // read as done "as Patient(e)". The audit service files the
                // row under the registrar's own role at this hospital; the
                // granted role is in the details and the description.
                .hospitalName(resolveHospitalName(hospital))
                // QA D521: assignmentId is the ASSIGNEE's row, which the audit
                // service drops for the registrar; anchor on the registrar's
                // own assignment at this hospital instead.
                .hospitalId(hospital != null ? hospital.getId() : null)
                .resourceName(assigneeDisplay)
                .resourceId(assignment.getId() != null ? assignment.getId().toString() : null)
                .entityType(AUDIT_ENTITY_ASSIGNMENT)
                .eventType(AuditEventType.ROLE_ASSIGNED)
                .eventDescription(String.format("Assigned role '%s' to %s", roleDisplay, assigneeDisplay))
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build();

            auditEventLogService.logEvent(auditEvent);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to record audit log for assignment '{}': {}", assignment.getId(), ex.getMessage());
        }
    }

    private void recordAssignmentConfirmationAudit(UserRoleHospitalAssignment assignment, User actor) {
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("assignmentCode", assignment.getAssignmentCode());
            details.put("confirmationCode", assignment.getConfirmationCode());
            details.put("confirmedAt", assignment.getConfirmationVerifiedAt());
            details.put("confirmationChannel", "PORTAL");
            if (assignment.getRole() != null) {
                details.put("assignedRole", getRoleCode(assignment.getRole()));
            }

            User assignee = assignment.getUser();
            Role role = assignment.getRole();

            String assigneeDisplay = resolveDisplayName(assignee, "unknown user");
            String roleDisplay = role != null ? getRoleCode(role) : UNKNOWN_ROLE;

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(actor != null ? actor.getId() : null)
                .assignmentId(assignment.getId())
                .userName(resolveDisplayName(actor, null))
                // QA D773: the actor's role is resolved by the audit service,
                // never the confirmed role (which is in the details).
                .hospitalName(resolveHospitalName(assignment.getHospital()))
                .hospitalId(assignment.getHospital() != null ? assignment.getHospital().getId() : null)
                .resourceName(assigneeDisplay)
                .resourceId(assignment.getId() != null ? assignment.getId().toString() : null)
                .entityType(AUDIT_ENTITY_ASSIGNMENT)
                .eventType(AuditEventType.ASSIGNMENT_CONFIRMED)
                .eventDescription(String.format("Confirmed assignment '%s' for %s", roleDisplay, assigneeDisplay))
                .status(AuditStatus.SUCCESS)
                .details(details)
                .build();

            auditEventLogService.logEvent(auditEvent);
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to record assignment confirmation audit for assignment '{}': {}", assignment.getId(), ex.getMessage());
        }
    }

    private List<String> buildRoleProfileChecklist(Role role) {
        if (role == null) {
            return DEFAULT_PROFILE_CHECKLIST;
        }
        String code = getRoleCode(role);
        if (code == null || code.isBlank()) {
            return DEFAULT_PROFILE_CHECKLIST;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        List<String> checklist = ROLE_PROFILE_CHECKLISTS.get(normalized);
        if (checklist != null) {
            return checklist;
        }
        if (!normalized.startsWith(ROLE_PREFIX)) {
            checklist = ROLE_PROFILE_CHECKLISTS.get(ROLE_PREFIX + normalized);
            if (checklist != null) {
                return checklist;
            }
        }
        return DEFAULT_PROFILE_CHECKLIST;
    }

    @Override
    public void sendNotifications(UUID assignmentId) {
        sendNotifications(assignmentId, false);
    }

    @Override
    public void sendNotifications(UUID assignmentId, boolean emailCoveredByWelcome) {
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> assignmentNotFound(assignmentId));
        // QA D789: the AFTER_COMMIT send and the public re-send land here. A
        // hospital closed in between gets no invitation: nothing is sent, and
        // there is no caller to answer, so no throw either.
        if (atClosedHospital(assignment)) {
            log.info("📭 Assignment '{}' is at a closed hospital; no invitation sent", assignment.getId());
            return;
        }
        // The welcome mail carries the code only when this assignment has both
        // the confirmation code and the assignment code it reads
        // (UserServiceImpl.welcomeRoles lists it); without
        // them it points at this mail, so this mail must still go out.
        boolean welcomeHasTheCode = hasText(assignment.getConfirmationCode())
            && hasText(assignment.getAssignmentCode());
        if (emailCoveredByWelcome && welcomeHasTheCode) {
            // A brand-new staff account gets ONE mail: the welcome mail
            // (UserServiceImpl) already carries this code, link and sign-in
            // details and records its own delivery outcome.
            log.info("📧 Assignment '{}' e-mail covered by the welcome mail; SMS only", assignment.getId());
            sendAssignmentSmsNotifications(assignment);
            return;
        }
        notifyAssignee(assignment);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public boolean resendPendingActivationCode(UUID assignmentId, java.time.Duration cooldown) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        // QA D789: the public re-send (/auth/resend-verification) neither
        // rotates nor sends the code of an invitation to a closed hospital:
        // the claim refuses the row in the database, and sendNotifications
        // refuses it again. The endpoint answers the same neutral text either way.
        int claimed = assignmentRepository.claimActivationResend(
            assignmentId, generateConfirmationCode(), now, now.minus(cooldown),
            FacilityAssignmentGuard.CLOSED_STATES);
        if (claimed == 0) {
            return false;
        }
        // The claim stands even when the delivery fails: the cooldown is what
        // keeps this public endpoint from flooding a number.
        try {
            sendNotifications(assignmentId);
        } catch (RuntimeException e) {
            log.warn("Activation re-send failed for assignment {}: {}", assignmentId, e.getClass().getSimpleName());
        }
        return true;
    }

    @Override
    public void resendNotifications(UUID assignmentId) {
        UserRoleHospitalAssignment assignment = findChangeable(assignmentId);
        // QA D789 re-test: POST /assignments/{id}/resend-notification for a row
        // at an archived hospital queued an EMAIL and an SMS activation (200).
        // It is the same re-invitation as regenerate-code: the same 409.
        FacilityAssignmentGuard.requireOpenForAssignment(assignment.getHospital());
        notifyAssignee(assignment);
    }

    /**
     * QA D789: the row's hospital is closed ({@link FacilityAssignmentGuard#isClosed}),
     * so it takes no invitation, re-invitation or activation. A global row
     * (no hospital) is never at a closed one.
     */
    private static boolean atClosedHospital(UserRoleHospitalAssignment assignment) {
        Hospital hospital = assignment.getHospital();
        return hospital != null && FacilityAssignmentGuard.isClosed(hospital.getLifecycleState());
    }

    /**
     * The assignee's invitation link, the one place it is built. QA D789: a
     * row at a closed hospital has none; the link leads to an activation the
     * hospital no longer takes.
     */
    private String profileCompletionUrl(UserRoleHospitalAssignment assignment) {
        return atClosedHospital(assignment) ? null
            : assignmentLinkService.buildProfileCompletionUrl(assignment.getAssignmentCode());
    }

    /** The registrar's confirmation link, held to the same rule. */
    private String assignerConfirmationUrl(UserRoleHospitalAssignment assignment) {
        return atClosedHospital(assignment) ? null
            : assignmentLinkService.buildAssignerConfirmationUrl(assignment.getAssignmentCode());
    }

    private void notifyAssignee(UserRoleHospitalAssignment assignment) {
        log.info("📧 Sending notifications for assignment '{}'", assignment.getId());
        sendAssignmentEmailNotification(assignment);
        sendAssignmentSmsNotifications(assignment);
    }

    @Override
    public List<AssignmentMinimalDTO> getMinimalAssignments() {
        return assignmentRepository.findAll()
            .stream()
            .map(assignment -> {
                User user = assignment.getUser();
                Role role = assignment.getRole();
                Hospital hospital = assignment.getHospital();

                String displayName = resolveUserDisplayName(user) + " - " + resolveRoleDisplayName(role);
                String description = (hospital != null && StringUtils.hasText(hospital.getName()))
                    ? hospital.getName()
                    : messageSource.getMessage("assignment.scope.global", null, LocaleContextHolder.getLocale());
                
                return AssignmentMinimalDTO.builder()
                    .id(assignment.getId())
                    .displayName(displayName)
                    .description(description)
                    .build();
            })
            .toList();
    }

    private String resolveUserDisplayName(User user) {
        if (user == null) {
            return "Unknown User";
        }

        String firstName = user.getFirstName() != null ? user.getFirstName().strip() : "";
        String lastName = user.getLastName() != null ? user.getLastName().strip() : "";

        if (StringUtils.hasText(firstName) && StringUtils.hasText(lastName)) {
            return firstName + " " + lastName;
        }

        if (StringUtils.hasText(firstName)) {
            return firstName;
        }

        if (StringUtils.hasText(lastName)) {
            return lastName;
        }

        if (StringUtils.hasText(user.getEmail())) {
            return user.getEmail();
        }

        return "Unknown User";
    }

    private String resolveRoleDisplayName(Role role) {
        if (role == null || !StringUtils.hasText(role.getName())) {
            return "Unassigned Role";
        }
        return role.getName();
    }
}
