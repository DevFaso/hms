package com.example.hms.service;

import com.example.hms.enums.RoleBlueprint;
import com.example.hms.mapper.RoleMapper;
import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.payload.dto.RoleRequestDTO;
import com.example.hms.payload.dto.RoleResponseDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleServiceImpl(roleRepository, permissionRepository, new RoleMapper(), assignmentRepository);
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

    private Role storedRole(String code) {
        Role role = Role.builder().name(code).code(code).permissions(new LinkedHashSet<>()).build();
        role.setId(UUID.randomUUID());
        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
        return role;
    }

    @Test
    void aBuiltInRolesCodeIsNeverChanged() {
        // QA D902 (#968 review): a new code stripped the role from every holder.
        Role superAdmin = storedRole("ROLE_SUPER_ADMIN");
        RoleRequestDTO request = RoleRequestDTO.builder().name("ROLE_SUPER_ADMIN").code("ROLE_OWNER").build();
        UUID id = superAdmin.getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(id, request))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(superAdmin.getCode()).isEqualTo("ROLE_SUPER_ADMIN");
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    void aBlankCodeOnABuiltInRoleKeepsItsCodeInsteadOfDerivingOneFromTheName() {
        Role doctor = storedRole("ROLE_DOCTOR");
        when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(doctor));
        when(roleRepository.findByNameIgnoreCase("Medecin")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0, Role.class));

        RoleResponseDTO response = service.update(doctor.getId(),
            RoleRequestDTO.builder().name("Medecin").code(" ").build());

        assertThat(response.getCode()).isEqualTo("ROLE_DOCTOR");
        assertThat(response.isBuiltIn()).isTrue();
        assertThat(response.getName()).isEqualTo("Medecin");
    }

    @Test
    void noCustomRoleTakesABuiltInCode() {
        Role custom = storedRole("CLINICAL_ADMIN");
        RoleRequestDTO request = RoleRequestDTO.builder().name("Clinical Admin").code("SUPER_ADMIN").build();
        UUID id = custom.getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(id, request))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(custom.getCode()).isEqualTo("CLINICAL_ADMIN");
    }

    @Test
    void aCustomRolesCodeStaysEditable() {
        Role custom = storedRole("CLINICAL_ADMIN");
        when(roleRepository.findByCode("CLINICAL_LEAD")).thenReturn(Optional.empty());
        when(roleRepository.findByNameIgnoreCase("Clinical Lead")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0, Role.class));

        RoleResponseDTO response = service.update(custom.getId(),
            RoleRequestDTO.builder().name("Clinical Lead").code("clinical lead").build());

        assertThat(response.getCode()).isEqualTo("CLINICAL_LEAD");
        assertThat(response.isBuiltIn()).isFalse();
    }

    @Test
    void noNewRoleTakesABuiltInCode() {
        // #968 review: a custom SUPER_ADMIN row, once assigned, counted as
        // "another super-admin remains". The legacy spelling and a code given
        // in lower case with a space are normalised before the check.
        for (String code : List.of("SUPER_ADMIN", "role physician", "role lab manager")) {
            RoleRequestDTO request = RoleRequestDTO.builder().name("Custom " + code).code(code).build();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        }
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    void aBuiltInRoleIsNeverDeleted() {
        // QA D902 (#968 review): DELETE /roles/{id} removed the super-admin
        // role and left every super-admin assignment pointing at nothing.
        Role superAdmin = storedRole("ROLE_SUPER_ADMIN");
        UUID id = superAdmin.getId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(roleRepository, never()).delete(any(Role.class));
        verify(roleRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void aCustomRoleStillAssignedIsNotDeleted() {
        Role custom = storedRole("CLINICAL_ADMIN");
        UUID id = custom.getId();
        when(assignmentRepository.existsByRoleId(id)).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(id))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(roleRepository, never()).delete(any(Role.class));
    }

    @Test
    void anUnusedCustomRoleIsDeleted() {
        Role custom = storedRole("CLINICAL_ADMIN");
        when(assignmentRepository.existsByRoleId(custom.getId())).thenReturn(false);

        service.delete(custom.getId());

        verify(roleRepository).delete(custom);
    }
}
