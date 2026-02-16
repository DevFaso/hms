package com.example.hms.service;

import com.example.hms.mapper.LabTestDefinitionMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Role;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabTestDefinitionServiceImplTest {

    @Mock private LabTestDefinitionRepository repository;
    @Mock private LabTestDefinitionMapper mapper;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private LabTestDefinitionServiceImpl service;

    private UUID defId, assignmentUUID, hospitalId;
    private LabTestDefinition definition;
    private LabTestDefinitionRequestDTO requestDTO;
    private LabTestDefinitionResponseDTO responseDTO;
    private User currentUser;
    private Hospital hospital;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        defId = UUID.randomUUID();
        assignmentUUID = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentUUID);
        assignment.setHospital(hospital);

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        currentUser.setUsername("admin");

        definition = LabTestDefinition.builder()
                .testCode("CBC")
                .name("Complete Blood Count")
                .category("Hematology")
                .active(true)
                .build();
        definition.setId(defId);

        requestDTO = LabTestDefinitionRequestDTO.builder()
                .testCode("CBC")
                .name("Complete Blood Count")
                .category("Hematology")
                .active(true)
                .assignmentId(assignmentUUID)
                .build();

        responseDTO = LabTestDefinitionResponseDTO.builder()
                .id(defId)
                .testCode("CBC")
                .name("Complete Blood Count")
                .active(true)
                .build();
    }

    // ---- create ----

    @Test
    void create_withAssignment_success() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(assignmentRepository.findById(assignmentUUID)).thenReturn(Optional.of(assignment));
            assignment.setUser(currentUser);
            when(mapper.toEntity(requestDTO, assignment)).thenReturn(definition);
            when(repository.save(definition)).thenReturn(definition);
            when(mapper.toDto(definition)).thenReturn(responseDTO);

            LabTestDefinitionResponseDTO result = service.create(requestDTO);

            assertThat(result).isNotNull();
            assertThat(result.getTestCode()).isEqualTo("CBC");
            verify(repository).save(definition);
        }
    }

    @Test
    void create_noAssignment_globalAdmin_success() {
        requestDTO.setAssignmentId(null);
        Role superRole = Role.builder().code("ROLE_SUPER_ADMIN").name("Super Admin").build();
        UserRole ur = UserRole.builder().role(superRole).build();
        currentUser.getUserRoles().add(ur);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(mapper.toEntity(requestDTO, null)).thenReturn(definition);
            when(repository.save(definition)).thenReturn(definition);
            when(mapper.toDto(definition)).thenReturn(responseDTO);

            LabTestDefinitionResponseDTO result = service.create(requestDTO);
            assertThat(result).isNotNull();
        }
    }

    @Test
    void create_unauthenticated_throwsAccessDenied() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn(null);

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unauthenticated");
        }
    }

    @Test
    void create_userNotFound_throwsAccessDenied() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("unknown");
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unable to resolve");
        }
    }

    @Test
    void create_assignmentNotFound_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(assignmentRepository.findById(assignmentUUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Assignment not found");
        }
    }

    @Test
    void create_noGlobalRole_throwsAccessDenied() {
        requestDTO.setAssignmentId(null);
        // currentUser has no roles
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Super Admins");
        }
    }

    // ---- getById ----

    @Test
    void getById_success() {
        when(repository.findById(defId)).thenReturn(Optional.of(definition));
        when(mapper.toDto(definition)).thenReturn(responseDTO);

        LabTestDefinitionResponseDTO result = service.getById(defId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(defId);
    }

    @Test
    void getById_notFound_throws() {
        when(repository.findById(defId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(defId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- getAll ----

    @Test
    void getAll_success() {
        when(repository.findAll()).thenReturn(List.of(definition));
        when(mapper.toDto(definition)).thenReturn(responseDTO);

        List<LabTestDefinitionResponseDTO> result = service.getAll();

        assertThat(result).hasSize(1);
    }

    // ---- getActiveByHospital ----

    @Test
    void getActiveByHospital_withHospitalId_mergesResults() {
        LabTestDefinition globalDef = LabTestDefinition.builder()
                .testCode("GLOB")
                .name("Global Test")
                .active(true)
                .build();
        globalDef.setId(UUID.randomUUID());

        LabTestDefinition hospitalDef = LabTestDefinition.builder()
                .testCode("CBC")
                .name("Hospital CBC")
                .active(true)
                .build();
        hospitalDef.setId(UUID.randomUUID());

        LabTestDefinitionResponseDTO globalResp = LabTestDefinitionResponseDTO.builder()
                .id(globalDef.getId())
                .testCode("GLOB")
                .build();
        LabTestDefinitionResponseDTO hospitalResp = LabTestDefinitionResponseDTO.builder()
                .id(hospitalDef.getId())
                .testCode("CBC")
                .build();

        when(repository.findByHospitalIsNullAndActiveTrue()).thenReturn(List.of(globalDef));
        when(repository.findByHospital_IdAndActiveTrue(hospitalId)).thenReturn(List.of(hospitalDef));
        when(mapper.toDto(hospitalDef)).thenReturn(hospitalResp);
        when(mapper.toDto(globalDef)).thenReturn(globalResp);

        List<LabTestDefinitionResponseDTO> result = service.getActiveByHospital(hospitalId);

        assertThat(result).hasSize(2);
    }

    @Test
    void getActiveByHospital_nullHospitalId_returnsGlobalOnly() {
        LabTestDefinition globalDef = LabTestDefinition.builder()
                .testCode("GLOB")
                .name("Global Test")
                .active(true)
                .build();
        globalDef.setId(UUID.randomUUID());
        LabTestDefinitionResponseDTO globalResp = LabTestDefinitionResponseDTO.builder()
                .id(globalDef.getId())
                .testCode("GLOB")
                .build();

        when(repository.findByHospitalIsNullAndActiveTrue()).thenReturn(List.of(globalDef));
        when(mapper.toDto(globalDef)).thenReturn(globalResp);

        List<LabTestDefinitionResponseDTO> result = service.getActiveByHospital(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTestCode()).isEqualTo("GLOB");
    }

    @Test
    void getActiveByHospital_hospitalOverridesGlobal() {
        LabTestDefinition globalDef = LabTestDefinition.builder()
                .testCode("CBC")
                .name("Global CBC")
                .active(true)
                .build();
        globalDef.setId(UUID.randomUUID());

        LabTestDefinition hospitalDef = LabTestDefinition.builder()
                .testCode("CBC")
                .name("Hospital CBC Override")
                .active(true)
                .build();
        hospitalDef.setId(UUID.randomUUID());

        LabTestDefinitionResponseDTO hospitalResp = LabTestDefinitionResponseDTO.builder()
                .id(hospitalDef.getId())
                .testCode("CBC")
                .name("Hospital CBC Override")
                .build();

        when(repository.findByHospitalIsNullAndActiveTrue()).thenReturn(List.of(globalDef));
        when(repository.findByHospital_IdAndActiveTrue(hospitalId)).thenReturn(List.of(hospitalDef));
        when(mapper.toDto(hospitalDef)).thenReturn(hospitalResp);

        List<LabTestDefinitionResponseDTO> result = service.getActiveByHospital(hospitalId);

        // Hospital definition overrides global with same testCode
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Hospital CBC Override");
    }

    // ---- update ----

    @Test
    void update_globalDefinition_superAdmin_success() {
        definition.setHospital(null);
        definition.setAssignment(null);
        Role superRole = Role.builder().code("ROLE_SUPER_ADMIN").name("Super Admin").build();
        UserRole ur = UserRole.builder().role(superRole).build();
        currentUser.getUserRoles().add(ur);
        requestDTO.setAssignmentId(null);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.of(definition));
            doNothing().when(mapper).updateEntityFromDto(requestDTO, definition);
            when(repository.save(definition)).thenReturn(definition);
            when(mapper.toDto(definition)).thenReturn(responseDTO);

            LabTestDefinitionResponseDTO result = service.update(defId, requestDTO);
            assertThat(result).isNotNull();
        }
    }

    @Test
    void update_notFound_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(defId, requestDTO))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Test
    void update_unauthenticated_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn(null);

            assertThatThrownBy(() -> service.update(defId, requestDTO))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ---- delete ----

    @Test
    void delete_globalDefinition_superAdmin_success() {
        definition.setHospital(null);
        definition.setAssignment(null);
        Role superRole = Role.builder().code("ROLE_SUPER_ADMIN").name("Super Admin").build();
        UserRole ur = UserRole.builder().role(superRole).build();
        currentUser.getUserRoles().add(ur);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.of(definition));

            service.delete(defId);

            verify(repository).delete(definition);
        }
    }

    @Test
    void delete_notFound_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(defId))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Test
    void delete_unauthenticated_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn(null);

            assertThatThrownBy(() -> service.delete(defId))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void delete_noRole_throwsAccessDenied() {
        definition.setHospital(null);
        definition.setAssignment(null);
        // currentUser has no roles

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.of(definition));

            assertThatThrownBy(() -> service.delete(defId))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Super Admins");
        }
    }

    // ---- search ----

    @Test
    void search_success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<LabTestDefinition> page = new PageImpl<>(List.of(definition));
        when(repository.search("CBC", null, null, null, pageable)).thenReturn(page);
        when(mapper.toDto(definition)).thenReturn(responseDTO);

        Page<LabTestDefinitionResponseDTO> result = service.search("CBC", null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void search_withNullPageable_usesUnpaged() {
        when(repository.search(eq("CBC"), isNull(), isNull(), isNull(), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(definition)));
        when(mapper.toDto(definition)).thenReturn(responseDTO);

        Page<LabTestDefinitionResponseDTO> result = service.search("CBC", null, null, null, null);

        assertThat(result.getContent()).hasSize(1);
    }

    // ---- hospital-level auth via assignment ----

    @Test
    void create_hospitalLevel_unauthorizedUser_throws() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(assignmentRepository.findById(assignmentUUID)).thenReturn(Optional.of(assignment));
            // assignment.user is different from currentUser, and no elevated role
            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            assignment.setUser(otherUser);
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                    eq(currentUser.getId()), eq(hospitalId), anySet()
            )).thenReturn(false);

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unauthorized");
        }
    }

    @Test
    void create_hospitalLevel_elevatedRole_success() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(assignmentRepository.findById(assignmentUUID)).thenReturn(Optional.of(assignment));
            User otherUser = new User();
            otherUser.setId(UUID.randomUUID());
            assignment.setUser(otherUser);
            when(assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
                    eq(currentUser.getId()), eq(hospitalId), anySet()
            )).thenReturn(true);
            when(mapper.toEntity(requestDTO, assignment)).thenReturn(definition);
            when(repository.save(definition)).thenReturn(definition);
            when(mapper.toDto(definition)).thenReturn(responseDTO);

            LabTestDefinitionResponseDTO result = service.create(requestDTO);
            assertThat(result).isNotNull();
        }
    }

    @Test
    void create_assignmentNoHospital_throwsIllegalState() {
        assignment.setHospital(null);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(assignmentRepository.findById(assignmentUUID)).thenReturn(Optional.of(assignment));

            assertThatThrownBy(() -> service.create(requestDTO))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not linked to a hospital");
        }
    }

    // ---- update with assignment change ----

    @Test
    void update_changeAssignment_success() {
        UUID newAssignmentId = UUID.randomUUID();
        requestDTO.setAssignmentId(newAssignmentId);
        definition.setHospital(hospital);
        definition.setAssignment(assignment);

        UserRoleHospitalAssignment newAssignment = new UserRoleHospitalAssignment();
        newAssignment.setId(newAssignmentId);
        newAssignment.setHospital(hospital);
        newAssignment.setUser(currentUser);

        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(currentUser));
            when(repository.findById(defId)).thenReturn(Optional.of(definition));
            assignment.setUser(currentUser);
            when(assignmentRepository.findById(newAssignmentId)).thenReturn(Optional.of(newAssignment));
            doNothing().when(mapper).updateEntityFromDto(requestDTO, definition);
            when(repository.save(definition)).thenReturn(definition);
            when(mapper.toDto(definition)).thenReturn(responseDTO);

            LabTestDefinitionResponseDTO result = service.update(defId, requestDTO);
            assertThat(result).isNotNull();
        }
    }
}
