package com.example.hms.service.scheduling;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.scheduling.VisitType;
import com.example.hms.payload.dto.scheduling.VisitTypeRequestDTO;
import com.example.hms.payload.dto.scheduling.VisitTypeResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
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
public class VisitTypeServiceImpl implements VisitTypeService {

    private final VisitTypeRepository visitTypeRepository;
    private final DepartmentRepository departmentRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional(readOnly = true)
    public List<VisitTypeResponseDTO> list(boolean includeInactive) {
        UUID hospitalId = requireHospital();
        List<VisitType> rows = includeInactive
            ? visitTypeRepository.findByHospital_IdOrderByNameAsc(hospitalId)
            : visitTypeRepository.findByHospital_IdAndActiveTrueOrderByNameAsc(hospitalId);
        return rows.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public VisitTypeResponseDTO create(VisitTypeRequestDTO request) {
        UUID hospitalId = requireHospital();
        rejectDuplicateCode(hospitalId, request.getCode(), null);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));

        VisitType entity = VisitType.builder()
            .hospital(hospital)
            .department(resolveDepartment(request.getDepartmentId(), hospitalId))
            .code(request.getCode().trim())
            .name(request.getName().trim())
            .description(request.getDescription())
            .durationMinutes(request.getDurationMinutes())
            .patientBookable(Boolean.TRUE.equals(request.getPatientBookable()))
            .active(true)
            .build();
        return toDto(visitTypeRepository.save(entity));
    }

    @Override
    @Transactional
    public VisitTypeResponseDTO update(UUID id, VisitTypeRequestDTO request) {
        VisitType entity = loadScoped(id);
        rejectDuplicateCode(entity.getHospital().getId(), request.getCode(), id);

        entity.setDepartment(resolveDepartment(request.getDepartmentId(), entity.getHospital().getId()));
        entity.setCode(request.getCode().trim());
        entity.setName(request.getName().trim());
        entity.setDescription(request.getDescription());
        entity.setDurationMinutes(request.getDurationMinutes());
        if (request.getPatientBookable() != null) {
            entity.setPatientBookable(request.getPatientBookable());
        }
        return toDto(visitTypeRepository.save(entity));
    }

    @Override
    @Transactional
    public VisitTypeResponseDTO deactivate(UUID id) {
        VisitType entity = loadScoped(id);
        entity.setActive(false);
        return toDto(visitTypeRepository.save(entity));
    }

    @Override
    @Transactional
    public VisitTypeResponseDTO reactivate(UUID id) {
        VisitType entity = loadScoped(id);
        entity.setActive(true);
        return toDto(visitTypeRepository.save(entity));
    }

    /* ---------------- helpers ---------------- */

    /**
     * Uniqueness is per (hospital, code) — the same constraint the DB enforces
     * (uq_visit_type_code_hospital), refused here so the admin gets a message
     * instead of a constraint violation.
     */
    private void rejectDuplicateCode(UUID hospitalId, String code, UUID excludeId) {
        visitTypeRepository.findByHospital_IdAndCodeIgnoreCase(hospitalId, code.trim())
            .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
            .ifPresent(existing -> {
                throw new BusinessException(existing.isActive()
                    ? "A visit type with code '" + code.trim() + "' already exists."
                    : "A visit type with code '" + code.trim()
                        + "' exists but was retired; reactivate it instead of re-creating it.");
            });
    }

    private Department resolveDepartment(UUID departmentId, UUID hospitalId) {
        if (departmentId == null) {
            return null;
        }
        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Department not found with ID: " + departmentId));
        if (department.getHospital() == null
            || !hospitalId.equals(department.getHospital().getId())) {
            // Foreign departments read as absent — the module's 404 idiom.
            throw new ResourceNotFoundException("Department not found with ID: " + departmentId);
        }
        return department;
    }

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            // Same rule as the slot inventory: the catalog is inherently
            // per-hospital; a super-admin must scope to one to curate it.
            throw new BusinessException("An active hospital is required to manage visit types.");
        }
        return hospitalId;
    }

    private VisitType loadScoped(UUID id) {
        VisitType entity = visitTypeRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Visit type not found with ID: " + id));
        UUID hospitalId = requireHospital();
        if (!hospitalId.equals(entity.getHospital().getId())) {
            throw new ResourceNotFoundException("Visit type not found with ID: " + id);
        }
        return entity;
    }

    private VisitTypeResponseDTO toDto(VisitType entity) {
        return VisitTypeResponseDTO.builder()
            .id(entity.getId())
            .departmentId(entity.getDepartment() != null ? entity.getDepartment().getId() : null)
            .departmentName(entity.getDepartment() != null ? entity.getDepartment().getName() : null)
            .code(entity.getCode())
            .name(entity.getName())
            .description(entity.getDescription())
            .durationMinutes(entity.getDurationMinutes())
            .patientBookable(entity.isPatientBookable())
            .active(entity.isActive())
            .build();
    }
}
