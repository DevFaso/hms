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
        return staffRepository.findAnyLicenseByUserId(userId);
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
            .filter(staff -> staff.isActive() && isStaffVisible(staff))
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

    // Find staff by hospital ID (paginated)
    @Transactional(readOnly = true)
    public Page<StaffResponseDTO> getStaffByHospitalId(UUID hospitalId, Pageable pageable) {
        requireHospitalScope(hospitalId);
        return staffRepository.findByHospital_Id(hospitalId, pageable)
            .map(staffMapper::toStaffDTO);
    }

    // Find staff by hospital ID and active status (paginated)
    @Transactional(readOnly = true)
    public Page<StaffResponseDTO> getStaffByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable) {
        requireHospitalScope(hospitalId);
        return staffRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)
            .map(staffMapper::toStaffDTO);
    }

    // Find first staff by user ID ordered by creation date
    @Transactional(readOnly = true)
    public Optional<StaffResponseDTO> getFirstStaffByUserIdOrderByCreatedAtAsc(UUID userId, Locale locale) {
        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)
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


    @Override
    @Transactional(readOnly = true)
    public List<StaffResponseDTO> getAllStaff(Locale locale) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) {
            return staffRepository.findAll().stream()
                .map(staffMapper::toStaffDTO)
                .toList();
        }

        Set<UUID> hospitalScope = HospitalScopeUtils.resolveScope(context);
        if (hospitalScope.isEmpty()) {
            return List.of();
        }

        return staffRepository.findByHospital_IdIn(hospitalScope).stream()
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

        User user = userRepository.findByEmail(dto.getUserEmail())
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("user.notFound", new Object[]{dto.getUserEmail()}, locale)));

        Hospital hospital = hospitalRepository.findByName(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(HOSPITAL_NOT_FOUND_KEY, new Object[]{dto.getHospitalName()}, locale)));

        requireHospitalScope(hospital.getId(), locale);

        Department department = null;
        if (dto.getDepartmentName() != null && !dto.getDepartmentName().isBlank()) {
            department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), dto.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(DEPARTMENT_NOT_FOUND_KEY, new Object[]{dto.getDepartmentName()}, locale)));
        }

        validateDepartmentHospitalConsistency(department, hospital, locale);

        Role role = null;
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            role = roleRepository.findByCode(dto.getRoleName().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("role.notFound", new Object[]{dto.getRoleName()}, locale)));
        }

        UserRoleHospitalAssignment assignment = null;
        if (role != null) {
            assignment = assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("assignment.notFound", null, locale)));
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

        User user = userRepository.findByEmail(dto.getUserEmail())
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("user.notFound", new Object[]{dto.getUserEmail()}, locale)));

        Hospital hospital = hospitalRepository.findByName(dto.getHospitalName())
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(HOSPITAL_NOT_FOUND_KEY, new Object[]{dto.getHospitalName()}, locale)));

        requireHospitalScope(hospital.getId(), locale);

        Department department = null;
        if (dto.getDepartmentName() != null && !dto.getDepartmentName().isBlank()) {
            department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), dto.getDepartmentName())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(DEPARTMENT_NOT_FOUND_KEY, new Object[]{dto.getDepartmentName()}, locale)));
        }

        validateDepartmentHospitalConsistency(department, hospital, locale);

        Role role = null;
        if (dto.getRoleName() != null && !dto.getRoleName().isBlank()) {
            role = roleRepository.findByCode(dto.getRoleName().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("role.notFound", new Object[]{dto.getRoleName()}, locale)));
        }

        UserRoleHospitalAssignment assignment = null;
        if (role != null) {
            assignment = assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId())
                .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("assignment.notFound", null, locale)));
        } else {
            assignment = existingStaff.getAssignment();
        }

        if (!existingStaff.getLicenseNumber().equals(dto.getLicenseNumber())) {
            validateLicenseNumber(dto, locale);
        }

        validateRoleSpecificRequirements(dto, dto.getRoleName(), locale);

        staffMapper.updateStaffFromDto(dto, existingStaff, user, hospital, department, assignment);
        Staff updatedStaff = staffRepository.save(existingStaff);

        handleHeadOfDepartmentUpdates(dto, existingStaff, department, locale);
        return staffMapper.toStaffDTO(updatedStaff);
    }

    @Transactional
    @Override
    public void updateStaffDepartment(String staffEmail, String departmentName, String hospitalName, Locale locale) {
        Staff staff = userRepository.findByEmail(staffEmail)
            .flatMap(user -> staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId()))
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage("staff.notFound", new Object[]{staffEmail}, locale)));

        Hospital hospital = hospitalRepository.findByName(hospitalName)
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(HOSPITAL_NOT_FOUND_KEY, new Object[]{hospitalName}, locale)));

        requireHospitalScope(hospital.getId(), locale);

        Department department = departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), departmentName)
            .orElseThrow(() -> new ResourceNotFoundException(getLocalizedMessage(DEPARTMENT_NOT_FOUND_KEY, new Object[]{departmentName}, locale)));

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

        if (!staff.isActive()) {
            return;
        }

        staff.setActive(false);
        staffRepository.save(staff);
    }

    @Override
    public StaffMinimalDTO toMinimalDTO(Staff staff) {
        if (staff == null) {
            return null;
        }
        return new StaffMinimalDTO(staff.getId(), staff.getFullName(), staff.getJobTitle());
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
            .orElseThrow(() -> new ResourceNotFoundException(
                getLocalizedMessage("staff.notFound", new Object[]{id}, locale)
            ));
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
            throw new AccessDeniedException(messageSource.getMessage("access.denied", null, "Access denied", locale));
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
            throw new AccessDeniedException(messageSource.getMessage("access.denied", null, "Access denied", locale));
        }
    }


    private void validateLicenseNumber(StaffRequestDTO dto, Locale locale) {
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
        switch (roleName) {
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

}
