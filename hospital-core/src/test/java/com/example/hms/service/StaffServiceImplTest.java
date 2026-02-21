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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class StaffServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffMapper staffMapper;
    @Mock private MessageSource messageSource;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private RoleRepository roleRepository;

    @InjectMocks
    private StaffServiceImpl staffService;

    private UUID staffId;
    private UUID hospitalId;
    private Staff staff;
    private User user;
    private Hospital hospital;
    private Locale locale;
    private StaffResponseDTO staffDto;

    @BeforeEach
    void setUp() {
        staffId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        locale = Locale.ENGLISH;

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("doctor@test.com");
        user.setFirstName("John");
        user.setLastName("Doe");

        staff = new Staff();
        staff.setId(staffId);
        staff.setUser(user);
        staff.setHospital(hospital);
        staff.setActive(true);
        staff.setLicenseNumber("LIC-001");

        staffDto = new StaffResponseDTO();
        staffDto.setId(staffId.toString());

        // Set up super admin context so hospital scoping does not block
        HospitalContext ctx = HospitalContext.builder().superAdmin(true).build();
        HospitalContextHolder.setContext(ctx);
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void getStaffById_returnsStaff() {
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        StaffResponseDTO result = staffService.getStaffById(staffId, locale);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(staffId.toString());
    }

    @Test
    void getStaffById_throwsWhenNotFound() {
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.getStaffById(staffId, locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllStaff_superAdmin_returnsAll() {
        when(staffRepository.findAll()).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getAllStaff(locale);

        assertThat(result).hasSize(1);
    }

    @Test
    void getStaffByUserEmail_returnsMatchingStaff() {
        when(staffRepository.findByUserEmail("doctor@test.com")).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getStaffByUserEmail("doctor@test.com", locale);

        assertThat(result).hasSize(1);
    }

    @Test
    void getStaffByUserPhoneNumber_returnsMatchingStaff() {
        when(staffRepository.findByUserPhoneNumber("555-1234")).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getStaffByUserPhoneNumber("555-1234", locale);

        assertThat(result).hasSize(1);
    }

    @Test
    void getAnyLicenseByUserId_returnsLicense() {
        when(staffRepository.findAnyLicenseByUserId(user.getId())).thenReturn(Optional.of("LIC-001"));

        Optional<String> result = staffService.getAnyLicenseByUserId(user.getId());

        assertThat(result).isPresent().contains("LIC-001");
    }

    @Test
    void getStaffByIdAndActiveTrue_returnsActiveStaff() {
        when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        Optional<StaffResponseDTO> result = staffService.getStaffByIdAndActiveTrue(staffId, locale);

        assertThat(result).isPresent();
    }

    @Test
    void getActiveStaffByUserId_returnsActiveOnly() {
        when(staffRepository.findByUserId(user.getId())).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getActiveStaffByUserId(user.getId(), locale);

        assertThat(result).hasSize(1);
    }

    @Test
    void existsByIdAndHospitalIdAndActiveTrue_returnsTrue() {
        when(staffRepository.existsByIdAndHospital_IdAndActiveTrue(staffId, hospitalId)).thenReturn(true);

        assertThat(staffService.existsByIdAndHospitalIdAndActiveTrue(staffId, hospitalId)).isTrue();
    }

    @Test
    void existsByLicenseNumberAndUserId_returnsTrue() {
        when(staffRepository.existsByLicenseNumberAndUserId("LIC-001", user.getId())).thenReturn(true);

        assertThat(staffService.existsByLicenseNumberAndUserId("LIC-001", user.getId())).isTrue();
    }

    @Test
    void getStaffByHospitalId_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Staff> page = new PageImpl<>(List.of(staff));
        when(staffRepository.findByHospital_Id(hospitalId, pageable)).thenReturn(page);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        Page<StaffResponseDTO> result = staffService.getStaffByHospitalId(hospitalId, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getStaffByHospitalIdAndActiveTrue_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Staff> page = new PageImpl<>(List.of(staff));
        when(staffRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)).thenReturn(page);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        Page<StaffResponseDTO> result = staffService.getStaffByHospitalIdAndActiveTrue(hospitalId, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getFirstStaffByUserIdOrderByCreatedAtAsc_returnsFirst() {
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(Optional.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        Optional<StaffResponseDTO> result = staffService.getFirstStaffByUserIdOrderByCreatedAtAsc(user.getId(), locale);

        assertThat(result).isPresent();
    }

    @Test
    void deleteStaff_deactivatesStaff() {
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.existsByHeadOfDepartmentId(staffId)).thenReturn(false);

        staffService.deleteStaff(staffId, locale);

        assertThat(staff.isActive()).isFalse();
        verify(staffRepository).save(staff);
    }

    @Test
    void deleteStaff_throwsWhenIsHead() {
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.existsByHeadOfDepartmentId(staffId)).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Is head");

        assertThatThrownBy(() -> staffService.deleteStaff(staffId, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void deleteStaff_skipsIfAlreadyInactive() {
        staff.setActive(false);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.existsByHeadOfDepartmentId(staffId)).thenReturn(false);

        staffService.deleteStaff(staffId, locale);

        verify(staffRepository, never()).save(any());
    }

    @Test
    void toMinimalDTO_returnsDto() {
        staff.setJobTitle(null);
        StaffMinimalDTO result = staffService.toMinimalDTO(staff);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(staffId);
    }

    @Test
    void toMinimalDTO_returnsNullForNullInput() {
        StaffMinimalDTO result = staffService.toMinimalDTO(null);

        assertThat(result).isNull();
    }

    @Test
    void createStaff_throwsWhenEmailBlank() {
        StaffRequestDTO dto = new StaffRequestDTO();
        dto.setUserEmail("");
        dto.setHospitalName("H");
        dto.setJobTitle("Doctor");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_throwsWhenHospitalNameBlank() {
        StaffRequestDTO dto = new StaffRequestDTO();
        dto.setUserEmail("user@test.com");
        dto.setHospitalName("");
        dto.setJobTitle("Doctor");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_throwsWhenJobTitleBlank() {
        StaffRequestDTO dto = new StaffRequestDTO();
        dto.setUserEmail("user@test.com");
        dto.setHospitalName("Hospital");
        dto.setJobTitle("");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void getStaffEntityById_returnsEntity() {
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        Staff result = staffService.getStaffEntityById(staffId, locale);

        assertThat(result).isEqualTo(staff);
    }

    @Test
    void updateStaffDepartment_updatesSuccessfully() {
        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setHospital(hospital);

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospitalId, "Cardiology")).thenReturn(Optional.of(dept));

        staffService.updateStaffDepartment("doctor@test.com", "Cardiology", "Test Hospital", locale);

        assertThat(staff.getDepartment()).isEqualTo(dept);
        verify(staffRepository).save(staff);
    }

    @Test
    void updateStaffDepartment_staffNotFound_throws() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.updateStaffDepartment("unknown@test.com", "Cardiology", "Test Hospital", locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStaffDepartment_hospitalNotFound_throws() {
        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findByName("Unknown Hospital")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.updateStaffDepartment("doctor@test.com", "Cardiology", "Unknown Hospital", locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStaffDepartment_departmentNotFound_throws() {
        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospitalId, "Unknown")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.updateStaffDepartment("doctor@test.com", "Unknown", "Test Hospital", locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStaffDepartment_wrongHospital_throws() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        staff.setHospital(otherHospital);

        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setHospital(hospital);

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospitalId, "Cardiology")).thenReturn(Optional.of(dept));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Wrong hospital");

        assertThatThrownBy(() -> staffService.updateStaffDepartment("doctor@test.com", "Cardiology", "Test Hospital", locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void getAllStaff_nonSuperAdmin_withHospitalScope_returnsFiltered() {
        HospitalContextHolder.clear();
        HospitalContext ctx = HospitalContext.builder()
            .superAdmin(false)
            .permittedHospitalIds(Set.of(hospitalId))
            .build();
        HospitalContextHolder.setContext(ctx);

        when(staffRepository.findByHospital_IdIn(any())).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getAllStaff(locale);

        assertThat(result).hasSize(1);
        verify(staffRepository).findByHospital_IdIn(any());
    }

    @Test
    void getAllStaff_nonSuperAdmin_noHospitalScope_returnsEmpty() {
        HospitalContextHolder.clear();
        HospitalContext ctx = HospitalContext.builder()
            .superAdmin(false)
            .permittedHospitalIds(Set.of())
            .build();
        HospitalContextHolder.setContext(ctx);

        List<StaffResponseDTO> result = staffService.getAllStaff(locale);

        assertThat(result).isEmpty();
    }

    @Test
    void createStaff_success_minimalWithRole() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .licenseNumber("NEW-LIC-001")
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("NEW-LIC-001")).thenReturn(false);
        when(staffMapper.toStaff(eq(dto), eq(user), eq(hospital), any(), eq(assignment))).thenReturn(staff);
        when(staffRepository.save(staff)).thenReturn(staff);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        StaffResponseDTO result = staffService.createStaff(dto, locale);

        assertThat(result).isNotNull();
        verify(staffRepository).save(staff);
    }

    @Test
    void createStaff_withDepartment() {
        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setHospital(hospital);

        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .departmentName("Cardiology")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .build();

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), "Cardiology")).thenReturn(Optional.of(dept));

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(staffMapper.toStaff(dto, user, hospital, dept, assignment)).thenReturn(staff);
        when(staffRepository.save(staff)).thenReturn(staff);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        StaffResponseDTO result = staffService.createStaff(dto, locale);
        assertThat(result).isNotNull();
    }

    @Test
    void createStaff_userNotFound_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("unknown@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .build();

        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStaff_hospitalNotFound_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Unknown")
            .jobTitle("Doctor")
            .build();

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Unknown")).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStaff_duplicateLicense_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .licenseNumber("LIC-EXISTING")
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-EXISTING")).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Duplicate");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_nullLicense_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .licenseNumber(null)
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_departmentNotInHospital_throws() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setHospital(otherHospital);

        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .departmentName("Cardiology")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .build();

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), "Cardiology")).thenReturn(Optional.of(dept));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Wrong hospital");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_nurseRole_validatesEmploymentType() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Nurse")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_NURSE")
            .employmentType(null)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_NURSE");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_NURSE")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_doctorRole_validatesSpecialization() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_DOCTOR")
            .specialization(null)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_radiologistRole_validatesFields() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Radiologist")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_RADIOLOGIST")
            .startDate(null)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_RADIOLOGIST");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_RADIOLOGIST")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_nurseRole_endDateBeforeStart_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Nurse")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_NURSE")
            .employmentType(com.example.hms.enums.EmploymentType.FULL_TIME)
            .startDate(java.time.LocalDate.now())
            .endDate(java.time.LocalDate.now().minusDays(1))
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_NURSE");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_NURSE")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Invalid");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void createStaff_withHeadOfDepartment_updatesDepartment() {
        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setHospital(hospital);

        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .departmentName("Cardiology")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_DOCTOR")
            .specialization(com.example.hms.enums.Specialization.CARDIOLOGY)
            .headOfDepartment(true)
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_DOCTOR");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.findByHospitalIdAndNameIgnoreCase(hospital.getId(), "Cardiology")).thenReturn(Optional.of(dept));
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(staffMapper.toStaff(dto, user, hospital, dept, assignment)).thenReturn(staff);
        when(staffRepository.save(staff)).thenReturn(staff);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        staffService.createStaff(dto, locale);

        verify(departmentRepository).updateHeadOfDepartment(dept.getId(), staff.getId());
    }

    @Test
    void createStaff_noRoleName_succeedsWithoutNPE() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Doctor")
            .licenseNumber("LIC-NEW")
            .roleName(null)
            .build();

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(staffMapper.toStaff(dto, user, hospital, null, null)).thenReturn(staff);
        when(staffRepository.save(staff)).thenReturn(staff);
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        StaffResponseDTO result = staffService.createStaff(dto, locale);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(staffId.toString());
        verify(staffRepository).save(staff);
    }

    @Test
    void getStaffById_notVisible_throws() {
        HospitalContextHolder.clear();
        HospitalContext ctx = HospitalContext.builder()
            .superAdmin(false)
            .permittedHospitalIds(Set.of(UUID.randomUUID())) // different hospital
            .build();
        HospitalContextHolder.setContext(ctx);

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("Access denied");

        assertThatThrownBy(() -> staffService.getStaffById(staffId, locale))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void getStaffByUserEmail_nonSuperAdmin_filtersHospital() {
        HospitalContextHolder.clear();
        HospitalContext ctx = HospitalContext.builder()
            .superAdmin(false)
            .permittedHospitalIds(Set.of(hospitalId))
            .build();
        HospitalContextHolder.setContext(ctx);

        when(staffRepository.findByUserEmail("doctor@test.com")).thenReturn(List.of(staff));
        when(staffMapper.toStaffDTO(staff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getStaffByUserEmail("doctor@test.com", locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void existsByIdAndHospitalIdAndActiveTrue_nonSuperAdmin_noAccess_throws() {
        HospitalContextHolder.clear();
        UUID otherHospitalId = UUID.randomUUID();
        HospitalContext ctx = HospitalContext.builder()
            .superAdmin(false)
            .permittedHospitalIds(Set.of(UUID.randomUUID()))
            .build();
        HospitalContextHolder.setContext(ctx);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class))).thenReturn("Access denied");

        assertThatThrownBy(() -> staffService.existsByIdAndHospitalIdAndActiveTrue(staffId, otherHospitalId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void isStaffVisible_nullStaff_returnsTrue() {
        // toMinimalDTO(null) returns null, but isStaffVisible is also called
        // Staff with null hospital → hospitalId is null → hasHospitalAccess returns true
        Staff nullHospitalStaff = new Staff();
        nullHospitalStaff.setId(UUID.randomUUID());

        when(staffRepository.findByUserEmail("x@test.com")).thenReturn(List.of(nullHospitalStaff));
        when(staffMapper.toStaffDTO(nullHospitalStaff)).thenReturn(staffDto);

        List<StaffResponseDTO> result = staffService.getStaffByUserEmail("x@test.com", locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void radiologist_endDateBeforeStart_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Radiologist")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_RADIOLOGIST")
            .specialization(com.example.hms.enums.Specialization.RADIOLOGY)
            .startDate(java.time.LocalDate.now())
            .endDate(java.time.LocalDate.now().minusDays(1))
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_RADIOLOGIST");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_RADIOLOGIST")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Invalid");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void radiologist_noSpecialization_throws() {
        StaffRequestDTO dto = StaffRequestDTO.builder()
            .userEmail("doctor@test.com")
            .hospitalName("Test Hospital")
            .jobTitle("Radiologist")
            .licenseNumber("LIC-NEW")
            .roleName("ROLE_RADIOLOGIST")
            .specialization(null)
            .startDate(java.time.LocalDate.now())
            .build();

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode("ROLE_RADIOLOGIST");
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(role).build();
        assignment.setId(UUID.randomUUID());

        when(userRepository.findByEmail("doctor@test.com")).thenReturn(Optional.of(user));
        when(hospitalRepository.findByName("Test Hospital")).thenReturn(Optional.of(hospital));
        when(roleRepository.findByCode("ROLE_RADIOLOGIST")).thenReturn(Optional.of(role));
        when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
            .thenReturn(Optional.of(assignment));
        when(staffRepository.existsByLicenseNumber("LIC-NEW")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Required");

        assertThatThrownBy(() -> staffService.createStaff(dto, locale))
                .isInstanceOf(BusinessRuleException.class);
    }
}
