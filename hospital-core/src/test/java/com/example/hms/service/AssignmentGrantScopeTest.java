package com.example.hms.service;

import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an {@code /assignments} caller may grant, and which existing rows it
 * may read or change, through the real {@link UserAccountAccess} rules: a
 * hospital admin grants non-admin roles at the hospitals they administer and
 * manages the rows there; a verified super-admin does anything. A row outside
 * the caller's scope answers exactly as a missing id.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentGrantScopeTest {

    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private AssignmentLinkService assignmentLinkService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private com.example.hms.repository.UserRoleRepository userRoleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private com.example.hms.repository.OrganizationRepository organizationRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private UserRoleHospitalAssignmentMapper mapper;
    @Mock private MessageSource messageSource;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UserRoleHospitalAssignmentServiceImpl service;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital hospitalA = hospital("HA");
    private final Hospital hospitalB = hospital("HB");
    private final Role nurse = role("ROLE_NURSE");
    private final Role hospitalAdmin = role("ROLE_HOSPITAL_ADMIN");
    private final Role superAdmin = role("ROLE_SUPER_ADMIN");
    private final User assignee = account(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        RoleValidator roleValidator = new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver());
        UserAccountAccess access = new UserAccountAccess(assignmentRepository, patientRepository,
            registrationRepository, staffRepository, roleValidator);
        ReflectionTestUtils.setField(service, "roleValidator", roleValidator);
        ReflectionTestUtils.setField(service, "accountAccess", access);

        when(messageSource.getMessage(anyString(), any(), anyString(), any()))
            .thenAnswer(inv -> inv.getArgument(2));
        for (Role r : List.of(nurse, hospitalAdmin, superAdmin)) {
            when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        for (Hospital h : List.of(hospitalA, hospitalB)) {
            when(hospitalRepository.findById(h.getId())).thenReturn(Optional.of(h));
        }
        when(userRepository.findById(assignee.getId())).thenReturn(Optional.of(assignee));
        when(mapper.toEntity(any(), any(), any(), any())).thenAnswer(inv -> {
            UserRoleHospitalAssignmentRequestDTO dto = inv.getArgument(0);
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            a.setUser(inv.getArgument(1));
            a.setHospital(inv.getArgument(2));
            a.setRole(inv.getArgument(3));
            a.setActive(dto.getActive());
            return a;
        });
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static Hospital hospital(String code) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setCode(code);
        h.setName("Hospital " + code);
        return h;
    }

    private static Role role(String code) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setCode(code);
        r.setName(code);
        return r;
    }

    private static User account(UUID id) {
        User u = new User();
        u.setId(id);
        u.setUsername("u-" + id.toString().substring(0, 8));
        u.setUserRoles(new HashSet<>());
        return u;
    }

    private static UserRoleHospitalAssignment row(User user, Role role, Hospital hospital, boolean active) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setRole(role);
        a.setHospital(hospital);
        a.setActive(active);
        a.setConfirmationCode("123456");
        a.setCreatedAt(LocalDateTime.now().minusDays(1));
        return a;
    }

    private UserRoleHospitalAssignment stored(UserRoleHospitalAssignment a) {
        when(assignmentRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    /** A hospital admin of hospital A only: the verified super-admin flag is off. */
    private void signInAsHospitalAdminOfA() {
        signIn(false, "ROLE_HOSPITAL_ADMIN");
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(List.of(row(account(callerId), hospitalAdmin, hospitalA, true)));
    }

    private void signInAsSuperAdmin() {
        signIn(true, "ROLE_SUPER_ADMIN");
    }

    private void signIn(boolean verifiedSuperAdmin, String... authorities) {
        var details = new CustomUserDetails(account(callerId), AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .superAdmin(verifiedSuperAdmin)
            .build());
    }

    private UserRoleHospitalAssignmentRequestDTO grant(Role role, Hospital hospital) {
        UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
        dto.setUserId(assignee.getId());
        dto.setRoleId(role.getId());
        dto.setHospitalId(hospital == null ? null : hospital.getId());
        return dto;
    }

    /** The answer for a row the caller may not touch is the answer for no row at all. */
    private void assertAnswersAsMissing(UUID id, Executable call) {
        ResourceNotFoundException missing = new ResourceNotFoundException("roleAssignment.notFound", id);
        Throwable thrown = catchThrowable(call::execute);
        assertThat(thrown).isExactlyInstanceOf(ResourceNotFoundException.class)
            .hasMessage(missing.getMessage());
        assertThat(((ResourceNotFoundException) thrown).getMessageKey()).isEqualTo(missing.getMessageKey());
        assertThat(((ResourceNotFoundException) thrown).getArgs()).containsExactly(id);
    }

    private void assertNothingWritten() {
        verify(assignmentRepository, never()).save(any());
        verify(assignmentRepository, never()).saveAll(any());
        verify(assignmentRepository, never()).deleteById(any());
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("creating an assignment")
    class Create {

        @Test
        @DisplayName("a hospital admin cannot grant SUPER_ADMIN, to anyone or themselves; nothing is saved")
        void hospitalAdminCannotGrantSuperAdmin() {
            signInAsHospitalAdminOfA();
            when(userRepository.findById(callerId)).thenReturn(Optional.of(account(callerId)));
            UserRoleHospitalAssignmentRequestDTO toSelf = grant(superAdmin, null);
            toSelf.setUserId(callerId);
            toSelf.setActive(true);

            assertThatThrownBy(() -> service.assignRole(toSelf)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.assignRole(grant(superAdmin, null)))
                .isInstanceOf(AccessDeniedException.class);

            assertNothingWritten();
            verify(userRepository, never()).findById(callerId);
            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService, org.mockito.Mockito.times(2)).logEvent(audit.capture());
            assertThat(audit.getValue().getStatus()).isEqualTo(AuditStatus.FAILURE);
            assertThat(audit.getValue().getUserId()).isEqualTo(callerId);
        }

        @Test
        @DisplayName("a ROLE_SUPER_ADMIN authority without the verified flag grants no SUPER_ADMIN either")
        void inflatedAuthorityIsNotSuperAdmin() {
            signIn(false, "ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN");

            assertThatThrownBy(() -> service.assignRole(grant(superAdmin, null)))
                .isInstanceOf(AccessDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin cannot grant another admin role, even at their own hospital")
        void hospitalAdminCannotGrantAdminRoles() {
            signInAsHospitalAdminOfA();

            assertThatThrownBy(() -> service.assignRole(grant(hospitalAdmin, hospitalA)))
                .isInstanceOf(AccessDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin cannot grant at a hospital they do not administer")
        void hospitalAdminCannotGrantElsewhere() {
            signInAsHospitalAdminOfA();

            assertThatThrownBy(() -> service.assignRole(grant(nurse, hospitalB)))
                .isInstanceOf(AccessDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin still grants a staff role at their hospital, inactive until confirmed")
        void hospitalAdminGrantsAtTheirHospital() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignmentRequestDTO dto = grant(nurse, hospitalA);
            dto.setActive(true);

            service.assignRole(dto);

            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(hospitalA);
            assertThat(saved.getValue().getActive()).isFalse();
        }

        @Test
        @DisplayName("a verified super-admin grants SUPER_ADMIN, active at once")
        void superAdminGrantsSuperAdmin() {
            signInAsSuperAdmin();

            service.assignRole(grant(superAdmin, null));

            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getRole()).isSameAs(superAdmin);
            assertThat(saved.getValue().getActive()).isTrue();
        }

        @Test
        @DisplayName("account creation's own SUPER_ADMIN row is active only when asked for explicitly")
        void accountCreationSuperAdminRowIsNeverActiveByDefault() {
            UserRoleHospitalAssignmentRequestDTO dto = grant(superAdmin, null);

            service.assignRoleOnAccountCreation(dto);

            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getActive()).isFalse();
        }

        @Test
        @DisplayName("a verified super-admin grants at any hospital")
        void superAdminGrantsAnywhere() {
            signInAsSuperAdmin();

            service.assignRole(grant(hospitalAdmin, hospitalB));

            verify(assignmentRepository).save(any());
        }

        @Test
        @DisplayName("multi-scope: a hospital the admin does not administer is reported, the others still go through")
        void multiScopeReportsTheRefusedScope() {
            signInAsHospitalAdminOfA();
            UserRoleAssignmentMultiRequestDTO request = new UserRoleAssignmentMultiRequestDTO();
            request.setUserId(assignee.getId());
            request.setRoleId(nurse.getId());
            request.setHospitalIds(List.of(hospitalA.getId(), hospitalB.getId()));
            request.setSkipConflicts(true);

            UserRoleAssignmentBatchResponseDTO result = service.assignRoleToMultipleScopes(request);

            assertThat(result.getCreatedAssignments()).isEqualTo(1);
            assertThat(result.getFailures()).singleElement()
                .satisfies(f -> assertThat(f.getHospitalId()).isEqualTo(hospitalB.getId()));
            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(hospitalA);
        }

        @Test
        @DisplayName("multi-scope with no hospital (a global grant) is refused outright for a hospital admin")
        void multiScopeGlobalGrantIsRefused() {
            signInAsHospitalAdminOfA();
            UserRoleAssignmentMultiRequestDTO request = new UserRoleAssignmentMultiRequestDTO();
            request.setUserId(assignee.getId());
            request.setRoleId(superAdmin.getId());

            assertThatThrownBy(() -> service.assignRoleToMultipleScopes(request))
                .isInstanceOf(AccessDeniedException.class);
            assertNothingWritten();
        }

        @Test
        @DisplayName("bulk import: a row the admin may not grant fails on its own, nothing saved for it")
        void bulkImportFailsTheRefusedRow() {
            signInAsHospitalAdminOfA();
            String csv = "user_id,role_id,hospital_id\n"
                + assignee.getId() + "," + nurse.getId() + "," + hospitalB.getId() + "\n"
                + assignee.getId() + "," + superAdmin.getId() + ",\n";
            UserRoleAssignmentBulkImportRequestDTO request = UserRoleAssignmentBulkImportRequestDTO.builder()
                .csvContent(csv)
                .delimiter(",")
                .skipConflicts(true)
                .build();

            UserRoleAssignmentBulkImportResponseDTO result = service.bulkImportAssignments(request);

            assertThat(result.getCreated()).isZero();
            assertThat(result.getFailed()).isEqualTo(2);
            assertNothingWritten();
        }
    }

    @Nested
    @DisplayName("updating an assignment")
    class Update {

        @Test
        @DisplayName("an inactive row at another hospital cannot be switched on: it answers as missing")
        void foreignInactiveRowCannotBeActivated() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment foreign = stored(row(assignee, nurse, hospitalB, false));
            UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
            dto.setActive(true);

            assertAnswersAsMissing(foreign.getId(), () -> service.updateAssignment(foreign.getId(), dto));
            assertThat(foreign.getActive()).isFalse();
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin cannot switch on even their own hospital's row by hand")
        void ownInactiveRowActivatesOnlyThroughItsCode() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment own = stored(row(assignee, nurse, hospitalA, false));
            UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
            dto.setActive(true);

            assertThatThrownBy(() -> service.updateAssignment(own.getId(), dto))
                .isInstanceOf(BusinessException.class);
            assertThat(own.getActive()).isFalse();
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin cannot move their row to another hospital, or to an admin role")
        void updateKeepsTheGrantRule() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment own = stored(row(assignee, nurse, hospitalA, true));
            UserRoleHospitalAssignmentRequestDTO elsewhere = new UserRoleHospitalAssignmentRequestDTO();
            elsewhere.setHospitalId(hospitalB.getId());
            UserRoleHospitalAssignmentRequestDTO promote = new UserRoleHospitalAssignmentRequestDTO();
            promote.setRoleId(hospitalAdmin.getId());

            assertThatThrownBy(() -> service.updateAssignment(own.getId(), elsewhere))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.updateAssignment(own.getId(), promote))
                .isInstanceOf(AccessDeniedException.class);
            assertThat(own.getHospital()).isSameAs(hospitalA);
            assertThat(own.getRole()).isSameAs(nurse);
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin still edits their hospital's staff row")
        void hospitalAdminEditsOwnRow() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment own = stored(row(assignee, nurse, hospitalA, true));
            UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
            dto.setActive(true);
            dto.setStartDate(java.time.LocalDate.now());

            service.updateAssignment(own.getId(), dto);

            verify(assignmentRepository).save(own);
        }

        @Test
        @DisplayName("a verified super-admin switches a row on directly, anywhere")
        void superAdminActivatesDirectly() {
            signInAsSuperAdmin();
            UserRoleHospitalAssignment foreign = stored(row(assignee, nurse, hospitalB, false));
            UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
            dto.setActive(true);

            service.updateAssignment(foreign.getId(), dto);

            verify(mapper).updateEntity(foreign, dto, hospitalB, nurse, null);
            verify(assignmentRepository).save(foreign);
        }
    }

    @Nested
    @DisplayName("reading and changing an existing row by id")
    class ById {

        @Test
        @DisplayName("another hospital's row: get, regenerate, resend, deactivate and delete all answer as missing")
        void foreignRowAnswersAsMissing() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment foreign = stored(row(assignee, nurse, hospitalB, true));
            UUID id = foreign.getId();

            assertAnswersAsMissing(id, () -> service.getAssignmentById(id));
            assertAnswersAsMissing(id, () -> service.regenerateAssignmentCode(id, true));
            assertAnswersAsMissing(id, () -> service.resendNotifications(id));
            assertAnswersAsMissing(id, () -> service.deactivateAssignment(id));
            assertAnswersAsMissing(id, () -> service.deleteAssignment(id));

            assertThat(foreign.getActive()).isTrue();
            assertThat(foreign.getConfirmationCode()).isEqualTo("123456");
            assertNothingWritten();
            org.mockito.Mockito.verifyNoInteractions(emailService, smsService, eventPublisher);
        }

        @Test
        @DisplayName("a global (super-admin) row is out of a hospital admin's reach")
        void globalRowAnswersAsMissing() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment global = stored(row(account(UUID.randomUUID()), superAdmin, null, true));

            assertAnswersAsMissing(global.getId(), () -> service.getAssignmentById(global.getId()));
            assertAnswersAsMissing(global.getId(), () -> service.deactivateAssignment(global.getId()));
            assertThat(global.getActive()).isTrue();
        }

        @Test
        @DisplayName("a peer hospital admin's row at the same hospital may be read but not changed")
        void peerAdminRowIsReadOnly() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment peer = stored(row(account(UUID.randomUUID()), hospitalAdmin, hospitalA, true));

            service.getAssignmentById(peer.getId());
            assertAnswersAsMissing(peer.getId(), () -> service.deactivateAssignment(peer.getId()));
            assertAnswersAsMissing(peer.getId(), () -> service.regenerateAssignmentCode(peer.getId(), false));
            assertThat(peer.getActive()).isTrue();
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin still manages their hospital's staff rows")
        void hospitalAdminManagesOwnRows() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment own = stored(row(assignee, nurse, hospitalA, true));

            service.getAssignmentById(own.getId());
            service.regenerateAssignmentCode(own.getId(), false);
            service.deactivateAssignment(own.getId());

            assertThat(own.getActive()).isFalse();
            verify(assignmentRepository, org.mockito.Mockito.atLeast(2)).save(own);
        }

        @Test
        @DisplayName("a verified super-admin manages any row")
        void superAdminManagesAnyRow() {
            signInAsSuperAdmin();
            UserRoleHospitalAssignment foreign = stored(row(assignee, nurse, hospitalB, true));

            service.getAssignmentById(foreign.getId());
            service.deactivateAssignment(foreign.getId());
            service.deleteAssignment(foreign.getId());

            verify(assignmentRepository).deleteById(foreign.getId());
        }
    }

    @Nested
    @DisplayName("retiring every assignment of a user")
    class RetireUser {

        @Test
        @DisplayName("a hospital admin leaves a super-admin's account untouched, even its rows at their hospital")
        void superAdminAccountIsShielded() {
            signInAsHospitalAdminOfA();
            User platformAdmin = account(UUID.randomUUID());
            UserRoleHospitalAssignment global = row(platformAdmin, superAdmin, null, true);
            UserRoleHospitalAssignment here = row(platformAdmin, nurse, hospitalA, true);
            when(userRepository.findById(platformAdmin.getId())).thenReturn(Optional.of(platformAdmin));
            when(assignmentRepository.findByUserId(platformAdmin.getId())).thenReturn(List.of(global, here));

            service.retireAssignmentsForUserWithinCallerScope(platformAdmin.getId());

            assertThat(global.getActive()).isTrue();
            assertThat(here.getActive()).isTrue();
            assertThat(here.getConfirmationCode()).isEqualTo("123456");
            assertNothingWritten();
        }

        @Test
        @DisplayName("…also when the super-admin role is only a global role")
        void superAdminByGlobalRoleIsShielded() {
            signInAsHospitalAdminOfA();
            User platformAdmin = account(UUID.randomUUID());
            UserRole link = new UserRole();
            link.setUser(platformAdmin);
            link.setRole(superAdmin);
            platformAdmin.getUserRoles().add(link);
            UserRoleHospitalAssignment here = row(platformAdmin, nurse, hospitalA, true);
            when(userRepository.findById(platformAdmin.getId())).thenReturn(Optional.of(platformAdmin));
            when(assignmentRepository.findByUserId(platformAdmin.getId())).thenReturn(List.of(here));

            service.retireAssignmentsForUserWithinCallerScope(platformAdmin.getId());

            assertThat(here.getActive()).isTrue();
            assertNothingWritten();
        }

        @Test
        @DisplayName("a hospital admin retires only the rows at their hospital")
        void hospitalAdminRetiresOnlyTheirRows() {
            signInAsHospitalAdminOfA();
            UserRoleHospitalAssignment here = row(assignee, nurse, hospitalA, true);
            UserRoleHospitalAssignment elsewhere = row(assignee, nurse, hospitalB, true);
            when(assignmentRepository.findByUserId(assignee.getId())).thenReturn(List.of(here, elsewhere));

            service.retireAssignmentsForUserWithinCallerScope(assignee.getId());

            assertThat(here.getActive()).isFalse();
            assertThat(elsewhere.getActive()).isTrue();
            verify(assignmentRepository).saveAll(List.of(here));
        }

        @Test
        @DisplayName("a verified super-admin retires every row")
        void superAdminRetiresEverything() {
            signInAsSuperAdmin();
            UserRoleHospitalAssignment here = row(assignee, nurse, hospitalA, true);
            UserRoleHospitalAssignment elsewhere = row(assignee, nurse, hospitalB, true);
            when(assignmentRepository.findByUserId(assignee.getId())).thenReturn(List.of(here, elsewhere));

            service.retireAssignmentsForUserWithinCallerScope(assignee.getId());

            assertThat(Arrays.asList(here.getActive(), elsewhere.getActive())).containsOnly(false);
        }
    }

    @Nested
    @DisplayName("deleting a role")
    class DeleteRole {

        @Test
        @DisplayName("a hospital admin may not delete a role: 403, nothing deleted")
        void hospitalAdminCannotDeleteARole() {
            signInAsHospitalAdminOfA();

            assertThatThrownBy(() -> service.deleteRole(nurse.getId())).isInstanceOf(AccessDeniedException.class);
            verify(roleRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("a verified super-admin deletes an unassigned role")
        void superAdminDeletesARole() {
            signInAsSuperAdmin();
            when(assignmentRepository.findByRoleId(nurse.getId())).thenReturn(List.of());

            service.deleteRole(nurse.getId());

            verify(roleRepository).deleteById(nurse.getId());
        }
    }
}
