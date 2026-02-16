package com.example.hms.mapper;

import com.example.hms.model.Permission;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PermissionMinimalDTO;
import com.example.hms.payload.dto.PermissionRequestDTO;
import com.example.hms.payload.dto.PermissionResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PermissionMapper {

    public PermissionResponseDTO toResponseDTO(Permission permission) {
        if (permission == null) return null;

        UserRoleHospitalAssignment assignment = permission.getAssignment();

        return PermissionResponseDTO.builder()
            .id(permission.getId())
            .name(permission.getName())
            .assignmentId(assignment != null ? assignment.getId() : null)
            .assignmentName(assignment != null ? buildAssignmentName(assignment) : null)
            .assignmentType(assignment != null ? assignment.getRole().getName() : null)
            .assignmentDescription(assignment != null ? assignment.getDescription() : null)
            .build();
    }

    public Permission toEntity(PermissionRequestDTO dto) {
        if (dto == null) return null;

        return Permission.builder()
            .name(dto.getName())
            .build();
    }

    public PermissionMinimalDTO toMinimalDTO(Permission permission) {
        if (permission == null) return null;

        return new PermissionMinimalDTO(
            permission.getId(),
            permission.getName()
        );
    }

    // Helper to generate a descriptive name (optional customization)
    private String buildAssignmentName(UserRoleHospitalAssignment assignment) {
        String hospital = assignment.getHospital() != null ? assignment.getHospital().getName() : "N/A";
        String role = assignment.getRole() != null ? assignment.getRole().getName() : "N/A";
        return role + " @ " + hospital;
    }
}
