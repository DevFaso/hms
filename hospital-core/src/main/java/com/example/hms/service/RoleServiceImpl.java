package com.example.hms.service;

import com.example.hms.enums.RoleBlueprint;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.RoleMapper;
import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.payload.dto.RoleBlueprintDTO;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RoleMapper roleMapper;

    private static final String ROLE_NOT_FOUND = "role.notFound";
    private static final String ROLE_TEMPLATE_NOT_FOUND = "role.template.notFound";
    private static final String PERMISSION_NOT_FOUND = "permission.notFound";

    @Override
    @Transactional
    public RoleResponseDTO create(RoleRequestDTO request) {
        String normalizedName = normalizeName(request.getName());
        String normalizedCode = normalizeCode(request.getCode(), normalizedName);
        String normalizedDescription = normalizeDescription(request.getDescription());

        validateFields(normalizedName, normalizedCode);
        checkDuplicate(normalizedName, normalizedCode, null);

        Role role = roleMapper.toEntity(request);
        role.setName(normalizedName);
        role.setCode(normalizedCode);
        role.setDescription(normalizedDescription);
        applyPermissionsFromRequest(role, request, true);
        Role saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public RoleResponseDTO update(UUID id, RoleRequestDTO request) {
        Role role = roleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_NOT_FOUND));
        String normalizedName = normalizeName(request.getName());
        String normalizedCode = normalizeCode(request.getCode(), normalizedName);
        String normalizedDescription = normalizeDescription(request.getDescription());

        validateFields(normalizedName, normalizedCode);
        checkDuplicate(normalizedName, normalizedCode, id);

        role.setName(normalizedName);
        role.setCode(normalizedCode);
        role.setDescription(normalizedDescription);
        applyPermissionsFromRequest(role, request, false);
        Role saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!roleRepository.existsById(id)) {
            throw new ResourceNotFoundException(ROLE_NOT_FOUND);
        }
        roleRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public RoleResponseDTO getById(UUID id) {
        return roleRepository.findByIdWithPermissions(id)
            .map(roleMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponseDTO> list() {
        return roleRepository.findAllWithPermissions().stream()
            .map(roleMapper::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public RoleResponseDTO assignPermissions(UUID roleId, Set<UUID> permissionIds) {
        Role role = roleRepository.findById(roleId)
            .orElseThrow(() -> new ResourceNotFoundException(ROLE_NOT_FOUND));
        if (permissionIds == null) permissionIds = Set.of();
        List<Permission> permissions = permissionRepository.findAllById(permissionIds);
        replacePermissions(role, new LinkedHashSet<>(permissions));
        Role saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleBlueprintDTO> listBlueprints() {
        return Arrays.stream(RoleBlueprint.values())
            .map(bp -> RoleBlueprintDTO.builder()
                .key(bp.name())
                .displayName(bp.getDisplayName())
                .description(bp.getDescription())
                .defaultPermissionCodes(bp.getPermissionCodes())
                .permissionCount(bp.getPermissionCodes().size())
                .build())
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportMatrixCsv() {
        List<Role> roles = new ArrayList<>(roleRepository.findAll());
        roles.sort(Comparator.comparing(Role::getCode, Comparator.nullsLast(String::compareTo))
            .thenComparing(Role::getName, Comparator.nullsLast(String::compareTo)));

        StringBuilder csv = new StringBuilder();
        csv.append("role_code,role_name,permission_code,permission_name\n");

        for (Role role : roles) {
            Set<Permission> permissions = Optional.ofNullable(role.getPermissions()).orElse(Set.of());
            if (permissions.isEmpty()) {
                csv.append(escapeCsv(role.getCode())).append(',')
                    .append(escapeCsv(role.getName())).append(',')
                    .append("").append(',')
                    .append("").append('\n');
                continue;
            }
            permissions.stream()
                .sorted(Comparator.comparing(Permission::getCode, Comparator.nullsLast(String::compareTo)))
                .forEach(permission -> csv.append(escapeCsv(role.getCode())).append(',')
                    .append(escapeCsv(role.getName())).append(',')
                    .append(escapeCsv(permission.getCode())).append(',')
                    .append(escapeCsv(permission.getName()))
                    .append('\n'));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void validateFields(String name, String code) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("role.name.blank");
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException("role.code.blank");
        }
    }

    private void checkDuplicate(String name, String code, UUID currentId) {
        roleRepository.findByCode(code).ifPresent(existing -> {
            if (currentId == null || !existing.getId().equals(currentId)) {
                throw new BusinessException("role.duplicate");
            }
        });

        roleRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (currentId == null || !existing.getId().equals(currentId)) {
                throw new BusinessException("role.duplicate");
            }
        });
    }

    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    private String normalizeCode(String code, String fallback) {
        String source = (code == null || code.isBlank()) ? fallback : code;
        if (source == null) {
            return null;
        }
        String canonical = source.trim().toUpperCase()
            .replaceAll("\\s+", "_")
            .replaceAll("[^A-Z0-9_]+", "_")
            .replaceAll("_+", "_");
        canonical = canonical.replaceAll("^_", "").replaceAll("_$", "");
        return canonical;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyPermissionsFromRequest(Role role, RoleRequestDTO request, boolean isCreate) {
        if (!hasPermissionPayload(request) && !isCreate) {
            return;
        }

        Set<Permission> resolved = resolvePermissions(request);
        replacePermissions(role, resolved);
    }

    private boolean hasPermissionPayload(RoleRequestDTO request) {
        return request.getPermissionIds() != null
            || request.getTemplateRoleId() != null
            || request.getBlueprint() != null;
    }

    private Set<Permission> resolvePermissions(RoleRequestDTO request) {
        Set<Permission> permissions = new LinkedHashSet<>();

        if (request.getTemplateRoleId() != null) {
            Role template = roleRepository.findById(request.getTemplateRoleId())
                .orElseThrow(() -> new ResourceNotFoundException(ROLE_TEMPLATE_NOT_FOUND));
            permissions.addAll(Optional.ofNullable(template.getPermissions()).orElse(Set.of()));
        }

        if (request.getBlueprint() != null) {
            RoleBlueprint blueprint = request.getBlueprint();
            Set<String> blueprintCodes = blueprint.getPermissionCodes();
            if (!blueprintCodes.isEmpty()) {
                List<Permission> blueprintPermissions = permissionRepository.findByCodeIn(blueprintCodes);
                Set<String> foundCodes = blueprintPermissions.stream()
                    .map(Permission::getCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                Set<String> missing = new LinkedHashSet<>(blueprintCodes);
                missing.removeAll(foundCodes);
                if (!missing.isEmpty()) {
                    log.warn("Missing permissions for blueprint {}: {}", blueprint.name(), missing);
                }
                blueprintPermissions.forEach(permissions::add);
            }
        }

        if (request.getPermissionIds() != null) {
            Set<UUID> ids = request.getPermissionIds();
            if (!ids.isEmpty()) {
                List<Permission> direct = permissionRepository.findAllById(ids);
                Set<UUID> found = direct.stream().map(Permission::getId).collect(Collectors.toSet());
                Set<UUID> missingIds = new LinkedHashSet<>(ids);
                missingIds.removeAll(found);
                if (!missingIds.isEmpty()) {
                    throw new ResourceNotFoundException(PERMISSION_NOT_FOUND);
                }
                direct.forEach(permissions::add);
            }
        }

        return permissions;
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\r")) {
            return '"' + escaped + '"';
        }
        return escaped;
    }

    private void replacePermissions(Role role, Set<Permission> permissions) {
        Set<Permission> existing = new LinkedHashSet<>(Optional.ofNullable(role.getPermissions()).orElse(Set.of()));
        existing.forEach(role::removePermission);
        permissions.forEach(role::addPermission);
    }
}
