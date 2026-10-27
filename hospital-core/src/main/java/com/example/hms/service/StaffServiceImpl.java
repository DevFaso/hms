package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.StaffMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.StaffRequestDTO;
import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StaffServiceImpl implements StaffService {
    private static final String HOSPITAL_NOT_FOUND_KEY = "hospital.notFound";
    private static final String DEPARTMENT_NOT_FOUND_KEY = "department.notFound";
    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // i18n key for "staff not found" appears 3x in this file.
    // Naming follows the existing *_NOT_FOUND_KEY siblings above.
    private static final String STAFF_NOT_FOUND_KEY = "staff.notFound";
    private static final String ROLE_LAB_DIRECTOR = "ROLE_LAB_DIRECTOR";

    // Find staff by user email
    @Transactional(readOnly = true)
    public List<StaffResponseDTO> getStaffByUserEmail(String email, Locale locale) {
        return staffRepository.findByUserEmail(email).stream()
            .filter(this::isStaffVisible)
            .map(staffMapper::toStaffDTO)
            .toList();
    }

    // Find staff by user phone number
    @Transactional(readOnly = true)
    public List<StaffResponseDTO> getStaffByUserPhoneNumber(String phone, Locale locale) {
        return staffRepository.findByUserPhoneNumber(phone).stream()
            .filter(this::isStaffVisible)
            .map(staffMapper::toStaffDTO)
            .toList();
    }

    // Find any license by user ID
    @Transactional(readOnly = true)
    public Optional<String> getAnyLicenseByUserId(UUID userId) {
        // QA D506 (sibling): only a licence held on a staff row the caller may see.
        return staffRepository.findByUserId(userId).stream()
            .filter(this::isStaffVisible)
            .map(Staff::getLicenseNumber)
            .filter(lic -> lic != null && !lic.isBlank())
            .findFirst();
    }

    // Find staff by ID and active status
    @Override
    @Transactional(readOnly = true)
    public Optional<StaffResponseDTO> getStaffByIdAndActiveTrue(UUID id, Locale locale) {
        return staffRepository.findByIdAndActiveTrue(id)
            .filter(this::isStaffVisible)
            .map(staffMapper::toStaffDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffResponseDTO> getActiveStaffByUserId(UUID userId, Locale locale) {
        return staffRepository.findByUserId(userId).stream()
            .filter(staff -> staff.isActive() && isStaffVisible(staff)
                    && (staff.getUser() == null || !staff.getUser().isDeleted()))
            .map(staffMapper::toStaffDTO)
            .toList();
    }

    // Check if staff exists by ID, hospital, and active status
    @Transactional(readOnly = true)
    public boolean existsByIdAndHospitalIdAndActiveTrue(UUID id, UUID hospitalId) {
        requireHospitalScope(hospitalId);
        return staffRepository.existsByIdAndHospital_IdAndActiveTrue(id, hospitalId);
    }

    // Check if staff exists by license number and user ID
    @Transactional(readOnly = true)
    public boolean existsByLicenseNumberAndUserId(String licenseNumber, UUID userId) {
        return staffRepository.existsByLicenseNumberAndUserId(licenseNumber, userId);
    }

    // Find staff by hospital ID (paginated, excludes deleted users)
    @Transactional(readOnly = true)
    public Page<StaffResponseDTO> getStaffByHospitalId(UUID hospitalId, Pageable pageable) {
        requireHospitalScope(hospitalId);
        return staffRepository.findByHospitalIdExcludingDeletedUsers(hospitalId, pageable)
            .map(staffMapper::toStaffDTO);
    }

    // Find staff by hospital ID and active status (paginated, excludes deleted users)
    @Transactional(readOnly = true)
    public Page<StaffResponseDTO> getStaffByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable) {
        requireHospitalScope(hospitalId);
        return staffRepository.findByHospitalIdAndActiveTrueExcludingDeletedUsers(hospitalId, pageable)
            .map(staffMapper::toStaffDTO);
    }

    // Find first staff by user ID ordered by creation date
    @Transactional(readOnly = true)
    public Optional<StaffResponseDTO> getFirstStaffByUserIdOrderByCreatedAtAsc(UUID userId, Locale locale) {
        // QA D506: the oldest staff row the caller may see. Hospital admin A asking
        // for a user employed at B got B's row (email, phone) back.
        return staffRepository.findByUserId(userId).stream()
            .filter(this::isStaffVisible)
            .min(java.util.Comparator.comparing(Staff::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .map(staffMapper::toStaffDTO);
    }

    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffMapper staffMapper;
    private final MessageSource messageSource;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RoleRepository roleRepository;
    private final com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver;
    private final UserRoleHospitalAssignmentService assignmentService;


    @Override
    @Transactional(readOnly = true)
    public List<StaffResponseDTO> getAllStaff(Locale locale) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) {
            return staffRepository.findAllExcludingDeletedUsers().stream()
                .map(staffMapper::toStaffDTO)
                .toList();
        }

        Set<UUID> hospitalScope = HospitalScopeUtils.resolveScope(context);
        if (hospitalScope.isEmpty()) {
            return List.of();
        }

        return staffRepository.findByHospitalIdInExcludingDeletedUsers(hospitalScope).stream()
            .map(staffMapper::toStaffDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StaffResponseDTO getStaffById(UUID id, Locale locale) {
        Staff staff = findStaffOrThrow(id, locale);
        return staffMapper.toStaffDTO(staff);
    }

    @Override
    @Transactional
    public StaffResponseDTO createStaff(StaffRequestDTO dto, Locale locale) {
        validateBaseStaffRequirements(dto, locale);

        User user = resolveStaffUser(dto.getUserEmail());

        Hospital hospital = hospitalRepository.findByName(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, dto.getHospitalName()));

        requireHospitalScope(hospital.getId(), locale);

        Department department = null;
        if (dto.getDepartmentName() != null && !dto.getDepartmentName().isBlank()) {
            department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), dto.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND_KEY, dto.getDepartmentName()));
        }

        validateDepartmentHospitalConsistency(department, hospital, locale);

        Role role = null;
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            role = roleRepository.findByCode(dto.getRoleName().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("role.notfound.byname", dto.getRoleName()));
        }

        UserRoleHospitalAssignment assignment;
        if (role != null) {
            assignment = assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notFound"));
        } else {
            // No role specified — resolve the user's active assignment at this hospital
            assignment = activeStaffAssignmentAt(user.getId(), hospital.getId())
                .orElseThrow(() -> new BusinessRuleException(
                    getLocalizedMessage("staff.assignment.required", new Object[]{dto.getUserEmail(), dto.getHospitalName()}, locale)));
        }

        validateLicenseNumber(dto, locale);
        validateRoleSpecificRequirements(dto, dto.getRoleName(), locale);

        Staff staff = staffMapper.toStaff(dto, user, hospital, department, assignment);
        Staff savedStaff = staffRepository.save(staff);

        if (dto.getHeadOfDepartment() != null && dto.getHeadOfDepartment() && department != null) {
            departmentRepository.updateHeadOfDepartment(department.getId(), savedStaff.getId());
        }

        return staffMapper.toStaffDTO(savedStaff);
    }

    @Transactional
    public StaffResponseDTO updateStaff(UUID id, StaffRequestDTO dto, Locale locale) {
        Staff existingStaff = findStaffOrThrow(id, locale);
        validateBaseStaffRequirements(dto, locale);

        User user = resolveStaffUser(dto.getUserEmail());

        Hospital hospital = hospitalRepository.findByName(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, dto.getHospitalName()));

        requireHospitalScope(hospital.getId(), locale);

        Department department = null;
        if (dto.getDepartmentName() != null && !dto.getDepartmentName().isBlank()) {
            department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), dto.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND_KEY, dto.getDepartmentName()));
        }

        validateDepartmentHospitalConsistency(department, hospital, locale);

        Role role = null;
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            role = roleRepository.findByCode(dto.getRoleName().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("role.notfound.byname", dto.getRoleName()));
        }

        UserRoleHospitalAssignment assignment;
        if (role != null) {
            assignment = assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId())
                .orElseThrow(() -> new ResourceNotFoundException("assignment.notFound"));
        } else {
            // No role change requested — resolve the user's active assignment at this hospital
            assignment = activeStaffAssignmentAt(user.getId(), hospital.getId())
                .orElseGet(existingStaff::getAssignment); // fall back to existing if nothing active found yet
            if (assignment == null) {
                throw new BusinessRuleException(
                    getLocalizedMessage("staff.assignment.required", new Object[]{dto.getUserEmail(), dto.getHospitalName()}, locale));
            }
        }

        String existingLic = existingStaff.getLicenseNumber();
        String newLic = dto.getLicenseNumber();
        boolean licenceChanged = (existingLic == null && newLic != null)
                || (existingLic != null && !existingLic.equals(newLic));
        if (licenceChanged) {
            validateLicenseNumber(dto, locale);
        }

        validateRoleSpecificRequirements(dto, dto.getRoleName(), locale);

        boolean wasActive = existingStaff.isActive();
        staffMapper.updateStaffFromDto(dto, existingStaff, user, hospital, department, assignment);
        // QA D907: an edit never switches a deactivated record back on while
        // the access it stands for is off - that read as "active" for someone
        // who cannot sign in here. Access comes back through the assignment
        // (a new code, or a super-admin), which brings the record back with it.
        if (!wasActive && existingStaff.getAssignment() != null
                && Boolean.FALSE.equals(existingStaff.getAssignment().getActive())) {
            existingStaff.setActive(false);
        }
        // QA D907 (#968 review): switching a record off through PUT
        // {active:false} cuts the same access DELETE does, under the same rules.
        if (wasActive && !existingStaff.isActive()) {
            assignmentService.retireStaffAccessAtHospital(user.getId(), hospital.getId(),
                assignment != null ? assignment.getId() : null);
        }
        Staff updatedStaff = staffRepository.save(existingStaff);

        handleHeadOfDepartmentUpdates(dto, existingStaff, department, locale);
        return staffMapper.toStaffDTO(updatedStaff);
    }

    @Transactional
    @Override
    public void updateStaffDepartment(String staffEmail, String departmentName, String hospitalName, Locale locale) {
        Staff staff = userRepository.findByEmail(staffEmail)
            .flatMap(user -> staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId()))
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND_KEY, staffEmail));

        Hospital hospital = hospitalRepository.findByName(hospitalName)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_KEY, hospitalName));

        requireHospitalScope(hospital.getId(), locale);

        Department department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), departmentName)
            .orElseThrow(() -> new ResourceNotFoundException(DEPARTMENT_NOT_FOUND_KEY, departmentName));

        if (!staff.getHospital().getId().equals(department.getHospital().getId())) {
            throw new BusinessRuleException(messageSource.getMessage("staff.department.wrongHospital", null, locale));
        }

        staff.setDepartment(department);
        staffRepository.save(staff);
    }

    @Transactional(readOnly = true)
    @Override
    public Staff getStaffEntityById(UUID id, Locale locale) {
        return findStaffOrThrow(id, locale);
    }

    @Override
    @Transactional
    public void deleteStaff(UUID id, Locale locale) {
        Staff staff = findStaffOrThrow(id, locale);

        if (departmentRepository.existsByHeadOfDepartmentId(id)) {
            throw new BusinessRuleException(
                messageSource.getMessage("staff.delete.isHead", new Object[]{id}, locale)
            );
        }

        // QA D907: the dialog promises the person loses access here, and only
        // the staff row used to change - their role assignment stayed on, so
        // they still signed in and read patients. The access this record stood
        // for at its hospital is switched off with it, in this transaction:
        // never the caller's own, never the last super-admin's, never a row the
        // caller may not change (refused, nothing changed). The account stays
        // on - its holder may work at another hospital. A record switched off
        // before this rule still has its access cut on a second deactivation.
        // An open session loses the hospital on its very next request: the
        // hospital scope is rebuilt from the live assignments on every request
        // (ActingScopeResolver.liveContext), so naming this hospital answers
        // 403 NO_LONGER_PERMITTED. The role name itself leaves the session at
        // the next token refresh, which re-reads the active assignments.
        UUID userId = staff.getUser() != null ? staff.getUser().getId() : null;
        UUID hospitalId = staff.getHospital() != null ? staff.getHospital().getId() : null;
        UUID assignmentId = staff.getAssignment() != null ? staff.getAssignment().getId() : null;
        assignmentService.retireStaffAccessAtHospital(userId, hospitalId, assignmentId);

        if (!staff.isActive()) {
            return;
        }

        staff.setActive(false);
        staffRepository.save(staff);
    }

    /**
     * The holder's active staff assignment at this hospital. A patient row is
     * their own care, not a job: linking a staff record to it read a
     * deactivated nurse who is also a patient there as active staff again
     * (QA D907).
     */
    private Optional<UserRoleHospitalAssignment> activeStaffAssignmentAt(UUID userId, UUID hospitalId) {
        return assignmentRepository.findByUser_IdAndActiveTrue(userId).stream()
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId()))
            .filter(a -> a.getRole() == null || !isPatientRole(a.getRole().getCode()))
            .findFirst();
    }

    private static boolean isPatientRole(String code) {
        return code != null && "PATIENT".equalsIgnoreCase(code.startsWith("ROLE_") ? code.substring(5) : code);
    }

    @Override
    public StaffMinimalDTO toMinimalDTO(Staff staff) {
        if (staff == null) {
            return null;
        }
        return new StaffMinimalDTO(staff.getId(), staff.getFullName(), staff.getJobTitle());
    }

    /**
     * The account a staff record is for. {@code userEmail} carries the
     * account's e-mail or, for an account registered by phone only (no
     * e-mail), its username: the "Nouveau Personnel" picker used to send the
     * literal text "undefined" for those (QA D122).
     */
    private User resolveStaffUser(String identifier) {
        String value = identifier == null ? null : identifier.trim();
        return userRepository.findByEmail(value)
            .or(() -> userRepository.findByUsername(value))
            .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByEmail", value));
    }

    private void validateBaseStaffRequirements(StaffRequestDTO dto, Locale locale) {
        if (dto.getUserEmail() == null || dto.getUserEmail().isBlank()) {
            throw new BusinessRuleException(
                messageSource.getMessage("staff.user.required", null, locale)
            );
        }
        if (dto.getHospitalName() == null || dto.getHospitalName().isBlank()) {
            throw new BusinessRuleException(
                messageSource.getMessage("staff.hospital.required", null, locale)
            );
        }
        if (dto.getJobTitle() == null || dto.getJobTitle().isBlank()) {
            throw new BusinessRuleException(
                messageSource.getMessage("staff.jobTitle.required", null, locale)
            );
        }
    }


    private void handleHeadOfDepartmentUpdates(StaffRequestDTO dto, Staff existingStaff, Department department, Locale locale) {
        if (dto.getHeadOfDepartment() != null) {
            if (Boolean.TRUE.equals(dto.getHeadOfDepartment())) {
                if (department == null) {
                    throw new BusinessRuleException(
                        messageSource.getMessage("staff.head.requiresDepartment", null, locale)
                    );
                }
                departmentRepository.updateHeadOfDepartment(department.getId(), existingStaff.getId());
            } else {
                departmentRepository.clearHeadOfDepartment(existingStaff.getId());
            }
        }
    }

    private void validateDepartmentHospitalConsistency(Department department, Hospital hospital, Locale locale) {
        if (department != null && !department.getHospital().getId().equals(hospital.getId())) {
            throw new BusinessRuleException(
                messageSource.getMessage("staff.department.wrongHospital", null, locale)
            );
        }
    }

    private Staff findStaffOrThrow(UUID id, Locale locale) {
        Staff staff = staffRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND_KEY, id));
        assertStaffAccessible(staff, locale);
        return staff;
    }

    private boolean hasHospitalAccess(UUID hospitalId) {
        if (hospitalId == null) {
            return true;
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) {
            return true;
        }
        return HospitalScopeUtils.resolveScope(context).contains(hospitalId);
    }

    private void requireHospitalScope(UUID hospitalId, Locale locale) {
        if (!hasHospitalAccess(hospitalId)) {
            throw accessDenied(locale);
        }
    }

    private void requireHospitalScope(UUID hospitalId) {
        requireHospitalScope(hospitalId, LocaleContextHolder.getLocale());
    }

    private boolean isStaffVisible(Staff staff) {
        UUID hospitalId = staff != null && staff.getHospital() != null ? staff.getHospital().getId() : null;
        return hasHospitalAccess(hospitalId);
    }

    private void assertStaffAccessible(Staff staff, Locale locale) {
        if (!isStaffVisible(staff)) {
            throw accessDenied(locale);
        }
    }


    private static final Set<String> CLINICAL_JOB_TITLES = Set.of(
        "DOCTOR", "PHYSICIAN", "SURGEON", "ANESTHESIOLOGIST", "RADIOLOGIST",
        "PHYSIOTHERAPIST", "PSYCHOLOGIST",
        "NURSE", "NURSE_PRACTITIONER", "MIDWIFE",
        "PHARMACIST",
        "LAB_TECHNICIAN", "LABORATORY_SCIENTIST"
    );

    private void validateLicenseNumber(StaffRequestDTO dto, Locale locale) {
        String jobTitle = dto.getJobTitle();
        // Only clinical roles require a license number
        if (jobTitle == null || !CLINICAL_JOB_TITLES.contains(jobTitle.toUpperCase())) {
            return;
        }
        String licenseNumber = dto.getLicenseNumber();
        if (licenseNumber == null || licenseNumber.isBlank()) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.license.required", null, locale)
            );
        }
        if (staffRepository.existsByLicenseNumber(licenseNumber)) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.license.duplicate", new Object[]{licenseNumber}, locale)
            );
        }
    }

    private void validateRoleSpecificRequirements(StaffRequestDTO dto, String roleName, Locale locale) {
        if (roleName == null || roleName.isBlank()) {
            return; // no role-specific validation needed when no role is assigned
        }
        switch (roleName.toUpperCase()) {
            case "ROLE_DOCTOR":
                validateDoctorFields(dto, locale);
                break;
            case "ROLE_NURSE":
                validateNurseFields(dto, locale);
                break;
            case "ROLE_RADIOLOGIST":
                validateRadiologistFields(dto, locale);
                break;
            default:
                break;
        }
    }

    private void validateDoctorFields(StaffRequestDTO dto, Locale locale) {
        if (dto.getSpecialization() == null) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.doctor.specializationRequired", null, locale)
            );
        }
    }

    private void validateNurseFields(StaffRequestDTO dto, Locale locale) {
        if (dto.getEmploymentType() == null) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.nurse.employmentTypeRequired", null, locale)
            );
        }
        if (dto.getStartDate() == null) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.nurse.startDateRequired", null, locale)
            );
        }
        if (dto.getEndDate() != null && dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.nurse.endDateAfterStart", null, locale)
            );
        }
    }

    private void validateRadiologistFields(StaffRequestDTO dto, Locale locale) {
        if (dto.getLicenseNumber() == null || dto.getLicenseNumber().isBlank()) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.radiologist.licenseRequired", null, locale)
            );
        }
        if (dto.getStartDate() == null) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.radiologist.startDateRequired", null, locale)
            );
        }
        if (dto.getEndDate() != null && dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.radiologist.endDateAfterStart", null, locale)
            );
        }

        if (dto.getSpecialization() == null) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.radiologist.specializationRequired", null, locale)
            );
        }
    }

    private String getLocalizedMessage(String code, Object[] args, Locale locale) {
        return messageSource.getMessage(code, args, locale);
    }

    // ── MVP 3: Lab staff management ─────────────────────────────

    private static final Set<String> LAB_ROLE_CODES = Set.of(
        "ROLE_LAB_TECHNICIAN", "ROLE_LAB_SCIENTIST", "ROLE_LAB_MANAGER", ROLE_LAB_DIRECTOR
    );

    private static final Set<String> ASSIGNABLE_LAB_ROLES = Set.of(
        "ROLE_LAB_TECHNICIAN", "ROLE_LAB_SCIENTIST", "ROLE_LAB_MANAGER"
    );

    @Override
    @Transactional(readOnly = true)
    public Page<StaffResponseDTO> getLabStaffByHospitalId(UUID hospitalId, Pageable pageable) {
        requireHospitalScope(hospitalId);
        return staffRepository.findLabStaffByHospitalId(hospitalId, LAB_ROLE_CODES, pageable)
            .map(staffMapper::toStaffDTO);
    }

    /** The roles that may change a lab role, held at the staff member's own hospital. */
    private static final Set<String> LAB_ROLE_ADMINISTERING_CODES = Set.of(
        "ROLE_HOSPITAL_ADMIN", "HOSPITAL_ADMIN", ROLE_LAB_DIRECTOR, "LAB_DIRECTOR"
    );

    /**
     * QA D846: a lab director at hospital A who is a doctor or technician at
     * hospital B passes {@link #requireHospitalScope} for B (any assignment
     * there puts B in scope) and holds ROLE_LAB_DIRECTOR (from A). Only a
     * verified super-admin, or an ACTIVE hospital-admin / lab-director
     * assignment at the staff member's hospital, may change a lab role there.
     */
    private AccessDeniedException accessDenied(Locale locale) {
        String message = messageSource.getMessage("access.denied", null, "Access denied", locale);
        return new AccessDeniedException(message != null ? message : "Access denied");
    }

    private void requireLabRoleAuthorityAt(UUID hospitalId, Locale locale) {
        if (actingScopeResolver.isVerifiedSuperAdmin()) {
            return;
        }
        UUID callerId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        if (callerId == null || hospitalId == null
            || !assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                callerId, hospitalId, LAB_ROLE_ADMINISTERING_CODES)) {
            throw accessDenied(locale);
        }
    }

    @Override
    @Transactional
    public StaffResponseDTO updateStaffLabRole(UUID staffId, String newRoleCode, Locale locale) {
        if (!ASSIGNABLE_LAB_ROLES.contains(newRoleCode)) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.lab.role.invalid",
                    new Object[]{String.join(", ", ASSIGNABLE_LAB_ROLES)}, locale));
        }

        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(STAFF_NOT_FOUND_KEY, staffId));

        // Verify the staff belongs to the caller's hospital scope, and that the
        // caller holds an administering role AT THAT hospital: the scope covers
        // every hospital with any assignment of the caller's, while the
        // ROLE_LAB_DIRECTOR / ROLE_HOSPITAL_ADMIN authority is global (QA D846).
        requireHospitalScope(staff.getHospital().getId());
        requireLabRoleAuthorityAt(staff.getHospital().getId(), locale);

        // Verify current role is a lab role (cannot change non-lab staff)
        UserRoleHospitalAssignment assignment = staff.getAssignment();
        String currentCode = assignment.getRole().getCode();
        if (!LAB_ROLE_CODES.contains(currentCode)) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.lab.role.nonLabStaff",
                    new Object[]{currentCode}, locale));
        }

        // Don't allow changing LAB_DIRECTOR role via this endpoint
        if (ROLE_LAB_DIRECTOR.equals(currentCode)) {
            throw new BusinessRuleException(
                getLocalizedMessage("staff.lab.role.directorProtected", null, locale));
        }

        if (newRoleCode.equals(currentCode)) {
            return staffMapper.toStaffDTO(staff);  // no-op
        }

        // Look up the new role entity
        Role newRole = roleRepository.findByCode(newRoleCode)
            .orElseThrow(() -> new ResourceNotFoundException("staff.lab.role.notFound", newRoleCode));

        // QA D789: no new role at an archived hospital.
        com.example.hms.security.provider.FacilityAssignmentGuard.requireOpenForAssignment(assignment.getHospital());
        // Update the assignment's role
        assignment.setRole(newRole);
        assignmentRepository.save(assignment);

        return staffMapper.toStaffDTO(staff);
    }

}
