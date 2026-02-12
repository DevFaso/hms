package com.example.hms.service;

import com.example.hms.enums.RoleBlueprint;
import com.example.hms.mapper.RoleMapper;
import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleServiceImpl(roleRepository, permissionRepository, new RoleMapper());
    }

    @Test
    void createWithBlueprintAndCustomPermissionsMergesAllSources() {
        UUID extraPermissionId = UUID.randomUUID();
        Permission blueprintPermission = Permission.builder()
            .code("PATIENT_READ")
            .name("Read patients")
            .roles(new LinkedHashSet<>())
            .build();
        blueprintPermission.setId(UUID.randomUUID());

        Permission customPermission = Permission.builder()
            .code("CUSTOM_MANAGE")
            .name("Custom manage")
            .roles(new LinkedHashSet<>())
            .build();
        customPermission.setId(extraPermissionId);

        when(permissionRepository.findByCodeIn(RoleBlueprint.CLINICAL_LEADERSHIP.getPermissionCodes()))
            .thenReturn(List.of(blueprintPermission));
        when(permissionRepository.findAllById(Set.of(extraPermissionId)))
            .thenReturn(List.of(customPermission));
        when(roleRepository.findByCode("CLINICAL_ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.findByNameIgnoreCase("Clinical Admin")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> {
            Role role = invocation.getArgument(0, Role.class);
            role.setId(UUID.randomUUID());
            return role;
        });

        RoleRequestDTO request = RoleRequestDTO.builder()
            .name("Clinical Admin")
            .code("CLINICAL_ADMIN")
            .description("Clinical admin who oversees operations")
            .blueprint(RoleBlueprint.CLINICAL_LEADERSHIP)
            .permissionIds(Set.of(extraPermissionId))
            .build();

        RoleResponseDTO response = service.create(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Clinical Admin");
        assertThat(response.getAuthority()).isEqualTo("CLINICAL_ADMIN");
        assertThat(response.getDescription()).contains("oversees operations");
        assertThat(response.getPermissions()).hasSize(2);
        assertThat(response.getPermissions())
            .extracting("code")
            .contains("PATIENT_READ", "CUSTOM_MANAGE");

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, atLeastOnce()).save(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getDescription()).isEqualTo("Clinical admin who oversees operations");
    }

    @Test
    void updateWithEmptyPermissionSetClearsAssignments() {
        UUID roleId = UUID.randomUUID();
        Permission existingPermission = Permission.builder()
            .code("LEGACY_CODE")
            .name("Legacy")
            .roles(new LinkedHashSet<>())
            .build();
        existingPermission.setId(UUID.randomUUID());

        Role persisted = Role.builder()
            .name("Legacy Role")
            .code("LEGACY_ROLE")
            .permissions(new LinkedHashSet<>(Set.of(existingPermission)))
            .build();
        persisted.setId(roleId);
        existingPermission.getRoles().add(persisted);

        when(roleRepository.findById(roleId)).thenReturn(Optional.of(persisted));
        when(roleRepository.findByCode("LEGACY_ROLE")).thenReturn(Optional.of(persisted));
        when(roleRepository.findByNameIgnoreCase("Legacy Role")).thenReturn(Optional.of(persisted));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleRequestDTO request = RoleRequestDTO.builder()
            .name("Legacy Role")
            .code("LEGACY_ROLE")
            .description("Legacy role without permissions")
            .permissionIds(Set.of())
            .build();

        RoleResponseDTO updated = service.update(roleId, request);

        assertThat(updated.getName()).isEqualTo("Legacy Role");
        assertThat(updated.getDescription()).isEqualTo("Legacy role without permissions");
        assertThat(updated.getPermissions()).isEmpty();
        assertThat(updated.getAuthority()).isEqualTo("LEGACY_ROLE");
        assertThat(persisted.getPermissions()).isEmpty();
        assertThat(persisted.getDescription()).isEqualTo("Legacy role without permissions");
    }

    @Test
    void exportMatrixCsvProducesHeaderAndRows() {
        Permission perm = Permission.builder()
            .code("AUDIT_LOG_VIEW")
            .name("Audit log view")
            .roles(new LinkedHashSet<>())
            .build();
        perm.setId(UUID.randomUUID());

        LinkedHashSet<Permission> permissions = new LinkedHashSet<>();
        permissions.add(perm);

        Role role = Role.builder()
            .code("SECURITY_OFFICER")
            .name("Security Officer")
            .permissions(permissions)
            .build();
        role.setId(UUID.randomUUID());

        when(roleRepository.findAll()).thenReturn(List.of(role));

        byte[] csv = service.exportMatrixCsv();
        String content = new String(csv, StandardCharsets.UTF_8);

        assertThat(content)
            .startsWith("role_code,role_name,permission_code,permission_name\n")
            .contains("SECURITY_OFFICER,Security Officer,AUDIT_LOG_VIEW,Audit log view");
    }

    @Test
    void listBlueprintsExposesAllDefinitions() {
        assertThat(service.listBlueprints()).hasSize(RoleBlueprint.values().length);
    }
}
