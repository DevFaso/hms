package com.example.hms.service;

import com.example.hms.exception.*;
import com.example.hms.mapper.DepartmentMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        verify(staffService).updateStaffDepartment(eq("doctor@test.com"), eq("Cardiology"), eq("Test Hospital"), eq(locale));
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
}
