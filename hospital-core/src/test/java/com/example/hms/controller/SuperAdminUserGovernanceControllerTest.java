package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetResponseDTO;
import com.example.hms.service.UserGovernanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminUserGovernanceControllerTest {

    @Mock
    private UserGovernanceService governanceService;

    @InjectMocks
    private SuperAdminUserGovernanceController controller;

    @Test
    void createUserReturnsCreated() {
        AdminSignupRequest request = AdminSignupRequest.builder()
            .username("jane")
            .email("jane@example.com")
            .password("Secret123!")
            .firstName("Jane")
            .lastName("Doe")
            .phoneNumber("1234567890")
            .roleNames(Collections.singleton("HOSPITAL_ADMIN"))
            .build();

        UserResponseDTO responseDto = UserResponseDTO.builder()
            .id(UUID.randomUUID())
            .username("jane")
            .email("jane@example.com")
            .build();

        when(governanceService.createUser(request)).thenReturn(responseDto);

        ResponseEntity<UserResponseDTO> response = controller.createUser(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(governanceService).createUser(request);
    }

    @Test
    void importUsersReturnsOk() {
        SuperAdminUserBulkImportRequestDTO request = SuperAdminUserBulkImportRequestDTO.builder()
            .csvContent("username,email,firstName,lastName,phoneNumber,roles")
            .build();

        SuperAdminUserBulkImportResponseDTO responseDto = SuperAdminUserBulkImportResponseDTO.builder()
            .processed(1)
            .imported(1)
            .failed(0)
            .build();

        when(governanceService.importUsers(request)).thenReturn(responseDto);

        ResponseEntity<SuperAdminUserBulkImportResponseDTO> response = controller.importUsers(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(governanceService).importUsers(request);
    }

    @Test
    void forcePasswordResetReturnsOk() {
        SuperAdminUserForcePasswordResetRequestDTO request = SuperAdminUserForcePasswordResetRequestDTO.builder()
            .usernames(Collections.singletonList("nurse.one"))
            .build();

        SuperAdminUserForcePasswordResetResponseDTO responseDto = SuperAdminUserForcePasswordResetResponseDTO.builder()
            .requested(1)
            .succeeded(1)
            .build();

        when(governanceService.forcePasswordReset(request)).thenReturn(responseDto);

        ResponseEntity<SuperAdminUserForcePasswordResetResponseDTO> response = controller.forcePasswordReset(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(responseDto);
        verify(governanceService).forcePasswordReset(request);
    }
}
