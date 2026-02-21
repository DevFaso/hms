package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserRoleHospitalAssignmentService assignmentService;
    @Mock private EmailService emailService;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private StaffRepository staffRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private UserServiceImpl userService;

    private UUID userId;
    private User user;
    private Role patientRole;
    private Role superAdminRole;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setEmail("test@example.com");
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");

        patientRole = new Role();
        patientRole.setId(UUID.randomUUID());
        patientRole.setCode("ROLE_PATIENT");
        patientRole.setName("ROLE_PATIENT");

        superAdminRole = new Role();
        superAdminRole.setId(UUID.randomUUID());
        superAdminRole.setCode("ROLE_SUPER_ADMIN");
        superAdminRole.setName("ROLE_SUPER_ADMIN");
    }

    @Test
    void bootstrapFirstUser_whenUsersExist_returnsFalse() {
        when(userRepository.count()).thenReturn(1L);

        BootstrapSignupRequest request = new BootstrapSignupRequest();
        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("not allowed");
    }

    @Test
    void bootstrapFirstUser_withExistingUsers_returnsFalse() {
        when(userRepository.count()).thenReturn(5L);

        BootstrapSignupRequest request = new BootstrapSignupRequest();
        request.setUsername("admin");
        request.setEmail("admin@test.com");
        request.setPassword("password");
        request.setFirstName("Admin");
        request.setLastName("User");

        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("not allowed");
    }

    @Test
    void bootstrapFirstUser_success() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(roleRepository.findByCode("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(userRoleRepository.existsById(any())).thenReturn(false);
        when(userRepository.getReferenceById(any())).thenReturn(user);
        when(roleRepository.getReferenceById(any())).thenReturn(superAdminRole);
        when(assignmentService.isRoleAlreadyAssigned(any(), any(), any())).thenReturn(false);

        BootstrapSignupRequest request = new BootstrapSignupRequest();
        request.setUsername("admin");
        request.setEmail("admin@test.com");
        request.setPassword("password");
        request.setFirstName("Admin");
        request.setLastName("User");

        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getUsername()).isEqualTo("admin");
    }

    // =====================================================================
    // changeOwnPassword
    // =====================================================================

    @Test
    void changeOwnPassword_success_clearsForcePasswordChangeFlag() {
        user.setForcePasswordChange(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass99!")).thenReturn("$2a$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.changeOwnPassword(userId, "NewPass99!");

        assertThat(user.isForcePasswordChange()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("$2a$hashed");
        assertThat(user.getPasswordRotationWarningAt()).isNull();
        assertThat(user.getPasswordRotationForcedAt()).isNull();
        assertThat(user.getPasswordChangedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void changeOwnPassword_userNotFound_throwsResourceNotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeOwnPassword(userId, "NewPass99!"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void changeOwnPassword_encodesNewPassword() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("BrandNewPass1!")).thenReturn("$2a$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.changeOwnPassword(userId, "BrandNewPass1!");

        verify(passwordEncoder).encode("BrandNewPass1!");
        assertThat(user.getPasswordHash()).isEqualTo("$2a$encoded");
    }
}
