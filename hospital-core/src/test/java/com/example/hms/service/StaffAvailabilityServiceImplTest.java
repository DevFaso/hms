package com.example.hms.service;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.payload.dto.StaffAvailabilityRequestDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffAvailabilityServiceImplTest {

    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffAvailabilityRepository availabilityRepository;
    @Mock private StaffAvailabilityMapper mapper;
    @Mock private MessageSource messageSource;
    @Mock private DepartmentRepository departmentRepository;

    @InjectMocks private StaffAvailabilityServiceImpl service;

    private UUID staffId, hospitalId, departmentId;
    private Hospital hospital;
    private Staff staff;
    private Department department;
    private Locale locale;

    @BeforeEach
    void setUp() {
        staffId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        locale = Locale.ENGLISH;

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");

        department = new Department();
        department.setId(departmentId);
        department.setHospital(hospital);

        staff = Staff.builder().active(true).build();
        staff.setId(staffId);
        staff.setHospital(hospital);
        staff.setDepartment(department);
        staff.setName("Dr. Smith");
    }

    private StaffAvailabilityRequestDTO buildDto(boolean dayOff) {
        return new StaffAvailabilityRequestDTO(
            staffId, hospitalId, LocalDate.now().plusDays(1),
            dayOff ? null : LocalTime.of(9, 0),
            dayOff ? null : LocalTime.of(17, 0),
            dayOff, "Test note", departmentId
        );
    }

    @Test
    void create_success() {
        StaffAvailabilityRequestDTO dto = buildDto(false);
        StaffAvailability entity = new StaffAvailability();
        StaffAvailabilityResponseDTO responseDTO = new StaffAvailabilityResponseDTO(
            UUID.randomUUID(), staffId, "Dr. Smith", "LIC001",
            hospitalId, "Test Hospital", departmentId, "Cardiology", null,
            dto.date(), dto.availableFrom(), dto.availableTo(), false, "note"
        );

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(false);
        when(mapper.toEntity(dto, staff, hospital)).thenReturn(entity);
        when(availabilityRepository.save(entity)).thenReturn(entity);
        when(mapper.toDto(entity)).thenReturn(responseDTO);

        StaffAvailabilityResponseDTO result = service.create(dto, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void create_dayOff_success() {
        StaffAvailabilityRequestDTO dto = buildDto(true);
        StaffAvailability entity = new StaffAvailability();
        StaffAvailabilityResponseDTO responseDTO = new StaffAvailabilityResponseDTO(
            UUID.randomUUID(), staffId, "Dr. Smith", null,
            hospitalId, "Test Hospital", departmentId, "Cardiology", null,
            dto.date(), null, null, true, "note"
        );

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(false);
        when(mapper.toEntity(dto, staff, hospital)).thenReturn(entity);
        when(availabilityRepository.save(entity)).thenReturn(entity);
        when(mapper.toDto(entity)).thenReturn(responseDTO);

        StaffAvailabilityResponseDTO result = service.create(dto, locale);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void create_hospitalNotFound_throws() {
        StaffAvailabilityRequestDTO dto = buildDto(false);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_staffNotFound_throws() {
        StaffAvailabilityRequestDTO dto = buildDto(false);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("not found");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_staffInactive_throws() {
        staff.setActive(false);
        StaffAvailabilityRequestDTO dto = buildDto(false);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("inactive");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_staffDifferentHospital_throws() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        other.setName("Other");
        staff.setHospital(other);
        StaffAvailabilityRequestDTO dto = buildDto(false);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("mismatch");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_nullDepartmentId_throws() {
        StaffAvailabilityRequestDTO dto = new StaffAvailabilityRequestDTO(
            staffId, hospitalId, LocalDate.now().plusDays(1),
            LocalTime.of(9, 0), LocalTime.of(17, 0), false, "note", null
        );
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(any())).thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("required");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_departmentHospitalMismatch_throws() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Department otherDept = new Department();
        otherDept.setId(departmentId);
        otherDept.setHospital(other);
        StaffAvailabilityRequestDTO dto = buildDto(false);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(otherDept));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("mismatch");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_staffDepartmentMismatch_throws() {
        Department otherDept = new Department();
        otherDept.setId(UUID.randomUUID());
        otherDept.setHospital(hospital);
        staff.setDepartment(otherDept);
        StaffAvailabilityRequestDTO dto = buildDto(false);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("mismatch");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_alreadyExists_throws() {
        StaffAvailabilityRequestDTO dto = buildDto(false);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("exists");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_missingTimeForNonDayOff_throws() {
        StaffAvailabilityRequestDTO dto = new StaffAvailabilityRequestDTO(
            staffId, hospitalId, LocalDate.now().plusDays(1),
            null, null, false, "note", departmentId
        );
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("time required");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_invalidTimeRange_throws() {
        StaffAvailabilityRequestDTO dto = new StaffAvailabilityRequestDTO(
            staffId, hospitalId, LocalDate.now().plusDays(1),
            LocalTime.of(17, 0), LocalTime.of(9, 0), false, "note", departmentId
        );
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("invalid");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void create_pastDate_throws() {
        StaffAvailabilityRequestDTO dto = new StaffAvailabilityRequestDTO(
            staffId, hospitalId, LocalDate.now().minusDays(1),
            LocalTime.of(9, 0), LocalTime.of(17, 0), false, "note", departmentId
        );
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(availabilityRepository.existsByStaff_IdAndDate(staffId, dto.date())).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("past");

        assertThatThrownBy(() -> service.create(dto, locale))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ---- isStaffAvailable ----

    @Test
    void isStaffAvailable_withinRange_returnsTrue() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
        StaffAvailability avail = new StaffAvailability();
        avail.setAvailableFrom(LocalTime.of(9, 0));
        avail.setAvailableTo(LocalTime.of(17, 0));
        avail.setDayOff(false);

        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(true);
        when(availabilityRepository.findByStaff_IdAndDate(staffId, appointmentTime.toLocalDate()))
            .thenReturn(Optional.of(avail));

        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isTrue();
    }

    @Test
    void isStaffAvailable_outsideRange_returnsFalse() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(18, 0));
        StaffAvailability avail = new StaffAvailability();
        avail.setAvailableFrom(LocalTime.of(9, 0));
        avail.setAvailableTo(LocalTime.of(17, 0));
        avail.setDayOff(false);

        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(true);
        when(availabilityRepository.findByStaff_IdAndDate(staffId, appointmentTime.toLocalDate()))
            .thenReturn(Optional.of(avail));

        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isFalse();
    }

    @Test
    void isStaffAvailable_dayOff_returnsFalse() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
        StaffAvailability avail = new StaffAvailability();
        avail.setDayOff(true);

        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(true);
        when(availabilityRepository.findByStaff_IdAndDate(staffId, appointmentTime.toLocalDate()))
            .thenReturn(Optional.of(avail));

        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isFalse();
    }

    @Test
    void isStaffAvailable_noRecord_returnsTrue() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(true);
        when(availabilityRepository.findByStaff_IdAndDate(staffId, appointmentTime.toLocalDate()))
            .thenReturn(Optional.empty());

        // No availability record = open schedule → staff is available by default
        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isTrue();
    }

    @Test
    void isStaffAvailable_staffNotActive_returnsFalse() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(false);

        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isFalse();
    }

    @Test
    void isStaffAvailable_nullTimes_returnsFalse() {
        LocalDateTime appointmentTime = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0));
        StaffAvailability avail = new StaffAvailability();
        avail.setDayOff(false);
        avail.setAvailableFrom(null);
        avail.setAvailableTo(null);

        when(staffRepository.existsByIdAndActiveTrue(staffId)).thenReturn(true);
        when(availabilityRepository.findByStaff_IdAndDate(staffId, appointmentTime.toLocalDate()))
            .thenReturn(Optional.of(avail));

        assertThat(service.isStaffAvailable(staffId, appointmentTime)).isFalse();
    }
}
