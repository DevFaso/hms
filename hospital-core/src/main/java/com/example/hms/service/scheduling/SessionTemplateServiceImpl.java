package com.example.hms.service.scheduling;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.model.scheduling.VisitType;
import com.example.hms.payload.dto.scheduling.SessionTemplateRequestDTO;
import com.example.hms.payload.dto.scheduling.SessionTemplateResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import com.example.hms.repository.scheduling.VisitTypeRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionTemplateServiceImpl implements SessionTemplateService {

    private final SessionTemplateRepository templateRepository;
    private final VisitTypeRepository visitTypeRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public List<SessionTemplateResponseDTO> list(boolean includeInactive) {
        UUID hospitalId = requireHospital();
        List<SessionTemplate> rows = includeInactive
            ? templateRepository.findByHospital_IdOrderByDayOfWeekAscStartTimeAsc(hospitalId)
            : templateRepository.findByHospital_IdAndActiveTrue(hospitalId);
        return rows.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public SessionTemplateResponseDTO create(SessionTemplateRequestDTO request) {
        UUID hospitalId = requireHospital();
        validateWindow(request);

        Staff staff = resolveStaff(request.getStaffId(), hospitalId);
        SessionTemplate entity = SessionTemplate.builder()
            // The staff member is verified to belong to the active hospital,
            // so their hospital reference IS the active hospital.
            .hospital(staff.getHospital())
            .department(resolveDepartment(request.getDepartmentId(), hospitalId))
            .staff(staff)
            .visitType(resolveVisitType(request.getVisitTypeId(), hospitalId))
            .dayOfWeek(request.getDayOfWeek().shortValue())
            .startTime(request.getStartTime())
            .endTime(request.getEndTime())
            .slotMinutes(request.getSlotMinutes())
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .notes(request.getNotes())
            .active(true)
            .build();
        return toDto(templateRepository.save(entity));
    }

    @Override
    @Transactional
    public SessionTemplateResponseDTO update(UUID id, SessionTemplateRequestDTO request) {
        SessionTemplate entity = loadScoped(id);
        validateWindow(request);
        UUID hospitalId = entity.getHospital().getId();

        entity.setStaff(resolveStaff(request.getStaffId(), hospitalId));
        entity.setDepartment(resolveDepartment(request.getDepartmentId(), hospitalId));
        entity.setVisitType(resolveVisitType(request.getVisitTypeId(), hospitalId));
        entity.setDayOfWeek(request.getDayOfWeek().shortValue());
        entity.setStartTime(request.getStartTime());
        entity.setEndTime(request.getEndTime());
        entity.setSlotMinutes(request.getSlotMinutes());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setEffectiveTo(request.getEffectiveTo());
        entity.setNotes(request.getNotes());
        // Editing a template only changes what future generation produces —
        // slots already materialised for past dates keep their history, and
        // already-generated future slots are left for the admin to block or
        // regenerate deliberately (the entity javadoc's editing rule).
        return toDto(templateRepository.save(entity));
    }

    @Override
    @Transactional
    public SessionTemplateResponseDTO deactivate(UUID id) {
        SessionTemplate entity = loadScoped(id);
        entity.setActive(false);
        return toDto(templateRepository.save(entity));
    }

    @Override
    @Transactional
    public SessionTemplateResponseDTO reactivate(UUID id) {
        SessionTemplate entity = loadScoped(id);
        entity.setActive(true);
        return toDto(templateRepository.save(entity));
    }

    /* ---------------- helpers ---------------- */

    private void validateWindow(SessionTemplateRequestDTO request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException("A session must end after it starts.");
        }
        if (request.getStartTime().plusMinutes(request.getSlotMinutes())
                .isAfter(request.getEndTime())) {
            // A template whose window cannot fit even one slot would generate
            // nothing, silently — the exact failure mode this feature closes.
            throw new BusinessException(
                "The session window is shorter than one slot; nothing would ever be generated.");
        }
        if (request.getEffectiveTo() != null
            && request.getEffectiveTo().isBefore(request.getEffectiveFrom())) {
            throw new BusinessException("The template cannot end before it takes effect.");
        }
    }

    private Staff resolveStaff(UUID staffId, UUID hospitalId) {
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found with ID: " + staffId));
        if (staff.getHospital() == null || !hospitalId.equals(staff.getHospital().getId())) {
            // Foreign staff read as absent — the module's 404 idiom.
            throw new ResourceNotFoundException("Staff not found with ID: " + staffId);
        }
        return staff;
    }

    private Department resolveDepartment(UUID departmentId, UUID hospitalId) {
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Department not found with ID: " + departmentId));
        if (department.getHospital() == null
            || !hospitalId.equals(department.getHospital().getId())) {
            throw new ResourceNotFoundException("Department not found with ID: " + departmentId);
        }
        return department;
    }

    private VisitType resolveVisitType(UUID visitTypeId, UUID hospitalId) {
        if (visitTypeId == null) {
            return null;
        }
        VisitType visitType = visitTypeRepository.findById(visitTypeId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Visit type not found with ID: " + visitTypeId));
        if (!hospitalId.equals(visitType.getHospital().getId())) {
            throw new ResourceNotFoundException("Visit type not found with ID: " + visitTypeId);
        }
        if (!visitType.isActive()) {
            throw new BusinessException(
                "That visit type is retired; reactivate it before scheduling sessions with it.");
        }
        return visitType;
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to manage session templates.");
        }
        return hospitalId;
    }

    private SessionTemplate loadScoped(UUID id) {
        SessionTemplate entity = templateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Session template not found with ID: " + id));
        UUID hospitalId = requireHospital();
        if (!hospitalId.equals(entity.getHospital().getId())) {
            throw new ResourceNotFoundException("Session template not found with ID: " + id);
        }
        return entity;
    }

    private SessionTemplateResponseDTO toDto(SessionTemplate entity) {
        VisitType visitType = entity.getVisitType();
        return SessionTemplateResponseDTO.builder()
            .id(entity.getId())
            .staffId(entity.getStaff().getId())
            .staffName(entity.getStaff().getName())
            .departmentId(entity.getDepartment().getId())
            .departmentName(entity.getDepartment().getName())
            .visitTypeId(visitType != null ? visitType.getId() : null)
            .visitTypeName(visitType != null ? visitType.getName() : null)
            .dayOfWeek(entity.getDayOfWeek())
            .startTime(entity.getStartTime())
            .endTime(entity.getEndTime())
            .slotMinutes(entity.getSlotMinutes())
            .effectiveFrom(entity.getEffectiveFrom())
            .effectiveTo(entity.getEffectiveTo())
            .active(entity.isActive())
            .notes(entity.getNotes())
            .build();
    }
}
