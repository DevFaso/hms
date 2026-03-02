package com.example.hms.service;

import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.utility.UserDisplayUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

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

    // =====================================================================
    // createUserWithRolesAndHospital — duplicate-field pre-checks (new)
    // =====================================================================

    @Nested
    @DisplayName("createUserWithRolesAndHospital duplicate checks")
    class CreateUserDuplicateChecks {

        private AdminSignupRequest buildRequest(String username, String email, String phone) {
            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername(username);
            req.setEmail(email);
            req.setPhoneNumber(phone);
            req.setFirstName("John");
            req.setLastName("Doe");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_HOSPITAL_ADMIN"));
            return req;
        }

        @Test
        @DisplayName("throws ConflictException with 'username' field when username already exists")
        void rejectsDuplicateUsername() {
            when(userRepository.existsByUsername("johndoe")).thenReturn(Boolean.TRUE);

            AdminSignupRequest req = buildRequest("johndoe", "new@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("username:");
        }

        @Test
        @DisplayName("throws ConflictException with 'email' field when email already exists")
        void rejectsDuplicateEmail() {
            when(userRepository.existsByUsername("newuser")).thenReturn(Boolean.FALSE);
            when(userRepository.existsByEmail("existing@hospital.com")).thenReturn(Boolean.TRUE);

            AdminSignupRequest req = buildRequest("newuser", "existing@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email:");
        }

        @Test
        @DisplayName("throws ConflictException with 'phone' field when phone already exists")
        void rejectsDuplicatePhone() {
            when(userRepository.existsByUsername("newuser")).thenReturn(Boolean.FALSE);
            when(userRepository.existsByEmail("new@hospital.com")).thenReturn(Boolean.FALSE);
            when(userRepository.existsByPhoneNumber("+1234567890")).thenReturn(true);

            AdminSignupRequest req = buildRequest("newuser", "new@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("phone:");
        }

        @Test
        @DisplayName("username check is skipped when username is null")
        void skipsUsernameCheckWhenNull() {
            // null username → should not call existsByUsername, should fail later on missing roles/etc.
            when(userRepository.existsByEmail("new@hospital.com")).thenReturn(Boolean.FALSE);
            when(userRepository.existsByPhoneNumber("+1234567890")).thenReturn(false);

            AdminSignupRequest req = buildRequest(null, "new@hospital.com", "+1234567890");
            // Expect some downstream exception — but NOT a ConflictException
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
            verify(userRepository, never()).existsByUsername(any());
        }

        @Test
        @DisplayName("phone check is skipped when phone is blank")
        void skipsPhoneCheckWhenBlank() {
            when(userRepository.existsByUsername("newuser")).thenReturn(Boolean.FALSE);
            when(userRepository.existsByEmail("new@hospital.com")).thenReturn(Boolean.FALSE);
            // existsByPhoneNumber should NOT be called — blank phone is skipped

            AdminSignupRequest req = buildRequest("newuser", "new@hospital.com", "");
            // Will fail downstream (roles), but NOT with a phone ConflictException
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
            verify(userRepository, never()).existsByPhoneNumber(any());
        }
    }

    // =====================================================================
    // resolveDisplayName — tested via UserDisplayUtil (extracted utility)
    // =====================================================================

    @Nested
    @DisplayName("resolveDisplayName")
    class ResolveDisplayName {

        @Test
        @DisplayName("returns 'firstName lastName' when both are set")
        void fullName() {
            User u = new User();
            u.setFirstName("Jane");
            u.setLastName("Doe");
            assertThat(UserDisplayUtil.resolveDisplayName(u)).isEqualTo("Jane Doe");
        }

        @Test
        @DisplayName("returns firstName alone when lastName is blank")
        void firstNameOnly() {
            User u = new User();
            u.setFirstName("Jane");
            u.setLastName("");
            assertThat(UserDisplayUtil.resolveDisplayName(u)).isEqualTo("Jane");
        }

        @Test
        @DisplayName("returns lastName alone when firstName is blank")
        void lastNameOnly() {
            User u = new User();
            u.setFirstName("");
            u.setLastName("Doe");
            assertThat(UserDisplayUtil.resolveDisplayName(u)).isEqualTo("Doe");
        }

        @Test
        @DisplayName("returns username when both names are blank")
        void fallsBackToUsername() {
            User u = new User();
            u.setFirstName("");
            u.setLastName("");
            u.setUsername("janedoe");
            assertThat(UserDisplayUtil.resolveDisplayName(u)).isEqualTo("janedoe");
        }

        @Test
        @DisplayName("returns 'there' when user is null")
        void nullUserReturnsDefault() {
            assertThat(UserDisplayUtil.resolveDisplayName(null)).isEqualTo("there");
        }

        @Test
        @DisplayName("returns 'there' when all name fields and username are null")
        void allNullReturnsDefault() {
            User u = new User(); // firstName, lastName, username all null
            assertThat(UserDisplayUtil.resolveDisplayName(u)).isEqualTo("there");
        }
    }

    // =====================================================================
    // formatRoleLabel (private static) — tested via reflection
    // =====================================================================

    @Nested
    @DisplayName("formatRoleLabel")
    class FormatRoleLabel {

        private String invoke(String code) throws Exception {
            Method m = UserServiceImpl.class.getDeclaredMethod("formatRoleLabel", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, code);
        }

        @Test
        @DisplayName("converts ROLE_HOSPITAL_ADMIN → 'Hospital Admin'")
        void hospitalAdmin() throws Exception {
            assertThat(invoke("ROLE_HOSPITAL_ADMIN")).isEqualTo("Hospital Admin");
        }

        @Test
        @DisplayName("converts ROLE_SUPER_ADMIN → 'Super Admin'")
        void superAdmin() throws Exception {
            assertThat(invoke("ROLE_SUPER_ADMIN")).isEqualTo("Super Admin");
        }

        @Test
        @DisplayName("converts ROLE_DOCTOR → 'Doctor'")
        void doctor() throws Exception {
            assertThat(invoke("ROLE_DOCTOR")).isEqualTo("Doctor");
        }

        @Test
        @DisplayName("converts ROLE_PATIENT → 'Patient'")
        void patient() throws Exception {
            assertThat(invoke("ROLE_PATIENT")).isEqualTo("Patient");
        }

        @Test
        @DisplayName("code without ROLE_ prefix still title-cases correctly")
        void withoutRolePrefix() throws Exception {
            assertThat(invoke("NURSE")).isEqualTo("Nurse");
        }

        @Test
        @DisplayName("null input returns 'User'")
        void nullReturnsUser() throws Exception {
            assertThat(invoke(null)).isEqualTo("User");
        }
    }

    // =====================================================================
    // deleteUser
    // =====================================================================

    @Nested
    @DisplayName("deleteUser")
    class DeleteUser {

        @Test
        @DisplayName("soft-deletes user: sets isDeleted=true, isActive=false and saves")
        void softDeletesUser() {
            user.setDeleted(false);
            user.setActive(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            userService.deleteUser(userId);

            assertThat(user.isDeleted()).isTrue();
            assertThat(user.isActive()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsWhenUserNotFound() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.deleteUser(userId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(userRepository, never()).save(any());
        }
    }

    // =====================================================================
    // restoreUser
    // =====================================================================

    @Nested
    @DisplayName("restoreUser")
    class RestoreUser {

        @Test
        @DisplayName("restores a deleted user: sets isDeleted=false, isActive=true and saves")
        void restoresUser() {
            user.setDeleted(true);
            user.setActive(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(emailService).sendAccountRestoredEmail(any(), any());

            userService.restoreUser(userId);

            assertThat(user.isDeleted()).isFalse();
            assertThat(user.isActive()).isTrue();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("sends account-restored notification email after save")
        void sendsNotificationEmail() {
            user.setDeleted(true);
            user.setActive(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(emailService).sendAccountRestoredEmail(any(), any());

            userService.restoreUser(userId);

            verify(emailService).sendAccountRestoredEmail("test@example.com", "Test User");
        }

        @Test
        @DisplayName("email failure does not roll back the restore (fire-and-forget)")
        void emailFailureDoesNotRollBack() {
            user.setDeleted(true);
            user.setActive(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("SMTP down"))
                    .when(emailService).sendAccountRestoredEmail(any(), any());

            // Must NOT throw — email failure is swallowed with a warning log
            userService.restoreUser(userId);

            assertThat(user.isDeleted()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsWhenUserNotFound() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.restoreUser(userId))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendAccountRestoredEmail(any(), any());
        }
    }
}
