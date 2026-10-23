package com.example.hms.service;

import com.example.hms.enums.StaffShiftType;
import com.example.hms.exception.BusinessRuleException;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /**
     * Fixed "today", deliberately in the future: a date that is past on the
     * injected clock but not on the system clock proves which one the guard reads.
     */
    private static final LocalDate TODAY = LocalDate.of(2099, 3, 2);
    private static final Clock FIXED =
        Clock.fixed(TODAY.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

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
            userRepository,
            FIXED
        );
    }

    @Test
    void findLeaves_withoutDatesReachesAYearAheadAndAMonthBack() {
        // D117: a leave request starting in a month sat outside the 14-day
        // shift window, so the admin never saw it to decide on it.
        UUID hospitalId = UUID.randomUUID();
        when(leaveRepository.findByHospital_IdAndStartDateBetweenOrderByStartDateAsc(any(), any(), any()))
            .thenReturn(Collections.emptyList());

        service.findLeaves(hospitalId, null, null, null, null, Locale.ENGLISH);

        verify(leaveRepository).findByHospital_IdAndStartDateBetweenOrderByStartDateAsc(
            hospitalId, TODAY.minusDays(31), TODAY.plusDays(366));
    }

    @Test
    void scheduleShift_rejectsYesterdayOnTheInjectedClock() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Staff staff = buildStaff(staffId, hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(staff.getHospital()));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        StaffShiftRequestDTO yesterday = new StaffShiftRequestDTO(
            staffId, hospitalId, null, TODAY.minusDays(1),
            LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.scheduleShift(yesterday, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("schedule.shift.pastDate");
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    /** QA D541: hospital admin A posts a shift for DOCTOR_B at hospital B — a 403 refusal, not a 400. */
    @Test
    void d541_scheduleShiftAtAnotherHospitalIsAnAccessRefusal() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        UUID adminA = UUID.randomUUID();
        Staff staffB = buildStaff(staffId, hospitalB);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staffB));
        when(hospitalRepository.findClinicalById(hospitalB)).thenReturn(Optional.of(staffB.getHospital()));
        when(roleValidator.getCurrentUserId()).thenReturn(adminA);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);
        when(roleValidator.isHospitalAdmin(adminA, hospitalB)).thenReturn(false);
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(adminA)).thenReturn(Optional.empty());

        StaffShiftRequestDTO tomorrow = new StaffShiftRequestDTO(
            staffId, hospitalB, null, TODAY.plusDays(1),
            LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.scheduleShift(tomorrow, Locale.ENGLISH))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("schedule.staff.permissionDenied");
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    @Test
    void scheduleShift_refusesAStaffMemberWhoseAccountIsInactive() {
        // QA D166 (partial D117): qa.r2.nurse290579 - staff row active, account
        // never verified - was given shift 03bb53f2.
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Staff staff = buildStaff(staffId, hospitalId);
        staff.getUser().setActive(false);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(staff.getHospital()));

        StaffShiftRequestDTO tomorrow = new StaffShiftRequestDTO(
            staffId, hospitalId, null, TODAY.plusDays(1),
            LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.scheduleShift(tomorrow, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("schedule.staff.inactive");
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    @Test
    void scheduleShift_refusesAStaffMemberWhoseAssignmentIsInactive() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Staff staff = buildStaff(staffId, hospitalId);
        com.example.hms.model.UserRoleHospitalAssignment pending = new com.example.hms.model.UserRoleHospitalAssignment();
        pending.setActive(false);
        staff.setAssignment(pending);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(staff.getHospital()));

        StaffShiftRequestDTO tomorrow = new StaffShiftRequestDTO(
            staffId, hospitalId, null, TODAY.plusDays(1),
            LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.scheduleShift(tomorrow, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("schedule.staff.inactive");
    }

    @Test
    void scheduleShift_autoCreatesAvailabilityWhenMissing() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        LocalDate shiftDate = TODAY.plusDays(1);
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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
        // QA D282: the overlap check runs under the staff row lock, so a second
        // concurrent submit waits and then sees this shift.
        org.mockito.InOrder lockFirst = org.mockito.Mockito.inOrder(staffRepository, shiftRepository);
        lockFirst.verify(staffRepository).findLockedById(staffId);
        lockFirst.verify(shiftRepository).existsOverlappingShift(staffId, shiftDate, startTime, endTime, null);
        lockFirst.verify(shiftRepository).save(any(StaffShift.class));
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
        LocalDate shiftDate = TODAY.plusDays(2);
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
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
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

    // ── QA D602: a shift is loaded by id AND the acting hospital ────────────

    private void actAt(UUID hospitalId) {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .activeHospitalId(hospitalId)
                .permittedHospitalIds(java.util.Set.of(hospitalId))
                .hospitalAdmin(true)
                .build());
    }

    @org.junit.jupiter.api.AfterEach
    void clearScope() {
        com.example.hms.security.context.HospitalContextHolder.clear();
    }

    private StaffShift shiftAt(Staff staff) {
        StaffShift shift = StaffShift.builder()
            .staff(staff).hospital(staff.getHospital())
            .shiftDate(TODAY.plusDays(1)).startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(16, 0))
            .shiftType(StaffShiftType.MORNING).build();
        shift.setId(UUID.randomUUID());
        when(shiftRepository.findDetailedById(shift.getId())).thenReturn(Optional.of(shift));
        return shift;
    }

    /** QA D602: HOSPITAL_ADMIN_B PUTs hospital A's shift with DOCTOR_B and hospital B in the body: 404, nothing moves. */
    @Test
    void d602_adminOfHospitalB_cannotTakeOverHospitalAsShift() {
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        Staff doctorA = buildStaff(UUID.randomUUID(), hospitalA);
        Staff doctorB = buildStaff(UUID.randomUUID(), hospitalB);
        StaffShift shift = shiftAt(doctorA);
        lenient().when(staffRepository.findById(doctorB.getId())).thenReturn(Optional.of(doctorB));
        lenient().when(hospitalRepository.findClinicalById(hospitalB)).thenReturn(Optional.of(doctorB.getHospital()));
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        lenient().when(roleValidator.isHospitalAdmin(any(), eq(hospitalB))).thenReturn(true);
        actAt(hospitalB);

        StaffShiftRequestDTO takeOver = new StaffShiftRequestDTO(doctorB.getId(), hospitalB, null,
            TODAY.plusDays(1), LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.updateShift(shift.getId(), takeOver, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(shift.getHospital().getId()).isEqualTo(hospitalA);
        assertThat(shift.getStaff()).isSameAs(doctorA);
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    /** QA D602 sibling: the status PUT on another hospital's shift is a 404 too. */
    @Test
    void d602_statusOfAnotherHospitalsShift_isNotFound() {
        UUID hospitalA = UUID.randomUUID();
        StaffShift shift = shiftAt(buildStaff(UUID.randomUUID(), hospitalA));
        actAt(UUID.randomUUID());

        assertThatThrownBy(() -> service.updateShiftStatus(shift.getId(),
                new com.example.hms.payload.dto.StaffShiftStatusUpdateDTO(
                    com.example.hms.enums.StaffShiftStatus.CANCELLED, "x"), Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    /** QA D602 sibling: a leave of another hospital cannot be decided or cancelled from here. */
    @Test
    void d602_leaveOfAnotherHospital_isNotFound() {
        UUID hospitalA = UUID.randomUUID();
        com.example.hms.model.StaffLeaveRequest leave = com.example.hms.model.StaffLeaveRequest.builder()
            .hospital(buildStaff(UUID.randomUUID(), hospitalA).getHospital())
            .build();
        leave.setId(UUID.randomUUID());
        when(leaveRepository.findDetailedById(leave.getId())).thenReturn(Optional.of(leave));
        actAt(UUID.randomUUID());

        assertThatThrownBy(() -> service.decideLeave(leave.getId(),
                new com.example.hms.payload.dto.StaffLeaveDecisionDTO(
                    com.example.hms.enums.StaffLeaveStatus.APPROVED, null), Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelLeave(leave.getId(), Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(leaveRepository, never()).save(any());
    }

    /** QA D602: hospital A's own admin cannot move A's shift to hospital B through the body either. */
    @Test
    void d602_ownAdmin_cannotMoveTheShiftToAnotherHospital() {
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        Staff doctorA = buildStaff(UUID.randomUUID(), hospitalA);
        StaffShift shift = shiftAt(doctorA);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.isHospitalAdmin(any(), eq(hospitalA))).thenReturn(true);
        actAt(hospitalA);

        StaffShiftRequestDTO move = new StaffShiftRequestDTO(doctorA.getId(), hospitalB, null,
            TODAY.plusDays(1), LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.updateShift(shift.getId(), move, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("schedule.shift.hospital.immutable");
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    /** D602 path re-verified: hospital A's admin still edits A's shift (new time, same staff). */
    @Test
    void d602_ownAdmin_stillEditsTheShift() {
        UUID hospitalA = UUID.randomUUID();
        Staff doctorA = buildStaff(UUID.randomUUID(), hospitalA);
        StaffShift shift = shiftAt(doctorA);
        UUID actor = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(actor);
        when(roleValidator.isHospitalAdmin(any(), eq(hospitalA))).thenReturn(true);
        when(staffRepository.findById(doctorA.getId())).thenReturn(Optional.of(doctorA));
        when(hospitalRepository.findClinicalById(hospitalA)).thenReturn(Optional.of(doctorA.getHospital()));
        lenient().when(staffAvailabilityRepository.findByStaff_IdAndDate(any(), any())).thenReturn(Optional.empty());
        lenient().when(staffAvailabilityRepository.save(any(StaffAvailability.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(leaveRepository.findLeavesOverlappingDate(any(), any(), any())).thenReturn(Collections.emptyList());
        when(userRepository.findById(actor)).thenReturn(Optional.of(buildUser(actor, "admin@example.com")));
        when(shiftRepository.save(any(StaffShift.class))).thenAnswer(i -> i.getArgument(0));
        actAt(hospitalA);

        StaffShiftRequestDTO edit = new StaffShiftRequestDTO(doctorA.getId(), hospitalA, null,
            TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(17, 0), StaffShiftType.MORNING, null);

        service.updateShift(shift.getId(), edit, Locale.ENGLISH);

        assertThat(shift.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(shift.getHospital().getId()).isEqualTo(hospitalA);
    }

    // ── QA D635: only a shift type that runs past midnight may end before it starts ──

    @Test
    void d635_morningShiftFrom16To08_isRefused() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Staff staff = buildStaff(staffId, hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(staff.getHospital()));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(roleValidator.getCurrentUserId()).thenReturn(UUID.randomUUID());

        StaffShiftRequestDTO backwards = new StaffShiftRequestDTO(staffId, hospitalId, null, TODAY.plusDays(1),
            LocalTime.of(16, 0), LocalTime.of(8, 0), StaffShiftType.MORNING, null);

        assertThatThrownBy(() -> service.scheduleShift(backwards, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("schedule.shift.timeRange.invalid");
        verify(shiftRepository, never()).save(any(StaffShift.class));
    }

    /** D635 path re-verified: a NIGHT shift 22:00 to 06:00 is still accepted. */
    @Test
    void d635_nightShiftAcrossMidnight_isStillAccepted() {
        UUID staffId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Staff staff = buildStaff(staffId, hospitalId);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(staff.getHospital()));
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        when(roleValidator.getCurrentUserId()).thenReturn(actorId);
        lenient().when(staffAvailabilityRepository.findByStaff_IdAndDate(any(), any())).thenReturn(Optional.empty());
        lenient().when(staffAvailabilityRepository.save(any(StaffAvailability.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(leaveRepository.findLeavesOverlappingDate(any(), any(), any())).thenReturn(Collections.emptyList());
        when(userRepository.findById(actorId)).thenReturn(Optional.of(buildUser(actorId, "a@example.com")));
        when(shiftRepository.save(any(StaffShift.class))).thenAnswer(i -> i.getArgument(0));

        StaffShiftRequestDTO night = new StaffShiftRequestDTO(staffId, hospitalId, null, TODAY.plusDays(1),
            LocalTime.of(22, 0), LocalTime.of(6, 0), StaffShiftType.NIGHT, null);

        assertThat(service.scheduleShift(night, Locale.ENGLISH)).isNotNull();
    }
}
