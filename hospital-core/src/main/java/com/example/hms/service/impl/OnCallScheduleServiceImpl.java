package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.OnCallScheduleRequestDTO;
import com.example.hms.payload.dto.OnCallScheduleResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.OnCallScheduleService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnCallScheduleServiceImpl implements OnCallScheduleService {

    private final OnCallScheduleRepository onCallRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public List<OnCallScheduleResponseDTO> listForHospital(OffsetDateTime from, OffsetDateTime to) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to read the on-call rota.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = from != null ? from : now.minusDays(1);
        OffsetDateTime end = to != null ? to : now.plusDays(7);
        if (end.isBefore(start)) {
            throw new BusinessException("The rota window ends before it starts.");
        }
        return onCallRepository.findByHospitalAndWindow(hospitalId, start, end).stream()
            .map(entry -> toResponse(entry, now))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OnCallScheduleResponseDTO> listForStaff(UUID staffId) {
        requireStaffInTenant(staffId);
        OffsetDateTime now = OffsetDateTime.now();
        return onCallRepository.findByStaff_IdOrderByStartTimeDesc(staffId).stream()
            .map(entry -> toResponse(entry, now))
            .toList();
    }

    @Override
    @Transactional
    public OnCallScheduleResponseDTO create(OnCallScheduleRequestDTO request) {
        Staff staff = requireStaffInTenant(request.getStaffId());
        validateWindow(request);
        rejectOverlap(request, null);

        OnCallSchedule entry = new OnCallSchedule();
        entry.setStaff(staff);
        entry.setDepartment(resolveDepartment(request.getDepartmentId()));
        entry.setStartTime(request.getStartTime());
        entry.setEndTime(request.getEndTime());
        entry.setNotes(request.getNotes());

        return toResponse(onCallRepository.save(entry), OffsetDateTime.now());
    }

    @Override
    @Transactional
    public OnCallScheduleResponseDTO update(UUID id, OnCallScheduleRequestDTO request) {
        OnCallSchedule entry = loadScoped(id);
        Staff staff = requireStaffInTenant(request.getStaffId());
        validateWindow(request);
        rejectOverlap(request, id);

        entry.setStaff(staff);
        entry.setDepartment(resolveDepartment(request.getDepartmentId()));
        entry.setStartTime(request.getStartTime());
        entry.setEndTime(request.getEndTime());
        entry.setNotes(request.getNotes());

        return toResponse(onCallRepository.save(entry), OffsetDateTime.now());
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        onCallRepository.delete(loadScoped(id));
    }

    /* ---------------- helpers ---------------- */

    private OnCallSchedule loadScoped(UUID id) {
        OnCallSchedule entry = onCallRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("On-call entry not found with ID: " + id));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (entry.getStaff() == null || entry.getStaff().getHospital() == null
            || !hospitalId.equals(entry.getStaff().getHospital().getId()))) {
            // Cross-hospital rows read as absent, matching the house convention.
            throw new ResourceNotFoundException("On-call entry not found with ID: " + id);
        }
        return entry;
    }

    private Staff requireStaffInTenant(UUID staffId) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found with ID: " + staffId));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (staff.getHospital() == null
            || !hospitalId.equals(staff.getHospital().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Cannot roster a staff member from another hospital.");
        }
        return staff;
    }

    private Department resolveDepartment(UUID departmentId) {
        if (departmentId == null) {
            // Null is legitimate: a hospital-wide rota covers no single department.
            return null;
        }
        return departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found with ID: " + departmentId));
    }

    private void validateWindow(OnCallScheduleRequestDTO request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException("An on-call shift must end after it starts.");
        }
    }

    /**
     * Two overlapping entries for the same clinician mean two rotas each believe
     * they have cover, which is worse than one rota knowing it has none.
     */
    private void rejectOverlap(OnCallScheduleRequestDTO request, UUID excludeId) {
        List<OnCallSchedule> clashes = onCallRepository.findOverlapping(
            request.getStaffId(), request.getStartTime(), request.getEndTime(), excludeId);
        if (!clashes.isEmpty()) {
            throw new BusinessException(
                "This staff member is already on call over part of that window.");
        }
    }

    private OnCallScheduleResponseDTO toResponse(OnCallSchedule entry, OffsetDateTime now) {
        Staff staff = entry.getStaff();
        Department department = entry.getDepartment();
        boolean current = entry.getStartTime() != null && entry.getEndTime() != null
            && !entry.getStartTime().isAfter(now) && !entry.getEndTime().isBefore(now);

        return OnCallScheduleResponseDTO.builder()
            .id(entry.getId())
            .staffId(staff != null ? staff.getId() : null)
            .staffName(staff != null ? staff.getName() : null)
            .departmentId(department != null ? department.getId() : null)
            .departmentName(department != null ? department.getName() : null)
            .startTime(entry.getStartTime())
            .endTime(entry.getEndTime())
            .notes(entry.getNotes())
            .currentlyOnCall(current)
            .build();
    }
}
