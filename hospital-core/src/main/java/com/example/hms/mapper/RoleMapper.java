package com.example.hms.mapper;

import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.payload.dto.PermissionResponseDTO;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RoleMapper {

    public Role toEntity(RoleRequestDTO dto) {
        if (dto == null) return null;
        Role role = new Role();
        role.setId(dto.getId());
        role.setName(dto.getName());
        role.setCode(dto.getCode());
        role.setDescription(dto.getDescription());
        return role;
    }

    public RoleResponseDTO toResponse(Role role) {
        if (role == null) return null;
        return RoleResponseDTO.builder()
            .id(role.getId())
            .name(role.getName())
            .authority(role.getCode())
            .description(role.getDescription())
            .code(role.getCode())
            .createdAt(role.getCreatedAt())
            .updatedAt(role.getUpdatedAt())
            .permissions(role.getPermissions() == null ? Set.of() : role.getPermissions().stream()
                .filter(Objects::nonNull)
                .map(this::toPermissionResponse)
                .collect(Collectors.toSet()))
            .build();
    }

    private PermissionResponseDTO toPermissionResponse(Permission p) {
        if (p == null) return null;
        return PermissionResponseDTO.builder()
            .id(p.getId())
            .name(p.getName())
            .code(p.getCode())
            .assignmentId(p.getAssignment() != null ? p.getAssignment().getId() : null)
            .assignmentName(p.getAssignment() != null && p.getAssignment().getHospital() != null ? p.getAssignment().getHospital().getName() : null)
            .assignmentType(p.getAssignment() != null && p.getAssignment().getRole() != null ? p.getAssignment().getRole().getName() : null)
            .assignmentDescription(null)
            .build();
    }
}
