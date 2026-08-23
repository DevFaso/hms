package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PermissionMapper;
import com.example.hms.model.Permission;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PermissionRequestDTO;
import com.example.hms.payload.dto.PermissionResponseDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The duplicate check used to be
 * {@code findAll().stream().anyMatch(p -> … p.getAssignment().getId() …)} —
 * it loaded every permission row in the database and threw a
 * NullPointerException on any row without an assignment. It is now a derived
 * exists-query; these tests pin both halves of that.
 */
class PermissionServiceImplCreateTest {

    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);

    private PermissionServiceImpl service;

    private static final UUID ASSIGNMENT_ID = UUID.randomUUID();
    private PermissionRequestDTO request;

    @BeforeEach
    void setUp() {
        service = new PermissionServiceImpl(permissionRepository, permissionMapper, assignmentRepository);
        request = new PermissionRequestDTO();
        request.setName("View Lab");
        request.setAssignmentId(ASSIGNMENT_ID);
    }

    @Test
    @DisplayName("asks the database for the one row instead of scanning them all")
    void createsViaDerivedExistsQuery() {
        when(permissionRepository.existsByNameIgnoreCaseAndAssignment_Id("View Lab", ASSIGNMENT_ID))
            .thenReturn(false);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setId(ASSIGNMENT_ID);
        when(assignmentRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(assignment));

        Permission entity = new Permission();
        when(permissionMapper.toEntity(request)).thenReturn(entity);
        when(permissionRepository.save(entity)).thenReturn(entity);
        PermissionResponseDTO expected = PermissionResponseDTO.builder().build();
        when(permissionMapper.toResponseDTO(entity)).thenReturn(expected);

        assertThat(service.createPermission(request, Locale.ENGLISH)).isSameAs(expected);

        assertThat(entity.getAssignment()).isSameAs(assignment);
        // The whole-table scan must be gone, not merely bypassed.
        verify(permissionRepository, never()).findAll();
    }

    @Test
    @DisplayName("refuses a duplicate name on the same assignment")
    void refusesDuplicate() {
        when(permissionRepository.existsByNameIgnoreCaseAndAssignment_Id("View Lab", ASSIGNMENT_ID))
            .thenReturn(true);

        assertThatThrownBy(() -> service.createPermission(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");

        verify(permissionRepository, never()).save(any(Permission.class));
    }

    @Test
    @DisplayName("404s when the assignment does not exist")
    void refusesUnknownAssignment() {
        when(permissionRepository.existsByNameIgnoreCaseAndAssignment_Id("View Lab", ASSIGNMENT_ID))
            .thenReturn(false);
        when(permissionMapper.toEntity(request)).thenReturn(new Permission());
        when(assignmentRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPermission(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(permissionRepository, never()).save(any(Permission.class));
    }
}
