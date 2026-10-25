package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.ConflictException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.provider.FacilityAssignmentGuard;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D789: the super-admin archived "QA R12 Hopital" and then
 * {@code POST /assignments} gave a NURSE role in it (200, with a code and a
 * confirmation link). Through the REAL assignment service and the REAL
 * {@link FacilityAssignmentGuard}: an archived hospital takes no new role on
 * any entry point (single, account creation, multi-scope, bulk), no
 * activation of an invitation and no re-invitation, with a 409
 * ({@link ConflictException}) before anything is saved. A suspended hospital
 * still takes them, an active one does too, and a row at an archived hospital
 * may still be switched off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchivedHospitalAssignmentTest {

    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private AssignmentLinkService assignmentLinkService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private com.example.hms.repository.UserRoleRepository userRoleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private com.example.hms.repository.EncounterRepository encounterRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private UserRoleHospitalAssignmentMapper mapper;
    @Mock private MessageSource messageSource;
    @Mock private FacilityAssignmentGuard unusedGuardMock;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UserRoleHospitalAssignmentServiceImpl service;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital archived = hospital("ARC", HospitalLifecycleState.ARCHIVED);
    private final Hospital pendingPurge = hospital("PUR", HospitalLifecycleState.PENDING_PURGE);
    private final Hospital suspended = hospital("SUS", HospitalLifecycleState.SUSPENDED);
    private final Hospital open = hospital("OPN", HospitalLifecycleState.ACTIVE);
    private final Role nurse = role("ROLE_NURSE");
    private final User assignee = account(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        RoleValidator roleValidator = new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver());
        UserAccountAccess access = new UserAccountAccess(assignmentRepository, patientRepository,
            registrationRepository, staffRepository, roleValidator);
        ReflectionTestUtils.setField(service, "roleValidator", roleValidator);
        ReflectionTestUtils.setField(service, "accountAccess", access);
        ReflectionTestUtils.setField(service, "facilityAssignmentGuard",
            new FacilityAssignmentGuard(assignmentRepository, hospitalRepository));

        when(messageSource.getMessage(anyString(), any(), anyString(), any()))
            .thenAnswer(inv -> inv.getArgument(2));
        when(roleRepository.findById(nurse.getId())).thenReturn(Optional.of(nurse));
        for (Hospital h : List.of(archived, pendingPurge, suspended, open)) {
            when(hospitalRepository.findById(h.getId())).thenReturn(Optional.of(h));
        }
        when(userRepository.findById(assignee.getId())).thenReturn(Optional.of(assignee));
        when(userRepository.findById(callerId)).thenReturn(Optional.of(account(callerId)));
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
        when(assignmentRepository.findByUser_IdAndActiveTrue(assignee.getId())).thenReturn(List.of());
        signInAsSuperAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("the QA call: POST /assignments, ROLE_NURSE in the archived hospital, is a 409 and nothing is saved")
    void nurseInArchivedHospital() {
        var request = grant(nurse, archived);
        assertThatThrownBy(() -> service.assignRole(request))
            .isInstanceOf(ConflictException.class)
            .hasMessage(FacilityAssignmentGuard.MSG_HOSPITAL_CLOSED);
        verify(assignmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a hospital on its way to the purge is closed as well")
    void pendingPurgeIsClosed() {
        var request = grant(nurse, pendingPurge);
        assertThatThrownBy(() -> service.assignRole(request))
            .isInstanceOf(ConflictException.class);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("account creation (admin-register and patient registration) is held to the same rule")
    void accountCreationPath() {
        var request = grant(nurse, archived);
        assertThatThrownBy(() -> service.assignRoleOnAccountCreation(request))
            .isInstanceOf(ConflictException.class);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("an active and a suspended hospital still take the role")
    void openAndSuspendedHospitalsStillTakeRoles() {
        assertThatCode(() -> service.assignRole(grant(nurse, open))).doesNotThrowAnyException();
        assertThatCode(() -> service.assignRole(grant(nurse, suspended))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("multi-scope with skipConflicts: the archived hospital is a per-scope failure, the open one goes through")
    void multiScopeSkipsTheArchivedHospital() {
        UserRoleAssignmentMultiRequestDTO request = multi(true);

        UserRoleAssignmentBatchResponseDTO result = service.assignRoleToMultipleScopes(request);

        assertThat(result.getCreatedAssignments()).isEqualTo(1);
        assertThat(result.getFailures()).singleElement().satisfies(f -> {
            assertThat(f.getHospitalId()).isEqualTo(archived.getId());
            assertThat(f.getMessage()).isEqualTo(FacilityAssignmentGuard.MSG_HOSPITAL_CLOSED);
        });
        ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertThat(saved.getValue().getHospital()).isSameAs(open);
    }

    @Test
    @DisplayName("multi-scope without skipConflicts: the whole request is the 409")
    void multiScopeWithoutSkipIsAConflict() {
        var request = multi(false);
        assertThatThrownBy(() -> service.assignRoleToMultipleScopes(request))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("bulk import: the archived hospital's line is skipped, the open one imports")
    void bulkImportLine() {
        String csv = "user_id,role_id,hospital_id\n"
            + assignee.getId() + "," + nurse.getId() + "," + archived.getId() + "\n"
            + assignee.getId() + "," + nurse.getId() + "," + open.getId() + "\n";
        UserRoleAssignmentBulkImportRequestDTO request = UserRoleAssignmentBulkImportRequestDTO.builder()
            .csvContent(csv)
            .delimiter(",")
            .skipConflicts(true)
            .build();

        UserRoleAssignmentBulkImportResponseDTO result = service.bulkImportAssignments(request);

        assertThat(result.getCreated()).isEqualTo(1);
        ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertThat(saved.getValue().getHospital()).isSameAs(open);
    }

    @Test
    @DisplayName("activation: an invitation to a hospital archived since is not switched on")
    void activationIsRefused() {
        UserRoleHospitalAssignment pending = row(nurse, archived, false);
        pending.setAssignmentCode("ASG-ARC");
        pending.setConfirmationSentAt(LocalDateTime.now());
        when(assignmentRepository.findByAssignmentCode("ASG-ARC")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.verifyAssignmentByCode("ASG-ARC", "123456"))
            .isInstanceOf(ConflictException.class);
        assertThat(pending.getActive()).isFalse();
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("regenerating the code (a fresh invitation) into an archived hospital is refused")
    void regenerateIsRefused() {
        UserRoleHospitalAssignment pending = row(nurse, archived, false);
        when(assignmentRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        UUID targetId = pending.getId();
        assertThatThrownBy(() -> service.regenerateAssignmentCode(targetId, true))
            .isInstanceOf(ConflictException.class);
        verify(assignmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PUT that switches a row on at an archived hospital is refused")
    void updateThatActivatesIsRefused() {
        UserRoleHospitalAssignment existing = row(nurse, archived, false);
        when(assignmentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
        dto.setActive(true);

        UUID targetId = existing.getId();
        assertThatThrownBy(() -> service.updateAssignment(targetId, dto))
            .isInstanceOf(ConflictException.class);
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT that switches a row off at an archived hospital still goes through")
    void updateThatDeactivatesIsAllowed() {
        UserRoleHospitalAssignment existing = row(nurse, archived, true);
        when(assignmentRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doAnswer(inv -> {
            UserRoleHospitalAssignment target = inv.getArgument(0);
            UserRoleHospitalAssignmentRequestDTO body = inv.getArgument(1);
            if (body.getActive() != null) {
                target.setActive(body.getActive());
            }
            return null;
        }).when(mapper).updateEntity(any(), any(), any(), any());
        UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
        dto.setActive(false);

        assertThatCode(() -> service.updateAssignment(existing.getId(), dto)).doesNotThrowAnyException();
        assertThat(existing.getActive()).isFalse();
    }

    @Test
    @DisplayName("the JPA backstop refuses a new row at an archived hospital, inactive or not")
    void backstopOnPersist() {
        var assignment = row(nurse, archived, false);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(assignment, "onAssign"))
            .isInstanceOf(ConflictException.class);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(row(nurse, suspended, false), "onAssign"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("and leaves an existing row at an archived hospital updatable (switching it off)")
    void backstopLeavesUpdatesAlone() {
        UserRoleHospitalAssignment existing = row(nurse, archived, false);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(existing, "onUpdateAssign"))
            .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- helpers

    private UserRoleAssignmentMultiRequestDTO multi(boolean skipConflicts) {
        UserRoleAssignmentMultiRequestDTO request = new UserRoleAssignmentMultiRequestDTO();
        request.setUserId(assignee.getId());
        request.setRoleId(nurse.getId());
        request.setHospitalIds(List.of(archived.getId(), open.getId()));
        request.setSkipConflicts(skipConflicts);
        return request;
    }

    private static Hospital hospital(String code, HospitalLifecycleState state) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setCode(code);
        h.setName("Hospital " + code);
        h.setFacilityType(FacilityType.HOSPITAL);
        h.setLifecycleState(state);
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

    private UserRoleHospitalAssignment row(Role role, Hospital hospital, boolean active) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setUser(assignee);
        a.setRole(role);
        a.setHospital(hospital);
        a.setActive(active);
        a.setConfirmationCode("123456");
        a.setCreatedAt(LocalDateTime.now().minusDays(1));
        return a;
    }

    private void signInAsSuperAdmin() {
        String[] authorities = {"ROLE_SUPER_ADMIN"};
        var details = new CustomUserDetails(account(callerId), AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .superAdmin(true)
            .build());
    }

    private UserRoleHospitalAssignmentRequestDTO grant(Role role, Hospital target) {
        UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
        dto.setUserId(assignee.getId());
        dto.setRoleId(role.getId());
        dto.setHospitalId(target.getId());
        return dto;
    }
}
