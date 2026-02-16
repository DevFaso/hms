package com.example.hms.service;

import com.example.hms.payload.dto.RoleBlueprintDTO;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RoleService {
    RoleResponseDTO create(RoleRequestDTO request);
    RoleResponseDTO update(UUID id, RoleRequestDTO request);
    void delete(UUID id);
    RoleResponseDTO getById(UUID id);
    List<RoleResponseDTO> list();
    RoleResponseDTO assignPermissions(UUID roleId, Set<UUID> permissionIds);
    List<RoleBlueprintDTO> listBlueprints();
    byte[] exportMatrixCsv();
}
