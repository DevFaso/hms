package com.example.hms.service;

import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.BootstrapSignupResponse;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UpdateUserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.exception.BusinessException;
import com.example.hms.security.provider.ClinicalHospitals;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.UserDisplayUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    @Mock private PasswordHistoryService passwordHistoryService;
    @Mock private com.example.hms.security.LoginAttemptService loginAttemptService;
    @Mock private AssignmentLinkService assignmentLinkService;
    @Mock private UserAccountAccess accountAccess;
    @Mock private com.example.hms.security.provider.FacilityAssignmentGuard facilityAssignmentGuard;
    @Mock private SmsService smsService;

    @InjectMocks
    private UserServiceImpl userService;

    private UUID userId;
    private User user;
    private Role patientRole;
    private Role superAdminRole;

    @BeforeEach
    void setUp() {
        // findClinicalById answers as the database does: the stubbed row, when it is a hospital.
        lenient().when(hospitalRepository.findClinicalById(any()))
            .thenAnswer(invocation -> hospitalRepository.findById(invocation.getArgument(0))
                .filter(ClinicalHospitals::isClinical));
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

        // The behaviour tests below run as an administrator of the account;
        // the guard itself is exercised in AccountAccessGuard and in
        // UserAccountAccessTest / UserEndpointAuthorizationIT.
        lenient().when(accountAccess.canAdminister(any())).thenReturn(true);
        lenient().when(accountAccess.canView(any())).thenReturn(true);
        lenient().when(accountAccess.canDelete(any())).thenReturn(true);
        // Registration runs as a super-admin: the grant check resolves the hospital and allows.
        UserAccountAccess.Grant anywhere = mock(UserAccountAccess.Grant.class);
        lenient().when(anywhere.requireAt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountAccess.requireMayGrant(any())).thenReturn(anywhere);
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
        request.setPhoneNumber("+1234567890");

        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("bootstrap succeeds even when audit logging throws an exception")
    void bootstrapFirstUser_auditFailure_doesNotFailBootstrap() {
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

        // Simulate audit failure — logEvent returns null (swallowed exception)
        when(auditEventLogService.logEvent(any())).thenReturn(null);

        BootstrapSignupRequest request = new BootstrapSignupRequest();
        request.setUsername("admin");
        request.setEmail("admin@test.com");
        request.setPassword("password");
        request.setFirstName("Admin");
        request.setLastName("User");
        request.setPhoneNumber("+1234567890");

        BootstrapSignupResponse response = userService.bootstrapFirstUser(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getUsername()).isEqualTo("admin");
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    @DisplayName("bootstrap passes null userId and SYSTEM userName to audit (no JWT actor)")
    void bootstrapFirstUser_passesSystemActorToAudit() {
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
        request.setPhoneNumber("+1234567890");

        userService.bootstrapFirstUser(request);

        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> captor =
                org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());

        com.example.hms.payload.dto.AuditEventRequestDTO captured = captor.getValue();
        assertThat(captured.getUserId()).isNull();
        assertThat(captured.getUserName()).isEqualTo("SYSTEM");
        assertThat(captured.getAssignmentId()).isNull();
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
        @DisplayName("an admin-registered staff account starts INACTIVE - the emailed code gates something real")
        void staffStartInactiveUntilVerified() {
            // Option A (2026-09-02). Until then staff were activated up
            // front while the system still mailed them a verification code
            // that gated nothing. Login is refused while inactive
            // (CustomUserDetails.isEnabled); verifyAssignmentByCode() and
            // the registrar's confirmAssignment() are the activation paths
            // for user and assignment alike. Registration runs to
            // COMPLETION here so a regression that force-activates the
            // assignment downstream cannot hide behind an early exception.
            when(userRepository.findByUsername("newstaff")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("staff@hospital.com")).thenReturn(Optional.empty());
            when(userRepository.findByPhoneNumber("+1234567899")).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) u.setId(UUID.randomUUID());
                return u;
            });
            when(roleRepository.findByCode("ROLE_SUPER_ADMIN"))
                .thenReturn(Optional.of(superAdminRole));
            when(passwordEncoder.encode(any())).thenReturn("encoded");

            UserRoleHospitalAssignment createdAssignment = new UserRoleHospitalAssignment();
            createdAssignment.setId(UUID.randomUUID());
            createdAssignment.setRole(superAdminRole);
            createdAssignment.setActive(false);
            when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(
                    any(), isNull(), any()))
                .thenReturn(Optional.of(createdAssignment));
            when(userRepository.findByIdWithRolesAndProfiles(any(UUID.class)))
                .thenAnswer(inv -> {
                    User reloaded = new User();
                    reloaded.setId(inv.getArgument(0));
                    return Optional.of(reloaded);
                });
            when(userMapper.toResponseDTO(any(), any())).thenReturn(new UserResponseDTO());

            AdminSignupRequest req = buildRequest("newstaff", "staff@hospital.com", "+1234567899");
            // SUPER_ADMIN: the one staff role registrable without a hospital
            // context, which keeps this fixture out of JWT-resolution stubs.
            req.setRoleNames(Set.of("ROLE_SUPER_ADMIN"));

            UserResponseDTO result = userService.createUserWithRolesAndHospital(req);
            assertThat(result).as("registration must run to completion").isNotNull();

            org.mockito.ArgumentCaptor<User> userCaptor =
                org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(userCaptor.capture());
            assertThat(userCaptor.getValue().isActive())
                .as("staff must start inactive until the emailed code is verified")
                .isFalse();

            org.mockito.ArgumentCaptor<com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO>
                assignCaptor = org.mockito.ArgumentCaptor.forClass(
                    com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO.class);
            verify(assignmentService).assignRoleOnAccountCreation(assignCaptor.capture());
            assertThat(assignCaptor.getValue().getActive())
                .as("the assignment request must also start inactive")
                .isFalse();
            assertThat(assignCaptor.getValue().isWelcomeMailCarriesCode())
                .as("D10: one role, new account -> the welcome mail is the only mail")
                .isTrue();

            // The old pre-approval override force-activated the reloaded
            // assignment and saved it; that save must never come back.
            assertThat(createdAssignment.getActive()).isFalse();
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("staff/admin accounts still require an email (phone-first applies to patients only)")
        void rejectsStaffWithoutEmail() {
            AdminSignupRequest req = buildRequest("newuser", null, "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email is required");
        }

        private User liveHolder() {
            User holder = new User();
            holder.setId(UUID.randomUUID());
            holder.setDeleted(false);
            return holder;
        }

        private User deletedHolder() {
            User holder = new User();
            holder.setId(UUID.randomUUID());
            holder.setDeleted(true);
            return holder;
        }

        @Test
        @DisplayName("throws ConflictException with 'username' field when username already exists")
        void rejectsDuplicateUsername() {
            when(userRepository.findByUsername("johndoe")).thenReturn(Optional.of(liveHolder()));

            AdminSignupRequest req = buildRequest("johndoe", "new@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("username:");
        }

        @Test
        @DisplayName("throws ConflictException with 'email' field when email already exists")
        void rejectsDuplicateEmail() {
            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("existing@hospital.com"))
                .thenReturn(Optional.of(liveHolder()));

            AdminSignupRequest req = buildRequest("newuser", "existing@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email:")
                .hasMessageContaining("already registered");
        }

        @Test
        @DisplayName("an email held by a soft-deleted account says so instead of pointing at nothing")
        void deletedGhostEmailSaysSo() {
            // The defect this pins: the identifier was held by a row the user
            // list filtered out, so "already registered" was a dead end the
            // administrator could not investigate, let alone act on.
            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("ghost@hospital.com"))
                .thenReturn(Optional.of(deletedHolder()));

            AdminSignupRequest req = buildRequest("newuser", "ghost@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email:")
                .hasMessageContaining("deleted account")
                .hasMessageContaining("Deleted filter");
        }

        @Test
        @DisplayName("throws ConflictException with 'phone' field when phone already exists")
        void rejectsDuplicatePhone() {
            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("new@hospital.com")).thenReturn(Optional.empty());
            when(userRepository.findByPhoneNumber("+1234567890"))
                .thenReturn(Optional.of(liveHolder()));

            AdminSignupRequest req = buildRequest("newuser", "new@hospital.com", "+1234567890");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("phone:");
        }

        @Test
        @DisplayName("username check is skipped when username is null")
        void skipsUsernameCheckWhenNull() {
            when(userRepository.findByEmail("new@hospital.com")).thenReturn(Optional.empty());
            when(userRepository.findByPhoneNumber("+1234567890")).thenReturn(Optional.empty());

            AdminSignupRequest req = buildRequest(null, "new@hospital.com", "+1234567890");
            // Expect some downstream exception — but NOT a ConflictException
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
            verify(userRepository, never()).findByUsername(any());
        }

        @Test
        @DisplayName("phone check is skipped when phone is blank")
        void skipsPhoneCheckWhenBlank() {
            when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("new@hospital.com")).thenReturn(Optional.empty());
            // findByPhoneNumber should NOT be called — blank phone is skipped

            AdminSignupRequest req = buildRequest("newuser", "new@hospital.com", "");
            // Will fail downstream (roles), but NOT with a phone ConflictException
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
            verify(userRepository, never()).findByPhoneNumber(any());
        }

        @Test
        @DisplayName("patient with existing email at DIFFERENT hospital skips 409 (multi-hospital)")
        void patientAtNewHospitalSkips409() {
            UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            hospital.setName("Hospital B");

            // Existing user found by email — but NOT yet assigned as PATIENT at target hospital
            when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                    "patient.doe", "existing@test.com", "+1111111111"))
                .thenReturn(Optional.of(user));

            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(roleRepository.findByCode("ROLE_PATIENT")).thenReturn(Optional.of(patientRole));
            when(assignmentService.isRoleAlreadyAssigned(user.getId(), hospitalId, patientRole.getId()))
                .thenReturn(false);

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("patient.doe");
            req.setEmail("existing@test.com");
            req.setPhoneNumber("+1111111111");
            req.setFirstName("Jane");
            req.setLastName("Doe");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_PATIENT"));
            req.setHospitalId(hospitalId);

            // Should NOT throw ConflictException — patient can span hospitals.
            // Will throw some downstream exception (role resolution, etc.) but not 409.
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("patient already assigned at SAME hospital throws specific conflict")
        void patientAtSameHospitalThrowsConflict() {
            UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            hospital.setName("Hospital A");

            when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                    "patient.doe", "existing@test.com", "+1111111111"))
                .thenReturn(Optional.of(user));

            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(roleRepository.findByCode("ROLE_PATIENT")).thenReturn(Optional.of(patientRole));
            when(assignmentService.isRoleAlreadyAssigned(user.getId(), hospitalId, patientRole.getId()))
                .thenReturn(true);

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("patient.doe");
            req.setEmail("existing@test.com");
            req.setPhoneNumber("+1111111111");
            req.setFirstName("Jane");
            req.setLastName("Doe");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_PATIENT"));
            req.setHospitalId(hospitalId);

            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("patient:Patient is already registered at this hospital.");
        }

        @Test
        @DisplayName("null email uses the phone-safe lookup — never the 3-way query's email IS NULL predicate")
        void nullEmailUsesPhoneSafeLookup() {
            UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-000000000007");
            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            hospital.setName("Hospital P");

            when(userRepository.findFirstByUsernameIgnoreCaseOrPhoneNumber("patient.awa", "+22670707070"))
                .thenReturn(Optional.of(user));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(roleRepository.findByCode("ROLE_PATIENT")).thenReturn(Optional.of(patientRole));
            when(assignmentService.isRoleAlreadyAssigned(user.getId(), hospitalId, patientRole.getId()))
                .thenReturn(true);

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("patient.awa");
            req.setEmail(null);
            req.setPhoneNumber("+22670707070");
            req.setFirstName("Awa");
            req.setLastName("Ouedraogo");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_PATIENT"));
            req.setHospitalId(hospitalId);

            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered at this hospital");
            verify(userRepository, never())
                .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(any(), any(), any());
        }

        @Test
        @DisplayName("re-registering a soft-deleted user clears isDeleted before reload")
        void reRegisterClearsSoftDeleteFlag() {
            UUID hospitalId = UUID.fromString("00000000-0000-0000-0000-000000000004");
            Hospital hospital = new Hospital();
            hospital.setId(hospitalId);
            hospital.setName("Hospital C");

            // Existing user was soft-deleted by the FE compensation flow
            // (admin-register succeeded → /api/patients failed → DELETE /users/{id}).
            user.setDeleted(true);
            // V184: the clean-up delete recorded a disable too.
            user.setDeactivatedByAdminAt(java.time.LocalDateTime.now());
            when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                    "patient.doe", "existing@test.com", "+1111111111"))
                .thenReturn(Optional.of(user));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(roleRepository.findByCode("ROLE_PATIENT")).thenReturn(Optional.of(patientRole));
            when(assignmentService.isRoleAlreadyAssigned(user.getId(), hospitalId, patientRole.getId()))
                .thenReturn(false);
            when(staffRepository.findByUserId(user.getId())).thenReturn(Collections.emptyList());

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("patient.doe");
            req.setEmail("existing@test.com");
            req.setPhoneNumber("+1111111111");
            req.setFirstName("Jane");
            req.setLastName("Doe");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_PATIENT"));
            req.setHospitalId(hospitalId);

            // The flow will fail downstream (no role/assignment mocks beyond the
            // restore step), but the soft-delete flag MUST be cleared and the
            // user re-saved before that — otherwise reloadAndMap() will report
            // the misleading "User disappeared after save" diagnostic.
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);

            assertThat(user.isDeleted()).isFalse();
            // Registering the person again undoes the delete's disable too, or
            // their code and activation link would refuse them forever.
            assertThat(user.getDeactivatedByAdminAt()).isNull();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("brand-new patient (no existing user) passes duplicate check")
        void brandNewPatientPassesDuplicateCheck() {
            when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                    "new.patient", "new@test.com", "+2222222222"))
                .thenReturn(Optional.empty());

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("new.patient");
            req.setEmail("new@test.com");
            req.setPhoneNumber("+2222222222");
            req.setFirstName("New");
            req.setLastName("Patient");
            req.setPassword("Temp@1234");
            req.setRoleNames(Set.of("ROLE_PATIENT"));
            req.setHospitalId(UUID.fromString("00000000-0000-0000-0000-000000000003"));

            // Should NOT throw ConflictException — brand new patient
            // Will fail downstream (hospital lookup, etc.) but not with 409
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isNotInstanceOf(ConflictException.class);
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
    // welcomeMailCarriesCode — when the welcome mail is the ONLY mail (D10)
    // =====================================================================

    @Nested
    @DisplayName("welcomeMailCarriesCode")
    class WelcomeMailCarriesCode {

        private Set<com.example.hms.model.Role> roles(int n) {
            Set<com.example.hms.model.Role> set = new java.util.LinkedHashSet<>();
            for (int i = 0; i < n; i++) {
                com.example.hms.model.Role r = new com.example.hms.model.Role();
                r.setId(java.util.UUID.randomUUID());
                r.setCode("ROLE_X" + i);
                set.add(r);
            }
            return set;
        }

        @Test
        @DisplayName("a new staff account gets one mail carrying the code, with one role or several")
        void newStaff() {
            assertThat(UserServiceImpl.welcomeMailCarriesCode(true, false, roles(1))).isTrue();
            assertThat(UserServiceImpl.welcomeMailCarriesCode(true, false, roles(2))).isTrue();
        }

        @Test
        @DisplayName("an existing user, a patient, or no role keep the assignment mail")
        void otherCasesKeepTheAssignmentMail() {
            assertThat(UserServiceImpl.welcomeMailCarriesCode(false, false, roles(1))).isFalse();
            assertThat(UserServiceImpl.welcomeMailCarriesCode(true, true, roles(1))).isFalse();
            assertThat(UserServiceImpl.welcomeMailCarriesCode(true, false, roles(0))).isFalse();
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
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());

            userService.deleteUser(userId);

            assertThat(user.isDeleted()).isTrue();
            assertThat(user.isActive()).isFalse();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("deactivates associated staff records when user is deleted")
        void deactivatesStaffRecords() {
            user.setDeleted(false);
            user.setActive(true);

            Staff activeStaff = new Staff();
            activeStaff.setId(UUID.randomUUID());
            activeStaff.setUser(user);
            activeStaff.setActive(true);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserId(userId)).thenReturn(List.of(activeStaff));

            userService.deleteUser(userId);

            assertThat(activeStaff.isActive()).isFalse();
            verify(staffRepository).save(activeStaff);
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
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());
            doNothing().when(emailService).sendAccountRestoredEmail(any(), any());

            userService.restoreUser(userId);

            assertThat(user.isDeleted()).isFalse();
            assertThat(user.isActive()).isTrue();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("reactivates associated staff records when user is restored")
        void reactivatesStaffRecords() {
            user.setDeleted(true);
            user.setActive(false);

            Staff inactiveStaff = new Staff();
            inactiveStaff.setId(UUID.randomUUID());
            inactiveStaff.setUser(user);
            inactiveStaff.setActive(false);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserId(userId)).thenReturn(List.of(inactiveStaff));
            doNothing().when(emailService).sendAccountRestoredEmail(any(), any());

            userService.restoreUser(userId);

            assertThat(inactiveStaff.isActive()).isTrue();
            verify(staffRepository).save(inactiveStaff);
        }

        @Test
        @DisplayName("sends account-restored notification email after save")
        void sendsNotificationEmail() {
            user.setDeleted(true);
            user.setActive(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());
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
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());
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

    // ── updateUser ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateUser (merge-preserve semantics)")
    class UpdateUser {

        private UserResponseDTO responseDTO;

        @BeforeEach
        void setUpUpdateUser() {
            // Give the shared 'user' a password hash so password-change tests work
            user.setPasswordHash("$2a$10$existingHash");
            user.setPhoneNumber("+1-555-0100");

            responseDTO = new UserResponseDTO();
            responseDTO.setId(userId);
        }

        /** Common stubbing for every updateUser happy-path test. */
        private void stubHappyPath() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findFirstByUserIdAndActiveTrue(userId))
                    .thenReturn(Optional.empty());
            when(assignmentRepository.findByUser(any(User.class)))
                    .thenReturn(Collections.emptySet());
            when(userMapper.toResponseDTO(any(User.class), any()))
                    .thenReturn(responseDTO);
            when(auditEventLogService.logEvent(any())).thenReturn(null);
        }

        @Test
        @DisplayName("partial update — only firstName — preserves every other field")
        void partialUpdateOnlyFirstName() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("NewFirst");

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            User saved = cap.getValue();

            assertThat(saved.getFirstName()).isEqualTo("NewFirst");
            // Everything else untouched
            assertThat(saved.getLastName()).isEqualTo("User");
            assertThat(saved.getEmail()).isEqualTo("test@example.com");
            assertThat(saved.getUsername()).isEqualTo("testuser");
            assertThat(saved.getPhoneNumber()).isEqualTo("+1-555-0100");
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$existingHash");
        }

        @ParameterizedTest(name = "phone \"{0}\" -> {1}")
        @CsvSource({
            "'70 20 20 20', +22670202020",
            "'', +1-555-0100",
            "+1-555-0999, +15550999"
        })
        @DisplayName("an updated phone is stored in E.164; a blank one keeps the number on file")
        void updatePhone(String sent, String stored) {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPhoneNumber(sent);

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            assertThat(cap.getValue().getPhoneNumber()).isEqualTo(stored);
        }

        @Test
        @DisplayName("an unchanged legacy phone sent back by the edit dialog does not block the edit")
        void unchangedLegacyPhoneDoesNotBlockEdit() {
            stubHappyPath();
            user.setPhoneNumber("70-20 ext 5");
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("NewFirst");
            dto.setPhoneNumber("70-20 ext 5");

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            assertThat(cap.getValue().getPhoneNumber()).isEqualTo("70-20 ext 5");
            assertThat(cap.getValue().getFirstName()).isEqualTo("NewFirst");
        }

        @Test
        @DisplayName("blank password is ignored — existing hash preserved")
        void blankPasswordPreservesHash() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("");

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());

            assertThat(cap.getValue().getPasswordHash()).isEqualTo("$2a$10$existingHash");
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("null password is ignored — existing hash preserved")
        void nullPasswordPreservesHash() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            // password remains null by default

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());

            assertThat(cap.getValue().getPasswordHash()).isEqualTo("$2a$10$existingHash");
            verify(passwordEncoder, never()).encode(any());
        }

        @Test
        @DisplayName("new password that differs from current hash is encoded and saved")
        void newPasswordUpdatesHash() {
            stubHappyPath();
            when(passwordEncoder.matches("NewPass123", "$2a$10$existingHash")).thenReturn(false);
            when(passwordEncoder.encode("NewPass123")).thenReturn("$2a$10$newHash");

            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("NewPass123");

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            User saved = cap.getValue();

            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$newHash");
            assertThat(saved.getPasswordChangedAt()).isNotNull();
            assertThat(saved.isForcePasswordChange()).isFalse();
        }

        @Test
        @DisplayName("same password as current hash is NOT re-encoded")
        void samePasswordIsNotReEncoded() {
            stubHappyPath();
            when(passwordEncoder.matches("SamePass123", "$2a$10$existingHash")).thenReturn(true);

            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("SamePass123");

            userService.updateUser(userId, dto);

            verify(passwordEncoder, never()).encode(any());

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            assertThat(cap.getValue().getPasswordHash()).isEqualTo("$2a$10$existingHash");
        }

        @Test
        @DisplayName("setting only active flag preserves all other fields")
        void activeOnlyPreservesOtherFields() {
            stubHappyPath();
            user.setActive(true);

            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setActive(false);

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            User saved = cap.getValue();

            assertThat(saved.isActive()).isFalse();
            // V184: the disable is recorded, so no re-invite or code undoes it.
            assertThat(saved.getDeactivatedByAdminAt()).isNotNull();
            assertThat(saved.getFirstName()).isEqualTo("Test");
            assertThat(saved.getEmail()).isEqualTo("test@example.com");
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$existingHash");
        }

        @Test
        @DisplayName("V184: an explicit active=false on a pending invitee is a disable: their code cannot switch them on")
        void explicitInactiveOnAPendingInvitee_isADisable() {
            stubHappyPath();
            user.setActive(false);
            UpdateUserRequestDTO disable = new UpdateUserRequestDTO();
            disable.setActive(false);

            userService.updateUser(userId, disable);

            // The portal's edit never sends `active`: its presence is intent.
            assertThat(user.getDeactivatedByAdminAt()).isNotNull();
        }

        @Test
        @DisplayName("V184: re-enabling clears the administrator's disable; echoing active=true does not")
        void reenableClearsTheDisable_anEchoDoesNot() {
            stubHappyPath();
            java.time.LocalDateTime disabledAt = java.time.LocalDateTime.of(2026, 1, 1, 9, 0);

            user.setActive(true);
            user.setDeactivatedByAdminAt(disabledAt);
            UpdateUserRequestDTO echo = new UpdateUserRequestDTO();
            echo.setActive(true);
            userService.updateUser(userId, echo);
            assertThat(user.getDeactivatedByAdminAt()).isEqualTo(disabledAt);

            user.setActive(false);
            UpdateUserRequestDTO reenable = new UpdateUserRequestDTO();
            reenable.setActive(true);
            userService.updateUser(userId, reenable);
            assertThat(user.getDeactivatedByAdminAt()).isNull();
        }

        @Test
        @DisplayName("completely empty DTO preserves every field (the exact bug scenario)")
        void emptyDtoPreservesEverything() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            User saved = cap.getValue();

            assertThat(saved.getFirstName()).isEqualTo("Test");
            assertThat(saved.getLastName()).isEqualTo("User");
            assertThat(saved.getEmail()).isEqualTo("test@example.com");
            assertThat(saved.getUsername()).isEqualTo("testuser");
            assertThat(saved.getPhoneNumber()).isEqualTo("+1-555-0100");
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$existingHash");
        }

        @Test
        @DisplayName("multiple fields updated simultaneously")
        void multipleFieldsUpdated() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("Jane");
            dto.setLastName("Doe");
            dto.setEmail("jane.doe@example.com");

            userService.updateUser(userId, dto);

            ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(cap.capture());
            User saved = cap.getValue();

            assertThat(saved.getFirstName()).isEqualTo("Jane");
            assertThat(saved.getLastName()).isEqualTo("Doe");
            assertThat(saved.getEmail()).isEqualTo("jane.doe@example.com");
            // Unchanged fields
            assertThat(saved.getUsername()).isEqualTo("testuser");
            assertThat(saved.getPhoneNumber()).isEqualTo("+1-555-0100");
        }

        @Test
        @DisplayName("audit event is logged on successful update")
        void auditEventIsLogged() {
            stubHappyPath();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("Audited");

            userService.updateUser(userId, dto);

            verify(auditEventLogService).logEvent(any());
        }

        @Test
        @DisplayName("mapper receives saved user and assignments")
        void mapperReceivesSavedUserAndAssignments() {
            Set<UserRoleHospitalAssignment> assignments = new HashSet<>();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findFirstByUserIdAndActiveTrue(userId))
                    .thenReturn(Optional.empty());
            when(assignmentRepository.findByUser(any(User.class))).thenReturn(assignments);
            when(userMapper.toResponseDTO(any(User.class), any())).thenReturn(responseDTO);
            when(auditEventLogService.logEvent(any())).thenReturn(null);

            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            UserResponseDTO result = userService.updateUser(userId, dto);

            verify(userMapper).toResponseDTO(any(User.class), any());
            assertThat(result).isSameAs(responseDTO);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsWhenUserNotFound() {
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(userRepository, never()).save(any());
        }
    }

    // =========================================================================
    // welcomeRoles — every role the one welcome mail lists, each with its code
    // =========================================================================

    @Nested
    @DisplayName("welcomeRoles")
    class WelcomeRoles {

        private UserRoleHospitalAssignment assignment(String roleCode, String hospital,
                                                      String assignmentCode, String confirmationCode) {
            var a = new UserRoleHospitalAssignment();
            var role = new com.example.hms.model.Role();
            role.setCode(roleCode);
            a.setRole(role);
            if (hospital != null) {
                var h = new com.example.hms.model.Hospital();
                h.setName(hospital);
                a.setHospital(h);
            }
            a.setAssignmentCode(assignmentCode);
            a.setConfirmationCode(confirmationCode);
            return a;
        }

        @Test
        @DisplayName("lists every role with its own code and a link to its own assignment")
        void everyRoleWithItsOwnCodeAndLink() {
            var roles = UserServiceImpl.welcomeRoles(List.of(
                    assignment("ROLE_NURSE", "CHU A", "A-1", "111111"),
                    assignment("ROLE_RECEPTIONIST", "CHU A", "A-2", "222222")),
                code -> "https://portal/onboarding/" + code);

            assertThat(roles).containsExactly(
                new EmailService.WelcomeRole("ROLE_NURSE", "CHU A", "111111", "https://portal/onboarding/A-1"),
                new EmailService.WelcomeRole("ROLE_RECEPTIONIST", "CHU A", "222222", "https://portal/onboarding/A-2"));
        }

        @Test
        @DisplayName("leaves out an assignment missing a code, so its own mail still goes out")
        void missingCodesAreLeftOut() {
            var roles = UserServiceImpl.welcomeRoles(List.of(
                    assignment("ROLE_NURSE", null, "A-1", "111111"),
                    assignment("ROLE_DOCTOR", null, "A-2", " "),
                    assignment("ROLE_MIDWIFE", null, null, "333333")),
                code -> "link-" + code);

            assertThat(roles).extracting(EmailService.WelcomeRole::roleCode).containsExactly("ROLE_NURSE");
            assertThat(roles.get(0).hospitalName()).isNull();
            assertThat(UserServiceImpl.welcomeRoles(null, code -> code)).isEmpty();
        }
    }

    // =========================================================================
    // Reactivation clears the login lockout
    // =========================================================================

    @Nested
    @DisplayName("reactivation and the login lockout")
    class ReactivationLockout {

        @Test
        @DisplayName("restoreUser clears the lockout the deactivation left behind")
        void restoreClearsTheLockout() {
            User target = new User();
            target.setId(userId);
            target.setUsername("someone");
            target.setActive(false);
            target.setDeleted(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());

            userService.restoreUser(userId);

            assertThat(target.isActive()).isTrue();
            // No transaction is active in a unit test, so the after-commit
            // callback runs inline — the assertion still pins the behaviour.
            verify(loginAttemptService).resetAttempts(userId);
        }

        @Test
        @DisplayName("updateUser clears it on a false to true transition")
        void updateClearsOnReactivation() {
            User target = reactivationTarget(false);

            userService.updateUser(userId, activeFlag(true));

            assertThat(target.isActive()).isTrue();
            verify(loginAttemptService).resetAttempts(userId);
        }

        @Test
        @DisplayName("creating an account clears nothing: its id has no lockout to inherit")
        void creationNeedsNoClear() {
            when(userRepository.findByUsername("newstaff")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("staff@hospital.com")).thenReturn(Optional.empty());
            when(userRepository.findByPhoneNumber("+1234567899")).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) u.setId(UUID.randomUUID());
                return u;
            });
            when(roleRepository.findByCode("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
            when(passwordEncoder.encode(any())).thenReturn("encoded");
            UserRoleHospitalAssignment created = new UserRoleHospitalAssignment();
            created.setId(UUID.randomUUID());
            created.setRole(superAdminRole);
            created.setActive(false);
            when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(
                    any(), isNull(), any()))
                .thenReturn(Optional.of(created));
            when(userRepository.findByIdWithRolesAndProfiles(any(UUID.class)))
                .thenAnswer(inv -> {
                    User reloaded = new User();
                    reloaded.setId(inv.getArgument(0));
                    return Optional.of(reloaded);
                });
            when(userMapper.toResponseDTO(any(), any())).thenReturn(new UserResponseDTO());

            AdminSignupRequest req = new AdminSignupRequest();
            req.setUsername("newstaff");
            req.setEmail("staff@hospital.com");
            req.setPhoneNumber("+1234567899");
            req.setFirstName("John");
            req.setLastName("Doe");
            req.setPassword("Temp@1234");
            // SUPER_ADMIN: the one staff role registrable without a hospital
            // context, which keeps this fixture out of JWT-resolution stubs.
            req.setRoleNames(Set.of("ROLE_SUPER_ADMIN"));

            userService.createUserWithRolesAndHospital(req);

            // The throttle is keyed on the account id: failures probed against
            // this name before it existed sit on the name's key, which the
            // name stops using once an account holds it (pinned in
            // LoginAttemptServiceTest). Nothing to clear here.
            verify(loginAttemptService, never()).resetAttempts(any());
        }

        @Test
        @DisplayName("updateUser leaves it alone when the account was already active")
        void updateDoesNotClearWhenAlreadyActive() {
            reactivationTarget(true);

            userService.updateUser(userId, activeFlag(true));

            // An ordinary profile edit must not silently clear a lockout
            // somebody else earned — PUT /users/{id} is not role-gated. Only
            // the never() carries weight here: updateUser sets active from the
            // DTO either way, so asserting it stayed true proves nothing.
            verify(loginAttemptService, never()).resetAttempts(any());
        }

        @Test
        @DisplayName("a rename + reactivate clears the account's own lockout, by id")
        void updateClearsByIdAcrossARename() {
            reactivationTarget(false);
            UpdateUserRequestDTO dto = activeFlag(true);
            dto.setUsername("renamed");

            userService.updateUser(userId, dto);

            // By id: the lockout collected under the old name is the
            // account's own and is cleared with it; no other account's can be.
            verify(loginAttemptService).resetAttempts(userId);
        }

        private User reactivationTarget(boolean alreadyActive) {
            User target = new User();
            target.setId(userId);
            target.setUsername("someone");
            target.setActive(alreadyActive);
            when(userRepository.findById(userId)).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findByUser(any())).thenReturn(Set.of());
            when(userMapper.toResponseDTO(any(), any())).thenReturn(new UserResponseDTO());
            return target;
        }

        private UpdateUserRequestDTO activeFlag(boolean active) {
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setActive(active);
            return dto;
        }
    }

    // =====================================================================
    // The caller guard (UserAccountAccess) as the service applies it
    // =====================================================================

    @Nested
    @DisplayName("account access guard")
    class AccountAccessGuard {

        private static final String EXISTING_HASH = "$2a$10$existingHash";

        @BeforeEach
        void target() {
            user.setPasswordHash(EXISTING_HASH);
            user.setActive(true);
            user.setDeleted(false);
        }

        private void refuseAdministration() {
            when(accountAccess.canAdminister(user)).thenReturn(false);
        }

        @Test
        @DisplayName("PUT on someone else's account without administering it: 404, nothing saved, hash unchanged")
        void updateByStrangerIsRefusedAsMissing() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            refuseAdministration();
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("Attacker-Chosen-1");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage(new ResourceNotFoundException("user.notFound", userId).getMessage());

            assertThat(user.getPasswordHash()).isEqualTo(EXISTING_HASH);
            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("a refused PUT writes exactly one FAILURE row, actor and target ids only, and saves nothing")
        void refusedWriteLeavesOneFailureRow() {
            UUID actorId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            refuseAdministration();
            when(accountAccess.currentUserId()).thenReturn(Optional.of(actorId));
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("Attacker-Chosen-1");
            dto.setEmail("attacker@evil.test");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(ResourceNotFoundException.class);

            ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> row =
                    ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(row.capture());
            assertThat(row.getValue().getStatus()).isEqualTo(com.example.hms.enums.AuditStatus.FAILURE);
            assertThat(row.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.USER_UPDATE);
            assertThat(row.getValue().getUserId()).isEqualTo(actorId);
            assertThat(row.getValue().getResourceId()).isEqualTo(userId.toString());
            // Never the submitted values, nor any name.
            assertThat(row.getValue().getDetails()).isNull();
            assertThat(row.getValue().getUserName()).isNull();
            assertThat(row.getValue().getResourceName()).isNull();
            assertThat(row.getValue().getEventDescription())
                    .doesNotContain("Attacker").doesNotContain("evil").doesNotContain("testuser");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("an admin rename to a case variant of another account's username: 400, nothing saved, other account not named")
        void adminRenameToCaseVariantIsRefused() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.existsUsernameOnOtherAccount("SuperAdmin", userId)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setUsername("SuperAdmin");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(com.example.hms.exception.BusinessException.class)
                    .hasMessageNotContaining("SuperAdmin");
            assertThat(user.getUsername()).isEqualTo("testuser");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("an admin re-email to one another account holds: 400, nothing saved")
        void adminReEmailToTakenAddressIsRefused() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.existsEmailOnOtherAccount("Boss@Example.com", userId)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setEmail("Boss@Example.com");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(com.example.hms.exception.BusinessException.class)
                    .hasMessageNotContaining("Boss");
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a self-edit may not set a password: it goes through /auth/me/change-password")
        void selfEditCannotSetPassword() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setPassword("New-Password-1");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(com.example.hms.exception.BusinessException.class)
                    .hasMessage(com.example.hms.utility.MessageUtil.resolve("user.update.self.password"));
            assertThat(user.getPasswordHash()).isEqualTo(EXISTING_HASH);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a self-edit may not switch its own account off")
        void selfEditCannotChangeActive() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setActive(false);

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(com.example.hms.exception.BusinessException.class);
            assertThat(user.isActive()).isTrue();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a self-edit may not rename: it goes through /auth/me/change-username")
        void selfEditCannotRename() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setUsername("someone-else");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(com.example.hms.exception.BusinessException.class)
                    .hasMessage(com.example.hms.utility.MessageUtil.resolve("user.update.self.username"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("a self-edit may not change the email: it goes through /auth/me/change-email, which needs the password")
        void selfEditCannotChangeEmail() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(true);
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("Awa");
            dto.setEmail("stolen-session@evil.test");

            assertThatThrownBy(() -> userService.updateUser(userId, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(com.example.hms.utility.MessageUtil.resolve("user.update.self.email"));
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getFirstName()).isEqualTo("Test");
            verify(userRepository, never()).save(any());
            verify(userRepository, never()).existsEmailOnOtherAccount(any(), any());
        }

        @Test
        @DisplayName("an administrator (not self) may still change an account's email on PUT")
        void administratorMayStillChangeEmail() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findByUser(any())).thenReturn(Set.of());
            when(userMapper.toResponseDTO(any(), any())).thenReturn(new UserResponseDTO());
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setEmail("new-address@example.com");

            userService.updateUser(userId, dto);

            assertThat(user.getEmail()).isEqualTo("new-address@example.com");
        }

        @Test
        @DisplayName("the profile form's self-edit — names, the unchanged email and username, phone, active flag — saves")
        void profileSelfEditSaves() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.isSelf(user)).thenReturn(true);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findByUser(any())).thenReturn(Set.of());
            when(userMapper.toResponseDTO(any(), any())).thenReturn(new UserResponseDTO());
            UpdateUserRequestDTO dto = new UpdateUserRequestDTO();
            dto.setFirstName("Awa");
            dto.setLastName("Traore");
            dto.setEmail("test@example.com");
            dto.setPhoneNumber("+22670111111");
            dto.setUsername("testuser");
            dto.setActive(true);

            userService.updateUser(userId, dto);

            assertThat(user.getFirstName()).isEqualTo("Awa");
            assertThat(user.getEmail()).isEqualTo("test@example.com");
            assertThat(user.getPasswordHash()).isEqualTo(EXISTING_HASH);
            verify(userRepository).save(user);
            // The self path never consults the administrator rule.
            verify(accountAccess, never()).canAdminister(any());
        }

        @Test
        @DisplayName("DELETE by a registrar of the unclaimed account its failed registration made: soft-deleted")
        void registrarDiscardsUnclaimedAccount() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.canDelete(user)).thenReturn(true);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(staffRepository.findByUserId(userId)).thenReturn(List.of());

            userService.deleteUser(userId);

            assertThat(user.isDeleted()).isTrue();
            verify(assignmentService).deleteAllAssignmentsForUser(userId);
        }

        @Test
        @DisplayName("DELETE of any other account without administering it: 404, untouched")
        void deleteByStrangerIsRefusedAsMissing() {
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(accountAccess.canDelete(user)).thenReturn(false);

            assertThatThrownBy(() -> userService.deleteUser(userId))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(user.isDeleted()).isFalse();
            verify(auditEventLogService).logEvent(argThat(r ->
                    r.getStatus() == com.example.hms.enums.AuditStatus.FAILURE
                            && r.getEventType() == com.example.hms.enums.AuditEventType.USER_DELETE));
            verify(userRepository, never()).save(any());
            verifyNoInteractions(assignmentService);
        }

        @Test
        @DisplayName("restore of an account the caller does not administer: 404, still deleted")
        void restoreByNonAdministratorIsRefusedAsMissing() {
            user.setDeleted(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            refuseAdministration();

            assertThatThrownBy(() -> userService.restoreUser(userId))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(user.isDeleted()).isTrue();
            verify(userRepository, never()).save(any());
            verify(auditEventLogService).logEvent(argThat(r ->
                    r.getStatus() == com.example.hms.enums.AuditStatus.FAILURE));
        }

        @Test
        @DisplayName("GET by id the caller may not view: 404, same as missing")
        void getByIdNotViewableIsRefusedAsMissing() {
            when(userRepository.findByIdWithRolesAndProfiles(userId)).thenReturn(Optional.of(user));
            when(accountAccess.canView(user)).thenReturn(false);

            assertThatThrownBy(() -> userService.getUserById(userId))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(userMapper);
        }

        @Test
        @DisplayName("a registration the caller may not grant: 403, one FAILURE row with actor id and role codes only, nothing written")
        void refusedGrantIsAuditedAndWritesNothing() {
            UUID actorId = UUID.randomUUID();
            when(accountAccess.requireMayGrant(any()))
                    .thenThrow(new org.springframework.security.access.AccessDeniedException("Access denied"));
            when(accountAccess.currentUserId()).thenReturn(Optional.of(actorId));
            AdminSignupRequest request = new AdminSignupRequest();
            request.setUsername("mint-admin");
            request.setEmail("mint@evil.test");
            request.setPassword("Chosen-Pass-1");
            request.setFirstName("Mint");
            request.setLastName("Admin");
            request.setPhoneNumber("+22670999999");
            request.setRoleNames(Set.of("SUPER_ADMIN"));

            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(request))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

            ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> row =
                    ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(row.capture());
            assertThat(row.getValue().getStatus()).isEqualTo(com.example.hms.enums.AuditStatus.FAILURE);
            assertThat(row.getValue().getUserId()).isEqualTo(actorId);
            assertThat(String.valueOf(row.getValue().getDetails())).contains("SUPER_ADMIN")
                    .doesNotContain("mint").doesNotContain("Chosen").doesNotContain("+22670999999");
            // Refused before any lookup: not even the duplicate checks ran.
            verifyNoInteractions(userRepository, passwordEncoder, assignmentService);
        }

        @Test
        @DisplayName("the directory refuses a non-staff caller before any query runs")
        void directoryRefusalPrecedesTheQuery() {
            doThrow(new org.springframework.security.access.AccessDeniedException("Access denied"))
                    .when(accountAccess).requireDirectoryAccess();

            assertThatThrownBy(() -> userService.getAllUsers(0, 10, false, false))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> userService.searchUsers("a", null, null, 0, 10, false, false))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verifyNoInteractions(userRepository);
        }
    }

    @Nested
    @DisplayName("the directory's hospital scope")
    class DirectoryScoping {

        private final org.springframework.data.domain.Page<User> onePage =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of());

        @Test
        @DisplayName("a scoped caller queries with its hospitals and scoped=true, never the deleted view")
        void scopedCallerPassesItsHospitals() {
            Set<UUID> hospitals = Set.of(UUID.randomUUID());
            when(accountAccess.requireDirectoryAccess())
                    .thenReturn(new UserAccountAccess.DirectoryScope(false, hospitals));
            when(userRepository.findAllPaged(anyBoolean(), anyBoolean(), anyBoolean(), any(), any()))
                    .thenReturn(onePage);
            when(userRepository.searchUsers(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(),
                    any())).thenReturn(onePage);

            // Even if a deleted flag reached the service, the scoped path has no deleted view.
            userService.getAllUsers(0, 10, true, true);
            userService.searchUsers("ami", "ROLE_NURSE", "x@y", 0, 10, true, true);

            verify(userRepository).findAllPaged(eq(false), eq(false), eq(true), eq(hospitals), any());
            verify(userRepository).searchUsers(eq("ami"), eq("ROLE_NURSE"), eq("x@y"), eq(false), eq(false),
                    eq(true), eq(hospitals), any());
        }

        @Test
        @DisplayName("an empty scope is an empty page, and no query runs")
        void emptyScopeIsAnEmptyPage() {
            when(accountAccess.requireDirectoryAccess())
                    .thenReturn(new UserAccountAccess.DirectoryScope(false, Set.of()));

            assertThat(userService.getAllUsers(0, 10, false, false).getTotalElements()).isZero();
            assertThat(userService.searchUsers("a", null, null, 0, 10, false, false).getTotalElements()).isZero();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("the super-admin queries unscoped, with the sentinel set and the deleted flags passed through")
        void superAdminQueriesUnscoped() {
            when(accountAccess.requireDirectoryAccess())
                    .thenReturn(new UserAccountAccess.DirectoryScope(true, Set.of()));
            when(userRepository.findAllPaged(anyBoolean(), anyBoolean(), anyBoolean(), any(), any()))
                    .thenReturn(onePage);
            when(userRepository.searchUsers(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(),
                    any())).thenReturn(onePage);

            userService.getAllUsers(0, 10, true, false);
            userService.searchUsers("a", null, null, 0, 10, false, true);

            verify(userRepository).findAllPaged(eq(true), eq(false), eq(false),
                    eq(UserRepository.DIRECTORY_UNSCOPED), any());
            verify(userRepository).searchUsers(eq("a"), any(), any(), eq(false), eq(true), eq(false),
                    eq(UserRepository.DIRECTORY_UNSCOPED), any());
        }
    }

    @Nested
    @DisplayName("registration without username/password/licence (D1, D2, D4, D13)")
    class GeneratedCredentials {


        private void stubCreation(Role role) {
            lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) u.setId(UUID.randomUUID());
                return u;
            });
            lenient().when(userRepository.existsByUsername(any())).thenReturn(false);
            lenient().when(roleRepository.findByCode(role.getCode())).thenReturn(Optional.of(role));
            lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            a.setId(UUID.randomUUID());
            a.setRole(role);
            lenient().when(assignmentRepository.findFirstByUserIdAndHospitalIdAndRoleId(any(), any(), any()))
                .thenReturn(Optional.of(a));
            lenient().when(userRepository.findByIdWithRolesAndProfiles(any(UUID.class)))
                .thenAnswer(inv -> {
                    User reloaded = new User();
                    reloaded.setId(inv.getArgument(0));
                    return Optional.of(reloaded);
                });
            lenient().when(userMapper.toResponseDTO(any(), any())).thenAnswer(inv -> new UserResponseDTO());
        }

        private AdminSignupRequest request(String roleCode, String email, String phone) {
            AdminSignupRequest req = new AdminSignupRequest();
            req.setFirstName("Aïcha");
            req.setLastName("Ouédraogo");
            req.setEmail(email);
            req.setPhoneNumber(phone);
            req.setRoleNames(Set.of(roleCode));
            return req;
        }

        private User savedUser() {
            org.mockito.ArgumentCaptor<User> c = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(c.capture());
            return c.getValue();
        }

        @Test
        @DisplayName("no username/password: both generated, both change flags set, local phone becomes +226")
        void generatesUsernameAndPassword() {
            stubCreation(superAdminRole);
            AdminSignupRequest req = request("ROLE_SUPER_ADMIN", "a@hospital.bf", "70202020");

            userService.createUserWithRolesAndHospital(req);

            User saved = savedUser();
            assertThat(saved.getUsername()).matches("aicha\\.ouedraogo\\d{4}");
            assertThat(saved.isForcePasswordChange()).isTrue();
            assertThat(saved.isForceUsernameChange()).isTrue();
            assertThat(saved.getPhoneNumber()).isEqualTo("+22670202020");
            assertThat(req.getPassword()).hasSize(14);
        }

        @Test
        @DisplayName("a username already taken is retried until a free one is found")
        void generatedUsernameAvoidsCollisions() {
            stubCreation(superAdminRole);
            when(userRepository.existsByUsername(any())).thenReturn(true, true, false);

            userService.createUserWithRolesAndHospital(request("ROLE_SUPER_ADMIN", "a@hospital.bf", "70202020"));

            verify(userRepository, times(3)).existsByUsername(any());
        }

        @Test
        @DisplayName("an invalid phone number is refused before anything is saved")
        void rejectsInvalidPhone() {
            AdminSignupRequest req = request("ROLE_SUPER_ADMIN", "a@hospital.bf", "7020-abc");
            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(req))
                .isInstanceOf(com.example.hms.exception.BusinessException.class);
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a patient with no e-mail and no SMS transport gets the generated credentials once in the response")
        void phoneOnlyPatientGetsCredentialsInResponse() {
            stubCreation(patientRole);
            when(smsService.deliversRealSms()).thenReturn(false);

            UserResponseDTO result = userService.createUserWithRolesAndHospital(
                request("ROLE_PATIENT", null, "70202020"));

            User saved = savedUser();
            assertThat(result.getTemporaryUsername()).isEqualTo(saved.getUsername());
            assertThat(result.getTemporaryPassword()).hasSize(14);
        }

        @Test
        @DisplayName("with a real SMS transport the credentials go by SMS, not into the response")
        void phoneOnlyPatientWithSmsGetsNothingInResponse() {
            stubCreation(patientRole);
            when(smsService.deliversRealSms()).thenReturn(true);

            UserResponseDTO result = userService.createUserWithRolesAndHospital(
                request("ROLE_PATIENT", null, "70202020"));

            assertThat(result.getTemporaryPassword()).isNull();
            assertThat(result.getTemporaryUsername()).isNull();
        }

        @Test
        @DisplayName("an admin-typed password is never handed out: not in the response, not on the patient assignment")
        void adminTypedPasswordIsNeverDelivered() {
            stubCreation(patientRole);
            AdminSignupRequest req = request("ROLE_PATIENT", null, "70202020");
            req.setUsername("chosen.name");
            req.setPassword("Typed@Secret1");

            UserResponseDTO result = userService.createUserWithRolesAndHospital(req);

            assertThat(result.getTemporaryPassword()).isNull();
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a person stored with a legacy local phone is matched, not created a second time")
        void legacyLocalPhoneRowIsMatched() {
            stubCreation(patientRole);
            User legacy = new User();
            legacy.setId(UUID.randomUUID());
            legacy.setPhoneNumber("70202020");
            when(userRepository.findByPhoneNumber("70202020")).thenReturn(Optional.of(legacy));

            assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(
                    request("ROLE_PATIENT", null, "70202020")))
                .isInstanceOf(Exception.class);
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a patient registration carrying a clinician's licence creates a new user, never resolves the clinician")
        void patientCarryingStaffLicenceCreatesNewUser() {
            stubCreation(patientRole);
            AdminSignupRequest req = request("ROLE_PATIENT", null, "70202020");
            req.setLicenseNumber("LIC-CLINICIAN-1");

            userService.createUserWithRolesAndHospital(req);

            verify(staffRepository, never()).findUserIdByLicense(any());
            assertThat(savedUser().getUsername()).isNotBlank();
        }
    }
}
