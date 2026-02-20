package com.example.hms.service;

import com.example.hms.enums.StaffShiftType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.payload.dto.StaffShiftRequestDTO;
import com.example.hms.payload.dto.StaffShiftResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffLeaveRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffSchedulingServiceImplTest {

    @Mock
    private StaffShiftRepository shiftRepository;
    @Mock
    private StaffLeaveRequestRepository leaveRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private StaffAvailabilityRepository staffAvailabilityRepository;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private MessageSource messageSource;
    @Mock
    private UserRepository userRepository;

    private StaffSchedulingServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any()))
            .thenAnswer(invocation -> {
                Object defaultMessage = invocation.getArgument(2);
                return defaultMessage != null ? defaultMessage : invocation.getArgument(0);
            });

        service = new StaffSchedulingServiceImpl(
            shiftRepository,
            leaveRepository,
            staffRepository,
            hospitalRepository,
            departmentRepository,
            staffAvailabilityRepository,
            new com.example.hms.mapper.StaffSchedulingMapper(),
            roleValidator,
            messageSource,
            userRepository
        );
    }

    @Test
    void scheduleShift_autoCreatesAvailabilityWhenMissing() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        LocalDate shiftDate = LocalDate.now().plusDays(1);
        LocalTime startTime = LocalTime.of(8, 0);
        LocalTime endTime = LocalTime.of(16, 0);

        Staff staff = buildStaff(staffId, hospitalId);
        Hospital hospital = staff.getHospital();
        User actor = buildUser(actorUserId, "scheduler@example.com");

        StaffShiftRequestDTO dto = new StaffShiftRequestDTO(
            staffId,
            hospitalId,
            null,
            shiftDate,
            startTime,
            endTime,
            StaffShiftType.MORNING,
            null
        );

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(shiftRepository.existsOverlappingShift(staffId, shiftDate, startTime, endTime, null)).thenReturn(false);
        when(staffAvailabilityRepository.findByStaff_IdAndDate(staffId, shiftDate)).thenReturn(Optional.empty());
        when(staffAvailabilityRepository.save(any(StaffAvailability.class))).thenAnswer(invocation -> {
            StaffAvailability availability = invocation.getArgument(0);
            availability.setId(UUID.randomUUID());
            return availability;
        });
        when(leaveRepository.findLeavesOverlappingDate(eq(staffId), eq(shiftDate), any()))
            .thenReturn(Collections.emptyList());
        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(actor));
        when(shiftRepository.save(any(StaffShift.class))).thenAnswer(invocation -> {
            StaffShift shift = invocation.getArgument(0);
            shift.setId(UUID.randomUUID());
            return shift;
        });

        StaffShiftResponseDTO response = service.scheduleShift(dto, Locale.ENGLISH);

        assertThat(response).isNotNull();
        ArgumentCaptor<StaffAvailability> availabilityCaptor = ArgumentCaptor.forClass(StaffAvailability.class);
        verify(staffAvailabilityRepository).save(availabilityCaptor.capture());
        StaffAvailability savedAvailability = availabilityCaptor.getValue();
        assertThat(savedAvailability.getStaff()).isEqualTo(staff);
        assertThat(savedAvailability.getHospital()).isEqualTo(hospital);
        assertThat(savedAvailability.getDate()).isEqualTo(shiftDate);
        assertThat(savedAvailability.getAvailableFrom()).isEqualTo(startTime);
        assertThat(savedAvailability.getAvailableTo()).isEqualTo(endTime);
    }

    @Test
    void scheduleShift_reusesExistingAvailabilityWhenPresent() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        LocalDate shiftDate = LocalDate.now().plusDays(2);
        LocalTime startTime = LocalTime.of(10, 0);
        LocalTime endTime = LocalTime.of(18, 0);

        Staff staff = buildStaff(staffId, hospitalId);
        Hospital hospital = staff.getHospital();
        User actor = buildUser(actorUserId, "manager@example.com");

        StaffAvailability availability = new StaffAvailability();
        availability.setId(UUID.randomUUID());
        availability.setStaff(staff);
        availability.setHospital(hospital);
        availability.setDate(shiftDate);
        availability.setAvailableFrom(LocalTime.of(9, 0));
        availability.setAvailableTo(LocalTime.of(19, 0));
        availability.setDayOff(false);

        StaffShiftRequestDTO dto = new StaffShiftRequestDTO(
            staffId,
            hospitalId,
            null,
            shiftDate,
            startTime,
            endTime,
            StaffShiftType.MORNING,
            null
        );

        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(shiftRepository.existsOverlappingShift(staffId, shiftDate, startTime, endTime, null)).thenReturn(false);
        when(staffAvailabilityRepository.findByStaff_IdAndDate(staffId, shiftDate)).thenReturn(Optional.of(availability));
        when(leaveRepository.findLeavesOverlappingDate(eq(staffId), eq(shiftDate), any()))
            .thenReturn(Collections.emptyList());
        when(roleValidator.getCurrentUserId()).thenReturn(actorUserId);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(actor));
        when(shiftRepository.save(any(StaffShift.class))).thenAnswer(invocation -> {
            StaffShift shift = invocation.getArgument(0);
            shift.setId(UUID.randomUUID());
            return shift;
        });

        StaffShiftResponseDTO response = service.scheduleShift(dto, Locale.ENGLISH);

        assertThat(response).isNotNull();
        verify(staffAvailabilityRepository, never()).save(any(StaffAvailability.class));
    }

    private Staff buildStaff(UUID staffId, UUID hospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");
        hospital.setCode("TEST-HOSP");

        User staffUser = buildUser(UUID.randomUUID(), "staff@example.com");

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setHospital(hospital);
        staff.setUser(staffUser);
        staff.setActive(true);
        return staff;
    }

    private User buildUser(UUID userId, String email) {
        User user = new User();
        user.setId(userId);
        user.setUsername(email);
        user.setEmail(email);
        user.setPasswordHash("hashed");
        user.setPhoneNumber("+10000000000");
        user.setFirstName("Test");
        user.setLastName("User");
        return user;
    }
}
