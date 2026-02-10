package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PermissionMapper;
import com.example.hms.model.Permission;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.specification.PermissionSpecification;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;
    private final PermissionMapper permissionMapper;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    @Override
    @Transactional
    public PermissionResponseDTO createPermission(PermissionRequestDTO request, Locale locale) {
        boolean exists = permissionRepository
            .findAll()
            .stream()
            .anyMatch(p ->
                p.getName().equalsIgnoreCase(request.getName()) &&
                    p.getAssignment().getId().equals(request.getAssignmentId()));

        if (exists) {
            throw new BusinessException("A permission with this name already exists for the specified assignment.");
        }

        Permission permission = permissionMapper.toEntity(request);

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        permission.setAssignment(assignment);

        Permission saved = permissionRepository.save(permission);
        return permissionMapper.toResponseDTO(saved);
    }

    @Override
    public PermissionResponseDTO getPermissionById(UUID id, Locale locale) {
        return permissionRepository.findById(id)
            .map(permissionMapper::toResponseDTO)
            .orElseThrow(() -> new ResourceNotFoundException("permission.notFound"));
    }

    @Override
    public List<PermissionResponseDTO> getAllPermissions(Locale locale) {
        return permissionRepository.findAllWithAssignmentDetails()
            .stream()
            .map(permissionMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public PermissionResponseDTO updatePermission(UUID id, PermissionRequestDTO request, Locale locale) {
        Permission permission = permissionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("permission.notFound"));

        // Prevent name duplication for the same assignment
        boolean nameConflict = permissionRepository.findAll()
            .stream()
            .anyMatch(p ->
                !p.getId().equals(id) &&
                    p.getName().equalsIgnoreCase(request.getName()) &&
                    p.getAssignment().getId().equals(request.getAssignmentId()));

        if (nameConflict) {
            throw new BusinessException("Another permission with this name already exists for the specified assignment.");
        }

        permission.setName(request.getName());
        Permission updated = permissionRepository.save(permission);
        return permissionMapper.toResponseDTO(updated);
    }

    @Override
    @Transactional
    public void deletePermission(UUID id, Locale locale) {
        if (!permissionRepository.existsById(id)) {
            throw new ResourceNotFoundException("permission.notFound");
        }
        permissionRepository.deleteById(id);
    }

    @Override
    public List<PermissionMinimalDTO> getMinimalPermissions() {
        return permissionRepository.findAll()
            .stream()
            .map(permissionMapper::toMinimalDTO)
            .collect(Collectors.toList());
    }

    @Override
    public Page<PermissionResponseDTO> getPermissions(PermissionFilterDTO filter, Pageable pageable, Locale locale) {
        return permissionRepository
            .findAll(PermissionSpecification.build(filter), pageable)
            .map(permissionMapper::toResponseDTO);
    }
}
