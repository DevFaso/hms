package com.example.hms.service;

import com.example.hms.payload.dto.PermissionFilterDTO;
import com.example.hms.payload.dto.PermissionMinimalDTO;
import com.example.hms.payload.dto.PermissionRequestDTO;
import com.example.hms.payload.dto.PermissionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface PermissionService {

    PermissionResponseDTO createPermission(PermissionRequestDTO requestDTO, Locale locale);

    PermissionResponseDTO getPermissionById(UUID id, Locale locale);

    List<PermissionResponseDTO> getAllPermissions(Locale locale);

    PermissionResponseDTO updatePermission(UUID id, PermissionRequestDTO requestDTO, Locale locale);

    void deletePermission(UUID id, Locale locale);
    List<PermissionMinimalDTO> getMinimalPermissions();

    Page<PermissionResponseDTO> getPermissions(PermissionFilterDTO filter, Pageable pageable, Locale locale);

}
