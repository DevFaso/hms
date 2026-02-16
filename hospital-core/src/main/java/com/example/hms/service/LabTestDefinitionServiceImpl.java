package com.example.hms.service;

import com.example.hms.mapper.LabTestDefinitionMapper;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import com.example.hms.payload.dto.LabTestDefinitionResponseDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.SecurityUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LabTestDefinitionServiceImpl implements LabTestDefinitionService {

    private static final String LAB_TEST_DEFINITION_NOT_FOUND = "Lab Test Definition not found";
    private static final String CURRENT_USER_RESOLUTION_ERROR = "Unable to resolve current user context";
    private static final String ASSIGNMENT_NOT_FOUND = "Assignment not found";

    private final LabTestDefinitionRepository repository;
    private final LabTestDefinitionMapper mapper;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    @Override
    public LabTestDefinitionResponseDTO create(LabTestDefinitionRequestDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test creation");
        }

        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        UserRoleHospitalAssignment assignment = null;
        if (dto.getAssignmentId() != null) {
            assignment = assignmentRepository.findById(dto.getAssignmentId())
                .orElseThrow(() -> new EntityNotFoundException(ASSIGNMENT_NOT_FOUND));
            assertUserCanManageHospital(currentUser, assignment);
        } else {
            assertUserCanManageGlobal(currentUser);
        }

        LabTestDefinition entity = mapper.toEntity(dto, assignment);
        entity.setHospital(null);
        return mapper.toDto(repository.save(entity));
    }

    @Override
    public LabTestDefinitionResponseDTO getById(UUID id) {
        return mapper.toDto(repository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND)));
    }

    @Override
    public List<LabTestDefinitionResponseDTO> getAll() {
        return repository.findAll()
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    public List<LabTestDefinitionResponseDTO> getActiveByHospital(UUID hospitalId) {
        List<LabTestDefinition> globalDefinitions = repository.findByHospitalIsNullAndActiveTrue();
        List<LabTestDefinition> hospitalDefinitions = hospitalId != null
            ? repository.findByHospital_IdAndActiveTrue(hospitalId)
            : List.of();

        Map<String, LabTestDefinition> deduplicated = new LinkedHashMap<>();
        hospitalDefinitions.forEach(def -> deduplicated.put(def.getTestCode(), def));
        globalDefinitions.forEach(def -> deduplicated.putIfAbsent(def.getTestCode(), def));

        return deduplicated.values().stream()
                .map(mapper::toDto)
                .toList();
    }

    @Override
    public LabTestDefinitionResponseDTO update(UUID id, LabTestDefinitionRequestDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test update");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition existing = repository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));

        if (isGlobalDefinition(existing)) {
            assertUserCanManageGlobal(currentUser);
        } else {
            assertUserCanManageHospital(currentUser, existing.getAssignment());
        }

        if (dto.getAssignmentId() != null) {
            if (existing.getAssignment() == null
                || !dto.getAssignmentId().equals(existing.getAssignment().getId())) {
                UserRoleHospitalAssignment assignment = assignmentRepository.findById(dto.getAssignmentId())
                    .orElseThrow(() -> new EntityNotFoundException(ASSIGNMENT_NOT_FOUND));
                assertUserCanManageHospital(currentUser, assignment);
                existing.setAssignment(assignment);
            }
        } else if (existing.getAssignment() != null) {
            assertUserCanManageGlobal(currentUser);
            existing.setAssignment(null);
            existing.setHospital(null);
        }
        existing.setHospital(null);
        mapper.updateEntityFromDto(dto, existing);
        return mapper.toDto(repository.save(existing));
    }

    @Override
    public void delete(UUID id) {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated access to lab test deletion");
        }
        User currentUser = userRepository.findByUsername(username)
            .orElseThrow(() -> new AccessDeniedException(CURRENT_USER_RESOLUTION_ERROR));

        LabTestDefinition existing = repository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException(LAB_TEST_DEFINITION_NOT_FOUND));
        if (isGlobalDefinition(existing)) {
            assertUserCanManageGlobal(currentUser);
        } else {
            assertUserCanManageHospital(currentUser, existing.getAssignment());
        }
        Objects.requireNonNull(existing, "Lab Test Definition reference cannot be null");
        repository.delete(existing);
    }

    @Override
    public Page<LabTestDefinitionResponseDTO> search(String keyword, String unit, String category, Boolean active, Pageable pageable) {
        Pageable normalizedPageable = normalizePageable(pageable);
        return repository.search(keyword, unit, category, active, normalizedPageable)
                .map(mapper::toDto);
    }

    private Pageable normalizePageable(Pageable pageable) {
        if (pageable == null) {
            return Pageable.unpaged();
        }

        if (pageable.isUnpaged()) {
            return pageable;
        }

        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> mappedOrders = sort.stream()
            .map(order -> {
                String property = order.getProperty();
                String mappedProperty = mapSortProperty(property);
                if (mappedProperty.equals(property)) {
                    return order;
                }

                Sort.Order mapped = new Sort.Order(order.getDirection(), mappedProperty)
                    .with(order.getNullHandling());
                if (order.isIgnoreCase()) {
                    mapped = mapped.ignoreCase();
                }
                return mapped;
            })
            .toList();

        if (mappedOrders.isEmpty()) {
            return pageable;
        }

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(mappedOrders));
    }

    private String mapSortProperty(String property) {
        if (property == null) {
            return "name";
        }
        return switch (property.toLowerCase()) {
            case "testname" -> "name";
            case "isactive" -> "active";
            default -> property;
        };
    }

    private void assertUserCanManageHospital(User currentUser, UserRoleHospitalAssignment assignment) {
        if (assignment == null || assignment.getHospital() == null) {
            throw new IllegalStateException("Assignment is not linked to a hospital");
        }

        if (assignment.getUser() != null && assignment.getUser().getId().equals(currentUser.getId())) {
            return;
        }

        boolean hasElevatedRole = assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            currentUser.getId(),
            assignment.getHospital().getId(),
            Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_LAB_MANAGER", "ROLE_SUPER_ADMIN", "ROLE_LAB_SCIENTIST")
        );

        if (!hasElevatedRole) {
            throw new AccessDeniedException("Unauthorized to manage lab tests for this hospital.");
        }
    }

    private void assertUserCanManageGlobal(User currentUser) {
        if (!hasAnyRole(currentUser, Set.of("ROLE_SUPER_ADMIN", "SUPER_ADMIN"))) {
            throw new AccessDeniedException("Only Super Admins may manage global lab test definitions.");
        }
    }

    private boolean hasAnyRole(User user, Set<String> targetRoles) {
        if (user == null || user.getUserRoles() == null || targetRoles == null || targetRoles.isEmpty()) {
            return false;
        }

        return user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(Objects::nonNull)
            .anyMatch(role -> matchesRole(role.getCode(), targetRoles) || matchesRole(role.getName(), targetRoles));
    }

    private boolean matchesRole(String value, Set<String> targets) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase();
        return targets.stream().anyMatch(target -> target.equalsIgnoreCase(normalized));
    }

    private boolean isGlobalDefinition(LabTestDefinition definition) {
        return definition == null || definition.getHospital() == null;
    }
}
