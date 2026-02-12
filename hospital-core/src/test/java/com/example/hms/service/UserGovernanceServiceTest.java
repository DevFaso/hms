package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserBulkImportResponseDTO;
import com.example.hms.enums.PasswordRotationStatus;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetRequestDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserForcePasswordResetResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminUserPasswordRotationDTO;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserGovernanceServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserGovernanceService service;

    @Test
    void createUserDelegatesToUserService() {
        AdminSignupRequest request = AdminSignupRequest.builder()
            .username("jane")
            .email("jane@example.com")
            .password("Secret123!")
            .firstName("Jane")
            .lastName("Doe")
            .phoneNumber("1234567890")
            .roleNames(Set.of("HOSPITAL_ADMIN"))
            .build();

        UserResponseDTO response = UserResponseDTO.builder()
            .id(UUID.randomUUID())
            .email("jane@example.com")
            .username("jane")
            .build();

        when(userService.createUserWithRolesAndHospital(any(AdminSignupRequest.class))).thenReturn(response);

        UserResponseDTO result = service.createUser(request);

        assertThat(result).isEqualTo(response);
        ArgumentCaptor<AdminSignupRequest> captor = ArgumentCaptor.forClass(AdminSignupRequest.class);
        verify(userService).createUserWithRolesAndHospital(captor.capture());
        assertThat(captor.getValue().getRoleNames()).containsExactly("HOSPITAL_ADMIN");
    }

    @Test
    void importUsersParsesCsvAndQueuesInvitations() {
        String csv = "username,email,firstName,lastName,phoneNumber,roles\n" +
            "alice,alice@example.com,Alice,Smith,1111,DOCTOR\n" +
            "bob,bob@example.com,Bob,Jones,2222,ROLE_NURSE";

        SuperAdminUserBulkImportRequestDTO request = SuperAdminUserBulkImportRequestDTO.builder()
            .csvContent(csv)
            .sendInviteEmails(true)
            .build();

        when(userService.createUserWithRolesAndHospital(any(AdminSignupRequest.class)))
            .thenAnswer(invocation -> {
                AdminSignupRequest signup = invocation.getArgument(0);
                return UserResponseDTO.builder()
                    .id(UUID.randomUUID())
                    .email(signup.getEmail())
                    .username(signup.getUsername())
                    .build();
            });

        doNothing().when(passwordResetService).requestReset(any(String.class), any(Locale.class));

        SuperAdminUserBulkImportResponseDTO response = service.importUsers(request);

        assertThat(response.getProcessed()).isEqualTo(2);
        assertThat(response.getImported()).isEqualTo(2);
        assertThat(response.getFailed()).isZero();
        assertThat(response.getResults()).hasSize(2);
        verify(passwordResetService).requestReset("alice@example.com", Locale.ENGLISH);
        verify(passwordResetService).requestReset("bob@example.com", Locale.ENGLISH);
    }

    @Test
    void forcePasswordResetHandlesUsersFromIdsAndEmails() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("reset@example.com");
        user.setUsername("resetUser");
        user.setUpdatedAt(LocalDateTime.now());

        when(userRepository.findAllById(any())).thenReturn(List.of(user));
        when(userRepository.findByEmailInIgnoreCase(any())).thenReturn(List.of());
        when(userRepository.findByUsernameInIgnoreCase(any())).thenReturn(List.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        doNothing().when(passwordResetService).invalidateAllForUser(userId);
        doNothing().when(passwordResetService).requestReset(any(String.class), any(Locale.class), any());

        SuperAdminUserForcePasswordResetRequestDTO request = SuperAdminUserForcePasswordResetRequestDTO.builder()
            .userIds(List.of(userId))
            .emails(List.of("missing@example.com"))
            .usernames(List.of("resetUser"))
            .sendEmail(true)
            .build();

        SuperAdminUserForcePasswordResetResponseDTO response = service.forcePasswordReset(request);

        assertThat(response.getRequested()).isEqualTo(3);
        assertThat(response.getSucceeded()).isEqualTo(1);
        assertThat(response.getResults()).anyMatch(result -> result.isSuccess());
        verify(passwordResetService).invalidateAllForUser(userId);
        verify(passwordResetService).requestReset("reset@example.com", Locale.ENGLISH, null);
        verify(userRepository).findByUsernameInIgnoreCase(any());
    }

    @Test
    void listPasswordRotationStatusComputesDueDatesAndOrdering() {
        LocalDateTime now = LocalDateTime.now();

        User overdueUser = new User();
        overdueUser.setId(UUID.randomUUID());
        overdueUser.setUsername("overdue");
        overdueUser.setEmail("overdue@example.com");
        overdueUser.setPasswordChangedAt(now.minusDays(100));
        overdueUser.setCreatedAt(now.minusDays(180));
        overdueUser.setUpdatedAt(now.minusDays(100));
        overdueUser.setForcePasswordChange(false);

        User warningUser = new User();
        warningUser.setId(UUID.randomUUID());
        warningUser.setUsername("warning");
        warningUser.setEmail("warning@example.com");
        warningUser.setPasswordChangedAt(now.minusDays(80));
        warningUser.setCreatedAt(now.minusDays(150));
        warningUser.setUpdatedAt(now.minusDays(80));
        warningUser.setForcePasswordChange(false);

        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(overdueUser, warningUser));

        List<SuperAdminUserPasswordRotationDTO> rotation = service.listPasswordRotationStatus();

        assertThat(rotation).hasSize(2);
        assertThat(rotation.get(0).getStatus()).isEqualTo(PasswordRotationStatus.FORCE_REQUIRED);
        assertThat(rotation.get(0).getRotationDueOn()).isNotNull();
        assertThat(rotation.get(1).getStatus()).isEqualTo(PasswordRotationStatus.WARNING);
        assertThat(rotation.get(1).getDaysUntilDue()).isGreaterThanOrEqualTo(0);
        verify(userRepository).findByIsDeletedFalse();
    }
}
