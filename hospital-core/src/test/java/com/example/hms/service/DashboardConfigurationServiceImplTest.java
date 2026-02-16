package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashboardConfigurationServiceImplTest {

    private AuthService authService;
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    private DashboardConfigurationService service;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        assignmentRepository = mock(UserRoleHospitalAssignmentRepository.class);
        service = new DashboardConfigurationServiceImpl(authService, assignmentRepository);
    }

    @Test
    void aggregatesPermissionsAcrossActiveAssignments() {
        UUID userId = UUID.randomUUID();
        when(authService.getCurrentUserId()).thenReturn(userId);

        Role doctorRole = Role.builder().code("ROLE_DOCTOR").name("Doctor").build();
        Hospital hospital = Hospital.builder().name("General Hospital").code("GH").build();
        hospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment doctorAssignment = UserRoleHospitalAssignment.builder()
            .role(doctorRole)
            .hospital(hospital)
            .active(true)
            .build();

        Role nurseRole = Role.builder().code("ROLE_NURSE").name("Nurse").build();
        UserRoleHospitalAssignment nurseAssignment = UserRoleHospitalAssignment.builder()
            .role(nurseRole)
            .active(true)
            .build();

        when(assignmentRepository.findByUser_IdAndActiveTrue(userId)).thenReturn(List.of(doctorAssignment, nurseAssignment));

        DashboardConfigResponseDTO response = service.getDashboardForCurrentUser();

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.roles())
            .extracting(r -> r.roleCode())
            .containsExactly("ROLE_DOCTOR", "ROLE_NURSE");
        assertThat(response.roles())
            .extracting(r -> r.permissions().size())
            .allMatch(size -> size > 0);
        assertThat(response.mergedPermissions()).isNotEmpty();

        verify(authService).getCurrentUserId();
        verify(assignmentRepository).findByUser_IdAndActiveTrue(userId);
        verifyNoMoreInteractions(authService, assignmentRepository);
    }

    @Test
    void fallsBackToUnknownRoleWhenNoAssignments() {
        UUID userId = UUID.randomUUID();
        when(authService.getCurrentUserId()).thenReturn(userId);
        when(assignmentRepository.findByUser_IdAndActiveTrue(userId)).thenReturn(List.of());

        DashboardConfigResponseDTO response = service.getDashboardForCurrentUser();

        assertThat(response.roles()).hasSize(1);
        assertThat(response.roles().get(0).roleCode()).isEqualTo("ROLE_UNKNOWN");
        assertThat(response.mergedPermissions()).contains("View Dashboard");
    }
}
