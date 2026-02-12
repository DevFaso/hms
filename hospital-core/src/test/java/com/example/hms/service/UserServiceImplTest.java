package com.example.hms.service;

import com.example.hms.mapper.UserMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.config.BootstrapProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    @Mock private BootstrapProperties bootstrapProperties;
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
    void bootstrapFirstUser_withInvalidToken_returnsFalse() {
        when(userRepository.count()).thenReturn(0L);
        when(bootstrapProperties.getToken()).thenReturn("secret-token");

        BootstrapSignupRequest request = new BootstrapSignupRequest();
        request.setBootstrapToken("wrong-token");

        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).contains("Invalid bootstrap token");
    }

    @Test
    void bootstrapFirstUser_success() {
        when(userRepository.count()).thenReturn(0L);
        when(bootstrapProperties.getToken()).thenReturn(null);
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
}
