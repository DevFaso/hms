package com.example.hms.service.impl;

import com.example.hms.enums.JobTitle;
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
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnCallScheduleServiceImpl implements OnCallScheduleService {

    private final OnCallScheduleRepository onCallRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;
    /**
     * The rota clock: "currently on call" and the default listing window.
     * {@code ClinicalDashboardServiceImpl.getOnCallStatus} asks the same
     * question ({@code findActiveByStaffIdAt}) with the same bean.
     */
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<OnCallScheduleResponseDTO> listForHospital(OffsetDateTime from, OffsetDateTime to) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("onCall.rota.hospitalRequired");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime start = from != null ? from : now.minusDays(1);
        OffsetDateTime end = to != null ? to : now.plusDays(7);
        if (end.isBefore(start)) {
            throw new BusinessException("onCall.rota.windowInverted");
        }
        return onCallRepository.findByHospitalAndWindow(hospitalId, start, end).stream()
            .map(entry -> toResponse(entry, now))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OnCallScheduleResponseDTO> listForStaff(UUID staffId) {
        requireStaffInTenant(staffId);
        OffsetDateTime now = OffsetDateTime.now(clock);
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

        return toResponse(onCallRepository.save(entry), OffsetDateTime.now(clock));
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

        return toResponse(onCallRepository.save(entry), OffsetDateTime.now(clock));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        onCallRepository.delete(loadScoped(id));
    }

    /* ---------------- helpers ---------------- */

    private OnCallSchedule loadScoped(UUID id) {
        OnCallSchedule entry = onCallRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("onCall.entry.notFound", id));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (entry.getStaff() == null || entry.getStaff().getHospital() == null
            || !hospitalId.equals(entry.getStaff().getHospital().getId()))) {
            // Cross-hospital rows read as absent, matching the house convention.
            throw new ResourceNotFoundException("onCall.entry.notFound", id);
        }
        return entry;
    }

    private Staff requireStaffInTenant(UUID staffId) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && (staff.getHospital() == null
            || !hospitalId.equals(staff.getHospital().getId()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Cannot roster a staff member from another hospital.");
        }
        // The rota is who answers a clinical page: a departed or deactivated
        // member, or a desk / back-office role, is not cover (QA D117).
        if (!staff.isActive() || staff.getUser() == null || !staff.getUser().isActive()
            || staff.getUser().isDeleted()) {
            throw new BusinessException(MessageUtil.resolve("onCall.staff.inactive"));
        }
        if (!ON_CALL_JOB_TITLES.contains(staff.getJobTitle())) {
            throw new BusinessException(MessageUtil.resolve("onCall.staff.notClinical"));
        }
        return staff;
    }

    /** Clinical titles that can carry an on-call page. */
    private static final Set<JobTitle> ON_CALL_JOB_TITLES = EnumSet.of(
        JobTitle.DOCTOR, JobTitle.PHYSICIAN, JobTitle.SURGEON, JobTitle.ANESTHESIOLOGIST,
        JobTitle.RADIOLOGIST, JobTitle.NURSE, JobTitle.NURSE_PRACTITIONER, JobTitle.MIDWIFE,
        JobTitle.PHARMACIST, JobTitle.LAB_TECHNICIAN, JobTitle.LAB_SCIENTIST,
        JobTitle.LABORATORY_SCIENTIST, JobTitle.LAB_DIRECTOR, JobTitle.TECHNICIAN,
        JobTitle.PHYSIOTHERAPIST, JobTitle.PSYCHOLOGIST);

    private Department resolveDepartment(UUID departmentId) {
        if (departmentId == null) {
            // Null is legitimate: a hospital-wide rota covers no single department.
            return null;
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("department.notFound", departmentId));
        // QA r5 tenancy sweep: another hospital's row answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(department.getHospital(), "department.notFound", departmentId);
        return department;
    }

    private void validateWindow(OnCallScheduleRequestDTO request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException("onCall.shift.endBeforeStart");
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
            throw new BusinessException("onCall.shift.overlap");
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
