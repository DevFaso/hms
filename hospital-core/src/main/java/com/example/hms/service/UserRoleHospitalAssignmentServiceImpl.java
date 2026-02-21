package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.AssignmentMinimalDTO;
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
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserRoleHospitalAssignmentServiceImpl implements UserRoleHospitalAssignmentService {
    private static final String UNKNOWN_ROLE = "UNKNOWN_ROLE";


    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String MSG_ASSIGNMENT_NOT_FOUND = "assignment.notfound";
    private static final String MSG_ASSIGNMENT_CONFLICT = "assignment.conflict";
    private static final String MSG_ASSIGNMENT_DOCTOR_CONFLICT = "assignment.doctor.conflict";
    private static final String MSG_ROLE_DELETE_CONFLICT = "role.delete.conflict";
    private static final String MSG_ROLE_NOT_FOUND = "role.notfound";
    private static final String MSG_ROLE_NOT_FOUND_BY_NAME = "role.notfound.byname";
    private static final String MSG_ROLE_REQUIRED = "role.required";
    private static final String MSG_USER_NOT_FOUND = "user.notfound";
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";
    private static final String MSG_ORGANIZATION_NOT_FOUND = "organization.notfound";
    private static final String DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX = "Assignment not found with ID: ";
    private static final String DEFAULT_ROLE_ALREADY_ASSIGNED = "Role already assigned to this user for this hospital.";
    private static final String DEFAULT_ROLE_DELETE_CONFLICT = "Cannot delete role. It is assigned to one or more users.";
    private static final String DEFAULT_USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String DEFAULT_ROLE_NOT_FOUND_PREFIX = "Role not found with ID: ";
    private static final String DEFAULT_ROLE_NOT_FOUND_BY_NAME_PREFIX = "Role not found with name: ";
    private static final String DEFAULT_HOSPITAL_NOT_FOUND_ID_PREFIX = "Hospital not found with ID: ";
    private static final String DEFAULT_HOSPITAL_NOT_FOUND_CODE_PREFIX = "Hospital not found with code: ";
    private static final String DEFAULT_HOSPITAL_NOT_FOUND_NAME_PREFIX = "Hospital not found with name: ";
    private static final String DEFAULT_ORGANIZATION_NOT_FOUND_PREFIX = "Organization not found: ";
    private static final String DEFAULT_ROLE_REQUIRED_MESSAGE = "Role must be specified by either ID or name.";
    private static final String DEFAULT_DOCTOR_CONFLICT_MESSAGE = "An active DOCTOR assignment already exists for this user in the given hospital.";
    private static final String DEFAULT_SUPER_ADMIN_SCOPE_MESSAGE = "SUPER_ADMIN assignments are global and must not include a hospital.";
    private static final String DEFAULT_HOSPITAL_REQUIRED_MESSAGE = "Hospital must be provided for non-SUPER_ADMIN roles.";
    private static final String DEFAULT_INVALID_UUID_MESSAGE = "Invalid UUID value: ";
    private static final String DEFAULT_INVALID_BOOLEAN_MESSAGE = "Invalid boolean value: ";
    private static final String DEFAULT_INVALID_DATE_MESSAGE = "Invalid date value: ";
    private static final String DEFAULT_ROW_MISSING_USER = "Row missing user identifier";
    private static final String DEFAULT_ROW_MISSING_ROLE = "Row missing role identifier";
    private static final String DEFAULT_CREATED_MESSAGE = "Created";
    private static final String DEFAULT_PROCESSING_ERROR = "Unable to process CSV content";
    private static final String MSG_ASSIGNMENT_INVALID_CODE = "assignment.confirmation.invalid";
    private static final String MSG_ASSIGNMENT_ALREADY_CONFIRMED = "assignment.confirmation.already";
    private static final String MSG_ASSIGNMENT_ACTOR_MISMATCH = "assignment.confirmation.actor-mismatch";
    private static final String MSG_ASSIGNMENT_NOT_FOUND_BY_CODE = "assignment.notfound.bycode";
    private static final String DEFAULT_CONFIRMATION_CODE_INVALID = "Invalid confirmation code.";
    private static final String DEFAULT_ASSIGNMENT_ALREADY_CONFIRMED = "This assignment has already been confirmed.";
    private static final String DEFAULT_CONFIRMATION_ACTOR_MISMATCH = "Only the assigner who created this assignment can confirm it.";
    private static final String DEFAULT_ASSIGNMENT_NOT_FOUND_BY_CODE_PREFIX = "Assignment not found with code: ";
    private static final String DEFAULT_ACTOR_RESOLUTION_FAILURE = "Unable to resolve the current user.";
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
    private final RoleRepository roleRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRoleHospitalAssignmentMapper mapper;
    private final MessageSource messageSource;

    /* ===================== Create ===================== */

    private UserRoleHospitalAssignmentResponseDTO toDtoWithLinks(UserRoleHospitalAssignment assignment) {
        UserRoleHospitalAssignmentResponseDTO dto = mapper.toResponseDTO(assignment);
        if (dto == null || assignment == null) {
            return dto;
        }
        String assignmentCode = assignment.getAssignmentCode();
        if (assignmentCode != null && !assignmentCode.isBlank()) {
            dto.setProfileCompletionUrl(assignmentLinkService.buildProfileCompletionUrl(assignmentCode));
            dto.setAssignerConfirmationUrl(assignmentLinkService.buildAssignerConfirmationUrl(assignmentCode));
        }
        dto.setProfileChecklist(buildRoleProfileChecklist(assignment.getRole()));
        dto.setConfirmationVerified(assignment.getConfirmationVerifiedAt() != null);
        return dto;
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO assignRole(UserRoleHospitalAssignmentRequestDTO dto) {
        Locale locale = Locale.getDefault();
        UserRoleHospitalAssignment assignment = createAssignment(dto, locale, true);
        return toDtoWithLinks(assignment);
    }

    private UserRoleHospitalAssignment createAssignment(UserRoleHospitalAssignmentRequestDTO dto,
                                                        Locale locale,
                                                        boolean sendNotifications) {
        User user = resolveUser(dto, locale);
        Role role = resolveRole(dto, locale);
        String roleCode = getRoleCode(role);

        enforceRoleScopeConstraints(dto, roleCode);

        Hospital hospital = resolveHospitalHumanAware(dto, role, locale);
        checkActiveDoctorConflict(dto, user, role, hospital, locale);
        checkExistingAssignment(user, role, hospital, locale);

        UserRoleHospitalAssignment assignment = mapper.toEntity(dto, user, hospital, role);
        assignment.setStartDate(dto.getStartDate() != null ? dto.getStartDate() : LocalDate.now());
        assignment.setAssignmentCode(generateAssignCode(user, hospital));
        assignment.setConfirmationCode(generateConfirmationCode());
        assignment.setConfirmationSentAt(LocalDateTime.now());
        assignment.setConfirmationVerifiedAt(null);

        User registrar = resolveRegistrar(dto, locale);
        if (registrar != null) {
            assignment.setRegisteredBy(registrar);
        }

        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);

        log.info("✅ Assigned role '{}' to user '{}'{}",
            roleCode,
            user.getEmail(),
            hospital != null ? (" in hospital '" + hospital.getName() + "'") : " (global)");

        syncLegacyRole(user, role);
        if (sendNotifications) {
            sendAssignmentEmailNotification(saved);
            sendAssignmentSmsNotifications(saved);
        }
        recordAssignmentAudit(saved);

        return saved;
    }

    private User resolveUser(UserRoleHospitalAssignmentRequestDTO dto, Locale locale) {
        if (dto.getUserId() != null) {
            return userRepository.findById(dto.getUserId()).orElseThrow(() ->
                new ResourceNotFoundException(messageSource.getMessage(
                    MSG_USER_NOT_FOUND,
                    new Object[]{dto.getUserId()},
                    DEFAULT_USER_NOT_FOUND_PREFIX + dto.getUserId(),
                    locale)));
        }

        String identifier = dto.getUserIdentifier();
        if (identifier != null && !identifier.isBlank()) {
            String trimmed = identifier.trim();
            return userRepository.findByUsername(trimmed)
                .or(() -> userRepository.findByEmail(trimmed))
                .or(() -> userRepository.findByPhoneNumber(trimmed))
                .orElseThrow(() -> new ResourceNotFoundException(messageSource.getMessage(
                    MSG_USER_NOT_FOUND,
                    new Object[]{trimmed},
                    DEFAULT_USER_NOT_FOUND_PREFIX + trimmed,
                    locale)));
        }

        throw new BusinessException(messageSource.getMessage(
            MSG_USER_NOT_FOUND,
            new Object[]{null},
            DEFAULT_USER_NOT_FOUND_PREFIX + "<unknown>",
            locale));
    }

    private User resolveRegistrar(UserRoleHospitalAssignmentRequestDTO dto, Locale locale) {
        if (dto.getRegisteredByUserId() != null) {
            return userRepository.findById(dto.getRegisteredByUserId()).orElseThrow(() ->
                new ResourceNotFoundException(messageSource.getMessage(
                    MSG_USER_NOT_FOUND,
                    new Object[]{dto.getRegisteredByUserId()},
                    DEFAULT_USER_NOT_FOUND_PREFIX + dto.getRegisteredByUserId(),
                    locale)));
        }

        String principal = SecurityUtils.getCurrentUsername();
        if (principal == null || principal.isBlank()) {
            return null;
        }

        return userRepository.findByUsername(principal)
            .or(() -> userRepository.findByEmail(principal))
            .orElse(null);
    }

    private void enforceRoleScopeConstraints(UserRoleHospitalAssignmentRequestDTO dto, String roleCode) {
        if (isRoleCode(roleCode, ROLE_SUPER_ADMIN)) {
            if (dto.getHospitalId() != null
                || (dto.getHospitalCode() != null && !dto.getHospitalCode().isBlank())
                || (dto.getHospitalName() != null && !dto.getHospitalName().isBlank())) {
                throw new BusinessException(DEFAULT_SUPER_ADMIN_SCOPE_MESSAGE);
            }
            return;
        }

        if (isRoleCode(roleCode, ROLE_PATIENT)) {
            // Patient assignments start inactive — activated after email verification
            if (Boolean.TRUE.equals(dto.getActive())) {
                throw new BusinessException("PATIENT assignments must be inactive until email verification.");
            }
            if (dto.getActive() == null) {
                dto.setActive(Boolean.FALSE);
            }
        }
    }

    /* ===================== Update ===================== */

    @Override
    public UserRoleHospitalAssignmentResponseDTO updateAssignment(UUID id, UserRoleHospitalAssignmentRequestDTO dto) {
        final Locale locale = Locale.getDefault();

        UserRoleHospitalAssignment target = assignmentRepository.findById(id).orElseThrow(() ->
            new ResourceNotFoundException(
                messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND,
                    new Object[]{id},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX + id, locale)));

        User newUser = resolveUserForUpdate(dto, target, locale);
        Role newRole = resolveRoleForUpdate(dto, target, locale);
        Hospital newHospital = resolveHospitalForUpdate(dto, target, newRole, locale);

        enforcePatientInactiveConstraint(dto, newRole, target);
        checkTupleDuplicateOnUpdate(id, target, newUser, newRole, newHospital, locale);

        if (Boolean.TRUE.equals(dto.getActive())) {
            checkActiveDoctorConflict(dto, newUser, newRole, newHospital, locale);
        }

        mapper.updateEntity(target, dto, newHospital, newRole, null);
        target.setUser(newUser);

        UserRoleHospitalAssignment saved = assignmentRepository.save(target);
        log.info("🔄 Updated assignment ID '{}' for user '{}'", id, newUser.getEmail());
        return toDtoWithLinks(saved);
    }

    private User resolveUserForUpdate(UserRoleHospitalAssignmentRequestDTO dto,
                                      UserRoleHospitalAssignment target, Locale locale) {
        if (dto.getUserId() == null) {
            return target.getUser();
        }
        return userRepository.findById(dto.getUserId()).orElseThrow(() ->
            new ResourceNotFoundException(
                messageSource.getMessage(MSG_USER_NOT_FOUND, new Object[]{dto.getUserId()},
                    DEFAULT_USER_NOT_FOUND_PREFIX + dto.getUserId(), locale)));
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
            throw new BusinessException("PATIENT assignments cannot be manually activated. Use email verification.");
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
        final Locale locale = Locale.getDefault();
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(id).orElseThrow(() ->
            new ResourceNotFoundException(
                messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND,
                    new Object[]{id},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX + id,
                    locale)));
        return toDtoWithLinks(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable) {
        log.info("📄 Fetching assignments without filters (page {}, size {})", pageable.getPageNumber(),
            pageable.getPageSize());
        return assignmentRepository.findAll(pageable).map(this::toDtoWithLinks);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable,
                                                                         AssignmentSearchCriteria criteria) {
        UUID hospitalId = criteria != null ? parseUuid(criteria.getHospitalId()) : null;
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

        Locale locale = Locale.getDefault();
        Set<UUID> hospitalIds = collectTargetHospitalIds(requestDTO, locale);
        List<UserRoleHospitalAssignmentResponseDTO> successes = new ArrayList<>();
        List<UserRoleAssignmentFailureDTO> failures = new ArrayList<>();

        if (hospitalIds.isEmpty()) {
            UserRoleHospitalAssignment assignment = createAssignment(
                buildSingleAssignmentRequest(requestDTO, null),
                locale,
                requestDTO.isSendNotifications());
            successes.add(toDtoWithLinks(assignment));
            return UserRoleAssignmentBatchResponseDTO.builder()
                .requestedAssignments(1)
                .createdAssignments(1)
                .assignments(successes)
                .failures(failures)
                .build();
        }

        for (UUID hospitalId : hospitalIds) {
            UserRoleHospitalAssignmentRequestDTO singleRequest = buildSingleAssignmentRequest(requestDTO, hospitalId);
            try {
                UserRoleHospitalAssignment assignment = createAssignment(singleRequest, locale, requestDTO.isSendNotifications());
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
            } catch (BusinessException | ResourceNotFoundException ex) {
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

    @Override
    public UserRoleHospitalAssignmentResponseDTO regenerateAssignmentCode(UUID assignmentId, boolean resendNotifications) {
        Locale locale = Locale.getDefault();
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_NOT_FOUND,
                    new Object[]{assignmentId},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX + assignmentId,
                    locale)));

        assignment.setAssignmentCode(generateAssignCode(assignment.getUser(), assignment.getHospital()));
        assignment.setConfirmationCode(generateConfirmationCode());
        assignment.setConfirmationSentAt(LocalDateTime.now());
        assignment.setConfirmationVerifiedAt(null);

        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);
        if (resendNotifications) {
            sendAssignmentEmailNotification(saved);
            sendAssignmentSmsNotifications(saved);
        }
        recordAssignmentAudit(saved);
        log.info("🔁 Regenerated assignment code for assignment '{}'", assignmentId);
        return toDtoWithLinks(saved);
    }

    @Override
    public UserRoleHospitalAssignmentResponseDTO confirmAssignment(UUID assignmentId, String confirmationCode) {
        Locale locale = Locale.getDefault();
        String sanitizedCode = confirmationCode != null ? confirmationCode.trim() : null;
        if (sanitizedCode == null || sanitizedCode.isBlank()) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_INVALID_CODE,
                    null,
                    DEFAULT_CONFIRMATION_CODE_INVALID,
                    locale));
        }

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_NOT_FOUND,
                    new Object[]{assignmentId},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX + assignmentId,
                    locale)));

        if (assignment.getConfirmationVerifiedAt() != null) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ALREADY_CONFIRMED,
                    null,
                    DEFAULT_ASSIGNMENT_ALREADY_CONFIRMED,
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

        User registrar = assignment.getRegisteredBy();
        User actor = resolveCurrentAuthenticatedUser().orElseThrow(() ->
            new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ACTOR_MISMATCH,
                    null,
                    DEFAULT_ACTOR_RESOLUTION_FAILURE,
                    locale)));

        if (registrar == null || registrar.getId() == null || !registrar.getId().equals(actor.getId())) {
            throw new BusinessException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_ACTOR_MISMATCH,
                    null,
                    DEFAULT_CONFIRMATION_ACTOR_MISMATCH,
                    locale));
        }

        assignment.setConfirmationVerifiedAt(LocalDateTime.now());
        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);

        recordAssignmentConfirmationAudit(saved, actor);
        log.info("✅ Assignment '{}' confirmed by actor '{}'", saved.getId(), actor.getId());
        return toDtoWithLinks(saved);
    }

    @Override
    public UserRoleAssignmentPublicViewDTO getAssignmentPublicView(String assignmentCode) {
        Locale locale = Locale.getDefault();
        String sanitized = assignmentCode != null ? assignmentCode.trim() : null;
        if (sanitized == null || sanitized.isBlank()) {
            throw new ResourceNotFoundException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_NOT_FOUND_BY_CODE,
                    new Object[]{assignmentCode},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_BY_CODE_PREFIX + assignmentCode,
                    locale));
        }

        UserRoleHospitalAssignment assignment = assignmentRepository.findByAssignmentCode(sanitized)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(
                    MSG_ASSIGNMENT_NOT_FOUND_BY_CODE,
                    new Object[]{sanitized},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_BY_CODE_PREFIX + sanitized,
                    locale)));

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
            .profileCompletionUrl(assignmentLinkService.buildProfileCompletionUrl(assignment.getAssignmentCode()))
            .profileChecklist(buildRoleProfileChecklist(role))
            .build();
    }

    /**
     * Self-service verification — called by the ASSIGNEE from the onboarding email link.
     * No authentication required. Verifies the 6-digit confirmation code sent to the assignee
     * and marks the assignment as verified on success.
     */
    @Override
    public UserRoleAssignmentPublicViewDTO verifyAssignmentByCode(String assignmentCode, String confirmationCode) {
        Locale locale = Locale.getDefault();

        String sanitizedCode = assignmentCode != null ? assignmentCode.trim() : null;
        if (sanitizedCode == null || sanitizedCode.isBlank()) {
            throw new ResourceNotFoundException(
                messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND_BY_CODE,
                    new Object[]{assignmentCode},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_BY_CODE_PREFIX + assignmentCode,
                    locale));
        }

        String sanitizedPin = confirmationCode != null ? confirmationCode.trim() : null;
        if (sanitizedPin == null || sanitizedPin.isBlank()) {
            throw new BusinessException(
                messageSource.getMessage(MSG_ASSIGNMENT_INVALID_CODE, null,
                    DEFAULT_CONFIRMATION_CODE_INVALID, locale));
        }

        UserRoleHospitalAssignment assignment = assignmentRepository.findByAssignmentCode(sanitizedCode)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND_BY_CODE,
                    new Object[]{sanitizedCode},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_BY_CODE_PREFIX + sanitizedCode,
                    locale)));

        if (assignment.getConfirmationVerifiedAt() != null) {
            log.info("ℹ️ Assignment '{}' was already verified at {}", sanitizedCode, assignment.getConfirmationVerifiedAt());
            return buildPublicView(assignment);
        }

        String expectedPin = assignment.getConfirmationCode();
        if (expectedPin == null || !expectedPin.equalsIgnoreCase(sanitizedPin)) {
            throw new BusinessException(
                messageSource.getMessage(MSG_ASSIGNMENT_INVALID_CODE, null,
                    DEFAULT_CONFIRMATION_CODE_INVALID, locale));
        }

        assignment.setConfirmationVerifiedAt(LocalDateTime.now());
        assignment.setActive(Boolean.TRUE);
        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);

        syncLegacyRole(saved.getUser(), saved.getRole());
        recordAssignmentConfirmationAudit(saved, saved.getUser());

        // Build the DTO before clearing so temp credentials are included in this
        // one-time response.  After the DTO is constructed the plaintext is wiped
        // from the database so it is never surfaced again.
        UserRoleAssignmentPublicViewDTO result = buildPublicView(saved);
        if (saved.getTempPlainPassword() != null) {
            saved.setTempPlainPassword(null);
            assignmentRepository.save(saved);
            log.info("🔐 Temp credentials delivered via verify response for assignment '{}'; plaintext cleared.", sanitizedCode);
        }

        log.info("✅ Assignment '{}' self-verified by assignee", sanitizedCode);
        return result;
    }

    private UserRoleAssignmentPublicViewDTO buildPublicView(UserRoleHospitalAssignment assignment) {
        Role role = assignment.getRole();
        Hospital hospital = assignment.getHospital();
        User assignee = assignment.getUser();
        String tempPlain = assignment.getTempPlainPassword();
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
            .profileCompletionUrl(assignmentLinkService.buildProfileCompletionUrl(assignment.getAssignmentCode()))
            .profileChecklist(buildRoleProfileChecklist(role))
            .tempUsername(tempPlain != null && assignee != null ? assignee.getUsername() : null)
            .tempPassword(tempPlain)
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
        final Locale locale = Locale.getDefault();
        if (!assignmentRepository.existsById(id)) {
            throw new ResourceNotFoundException(
                messageSource.getMessage(MSG_ASSIGNMENT_NOT_FOUND,
                    new Object[]{id},
                    DEFAULT_ASSIGNMENT_NOT_FOUND_PREFIX + id,
                    locale));
        }
        assignmentRepository.deleteById(id);
        log.info("🗑️ Deleted assignment ID '{}'", id);
    }

    @Override
    public void deleteAllAssignmentsForUser(UUID userId) {
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(userId);
        assignmentRepository.deleteAll(assignments);
        log.info("🗑️ Deleted {} assignments for user ID '{}'", assignments.size(), userId);
    }

    @Override
    public void deleteRole(UUID roleId) {
        final Locale locale = Locale.getDefault();
        List<UserRoleHospitalAssignment> attached = assignmentRepository.findByRoleId(roleId);
        if (!attached.isEmpty()) {
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
                new ResourceNotFoundException(
                    messageSource.getMessage(MSG_ROLE_NOT_FOUND,
                        new Object[]{dto.getRoleId()},
                        DEFAULT_ROLE_NOT_FOUND_PREFIX + dto.getRoleId(), locale)));
        }
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            return roleRepository.findByNameIgnoreCase(dto.getRoleName()).orElseThrow(() ->
                new ResourceNotFoundException(
                    messageSource.getMessage(MSG_ROLE_NOT_FOUND_BY_NAME,
                        new Object[]{dto.getRoleName()},
                        DEFAULT_ROLE_NOT_FOUND_BY_NAME_PREFIX + dto.getRoleName(), locale)));
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
                new ResourceNotFoundException(
                    messageSource.getMessage(MSG_HOSPITAL_NOT_FOUND,
                        new Object[]{dto.getHospitalId()},
                        DEFAULT_HOSPITAL_NOT_FOUND_ID_PREFIX + dto.getHospitalId(), locale)));
        }
        // Try code then name
        if (dto.getHospitalCode() != null && !dto.getHospitalCode().isBlank()) {
            return hospitalRepository.findByCodeIgnoreCase(dto.getHospitalCode().trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage(MSG_HOSPITAL_NOT_FOUND, new Object[]{dto.getHospitalCode()},
                        DEFAULT_HOSPITAL_NOT_FOUND_CODE_PREFIX + dto.getHospitalCode(), locale)));
        }
        if (dto.getHospitalName() != null && !dto.getHospitalName().isBlank()) {
            return hospitalRepository.findByNameIgnoreCase(dto.getHospitalName().trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage(MSG_HOSPITAL_NOT_FOUND, new Object[]{dto.getHospitalName()},
                        DEFAULT_HOSPITAL_NOT_FOUND_NAME_PREFIX + dto.getHospitalName(), locale)));
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
            log.info("⚠️ Role '{}' already assigned to user '{}' for hospital '{}'.",
                getRoleCode(role),
                user.getEmail(),
                hospital != null ? hospital.getName() : GLOBAL_SCOPE);
            throw new ConflictException(
                messageSource.getMessage(MSG_ASSIGNMENT_CONFLICT, null,
                    DEFAULT_ROLE_ALREADY_ASSIGNED, locale));
        }
    }

    private Set<UUID> collectTargetHospitalIds(UserRoleAssignmentMultiRequestDTO request, Locale locale) {
        Set<UUID> hospitalIds = new LinkedHashSet<>();
        if (request.getHospitalIds() != null) {
            hospitalIds.addAll(request.getHospitalIds());
        }
        if (request.getOrganizationIds() != null) {
            for (UUID organizationId : request.getOrganizationIds()) {
                Organization organization = organizationRepository.findByIdWithHospitals(organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage(
                            MSG_ORGANIZATION_NOT_FOUND,
                            new Object[]{organizationId},
                            DEFAULT_ORGANIZATION_NOT_FOUND_PREFIX + organizationId,
                            locale)));
                organization.getHospitals().stream()
                    .filter(Objects::nonNull)
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
            .registeredByUserId(request.getRegisteredByUserId())
            .build();
    }

    private BulkImportOutcome handleBulkImportRow(int rowNumber,
                                                  String[] tokens,
                                                  Map<String, Integer> headerIndex,
                                                  UserRoleAssignmentBulkImportRequestDTO options) {
        try {
            UserRoleHospitalAssignmentRequestDTO request = buildRequestFromCsvRow(tokens, headerIndex, options);
            UserRoleHospitalAssignment assignment = createAssignment(request, Locale.getDefault(), options.isSendNotifications());

            UserRoleAssignmentBulkImportResultDTO result = UserRoleAssignmentBulkImportResultDTO.builder()
                .rowNumber(rowNumber)
                .identifier(resolveRowIdentifier(request))
                .success(true)
                .message(DEFAULT_CREATED_MESSAGE)
                .assignmentId(assignment.getId())
                .assignmentCode(assignment.getAssignmentCode())
                .hospitalId(assignment.getHospital() != null ? assignment.getHospital().getId() : null)
                .roleCode(assignment.getRole() != null ? getRoleCode(assignment.getRole()) : null)
                .profileCompletionUrl(assignmentLinkService.buildProfileCompletionUrl(assignment.getAssignmentCode()))
                .build();

            return BulkImportOutcome.created(result);
        } catch (ConflictException conflict) {
            if (!options.isSkipConflicts()) {
                throw conflict;
            }
            return BulkImportOutcome.skipped(buildFailureResult(rowNumber, tokens, headerIndex, conflict.getMessage()));
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

        UUID registrarId = parseUuid(getColumnValue(tokens, headerIndex, "registered_by_user_id", "registrar_id"));
        if (registrarId == null) {
            registrarId = options.getRegisteredByUserId();
        }
        if (registrarId != null) {
            builder.registeredByUserId(registrarId);
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

            notifyRegistrarBySms(assignment.getRegisteredBy(), roleDisplay, assigneeDisplay, hospitalDisplay, assignmentCode, confirmationCode);
            notifyHospitalBySms(hospital, assigneeDisplay, roleDisplay, assignmentCode, confirmationCode);
            notifyAssigneeBySms(assignment, user, roleDisplay, hospitalDisplay, confirmationCode, assignmentCode);
        } catch (RuntimeException e) {
            log.warn("⚠️ Failed to send SMS notifications for assignment '{}': {}", assignment.getId(), e.getMessage());
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
            return;
        }
        if (confirmationCode == null || confirmationCode.isBlank()) {
            log.warn("⚠️ Confirmation code missing for assignment '{}'; skipping SMS confirmation message to user", assignment.getId());
            return;
        }
        String message = String.format(
            "👋 Confirm your '%s' role at '%s'. Use code: %s (reference: %s)",
            roleDisplay,
            hospitalDisplay,
            confirmationCode,
            assignmentCode
        );
        sendSmsSilently(phone, message);
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

    private void sendAssignmentEmailNotification(UserRoleHospitalAssignment assignment) {
        try {
            User user = assignment.getUser();
            if (user == null) {
                return;
            }

            String email = user.getEmail();
            if (email == null || email.isBlank()) {
                return;
            }

            String confirmationCode = assignment.getConfirmationCode();
            if (confirmationCode == null || confirmationCode.isBlank()) {
                log.warn("⚠️ Confirmation code missing for assignment '{}'; skipping email notification", assignment.getId());
                return;
            }

            String userDisplayName = resolveDisplayName(user, email);
            String roleDisplay = assignment.getRole() != null ? getRoleCode(assignment.getRole()) : "assigned role";
            String hospitalDisplay = resolveHospitalName(assignment.getHospital());
            String profileCompletionUrl = assignmentLinkService.buildProfileCompletionUrl(assignment.getAssignmentCode());

            // Capture temp credentials before clearing them from the DB
            String tempUsername = user.getUsername();
            String tempPlain = assignment.getTempPlainPassword();

            emailService.sendRoleAssignmentConfirmationEmail(
                email,
                userDisplayName,
                roleDisplay,
                hospitalDisplay,
                confirmationCode,
                assignment.getAssignmentCode(),
                profileCompletionUrl,
                tempPlain != null ? tempUsername : null,
                tempPlain
            );

            if (tempPlain != null) {
                log.info("🔐 Temp credentials included in onboarding email for assignment '{}'.", assignment.getId());
            }
        } catch (RuntimeException ex) {
            log.warn("⚠️ Failed to send assignment confirmation email for assignment '{}': {}", assignment.getId(), ex.getMessage());
        }
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
                .roleName(role != null ? role.getName() : null)
                .hospitalName(resolveHospitalName(hospital))
                .resourceName(assigneeDisplay)
                .resourceId(assignment.getId() != null ? assignment.getId().toString() : null)
                .entityType("USER_ROLE_ASSIGNMENT")
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

            User assignee = assignment.getUser();
            Role role = assignment.getRole();

            String assigneeDisplay = resolveDisplayName(assignee, "unknown user");
            String roleDisplay = role != null ? getRoleCode(role) : UNKNOWN_ROLE;

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                .userId(actor != null ? actor.getId() : null)
                .assignmentId(assignment.getId())
                .userName(resolveDisplayName(actor, null))
                .roleName(role != null ? role.getName() : null)
                .hospitalName(resolveHospitalName(assignment.getHospital()))
                .resourceName(assigneeDisplay)
                .resourceId(assignment.getId() != null ? assignment.getId().toString() : null)
                .entityType("USER_ROLE_ASSIGNMENT")
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

    private Optional<User> resolveCurrentAuthenticatedUser() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
            username,
            username,
            null
        );
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
    public List<AssignmentMinimalDTO> getMinimalAssignments() {
        return assignmentRepository.findAll()
            .stream()
            .map(assignment -> {
                User user = assignment.getUser();
                Role role = assignment.getRole();
                Hospital hospital = assignment.getHospital();

                String displayName = String.format("%s - %s",
                    resolveUserDisplayName(user),
                    resolveRoleDisplayName(role)
                );
                String description = (hospital != null && StringUtils.hasText(hospital.getName()))
                    ? hospital.getName()
                    : "Global";
                
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
