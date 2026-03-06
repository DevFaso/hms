package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DepartmentMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import com.example.hms.utility.RoleValidator;
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
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Locale;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplTest {

    @Mock private DepartmentRepository departmentRepository;
    @Mock private DepartmentMapper departmentMapper;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffService staffService;
    @Mock private MessageSource messageSource;
    @Mock private AuthService authService;
    @Mock private UserRoleHospitalAssignmentRepository roleAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleValidator roleValidator;

    @InjectMocks
    private DepartmentServiceImpl departmentService;

    private UUID deptId;
    private UUID hospitalId;
    private UUID staffId;
    private Department department;
    private Hospital hospital;
    private Staff staff;
    private User user;
    private Locale locale;

    @BeforeEach
    void setUp() {
        deptId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        locale = Locale.ENGLISH;

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");
        hospital.setCode("TH");

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("doctor@test.com");
        user.setFirstName("John");
        user.setLastName("Doe");

        staff = new Staff();
        staff.setId(staffId);
        staff.setUser(user);
        staff.setHospital(hospital);

        department = new Department();
        department.setId(deptId);
        department.setName("Cardiology");
        department.setDescription("Heart department");
        department.setEmail("cardio@test.com");
        department.setPhoneNumber("555-1234");
        department.setHospital(hospital);
        department.setHeadOfDepartment(staff);
        department.setStaffMembers(new HashSet<>());
        department.setDepartmentTranslations(new ArrayList<>());

        // Default: all existing tests run as SUPER_ADMIN (permissive path).
        // Tenant-isolation-specific tests override this per test.
        lenient().when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
    }

    @Test
    void getAllDepartments_returnsDepartments() {
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setId(deptId.toString());
        dto.setName("Cardiology");

        when(departmentRepository.findAllWithHospitalAndHead()).thenReturn(List.of(department));
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        List<DepartmentResponseDTO> result = departmentService.getAllDepartments(null, null, null, null, locale);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Cardiology");
    }

    @Test
    void getAllDepartments_withPageable_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setName("Cardiology");

        when(departmentRepository.findAll(pageable)).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.getAllDepartments(pageable, locale);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getDepartmentById_returnsDepartment() {
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setId(deptId.toString());
        dto.setName("Cardiology");

        when(departmentRepository.findByIdWithHeadOfDepartment(deptId)).thenReturn(Optional.of(department));
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        DepartmentResponseDTO result = departmentService.getDepartmentById(deptId, locale);

        assertThat(result.getName()).isEqualTo("Cardiology");
    }

    @Test
    void getDepartmentById_throwsWhenNotFound() {
        when(departmentRepository.findByIdWithHeadOfDepartment(deptId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.getDepartmentById(deptId, locale))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateDepartmentHead_updatesHead() {
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        dto.setId(deptId.toString());

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(staffService.getStaffEntityById(staffId, locale)).thenReturn(staff);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        DepartmentResponseDTO result = departmentService.updateDepartmentHead(deptId, staffId, locale);

        assertThat(result).isNotNull();
        verify(staffService).updateStaffDepartment("doctor@test.com", "Cardiology", "Test Hospital", locale);
    }

    @Test
    void updateDepartmentHead_throwsWhenStaffFromDifferentHospital() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        staff.setHospital(otherHospital);

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(staffService.getStaffEntityById(staffId, locale)).thenReturn(staff);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Wrong hospital");

        assertThatThrownBy(() -> departmentService.updateDepartmentHead(deptId, staffId, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void getActiveDepartmentsMinimal_returnsMinimalList() {
        when(departmentRepository.findByHospitalId(hospitalId)).thenReturn(List.of(department));

        List<DepartmentMinimalDTO> result = departmentService.getActiveDepartmentsMinimal(hospitalId, locale);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Cardiology");
    }

    @Test
    void isHeadOfDepartment_returnsTrue() {
        when(departmentRepository.existsByHeadOfDepartment_Id(staffId)).thenReturn(true);
        assertThat(departmentService.isHeadOfDepartment(staffId, locale)).isTrue();
    }

    @Test
    void isHeadOfDepartment_returnsFalse() {
        when(departmentRepository.existsByHeadOfDepartment_Id(staffId)).thenReturn(false);
        assertThat(departmentService.isHeadOfDepartment(staffId, locale)).isFalse();
    }

    @Test
    void getDepartmentsByHospital_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findByHospitalId(hospitalId, pageable)).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.getDepartmentsByHospital(hospitalId, pageable, locale);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void searchDepartments_withNullQuery_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findAll(pageable)).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.searchDepartments(null, pageable, locale);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void searchDepartments_withBlankQuery_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findAll(pageable)).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.searchDepartments("   ", pageable, locale);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void searchDepartments_withQuery_usesSpecification() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.searchDepartments("card", pageable, locale);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void deleteDepartment_deletesSuccessfully() {
        when(departmentRepository.hasStaffMembers(deptId)).thenReturn(false);
        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));

        departmentService.deleteDepartment(deptId, locale);

        verify(departmentRepository).delete(department);
    }

    @Test
    void deleteDepartment_throwsWhenHasStaff() {
        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(departmentRepository.hasStaffMembers(deptId)).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Has staff");

        assertThatThrownBy(() -> departmentService.deleteDepartment(deptId, locale))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void getDepartmentWithStaff_returnsFullDto() {
        department.setStaffMembers(Set.of(staff));
        StaffMinimalDTO staffMinimal = new StaffMinimalDTO(staffId, "John Doe", null);
        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(staffService.toMinimalDTO(staff)).thenReturn(staffMinimal);

        DepartmentWithStaffDTO result = departmentService.getDepartmentWithStaff(deptId, locale);

        assertThat(result.getDepartmentName()).isEqualTo("Cardiology");
        assertThat(result.getStaffMembers()).hasSize(1);
    }

    @Test
    void getDepartmentWithStaff_withNullStaffMembers_returnsEmptyList() {
        department.setStaffMembers(null);
        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));

        DepartmentWithStaffDTO result = departmentService.getDepartmentWithStaff(deptId, locale);

        assertThat(result.getStaffMembers()).isEmpty();
    }

    @Test
    void getDepartmentWithStaff_withNoHead_returnsNullHead() {
        department.setHeadOfDepartment(null);
        department.setStaffMembers(new HashSet<>());
        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));

        DepartmentWithStaffDTO result = departmentService.getDepartmentWithStaff(deptId, locale);

        assertThat(result.getHeadOfDepartment()).isNull();
    }

    @Test
    void getDepartmentStatistics_returnsCounts() {
        Role doctorRole = new Role();
        doctorRole.setName("ROLE_DOCTOR");
        Role nurseRole = new Role();
        nurseRole.setName("ROLE_NURSE");

        UserRoleHospitalAssignment doctorAssignment = UserRoleHospitalAssignment.builder().role(doctorRole).build();
        UserRoleHospitalAssignment nurseAssignment = UserRoleHospitalAssignment.builder().role(nurseRole).build();

        Staff doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setAssignment(doctorAssignment);
        Staff nurse = new Staff();
        nurse.setId(UUID.randomUUID());
        nurse.setAssignment(nurseAssignment);

        Set<Staff> staffSet = new HashSet<>();
        staffSet.add(doctor);
        staffSet.add(nurse);
        department.setStaffMembers(staffSet);

        when(departmentRepository.findByIdWithTranslations(deptId)).thenReturn(Optional.of(department));

        DepartmentStatsDTO result = departmentService.getDepartmentStatistics(deptId, locale);

        assertThat(result.getTotalStaff()).isEqualTo(2);
        assertThat(result.getTotalDoctors()).isEqualTo(1);
        assertThat(result.getTotalNurses()).isEqualTo(1);
    }

    @Test
    void updateDepartment_updatesSuccessfully() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Updated Cardiology");
        dto.setHospitalId(hospitalId);
        dto.setCode("UC");

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("Updated Cardiology");

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        DepartmentResponseDTO result = departmentService.updateDepartment(deptId, dto, locale);

        assertThat(result.getName()).isEqualTo("Updated Cardiology");
    }

    @Test
    void filterDepartments_returnsFilteredPage() {
        DepartmentFilterDTO filter = DepartmentFilterDTO.builder()
                .hospitalId(hospitalId)
                .build();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.filterDepartments(filter, pageable, locale);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getAllDepartments_withOrgFilterCriteria_usesSpecification() {
        UUID orgId = UUID.randomUUID();
        when(departmentRepository.findAll(any(Specification.class))).thenReturn(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        List<DepartmentResponseDTO> result = departmentService.getAllDepartments(orgId, null, "New York", null, locale);

        assertThat(result).hasSize(1);
        verify(departmentRepository).findAll(any(Specification.class));
    }

    @Test
    void getAllDepartments_withUnassignedOnly_usesSpecification() {
        when(departmentRepository.findAll(any(Specification.class))).thenReturn(List.of());

        List<DepartmentResponseDTO> result = departmentService.getAllDepartments(null, true, null, null, locale);

        assertThat(result).isEmpty();
        verify(departmentRepository).findAll(any(Specification.class));
    }

    @Test
    void getAllDepartments_emptyList_returnsEmpty() {
        when(departmentRepository.findAllWithHospitalAndHead()).thenReturn(List.of());

        List<DepartmentResponseDTO> result = departmentService.getAllDepartments(null, null, null, null, locale);

        assertThat(result).isEmpty();
    }

    // ========== createDepartment tests ==========

    @Test
    void createDepartment_success_minimal() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Neurology");
        dto.setCode("neuro");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("Neurology");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Neurology", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        DepartmentResponseDTO result = departmentService.createDepartment(dto, locale);

        assertThat(result.getName()).isEqualTo("Neurology");
        verify(departmentRepository).save(department);
    }

    @Test
    void createDepartment_nullDto_throwsIllegalArgument() {
        assertThatThrownBy(() -> departmentService.createDepartment(null, locale))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDepartment_noHospitalIdOrName_throwsIllegalArgument() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Test");
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Hospital required");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDepartment_noName_throwsIllegalArgument() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Name required");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDepartment_duplicateName_throwsConflict() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Cardiology");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Cardiology", hospitalId)).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Duplicate");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void createDepartment_hospitalNotFoundById_throws() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Test");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDepartment_resolveHospitalByName() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalName("Test Hospital");
        dto.setName("ICU");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("ICU");

        when(hospitalRepository.findByNameIgnoreCase("Test Hospital")).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("ICU", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        DepartmentResponseDTO result = departmentService.createDepartment(dto, locale);
        assertThat(result.getName()).isEqualTo("ICU");
    }

    @Test
    void createDepartment_hospitalByNameNotFound_throws() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalName("NonexistentHospital");
        dto.setName("Test");

        when(hospitalRepository.findByNameIgnoreCase("NonexistentHospital")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDepartment_withHeadOfDepartment() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Surgery");
        dto.setHeadOfDepartmentEmail("doctor@test.com");

        UUID userId = UUID.randomUUID();
        StaffResponseDTO staffDto = new StaffResponseDTO();
        staffDto.setId(staffId.toString());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("Surgery");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Surgery", hospitalId)).thenReturn(false);
        when(staffService.getStaffByUserEmail("doctor@test.com", locale)).thenReturn(List.of(staffDto));
        when(staffService.getStaffEntityById(staffId, locale)).thenReturn(staff);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), eq(staff), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(staffService).getStaffEntityById(staffId, locale);
    }

    @Test
    void createDepartment_headEmailBlank_skipsHead() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Radiology");
        dto.setHeadOfDepartmentEmail("  ");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Radiology", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(staffService, never()).getStaffByUserEmail(anyString(), any());
    }

    @Test
    void createDepartment_superAdmin_noAssignment_autoProvisions() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Lab");

        UUID superAdminId = UUID.randomUUID();
        Role hospitalAdminRole = new Role();
        hospitalAdminRole.setId(UUID.randomUUID());
        hospitalAdminRole.setName("ROLE_HOSPITAL_ADMIN");

        UserRoleHospitalAssignment provisioned = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(hospitalAdminRole).active(true)
            .startDate(LocalDate.now()).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Lab", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(superAdminId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(superAdminId, hospitalId)).thenReturn(Optional.empty());
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        when(roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")).thenReturn(Optional.of(hospitalAdminRole));
        when(roleAssignmentRepository.findFirstByHospitalIdAndRole_Name(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(Optional.empty());
        when(userRepository.findById(superAdminId)).thenReturn(Optional.of(user));
        when(userRoleRepository.existsByUserIdAndRoleId(user.getId(), hospitalAdminRole.getId())).thenReturn(false);
        when(userRoleRepository.save(any(UserRole.class))).thenReturn(null);
        when(roleAssignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenReturn(provisioned);
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(provisioned))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(userRoleRepository).save(any(UserRole.class));
        verify(roleAssignmentRepository).save(any(UserRoleHospitalAssignment.class));
    }

    @Test
    void createDepartment_nonSuperAdmin_noAssignment_throws() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Lab");

        UUID userId = UUID.randomUUID();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Lab", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.empty());
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDepartment_nullLocale_usesDefault() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Oncology");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Oncology", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, null);

        verify(departmentRepository).save(department);
    }

    // ========== updateDepartment tests ==========

    @Test
    void updateDepartment_withNewHead_updatesStaffDepartment() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Updated");
        dto.setHospitalId(hospitalId);
        dto.setCode("UPD");
        dto.setHeadOfDepartmentEmail("doctor@test.com");

        StaffResponseDTO staffDto = new StaffResponseDTO();
        staffDto.setId(staffId.toString());

        // Current head is different from new head
        Staff currentHead = new Staff();
        currentHead.setId(UUID.randomUUID());
        department.setHeadOfDepartment(currentHead);

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffService.getStaffByUserEmail("doctor@test.com", locale)).thenReturn(List.of(staffDto));
        when(staffService.getStaffEntityById(staffId, locale)).thenReturn(staff);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.updateDepartment(deptId, dto, locale);

        verify(staffService).updateStaffDepartment(eq("doctor@test.com"), anyString(), anyString(), eq(locale));
    }

    @Test
    void updateDepartment_sameHead_noStaffDepartmentUpdate() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Updated");
        dto.setHospitalId(hospitalId);
        dto.setCode("UPD");
        dto.setHeadOfDepartmentEmail("doctor@test.com");

        StaffResponseDTO staffDto = new StaffResponseDTO();
        staffDto.setId(staffId.toString());

        // Same head
        department.setHeadOfDepartment(staff);

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffService.getStaffByUserEmail("doctor@test.com", locale)).thenReturn(List.of(staffDto));
        when(staffService.getStaffEntityById(staffId, locale)).thenReturn(staff);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.updateDepartment(deptId, dto, locale);

        verify(staffService, never()).updateStaffDepartment(anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateDepartment_noHead_noStaffLookup() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Updated");
        dto.setHospitalId(hospitalId);
        dto.setCode("UPD");
        // no headOfDepartmentEmail

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.updateDepartment(deptId, dto, locale);

        verify(staffService, never()).getStaffByUserEmail(anyString(), any());
    }

    @Test
    void updateDepartment_notFound_throws() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Test");
        when(departmentRepository.findById(deptId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.updateDepartment(deptId, dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ========== deleteDepartment tests ==========

    @Test
    void deleteDepartment_notFound_throwsEntityNotFound() {
        when(departmentRepository.findById(deptId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.deleteDepartment(deptId, locale))
            .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    // ========== getDepartmentStatistics edge case ==========

    @Test
    void getDepartmentStatistics_notFound_throws() {
        when(departmentRepository.findByIdWithTranslations(deptId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.getDepartmentStatistics(deptId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ========== updateDepartmentHead edge cases ==========

    @Test
    void updateDepartmentHead_departmentNotFound_throws() {
        when(departmentRepository.findById(deptId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.updateDepartmentHead(deptId, staffId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ========== getDepartmentWithStaff edge case ==========

    @Test
    void getDepartmentWithStaff_notFound_throws() {
        when(departmentRepository.findById(deptId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.getDepartmentWithStaff(deptId, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ========== filter with multiple criteria ==========

    @Test
    void filterDepartments_withMultipleFilters() {
        DepartmentFilterDTO filter = DepartmentFilterDTO.builder()
            .hospitalId(hospitalId)
            .name("Cardiology")
            .email("cardio@test.com")
            .active(true)
            .city("New York")
            .state("NY")
            .build();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Department> page = new PageImpl<>(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();

        when(departmentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        Page<DepartmentResponseDTO> result = departmentService.filterDepartments(filter, pageable, locale);
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getAllDepartments_withHospitalIdFilter() {
        when(departmentRepository.findAll(any(Specification.class))).thenReturn(List.of(department));
        DepartmentResponseDTO dto = new DepartmentResponseDTO();
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(dto);

        List<DepartmentResponseDTO> result = departmentService.getAllDepartments(null, null, null, "CA", locale);
        assertThat(result).hasSize(1);
    }

    @Test
    void createDepartment_superAdmin_existingHospitalAssignment_reuses() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Pharmacy");

        UUID superAdminId = UUID.randomUUID();
        Role hospitalAdminRole = new Role();
        hospitalAdminRole.setId(UUID.randomUUID());
        hospitalAdminRole.setName("ROLE_HOSPITAL_ADMIN");

        UserRoleHospitalAssignment existingAssignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(hospitalAdminRole).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Pharmacy", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(superAdminId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(superAdminId, hospitalId))
            .thenReturn(Optional.empty());
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        when(roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")).thenReturn(Optional.of(hospitalAdminRole));
        when(roleAssignmentRepository.findFirstByHospitalIdAndRole_Name(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(Optional.of(existingAssignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(existingAssignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void createDepartment_superAdmin_userNotFound_throws() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Lab");

        UUID superAdminId = UUID.randomUUID();
        Role hospitalAdminRole = new Role();
        hospitalAdminRole.setId(UUID.randomUUID());
        hospitalAdminRole.setName("ROLE_HOSPITAL_ADMIN");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Lab", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(superAdminId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(superAdminId, hospitalId)).thenReturn(Optional.empty());
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        when(roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")).thenReturn(Optional.of(hospitalAdminRole));
        when(roleAssignmentRepository.findFirstByHospitalIdAndRole_Name(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(Optional.empty());
        when(userRepository.findById(superAdminId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDepartment_superAdmin_userAlreadyHasRole_skipsRoleCreation() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Lab");

        UUID superAdminId = UUID.randomUUID();
        Role hospitalAdminRole = new Role();
        hospitalAdminRole.setId(UUID.randomUUID());
        hospitalAdminRole.setName("ROLE_HOSPITAL_ADMIN");

        UserRoleHospitalAssignment provisioned = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).role(hospitalAdminRole).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Lab", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(superAdminId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(superAdminId, hospitalId)).thenReturn(Optional.empty());
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        when(roleRepository.findByCode("ROLE_HOSPITAL_ADMIN")).thenReturn(Optional.of(hospitalAdminRole));
        when(roleAssignmentRepository.findFirstByHospitalIdAndRole_Name(hospitalId, "ROLE_HOSPITAL_ADMIN"))
            .thenReturn(Optional.empty());
        when(userRepository.findById(superAdminId)).thenReturn(Optional.of(user));
        when(userRoleRepository.existsByUserIdAndRoleId(user.getId(), hospitalAdminRole.getId())).thenReturn(true);
        when(roleAssignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenReturn(provisioned);
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(provisioned))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    @Test
    void createDepartment_headStaffNotFoundByEmail_skipsHead() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Test");
        dto.setHeadOfDepartmentEmail("unknown@test.com");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Test", hospitalId)).thenReturn(false);
        when(staffService.getStaffByUserEmail("unknown@test.com", locale)).thenReturn(List.of());
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(staffService, never()).getStaffEntityById(any(), any());
    }

    @Test
    void updateDepartment_headEmailEmpty_skipsLookup() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Updated");
        dto.setHospitalId(hospitalId);
        dto.setCode("UPD");
        dto.setHeadOfDepartmentEmail("");

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.updateDepartment(deptId, dto, locale);

        verify(staffService, never()).getStaffByUserEmail(anyString(), any());
    }

    @Test
    void createDepartment_codeNormalization() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Pediatrics");
        dto.setCode("  peds  ");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Pediatrics", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        assertThat(dto.getCode()).isEqualTo("PEDS");
    }

    @Test
    void createDepartment_nullCode_remainsNull() {
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Dermatology");
        dto.setCode(null);

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Dermatology", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        assertThat(dto.getCode()).isNull();
    }

    // ========== Tenant Isolation Tests ==========

    @Test
    void createDepartment_hospitalAdmin_forcesActiveHospitalId() {
        // HOSPITAL_ADMIN → enforceHospitalScopeOnDto should override dto.hospitalId
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setName("Neurology");
        // dto.hospitalId intentionally NOT set — enforcement should fill it

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("Neurology");

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Neurology", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        DepartmentResponseDTO result = departmentService.createDepartment(dto, locale);

        assertThat(result.getName()).isEqualTo("Neurology");
        // The dto's hospitalId should have been forced to the active hospital
        assertThat(dto.getHospitalId()).isEqualTo(hospitalId);
    }

    @Test
    void createDepartment_hospitalAdmin_matchingHospitalId_succeeds() {
        // HOSPITAL_ADMIN sends DTO with their own hospital — should pass
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);  // matches active hospital
        dto.setName("Orthopedics");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("Orthopedics", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(departmentRepository).save(department);
    }

    @Test
    void createDepartment_hospitalAdmin_crossHospitalId_throwsBusinessRule() {
        // HOSPITAL_ADMIN sends DTO with a DIFFERENT hospital → must be rejected
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(otherHospitalId); // cross-hospital attempt!
        dto.setName("Evil Dept");

        assertThatThrownBy(() -> departmentService.createDepartment(dto, locale))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("assigned hospital");
    }

    @Test
    void createDepartment_superAdmin_anyHospitalId_succeeds() {
        // SUPER_ADMIN can target any hospital (default setUp already sets isSuperAdminFromAuth=true)
        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("SuperAdminDept");

        UUID userId = UUID.randomUUID();
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .user(user).hospital(hospital).active(true).build();
        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.existsByNameIgnoreCaseAndHospitalId("SuperAdminDept", hospitalId)).thenReturn(false);
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(roleAssignmentRepository.findByUserIdAndHospitalId(userId, hospitalId))
            .thenReturn(Optional.of(assignment));
        when(departmentMapper.toDepartment(any(), eq(hospital), isNull(), eq(assignment))).thenReturn(department);
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        departmentService.createDepartment(dto, locale);

        verify(departmentRepository).save(department);
        // roleValidator.requireActiveHospitalId should never be called for super-admin
        verify(roleValidator, never()).requireActiveHospitalId();
    }

    @Test
    void updateDepartment_hospitalAdmin_crossHospitalEntity_throwsNotFound() {
        // HOSPITAL_ADMIN scoped to hospitalId tries to update a department in a different hospital
        UUID otherHospitalId = UUID.randomUUID();
        Hospital otherHospital = new Hospital();
        otherHospital.setId(otherHospitalId);
        otherHospital.setName("Other Hospital");
        department.setHospital(otherHospital);

        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(otherHospitalId);
        dto.setName("Cross Update");

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.updateDepartment(deptId, dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateDepartment_hospitalAdmin_ownHospital_succeeds() {
        // HOSPITAL_ADMIN updates a department in their own hospital
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        DepartmentRequestDTO dto = new DepartmentRequestDTO();
        dto.setHospitalId(hospitalId);
        dto.setName("Updated Cardiology");
        dto.setCode("UC");

        DepartmentResponseDTO responseDto = new DepartmentResponseDTO();
        responseDto.setName("Updated Cardiology");

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(departmentRepository.save(department)).thenReturn(department);
        when(departmentMapper.toDepartmentResponseDTO(eq(department), any(Locale.class))).thenReturn(responseDto);

        DepartmentResponseDTO result = departmentService.updateDepartment(deptId, dto, locale);

        assertThat(result.getName()).isEqualTo("Updated Cardiology");
    }

    @Test
    void deleteDepartment_hospitalAdmin_crossHospital_throwsNotFound() {
        // HOSPITAL_ADMIN scoped to hospitalId tries to delete a department in another hospital
        UUID otherHospitalId = UUID.randomUUID();
        Hospital otherHospital = new Hospital();
        otherHospital.setId(otherHospitalId);
        otherHospital.setName("Other Hospital");
        department.setHospital(otherHospital);

        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("Not found");

        assertThatThrownBy(() -> departmentService.deleteDepartment(deptId, locale))
            .isInstanceOf(ResourceNotFoundException.class);

        // delete should never be called
        verify(departmentRepository, never()).delete(any(Department.class));
    }

    @Test
    void deleteDepartment_hospitalAdmin_ownHospital_succeeds() {
        // HOSPITAL_ADMIN deletes a department in their own hospital
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        when(departmentRepository.findById(deptId)).thenReturn(Optional.of(department));
        when(departmentRepository.hasStaffMembers(deptId)).thenReturn(false);

        departmentService.deleteDepartment(deptId, locale);

        verify(departmentRepository).delete(department);
    }
}
