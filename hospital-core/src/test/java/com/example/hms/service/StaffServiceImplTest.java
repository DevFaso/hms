package com.example.hms.service;

import com.example.hms.exception.*;
import com.example.hms.mapper.StaffMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
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

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
}
