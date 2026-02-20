package com.example.hms.service;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.StaffSchedulingMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.model.StaffLeaveRequest;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.payload.dto.BulkShiftRequestDTO;
import com.example.hms.payload.dto.BulkShiftResultDTO;
import com.example.hms.payload.dto.BulkShiftSkipDTO;
import com.example.hms.payload.dto.StaffLeaveDecisionDTO;
import com.example.hms.payload.dto.StaffLeaveRequestDTO;
import com.example.hms.payload.dto.StaffLeaveResponseDTO;
import com.example.hms.payload.dto.StaffShiftRequestDTO;
import com.example.hms.payload.dto.StaffShiftResponseDTO;
import com.example.hms.payload.dto.StaffShiftStatusUpdateDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffLeaveRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaffSchedulingServiceImpl implements StaffSchedulingService {

    private static final Set<StaffLeaveStatus> ACTIVE_LEAVE_STATUSES =
        EnumSet.of(StaffLeaveStatus.PENDING, StaffLeaveStatus.APPROVED);

    private static final Set<StaffShiftStatus> MUTABLE_SHIFT_STATUSES =
        EnumSet.of(StaffShiftStatus.SCHEDULED);

    private final StaffShiftRepository shiftRepository;
    private final StaffLeaveRequestRepository leaveRepository;
    private final StaffRepository staffRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffAvailabilityRepository staffAvailabilityRepository;
    private final StaffSchedulingMapper mapper;
    private final RoleValidator roleValidator;
    private final MessageSource messageSource;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public StaffShiftResponseDTO scheduleShift(StaffShiftRequestDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        Staff staff = findStaff(dto.staffId(), effectiveLocale);
        Hospital hospital = findHospital(dto.hospitalId(), effectiveLocale);
        Department department = resolveDepartment(dto.departmentId(), staff, hospital, effectiveLocale);

        ensureStaffBelongsToHospital(staff, hospital, effectiveLocale);
        ensureStaffActive(staff, effectiveLocale);
        ensureSchedulerPermissions(staff, hospital.getId(), department, effectiveLocale);

        validateShiftWindow(dto, staff, effectiveLocale, null);

        User actor = getCurrentUser(effectiveLocale);
        StaffShift saved = persistShift(staff, hospital, department, dto, actor);
        log.info("[schedule] shift created staff={}, date={}, start={}, end={}",
            staff.getId(), dto.shiftDate(), dto.startTime(), dto.endTime());
        return mapper.toShiftDto(saved);
    }

    @Override
    @Transactional
    public BulkShiftResultDTO bulkScheduleShifts(BulkShiftRequestDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);

        // ── Resolve & validate shared context ────────────────────────────────
        if (dto.endDate().isBefore(dto.startDate())) {
            throw new BusinessRuleException(message("schedule.dateRange.invalid", effectiveLocale));
        }
        if (dto.daysOfWeek() == null || dto.daysOfWeek().isEmpty()) {
            throw new BusinessRuleException(message("schedule.bulk.daysOfWeek.required", effectiveLocale));
        }
        if (dto.endTime().equals(dto.startTime())) {
            throw new BusinessRuleException(message("schedule.shift.timeRange.invalid", effectiveLocale));
        }

        Staff staff = findStaff(dto.staffId(), effectiveLocale);
        Hospital hospital = findHospital(dto.hospitalId(), effectiveLocale);
        Department department = resolveDepartment(dto.departmentId(), staff, hospital, effectiveLocale);

        ensureStaffBelongsToHospital(staff, hospital, effectiveLocale);
        ensureStaffActive(staff, effectiveLocale);
        ensureSchedulerPermissions(staff, hospital.getId(), department, effectiveLocale);

        User actor = getCurrentUser(effectiveLocale);
        Set<DayOfWeek> targetDays = dto.daysOfWeek();

        List<StaffShiftResponseDTO> scheduled = new ArrayList<>();
        List<BulkShiftSkipDTO> skipped = new ArrayList<>();

        BulkContext ctx = new BulkContext(staff, hospital, department, actor, effectiveLocale, dto);

        // ── Iterate each date in the range ───────────────────────────────────
        for (LocalDate current = dto.startDate(); !current.isAfter(dto.endDate()); current = current.plusDays(1)) {
            processBulkDate(current, targetDays, ctx, scheduled, skipped);
        }

        log.info("[bulk-schedule] done staff={} scheduled={} skipped={}",
            staff.getId(), scheduled.size(), skipped.size());
        return new BulkShiftResultDTO(scheduled, skipped, scheduled.size(), skipped.size());
    }

    /** Bundles immutable context shared across every date in a bulk operation. */
    private record BulkContext(Staff staff, Hospital hospital, Department department,
                               User actor, Locale locale, BulkShiftRequestDTO dto) { }

    /**
     * Attempt to schedule a single date as part of a bulk operation.
     * Mutates {@code scheduled} or {@code skipped} in place.
     */
    private void processBulkDate(LocalDate date,
                                  Set<DayOfWeek> targetDays,
                                  BulkContext ctx,
                                  List<StaffShiftResponseDTO> scheduled,
                                  List<BulkShiftSkipDTO> skipped) {
        if (!targetDays.contains(date.getDayOfWeek())) {
            return; // Not a target day — silently skip
        }

        BulkShiftRequestDTO dto = ctx.dto();
        StaffShiftRequestDTO perDayDto = new StaffShiftRequestDTO(
            dto.staffId(), dto.hospitalId(), dto.departmentId(),
            date, dto.startTime(), dto.endTime(), dto.shiftType(), dto.notes()
        );

        try {
            validateShiftWindow(perDayDto, ctx.staff(), ctx.locale(), null);
        } catch (BusinessRuleException | IllegalStateException ex) {
            if (dto.skipConflicts()) {
                skipped.add(new BulkShiftSkipDTO(date, ex.getMessage()));
                log.debug("[bulk-schedule] skipped date={} reason={}", date, ex.getMessage());
                return;
            }
            throw ex;
        }

        StaffShift saved = persistShift(ctx.staff(), ctx.hospital(), ctx.department(), perDayDto, ctx.actor());
        scheduled.add(mapper.toShiftDto(saved));
        log.info("[bulk-schedule] shift created staff={} date={} start={} end={}",
            ctx.staff().getId(), date, dto.startTime(), dto.endTime());
    }

    /**
     * Persists a new {@link StaffShift} entity without any validation — validation
     * must be done by the caller ({@link #validateShiftWindow}) before invoking this.
     */
    private StaffShift persistShift(Staff staff,
                                    Hospital hospital,
                                    Department department,
                                    StaffShiftRequestDTO dto,
                                    User actor) {
        StaffShift shift = StaffShift.builder()
            .staff(staff)
            .hospital(hospital)
            .department(department)
            .shiftDate(dto.shiftDate())
            .startTime(dto.startTime())
            .endTime(dto.endTime())
            .shiftType(dto.shiftType())
            .status(StaffShiftStatus.SCHEDULED)
            .notes(dto.notes())
            .scheduledBy(actor)
            .lastModifiedBy(actor)
            .statusChangedAt(LocalDateTime.now())
            .build();
        return shiftRepository.save(shift);
    }

    @Override
    @Transactional
    public StaffShiftResponseDTO updateShift(UUID shiftId, StaffShiftRequestDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        StaffShift existing = loadShift(shiftId, effectiveLocale);
        ensureShiftMutable(existing, effectiveLocale);

        Staff staff = findStaff(dto.staffId(), effectiveLocale);
        Hospital hospital = findHospital(dto.hospitalId(), effectiveLocale);
        Department department = resolveDepartment(dto.departmentId(), staff, hospital, effectiveLocale);

        ensureStaffBelongsToHospital(staff, hospital, effectiveLocale);
        ensureSchedulerPermissions(staff, hospital.getId(), department, effectiveLocale);

        validateShiftWindow(dto, staff, effectiveLocale, existing.getId());

        existing.setStaff(staff);
        existing.setHospital(hospital);
        existing.setDepartment(department);
        existing.setShiftDate(dto.shiftDate());
        existing.setStartTime(dto.startTime());
        existing.setEndTime(dto.endTime());
        existing.setShiftType(dto.shiftType());
        existing.setNotes(dto.notes());
        existing.setLastModifiedBy(getCurrentUser(effectiveLocale));
        existing.setStatusChangedAt(LocalDateTime.now());

        StaffShift saved = shiftRepository.save(existing);
        log.info("[schedule] shift updated id={} staff={} date={}", shiftId, staff.getId(), dto.shiftDate());
        return mapper.toShiftDto(saved);
    }

    @Override
    @Transactional
    public StaffShiftResponseDTO updateShiftStatus(UUID shiftId, StaffShiftStatusUpdateDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        StaffShift shift = loadShift(shiftId, effectiveLocale);
        ensureSchedulerPermissions(shift.getStaff(), shift.getHospital().getId(), shift.getDepartment(), effectiveLocale);

        StaffShiftStatus newStatus = dto.status();
        if (newStatus == null) {
            throw new BusinessRuleException(message("schedule.shift.status.required", effectiveLocale));
        }
        if (shift.getStatus() == newStatus) {
            return mapper.toShiftDto(shift);
        }
        if (newStatus == StaffShiftStatus.CANCELLED && (dto.cancellationReason() == null || dto.cancellationReason().isBlank())) {
            throw new BusinessRuleException(message("schedule.shift.cancellation.reason.required", effectiveLocale));
        }

        shift.setStatus(newStatus);
        shift.setCancellationReason(newStatus == StaffShiftStatus.CANCELLED ? dto.cancellationReason() : null);
        shift.setLastModifiedBy(getCurrentUser(effectiveLocale));
        shift.setStatusChangedAt(LocalDateTime.now());

        StaffShift saved = shiftRepository.save(shift);
        log.info("[schedule] shift status change id={} status={}", shiftId, newStatus);
        return mapper.toShiftDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffShiftResponseDTO> findShifts(UUID hospitalId,
                                                  UUID departmentId,
                                                  UUID staffId,
                                                  LocalDate startDate,
                                                  LocalDate endDate,
                                                  Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        DateRange range = resolveDateRange(startDate, endDate, effectiveLocale);

        // Extend the query window one day back so that cross-midnight shifts whose
        // shiftDate = (rangeStart - 1 day) but whose shiftEndDate falls inside the
        // requested range are included in the results.
        LocalDate queryStart = range.start().minusDays(1);
        LocalDate queryEnd   = range.end();

        List<StaffShift> shifts;
        if (staffId != null) {
            shifts = shiftRepository.findByStaff_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                staffId, queryStart, queryEnd);
        } else if (departmentId != null) {
            shifts = shiftRepository.findByDepartment_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                departmentId, queryStart, queryEnd);
        } else if (hospitalId != null) {
            shifts = shiftRepository.findByHospital_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                hospitalId, queryStart, queryEnd);
        } else if (roleValidator.isSuperAdminFromAuth()) {
            shifts = shiftRepository.findByShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                queryStart, queryEnd);
        } else {
            shifts = shiftRepository.findByHospital_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                requireCurrentHospitalId(effectiveLocale), queryStart, queryEnd);
        }

        // Filter out the extra day-before shifts that are NOT cross-midnight (they
        // have shiftDate = queryStart but shiftEndDate = shiftDate, i.e. same-day,
        // so they fall outside the originally requested range).
        return shifts.stream()
            .map(mapper::toShiftDto)
            .filter(s -> !s.shiftDate().isBefore(range.start())    // normal same-day shifts
                || s.shiftEndDate().compareTo(range.start()) >= 0) // cross-midnight from prior day
            .toList();
    }

    @Override
    @Transactional
    public StaffLeaveResponseDTO requestLeave(StaffLeaveRequestDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        Staff staff = findStaff(dto.staffId(), effectiveLocale);
        Hospital hospital = findHospital(dto.hospitalId(), effectiveLocale);
        Department department = resolveDepartment(dto.departmentId(), staff, hospital, effectiveLocale);

        ensureStaffBelongsToHospital(staff, hospital, effectiveLocale);
        ensureLeaveRequestPermissions(staff, hospital.getId(), department, effectiveLocale);
        validateLeaveWindow(dto, staff, effectiveLocale, null);

        User actor = getCurrentUser(effectiveLocale);
        StaffLeaveRequest leave = StaffLeaveRequest.builder()
            .staff(staff)
            .hospital(hospital)
            .department(department)
            .leaveType(dto.leaveType())
            .status(StaffLeaveStatus.PENDING)
            .startDate(dto.startDate())
            .endDate(dto.endDate())
            .startTime(dto.startTime())
            .endTime(dto.endTime())
            .requiresCoverage(dto.requiresCoverage())
            .reason(dto.reason())
            .requestedBy(actor)
            .build();

        StaffLeaveRequest saved = leaveRepository.save(leave);
        log.info("[leave] request created id={} staff={} start={} end={}",
            saved.getId(), staff.getId(), dto.startDate(), dto.endDate());
        return mapper.toLeaveDto(saved);
    }

    @Override
    @Transactional
    public StaffLeaveResponseDTO decideLeave(UUID leaveId, StaffLeaveDecisionDTO dto, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        StaffLeaveRequest leave = loadLeave(leaveId, effectiveLocale);
        ensureLeaveDecisionPermissions(leave, effectiveLocale);

        if (leave.getStatus() != StaffLeaveStatus.PENDING) {
            throw new BusinessRuleException(message("schedule.leave.notPending", effectiveLocale));
        }
        StaffLeaveStatus newStatus = dto.status();
        if (newStatus == null) {
            throw new BusinessRuleException(message("schedule.leave.status.required", effectiveLocale));
        }
        if (newStatus == StaffLeaveStatus.CANCELLED) {
            throw new BusinessRuleException(message("schedule.leave.status.invalid", effectiveLocale));
        }
        leave.setStatus(newStatus);
        leave.setManagerNote(dto.managerNote());
        leave.setReviewedBy(getCurrentUser(effectiveLocale));
        leave.setReviewedAt(LocalDateTime.now());

        if (newStatus == StaffLeaveStatus.APPROVED) {
            cancelOverlappingShiftsForLeave(leave, effectiveLocale);
        }

        StaffLeaveRequest saved = leaveRepository.save(leave);
        log.info("[leave] decision leaveId={} status={}", leaveId, newStatus);
        return mapper.toLeaveDto(saved);
    }

    @Override
    @Transactional
    public StaffLeaveResponseDTO cancelLeave(UUID leaveId, Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        StaffLeaveRequest leave = loadLeave(leaveId, effectiveLocale);
        ensureLeaveCancellationPermissions(leave, effectiveLocale);

        if (leave.getStatus() != StaffLeaveStatus.PENDING) {
            throw new BusinessRuleException(message("schedule.leave.cancel.onlyPending", effectiveLocale));
        }
        leave.setStatus(StaffLeaveStatus.CANCELLED);
        leave.setReviewedBy(getCurrentUser(effectiveLocale));
        leave.setReviewedAt(LocalDateTime.now());
        StaffLeaveRequest saved = leaveRepository.save(leave);
        log.info("[leave] cancelled leaveId={}", leaveId);
        return mapper.toLeaveDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffLeaveResponseDTO> findLeaves(UUID hospitalId,
                                                  UUID departmentId,
                                                  UUID staffId,
                                                  LocalDate startDate,
                                                  LocalDate endDate,
                                                  Locale locale) {
        Locale effectiveLocale = safeLocale(locale);
        DateRange range = resolveDateRange(startDate, endDate, effectiveLocale);
        List<StaffLeaveRequest> leaves;
        if (staffId != null) {
            leaves = leaveRepository.findByStaff_IdAndStartDateBetweenOrderByStartDateAsc(
                staffId, range.start(), range.end());
        } else if (departmentId != null) {
            leaves = leaveRepository.findByDepartment_IdAndStartDateBetweenOrderByStartDateAsc(
                departmentId, range.start(), range.end());
        } else if (hospitalId != null) {
            leaves = leaveRepository.findByHospital_IdAndStartDateBetweenOrderByStartDateAsc(
                hospitalId, range.start(), range.end());
        } else if (roleValidator.isSuperAdminFromAuth()) {
            leaves = leaveRepository.findByStartDateBetweenOrderByStartDateAsc(
                range.start(), range.end());
        } else {
            leaves = leaveRepository.findByHospital_IdAndStartDateBetweenOrderByStartDateAsc(
                requireCurrentHospitalId(effectiveLocale), range.start(), range.end());
        }
        return leaves.stream().map(mapper::toLeaveDto).toList();
    }

    private StaffShift loadShift(UUID id, Locale locale) {
        return shiftRepository.findDetailedById(id)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.shift.notFound", locale)));
    }

    private StaffLeaveRequest loadLeave(UUID id, Locale locale) {
        return leaveRepository.findDetailedById(id)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.leave.notFound", locale)));
    }

    private void ensureShiftMutable(StaffShift shift, Locale locale) {
        if (!MUTABLE_SHIFT_STATUSES.contains(shift.getStatus())) {
            throw new BusinessRuleException(message("schedule.shift.notEditable", locale));
        }
    }

    private void ensureSchedulerPermissions(Staff targetStaff,
                                            UUID hospitalId,
                                            Department department,
                                            Locale locale) {
        UUID currentUserId = requireCurrentUserId(locale);
        if (roleValidator.isSuperAdminFromAuth() || roleValidator.isHospitalAdmin(currentUserId, hospitalId)) {
            return;
        }
        Optional<Staff> actorStaffOpt = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(currentUserId);
        if (actorStaffOpt.isPresent()) {
            Staff actorStaff = actorStaffOpt.get();
            if (isDepartmentHeadOf(actorStaff, department) || headsTargetStaffDepartment(actorStaff, targetStaff)) {
                return;
            }
        }
        throw new BusinessRuleException(message("schedule.staff.permissionDenied", locale));
    }

    private void ensureLeaveRequestPermissions(Staff staff,
                                               UUID hospitalId,
                                               Department department,
                                               Locale locale) {
        UUID currentUserId = requireCurrentUserId(locale);
        if (staff.getUser() != null && Objects.equals(staff.getUser().getId(), currentUserId)) {
            return;
        }
        ensureSchedulerPermissions(staff, hospitalId, department, locale);
    }

    private void ensureLeaveDecisionPermissions(StaffLeaveRequest leave, Locale locale) {
        ensureSchedulerPermissions(leave.getStaff(), leave.getHospital().getId(), leave.getDepartment(), locale);
    }

    private void ensureLeaveCancellationPermissions(StaffLeaveRequest leave, Locale locale) {
        UUID currentUserId = requireCurrentUserId(locale);
        if (leave.getRequestedBy() != null && Objects.equals(leave.getRequestedBy().getId(), currentUserId)) {
            return;
        }
        ensureLeaveDecisionPermissions(leave, locale);
    }

    private void validateShiftWindow(StaffShiftRequestDTO dto,
                                     Staff staff,
                                     Locale locale,
                                     UUID excludeShiftId) {
        if (dto.shiftDate().isBefore(LocalDate.now())) {
            throw new BusinessRuleException(message("schedule.shift.pastDate", locale));
        }
        // Allow cross-midnight shifts where endTime < startTime (e.g. NIGHT: 16:30 → 01:30 next day)
        if (dto.endTime().equals(dto.startTime())) {
            throw new BusinessRuleException(message("schedule.shift.timeRange.invalid", locale));
        }
        if (shiftRepository.existsOverlappingShift(staff.getId(), dto.shiftDate(), dto.startTime(), dto.endTime(), excludeShiftId)) {
            throw new BusinessRuleException(message("schedule.shift.overlap", locale));
        }
        ensureAvailabilityCoversShift(dto, staff, locale);
        ensureNoLeaveConflict(dto, staff, locale);
    }

    private void ensureAvailabilityCoversShift(StaffShiftRequestDTO dto, Staff staff, Locale locale) {
        StaffAvailability availability = staffAvailabilityRepository
            .findByStaff_IdAndDate(staff.getId(), dto.shiftDate())
            .orElseGet(() -> provisionAvailabilityForShift(dto, staff, locale));
        if (availability.isDayOff()) {
            throw new BusinessRuleException(message("schedule.shift.availability.dayOff", locale));
        }
        // For cross-midnight availability windows (availableTo < availableFrom), coverage is always satisfied
        // because the window spans the entire day boundary. Only check for same-day windows.
        boolean crossMidnightAvail = availability.getAvailableFrom() != null
            && availability.getAvailableTo() != null
            && availability.getAvailableTo().isBefore(availability.getAvailableFrom());
        if (!crossMidnightAvail) {
            if (availability.getAvailableFrom() != null && dto.startTime().isBefore(availability.getAvailableFrom())) {
                throw new BusinessRuleException(message("schedule.shift.availability.start", locale));
            }
            if (availability.getAvailableTo() != null && dto.endTime().isAfter(availability.getAvailableTo())) {
                throw new BusinessRuleException(message("schedule.shift.availability.end", locale));
            }
        }
    }

    private StaffAvailability provisionAvailabilityForShift(StaffShiftRequestDTO dto,
                                                            Staff staff,
                                                            Locale locale) {
        if (staff.getHospital() == null) {
            throw new BusinessRuleException(message("schedule.staff.hospital.mismatch", locale));
        }
        StaffAvailability availability = StaffAvailability.builder()
            .staff(staff)
            .hospital(staff.getHospital())
            .date(dto.shiftDate())
            .availableFrom(dto.startTime())
            .availableTo(dto.endTime())
            .dayOff(false)
            .build();
        StaffAvailability saved = staffAvailabilityRepository.save(availability);
        log.info("[schedule] auto-created availability staff={} date={} start={} end={}",
            staff.getId(), dto.shiftDate(), dto.startTime(), dto.endTime());
        return saved;
    }

    private void ensureNoLeaveConflict(StaffShiftRequestDTO dto, Staff staff, Locale locale) {
        List<StaffLeaveRequest> overlappingLeaves = leaveRepository.findLeavesOverlappingDate(
            staff.getId(), dto.shiftDate(), ACTIVE_LEAVE_STATUSES);
        for (StaffLeaveRequest leave : overlappingLeaves) {
            if (leave.getStartTime() == null || leave.getEndTime() == null) {
                // Full-day leave — any shift on that date conflicts
                throw new BusinessRuleException(message("schedule.shift.leaveConflict", locale));
            }
            // Convert both windows to minutes-since-midnight, expanding cross-midnight windows
            // to span into the next day (i.e. add 1440 minutes when endTime < startTime).
            int shiftStart  = toMinutes(dto.startTime());
            int shiftEnd    = isCrossMidnight(dto.startTime(), dto.endTime())
                ? toMinutes(dto.endTime()) + 1440
                : toMinutes(dto.endTime());
            int leaveStart  = toMinutes(leave.getStartTime());
            int leaveEnd    = isCrossMidnight(leave.getStartTime(), leave.getEndTime())
                ? toMinutes(leave.getEndTime()) + 1440
                : toMinutes(leave.getEndTime());

            boolean overlaps = shiftStart < leaveEnd && shiftEnd > leaveStart;
            if (overlaps) {
                throw new BusinessRuleException(message("schedule.shift.leaveConflict", locale));
            }
        }
    }

    /** @return true when {@code end} is strictly before {@code start} (cross-midnight window). */
    private static boolean isCrossMidnight(LocalTime start, LocalTime end) {
        return end.isBefore(start);
    }

    /** @return minutes since midnight for the given time. */
    private static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private void validateLeaveWindow(StaffLeaveRequestDTO dto,
                                     Staff staff,
                                     Locale locale,
                                     UUID excludeLeaveId) {
        if (dto.startDate().isBefore(LocalDate.now())) {
            throw new BusinessRuleException(message("schedule.leave.request.past", locale));
        }
        if (dto.endDate().isBefore(dto.startDate())) {
            throw new BusinessRuleException(message("schedule.leave.request.invalidRange", locale));
        }
        if ((dto.startTime() != null && dto.endTime() == null) || (dto.startTime() == null && dto.endTime() != null)) {
            throw new BusinessRuleException(message("schedule.leave.request.partial.invalid", locale));
        }
        if (dto.startTime() != null && !dto.endTime().isAfter(dto.startTime())) {
            throw new BusinessRuleException(message("schedule.leave.request.partial.range", locale));
        }
        if (leaveRepository.existsOverlappingLeave(staff.getId(), dto.startDate(), dto.endDate(), ACTIVE_LEAVE_STATUSES, excludeLeaveId)) {
            throw new BusinessRuleException(message("schedule.leave.overlap", locale));
        }
    }

    private boolean isDepartmentHeadOf(Staff actorStaff, Department department) {
        return department != null
            && department.getHeadOfDepartment() != null
            && Objects.equals(department.getHeadOfDepartment().getId(), actorStaff.getId());
    }

    private boolean headsTargetStaffDepartment(Staff actorStaff, Staff targetStaff) {
        Department targetDepartment = targetStaff.getDepartment();
        return isDepartmentHeadOf(actorStaff, targetDepartment);
    }

    private void cancelOverlappingShiftsForLeave(StaffLeaveRequest leave, Locale locale) {
        List<StaffShift> shifts = shiftRepository.findActiveShiftsBetween(
            leave.getStaff().getId(), leave.getStartDate(), leave.getEndDate());
        if (shifts.isEmpty()) {
            return;
        }
        User actor = getCurrentUser(locale);
        LocalDateTime now = LocalDateTime.now();
        for (StaffShift shift : shifts) {
            shift.setStatus(StaffShiftStatus.CANCELLED);
            shift.setCancellationReason(message("schedule.shift.cancelled.leave", locale));
            shift.setLastModifiedBy(actor);
            shift.setStatusChangedAt(now);
        }
        shiftRepository.saveAll(shifts);
    }

    private void ensureStaffBelongsToHospital(Staff staff, Hospital hospital, Locale locale) {
        if (staff.getHospital() == null || !Objects.equals(staff.getHospital().getId(), hospital.getId())) {
            throw new BusinessRuleException(message("schedule.staff.hospital.mismatch", locale));
        }
    }

    private void ensureStaffActive(Staff staff, Locale locale) {
        if (!staff.isActive()) {
            throw new BusinessRuleException(message("schedule.staff.inactive", locale));
        }
    }

    private Staff findStaff(UUID staffId, Locale locale) {
        return staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.staff.notFound", locale)));
    }

    private Hospital findHospital(UUID hospitalId, Locale locale) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.hospital.notFound", locale)));
    }

    private Department resolveDepartment(UUID departmentId,
                                         Staff staff,
                                         Hospital hospital,
                                         Locale locale) {
        if (departmentId == null) {
            return staff.getDepartment();
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.department.notFound", locale)));
        if (department.getHospital() == null || !Objects.equals(department.getHospital().getId(), hospital.getId())) {
            throw new BusinessRuleException(message("schedule.department.hospital.mismatch", locale));
        }
        if (staff.getDepartment() != null && !Objects.equals(staff.getDepartment().getId(), departmentId)) {
            throw new BusinessRuleException(message("schedule.staff.department.mismatch", locale));
        }
        return department;
    }

    private User getCurrentUser(Locale locale) {
        UUID currentUserId = requireCurrentUserId(locale);
        return userRepository.findById(currentUserId)
            .orElseThrow(() -> new ResourceNotFoundException(message("schedule.user.notFound", locale)));
    }

    private UUID requireCurrentUserId(Locale locale) {
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessRuleException(message("schedule.user.unauthenticated", locale));
        }
        return currentUserId;
    }

    private UUID requireCurrentHospitalId(Locale locale) {
        UUID hospitalId = roleValidator.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new BusinessRuleException(message("schedule.hospital.context.required", locale));
        }
        return hospitalId;
    }

    private Locale safeLocale(Locale locale) {
        return locale == null ? Locale.getDefault() : locale;
    }

    private String message(String code, Locale locale) {
        return messageSource.getMessage(code, null, code, locale);
    }

    private DateRange resolveDateRange(LocalDate start, LocalDate end, Locale locale) {
        LocalDate effectiveStart = start != null ? start : LocalDate.now();
        LocalDate effectiveEnd = end != null ? end : effectiveStart.plusDays(14);
        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new BusinessRuleException(message("schedule.dateRange.invalid", locale));
        }
        return new DateRange(effectiveStart, effectiveEnd);
    }

    private record DateRange(LocalDate start, LocalDate end) { }
}
