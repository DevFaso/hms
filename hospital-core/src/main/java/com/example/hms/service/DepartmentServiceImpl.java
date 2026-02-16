package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DepartmentMapper;
import com.example.hms.model.Department;
import com.example.hms.model.DepartmentTranslation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.payload.dto.DepartmentFilterDTO;
import com.example.hms.payload.dto.DepartmentMinimalDTO;
import com.example.hms.payload.dto.DepartmentRequestDTO;
import com.example.hms.payload.dto.DepartmentResponseDTO;
import com.example.hms.payload.dto.DepartmentStatsDTO;
import com.example.hms.payload.dto.DepartmentWithStaffDTO;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper;
    private final HospitalRepository hospitalRepository;
    private final StaffService staffService;
    private final MessageSource messageSource;
    private final AuthService authService;
    private final UserRoleHospitalAssignmentRepository roleAssignmentRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final String FIELD_HEAD_OF_DEPARTMENT = "headOfDepartment";
    private static final String FIELD_HOSPITAL = "hospital";
    private static final String FIELD_EMAIL = "email";
    private static final String MESSAGE_DEPARTMENT_NOT_FOUND = "department.notFound";
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    @Transactional(readOnly = true)
    @Override
    public List<DepartmentResponseDTO> getAllDepartments(UUID organizationId,
                                                         Boolean unassignedOnly,
                                                         String city,
                                                         String state,
                                                         Locale locale) {
        DepartmentFilterDTO filter = DepartmentFilterDTO.builder()
            .organizationId(Boolean.TRUE.equals(unassignedOnly) ? null : organizationId)
            .unassignedOnly(Boolean.TRUE.equals(unassignedOnly) ? Boolean.TRUE : null)
            .city(city)
            .state(state)
            .build();

        List<Department> entities = hasFilterCriteria(filter)
            ? departmentRepository.findAll(createDepartmentSpecification(filter))
            : departmentRepository.findAllWithHospitalAndHead();

        log.debug("Loaded {} departments with filters org={} unassigned={} city={} state={}",
            entities.size(), organizationId, unassignedOnly, city, state);

        return mapDepartments(locale, entities);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<DepartmentResponseDTO> getAllDepartments(Pageable pageable, Locale locale) {
        return departmentRepository.findAll(pageable)
                .map(department -> buildLocalizedResponse(department, locale));
    }

    @Override
    @Transactional
    public DepartmentResponseDTO createDepartment(DepartmentRequestDTO dto, Locale locale) {
        long start = System.currentTimeMillis();
        log.debug("[dept:create] start dto={} locale={}", dto, locale);

        Locale effectiveLocale = determineEffectiveLocale(locale);
        validateDepartmentRequest(dto, effectiveLocale);

        Hospital hospital = resolveHospitalAndSyncDto(dto, effectiveLocale);
        ensureDepartmentUniqueness(dto, hospital, effectiveLocale);

        Staff headOfDepartment = resolveHeadOfDepartment(dto, effectiveLocale);
        UserRoleHospitalAssignment assignment = resolveCreatorAssignment(hospital, effectiveLocale);

        Department department = departmentMapper.toDepartment(dto, hospital, headOfDepartment, assignment);
        ensureLocaleTranslation(department, assignment, effectiveLocale);

        Department savedDepartment = departmentRepository.save(department);
        updateHeadDepartmentIfNeeded(savedDepartment, headOfDepartment, effectiveLocale);

        log.debug("[dept:create] end totalMs={}", (System.currentTimeMillis() - start));
        return buildLocalizedResponse(savedDepartment, effectiveLocale);
    }

    @Override
    @Transactional
    public DepartmentResponseDTO updateDepartmentHead(UUID departmentId, UUID staffId, Locale locale) {
        Department department = findDepartmentOrThrow(departmentId, locale);
        Staff newHead = staffService.getStaffEntityById(staffId, locale);

        if (!newHead.getHospital().getId().equals(department.getHospital().getId())) {
            throw new BusinessRuleException(messageSource.getMessage("department.head.wrongHospital", null, locale));
        }

        department.setHeadOfDepartment(newHead);
        departmentRepository.save(department);
        staffService.updateStaffDepartment(newHead.getUser().getEmail(), department.getName(), department.getHospital().getName(), locale);
        return buildLocalizedResponse(department, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentMinimalDTO> getActiveDepartmentsMinimal(UUID hospitalId, Locale locale) {
        return departmentRepository.findByHospitalId(hospitalId).stream()
                .map(dept -> new DepartmentMinimalDTO(dept.getId(), dept.getName(), dept.getEmail(), dept.getPhoneNumber()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHeadOfDepartment(UUID staffId, Locale locale) {
        return departmentRepository.existsByHeadOfDepartment_Id(staffId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepartmentResponseDTO> getDepartmentsByHospital(UUID hospitalId, Pageable pageable, Locale locale) {
        Page<Department> page = departmentRepository.findByHospitalId(hospitalId, pageable);
        return page.map(d -> departmentMapper.toDepartmentResponseDTO(d, locale));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepartmentResponseDTO> searchDepartments(String query, Pageable pageable, Locale locale) {
        String normalized = normalize(query);
        Page<Department> page;
        if (normalized == null) {
            page = departmentRepository.findAll(pageable);
        } else {
            Specification<Department> spec = (root, criteriaQuery, cb) -> {
                if (criteriaQuery != null) {
                    criteriaQuery.distinct(true);
                }
                String likePattern = "%" + normalized.toLowerCase(Locale.ROOT) + "%";
                Join<Department, Hospital> hospitalJoin = root.join(FIELD_HOSPITAL, JoinType.LEFT);
                Join<Department, Staff> headJoin = root.join(FIELD_HEAD_OF_DEPARTMENT, JoinType.LEFT);
                Join<Staff, User> userJoin = headJoin.join("user", JoinType.LEFT);

                return cb.or(
                        cb.like(cb.lower(root.get("name")), likePattern),
                        cb.like(cb.lower(root.get("code")), likePattern),
                        cb.like(cb.lower(root.get(FIELD_EMAIL)), likePattern),
                        cb.like(cb.lower(root.get("phoneNumber")), likePattern),
                        cb.like(cb.lower(hospitalJoin.get("name")), likePattern),
                        cb.like(cb.lower(hospitalJoin.get("code")), likePattern),
                        cb.like(cb.lower(hospitalJoin.get("city")), likePattern),
                        cb.like(cb.lower(userJoin.get(FIELD_EMAIL)), likePattern),
                        cb.like(cb.lower(userJoin.get("firstName")), likePattern),
                        cb.like(cb.lower(userJoin.get("lastName")), likePattern)
                );
            };
            page = departmentRepository.findAll(spec, pageable);
        }

        return page.map(d -> departmentMapper.toDepartmentResponseDTO(d, locale));
    }

    @Override
    @Transactional
    public DepartmentResponseDTO updateDepartment(UUID id, DepartmentRequestDTO dto, Locale locale) {
        Department department = findDepartmentOrThrow(id, locale);
        validateDepartmentRequest(dto, locale);

        Hospital hospital = resolveHospital(dto, locale);
        dto.setHospitalName(hospital.getName());
        dto.setCode(normalizeDepartmentCode(dto.getCode()));

        Staff newHeadOfDepartment = null;
        if (dto.getHeadOfDepartmentEmail() != null && !dto.getHeadOfDepartmentEmail().isBlank()) {
            List<StaffResponseDTO> staffList = staffService.getStaffByUserEmail(dto.getHeadOfDepartmentEmail(), locale);
            if (!staffList.isEmpty()) {
                StaffResponseDTO staffDto = staffList.get(0);
                newHeadOfDepartment = staffService.getStaffEntityById(UUID.fromString(staffDto.getId()), locale);
            }
        }
        Staff currentHead = department.getHeadOfDepartment();

        departmentMapper.updateDepartmentFromDto(dto, department, hospital, newHeadOfDepartment);
        Department updatedDepartment = departmentRepository.save(department);

        if (newHeadOfDepartment != null &&
                (currentHead == null || !newHeadOfDepartment.getId().equals(currentHead.getId()))) {
            staffService.updateStaffDepartment(newHeadOfDepartment.getUser().getEmail(), department.getName(), hospital.getName(), locale);
        }

        return buildLocalizedResponse(updatedDepartment, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentWithStaffDTO getDepartmentWithStaff(UUID departmentId, Locale locale) {
        Department department = findDepartmentOrThrow(departmentId, locale);

        List<StaffMinimalDTO> staffList = department.getStaffMembers() != null ?
                department.getStaffMembers().stream()
                        .map(staffService::toMinimalDTO)
                        .toList() :
                Collections.emptyList();

        return new DepartmentWithStaffDTO(
                department.getId(),
                department.getName(),
                department.getDescription(),
                department.getEmail(),
                department.getPhoneNumber(),
                department.getHeadOfDepartment() != null ?
                        new StaffMinimalDTO(
                                department.getHeadOfDepartment().getId(),
                                department.getHeadOfDepartment().getUser().getFirstName() + " " +
                                        department.getHeadOfDepartment().getUser().getLastName(),
                                department.getHeadOfDepartment().getJobTitle()
                        ) : null,
                staffList
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepartmentResponseDTO> filterDepartments(DepartmentFilterDTO filter, Pageable pageable, Locale locale) {
        Specification<Department> spec = createDepartmentSpecification(filter);
        return departmentRepository.findAll(spec, pageable)
                .map(department -> buildLocalizedResponse(department, locale));
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentResponseDTO getDepartmentById(UUID id, Locale locale) {
        Department department = departmentRepository.findByIdWithHeadOfDepartment(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage(MESSAGE_DEPARTMENT_NOT_FOUND, new Object[]{id}, locale)
                ));

        return buildLocalizedResponse(department, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentStatsDTO getDepartmentStatistics(UUID departmentId, Locale locale) {
        Department department = departmentRepository.findByIdWithTranslations(departmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage(MESSAGE_DEPARTMENT_NOT_FOUND, new Object[]{departmentId}, locale)
                ));

        int totalStaff = department.getStaffMembers() != null ? department.getStaffMembers().size() : 0;
        assert department.getStaffMembers() != null;
        int totalDoctors = (int) department.getStaffMembers().stream()
                .filter(staff -> staff.getAssignment() != null && "ROLE_DOCTOR".equalsIgnoreCase(staff.getAssignment().getRole().getName()))
                .count();

        int totalNurses = (int) department.getStaffMembers().stream()
                .filter(staff -> staff.getAssignment() != null && "ROLE_NURSE".equalsIgnoreCase(staff.getAssignment().getRole().getName()))
                .count();

        return new DepartmentStatsDTO(totalStaff, totalDoctors, totalNurses, 0, 0);
    }

    @Override
    @Transactional
    public void deleteDepartment(UUID id, Locale locale) {
        if (departmentRepository.hasStaffMembers(id)) {
            throw new BusinessRuleException(
                    messageSource.getMessage("department.delete.withStaff", new Object[]{id}, locale)
            );
        }
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Department not found"));

        departmentRepository.delete(department);
    }

    private Locale determineEffectiveLocale(Locale locale) {
        return locale != null ? locale : DEFAULT_LOCALE;
    }

    private Hospital resolveHospitalAndSyncDto(DepartmentRequestDTO dto, Locale locale) {
        Hospital hospital = resolveHospital(dto, locale);
        dto.setHospitalName(hospital.getName());
        dto.setCode(normalizeDepartmentCode(dto.getCode()));
        return hospital;
    }

    private void ensureDepartmentUniqueness(DepartmentRequestDTO dto, Hospital hospital, Locale locale) {
        boolean exists = departmentRepository.existsByNameIgnoreCaseAndHospitalId(dto.getName(), hospital.getId());
        if (exists) {
            throw new ConflictException(messageSource.getMessage(
                "department.duplicate",
                new Object[]{dto.getName(), hospital.getName()},
                locale
            ));
        }
    }

    private Staff resolveHeadOfDepartment(DepartmentRequestDTO dto, Locale locale) {
        if (dto.getHeadOfDepartmentEmail() == null || dto.getHeadOfDepartmentEmail().isBlank()) {
            return null;
        }

        List<StaffResponseDTO> staffList = staffService.getStaffByUserEmail(dto.getHeadOfDepartmentEmail(), locale);
        if (staffList.isEmpty()) {
            return null;
        }

        StaffResponseDTO staffDto = staffList.get(0);
        return staffService.getStaffEntityById(UUID.fromString(staffDto.getId()), locale);
    }

    private UserRoleHospitalAssignment resolveCreatorAssignment(Hospital hospital, Locale locale) {
        UUID currentUserId = authService.getCurrentUserId();
        Optional<UserRoleHospitalAssignment> existingAssignment = roleAssignmentRepository
            .findByUserIdAndHospitalId(currentUserId, hospital.getId());

        if (existingAssignment.isPresent()) {
            return existingAssignment.get();
        }

        if (!authService.hasRole("ROLE_SUPER_ADMIN")) {
            throw new ResourceNotFoundException(
                messageSource.getMessage("assignment.notfound", new Object[]{currentUserId, hospital.getId()}, locale)
            );
        }

        Role hospitalAdminRole = resolveHospitalAdminRole(locale);
        return roleAssignmentRepository
            .findFirstByHospitalIdAndRole_Name(hospital.getId(), hospitalAdminRole.getName())
            .orElseGet(() -> provisionHospitalAdminAssignmentForSuperAdmin(currentUserId, hospital, hospitalAdminRole, locale));
    }

    private void updateHeadDepartmentIfNeeded(Department department, Staff headOfDepartment, Locale locale) {
        if (headOfDepartment == null) {
            return;
        }

        boolean needsUpdate = !department.equals(headOfDepartment.getDepartment());
        if (needsUpdate) {
            staffService.updateStaffDepartment(
                headOfDepartment.getUser().getEmail(),
                department.getName(),
                department.getHospital().getName(),
                locale
            );
        }
    }

    private Specification<Department> createDepartmentSpecification(DepartmentFilterDTO filter) {
        return (root, query, cb) -> {
            if (query != null && Department.class.equals(query.getResultType())) {
                root.fetch(FIELD_HOSPITAL, JoinType.LEFT);
                root.fetch(FIELD_HEAD_OF_DEPARTMENT, JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            Join<Department, Hospital> hospitalJoin = root.join(FIELD_HOSPITAL, JoinType.LEFT);
            Join<Hospital, Organization> organizationJoin = hospitalJoin.join("organization", JoinType.LEFT);

            applyDepartmentFilterPredicates(filter, cb, root, hospitalJoin, organizationJoin, predicates);

            if (query != null) {
                query.distinct(true);
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void applyDepartmentFilterPredicates(DepartmentFilterDTO filter,
                                                 CriteriaBuilder cb,
                                                 Root<Department> root,
                                                 Join<Department, Hospital> hospitalJoin,
                                                 Join<Hospital, Organization> organizationJoin,
                                                 List<Predicate> predicates) {
        if (filter == null) {
            return;
        }

        addLikePredicate(predicates, cb, root.get(FIELD_EMAIL), filter.getEmail());
        addLikePredicate(predicates, cb, root.get("phoneNumber"), filter.getPhoneNumber());
        addLikePredicate(predicates, cb, root.get("name"), filter.getName());
    addEqualsPredicate(predicates, cb, hospitalJoin.get("id"), filter.getHospitalId());
    addOrganizationFilterPredicate(filter, cb, organizationJoin, predicates);
    addEqualsPredicate(predicates, cb, root.get("active"), filter.getActive());
    addEqualsPredicate(predicates, cb, root.get(FIELD_HEAD_OF_DEPARTMENT).get("id"), filter.getHeadOfDepartmentId());
    addLikePredicate(predicates, cb, hospitalJoin.get("city"), filter.getCity());
    addLikePredicate(predicates, cb, hospitalJoin.get("state"), filter.getState());
    }

    private void addOrganizationFilterPredicate(DepartmentFilterDTO filter,
                                                CriteriaBuilder cb,
                                                Join<Hospital, Organization> organizationJoin,
                                                List<Predicate> predicates) {
        if (Boolean.TRUE.equals(filter.getUnassignedOnly())) {
            predicates.add(cb.isNull(organizationJoin.get("id")));
            return;
        }

        addEqualsPredicate(predicates, cb, organizationJoin.get("id"), filter.getOrganizationId());
    }

    private void addLikePredicate(List<Predicate> predicates,
                                  CriteriaBuilder cb,
                                  Expression<String> expression,
                                  String rawValue) {
        String normalized = normalize(rawValue);
        if (normalized != null) {
            predicates.add(cb.like(cb.lower(expression), likePattern(normalized)));
        }
    }

    private void addEqualsPredicate(List<Predicate> predicates,
                                    CriteriaBuilder cb,
                                    Expression<?> expression,
                                    Object value) {
        if (value != null) {
            predicates.add(cb.equal(expression, value));
        }
    }

    private List<DepartmentResponseDTO> mapDepartments(Locale locale, List<Department> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }

        List<DepartmentResponseDTO> results = new ArrayList<>(entities.size());
        for (Department d : entities) {
            if (d == null) {
                continue;
            }
            try {
                results.add(buildLocalizedResponse(d, locale));
            } catch (RuntimeException e) {
                log.error("Failed to map department id={} name={} -> skipping", d.getId(), d.getName(), e);
            }
        }
        return results;
    }

    private boolean hasFilterCriteria(DepartmentFilterDTO filter) {
        if (filter == null) {
            return false;
        }

        return filter.getHospitalId() != null
            || filter.getOrganizationId() != null
            || Boolean.TRUE.equals(filter.getUnassignedOnly())
            || StringUtils.hasText(filter.getName())
            || StringUtils.hasText(filter.getEmail())
            || StringUtils.hasText(filter.getPhoneNumber())
            || filter.getHeadOfDepartmentId() != null
            || filter.getActive() != null
            || StringUtils.hasText(filter.getCity())
            || StringUtils.hasText(filter.getState());
    }

    private Department findDepartmentOrThrow(UUID id, Locale locale) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        messageSource.getMessage(MESSAGE_DEPARTMENT_NOT_FOUND, new Object[]{id}, locale)
                ));
    }


    private void validateDepartmentRequest(DepartmentRequestDTO dto, Locale locale) {
        if (dto == null) {
            throw new IllegalArgumentException("Department request cannot be null");
        }

        if (dto.getHospitalId() == null && !StringUtils.hasText(dto.getHospitalName())) {
            throw new IllegalArgumentException(
                    messageSource.getMessage("department.hospital.required", null, locale)
            );
        }

        if (!StringUtils.hasText(dto.getName())) {
            throw new IllegalArgumentException(
                    messageSource.getMessage("department.name.required", null, locale)
            );
        }
    }

    private Hospital resolveHospital(DepartmentRequestDTO dto, Locale locale) {
        if (dto.getHospitalId() != null) {
            return hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    messageSource.getMessage("hospital.notfound", new Object[]{dto.getHospitalId()}, locale)
                ));
        }

        return hospitalRepository.findByNameIgnoreCase(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
    }

    private String normalizeDepartmentCode(String code) {
        if (!StringUtils.hasText(code)) {
            return code;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }


    private DepartmentResponseDTO buildLocalizedResponse(Department department, Locale locale) {
        // The mapper now sets all human-readable fields, so just delegate
        return departmentMapper.toDepartmentResponseDTO(department, locale);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private void ensureLocaleTranslation(Department department,
                                         UserRoleHospitalAssignment assignment,
                                         Locale locale) {
        List<DepartmentTranslation> translations = department.getDepartmentTranslations();
        if (translations == null) {
            translations = new ArrayList<>();
            department.setDepartmentTranslations(translations);
        }

        for (DepartmentTranslation existing : translations) {
            if (existing.getAssignment() == null) {
                existing.setAssignment(assignment);
            }
            if (existing.getDepartment() == null) {
                existing.setDepartment(department);
            }
            if (!StringUtils.hasText(existing.getLanguage())) {
                existing.setLanguage(existing.getLanguageCode());
            }
        }

        String targetLanguage = (locale != null ? locale.getLanguage() : Locale.ENGLISH.getLanguage());
        boolean hasLocaleTranslation = translations.stream()
            .filter(Objects::nonNull)
            .anyMatch(t -> targetLanguage.equalsIgnoreCase(normalizeLanguageCode(t.getLanguageCode())));

        if (!hasLocaleTranslation) {
            DepartmentTranslation translation = new DepartmentTranslation();
            translation.setDepartment(department);
            translation.setAssignment(assignment);
            translation.setLanguageCode(targetLanguage.toLowerCase(Locale.ROOT));
            translation.setLanguage(targetLanguage);
            translation.setName(department.getName());
            translation.setDescription(department.getDescription());
            translations.add(translation);
        }
    }

    private String normalizeLanguageCode(String languageCode) {
        return languageCode == null ? null : languageCode.trim().toLowerCase(Locale.ROOT);
    }

    private Role resolveHospitalAdminRole(Locale locale) {
        return roleRepository.findByCode(ROLE_HOSPITAL_ADMIN)
            .or(() -> roleRepository.findByNameIgnoreCase(ROLE_HOSPITAL_ADMIN))
            .or(() -> roleRepository.findByNameIgnoreCase("HOSPITAL_ADMIN"))
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("role.notfound", new Object[]{ROLE_HOSPITAL_ADMIN}, locale)
            ));
    }

    private UserRoleHospitalAssignment provisionHospitalAdminAssignmentForSuperAdmin(UUID superAdminId,
                                                                                     Hospital hospital,
                                                                                     Role hospitalAdminRole,
                                                                                     Locale locale) {
        log.warn("[dept:create] Super admin {} lacks hospital admin assignment for hospital {} – auto provisioning",
            superAdminId, hospital.getId());

        User user = userRepository.findById(superAdminId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messageSource.getMessage("user.notfound", new Object[]{superAdminId}, locale)
            ));

        ensureUserHasRole(user, hospitalAdminRole);

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user)
            .hospital(hospital)
            .role(hospitalAdminRole)
            .active(true)
            .startDate(LocalDate.now())
            .registeredBy(user)
            .description("Auto-provisioned hospital admin assignment for department creation")
            .assignmentCode(generateAssignmentCode(hospital))
            .build();

        assignment.setAssignedAt(LocalDateTime.now());
        return roleAssignmentRepository.save(assignment);
    }

    private void ensureUserHasRole(User user, Role role) {
        if (!userRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) {
            UserRole userRole = UserRole.builder()
                .id(new UserRoleId(user.getId(), role.getId()))
                .user(user)
                .role(role)
                .build();
            userRoleRepository.save(userRole);
        }
    }

    private String generateAssignmentCode(Hospital hospital) {
        String prefix = hospital.getCode() != null && !hospital.getCode().isBlank()
            ? hospital.getCode().trim().toUpperCase(Locale.ROOT)
            : "HOSP";
        return prefix + "-AUTO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

}

