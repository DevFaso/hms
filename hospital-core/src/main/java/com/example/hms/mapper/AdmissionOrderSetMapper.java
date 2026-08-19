package com.example.hms.mapper;

import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Hand-written DTO mapper for {@link AdmissionOrderSet} per
 * {@code .github/agents/hms-implement.agent.md} (no MapStruct).
 */
@Component
public class AdmissionOrderSetMapper {

    public AdmissionOrderSetResponseDTO toDto(AdmissionOrderSet entity) {
        if (entity == null) return null;
        AdmissionOrderSetResponseDTO dto = new AdmissionOrderSetResponseDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setAdmissionType(entity.getAdmissionType());

        if (entity.getDepartment() != null) {
            dto.setDepartmentId(entity.getDepartment().getId());
            dto.setDepartmentName(entity.getDepartment().getName());
        }
        if (entity.getHospital() != null) {
            dto.setHospitalId(entity.getHospital().getId());
            dto.setHospitalName(entity.getHospital().getName());
        }

        dto.setOrderItems(entity.getOrderItems());
        dto.setClinicalGuidelines(entity.getClinicalGuidelines());
        dto.setActive(entity.getActive());
        dto.setVersion(entity.getVersion());

        if (entity.getCreatedBy() != null) {
            dto.setCreatedById(entity.getCreatedBy().getId());
            dto.setCreatedByName(staffName(entity.getCreatedBy()));
        }
        if (entity.getLastModifiedBy() != null) {
            dto.setLastModifiedById(entity.getLastModifiedBy().getId());
            dto.setLastModifiedByName(staffName(entity.getLastModifiedBy()));
        }

        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setDeactivatedAt(entity.getDeactivatedAt());
        dto.setDeactivationReason(entity.getDeactivationReason());
        dto.setOrderCount(entity.getOrderCount());
        return dto;
    }

    private static String staffName(com.example.hms.model.Staff staff) {
        if (staff == null) return null;
        String fullName = staff.getFullName();
        return fullName == null || fullName.isBlank() ? staff.getName() : fullName;
    }
}
