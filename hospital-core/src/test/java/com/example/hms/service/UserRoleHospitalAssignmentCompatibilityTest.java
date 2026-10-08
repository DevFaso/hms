package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
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
import org.junit.jupiter.api.Nested;
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
import java.util.Set;
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
 * Provider plan AC-7 and §3.2a, through the REAL assignment service and the
 * REAL {@link FacilityAssignmentGuard}: a role a facility type does not take
 * is refused with a 400 ({@code role.facility.incompatible}) before anything
 * is saved, on every entry point (single, multi-scope, bulk); a user holds
 * active assignments at one kind of facility only ({@code role.facility.mixed}),
 * at creation and at activation; and the JPA backstop on the entity refuses
 * an incompatible row whatever its {@code active} flag says.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRoleHospitalAssignmentCompatibilityTest {

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
    private final Hospital hospital = facility("H", FacilityType.HOSPITAL);
    private final Hospital pharmacy = facility("P", FacilityType.PHARMACY);
    private final Hospital laboratory = facility("L", FacilityType.LABORATORY);
    private final Role doctor = role("ROLE_DOCTOR");
    private final Role pharmacist = role("ROLE_PHARMACIST");
    private final Role labScientist = role("ROLE_LAB_SCIENTIST");
    private final Role providerAdmin = role("ROLE_PROVIDER_ADMIN");
    private final Role patient = role("ROLE_PATIENT");
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
        for (Role r : List.of(doctor, pharmacist, labScientist, providerAdmin, patient)) {
            when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        for (Hospital h : List.of(hospital, pharmacy, laboratory)) {
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
        when(assignmentRepository.findByUser_IdAndActiveTrue(assignee.getId())).thenReturn(List.of());
        signInAsSuperAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @Nested
    @DisplayName("role/facility compatibility (400, before anything is saved)")
    class Compatibility {

        @Test
        @DisplayName("POST /assignments: DOCTOR at a pharmacy is refused")
        void doctorAtPharmacy() {
            assertRefused(() -> service.assignRole(grant(doctor, pharmacy)), FacilityAssignmentGuard.MSG_INCOMPATIBLE);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("POST /assignments: PROVIDER_ADMIN at a hospital is refused, even for a verified super-admin")
        void providerAdminAtHospital() {
            assertRefused(() -> service.assignRole(grant(providerAdmin, hospital)),
                FacilityAssignmentGuard.MSG_INCOMPATIBLE);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("account creation (admin-register's path) is held to the same rule")
        void accountCreationPath() {
            assertRefused(() -> service.assignRoleOnAccountCreation(grant(labScientist, pharmacy)),
                FacilityAssignmentGuard.MSG_INCOMPATIBLE);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("compatible pairs go through: PHARMACIST at a pharmacy, LAB_SCIENTIST at a lab, PROVIDER_ADMIN at both")
        void compatiblePairs() {
            assertThatCode(() -> service.assignRole(grant(pharmacist, pharmacy))).doesNotThrowAnyException();
            assertThatCode(() -> service.assignRole(grant(labScientist, laboratory))).doesNotThrowAnyException();
            assertThatCode(() -> service.assignRole(grant(providerAdmin, pharmacy))).doesNotThrowAnyException();
            assertThatCode(() -> service.assignRole(grant(providerAdmin, laboratory))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("multi-scope: the pharmacy is a per-hospital failure, not a 500; the hospital still goes through")
        void multiScopeRecordsAFailure() {
            UserRoleAssignmentMultiRequestDTO request = new UserRoleAssignmentMultiRequestDTO();
            request.setUserId(assignee.getId());
            request.setRoleId(doctor.getId());
            request.setHospitalIds(List.of(hospital.getId(), pharmacy.getId()));
            request.setSkipConflicts(true);

            UserRoleAssignmentBatchResponseDTO result = service.assignRoleToMultipleScopes(request);

            assertThat(result.getCreatedAssignments()).isEqualTo(1);
            assertThat(result.getFailures()).singleElement()
                .satisfies(f -> assertThat(f.getHospitalId()).isEqualTo(pharmacy.getId()));
            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(hospital);
        }

        @Test
        @DisplayName("bulk import: LAB_SCIENTIST at a pharmacy fails on its own line")
        void bulkImportLine() {
            String csv = "user_id,role_id,hospital_id\n"
                + assignee.getId() + "," + labScientist.getId() + "," + pharmacy.getId() + "\n"
                + assignee.getId() + "," + labScientist.getId() + "," + laboratory.getId() + "\n";
            UserRoleAssignmentBulkImportRequestDTO request = UserRoleAssignmentBulkImportRequestDTO.builder()
                .csvContent(csv)
                .delimiter(",")
                .skipConflicts(true)
                .build();

            UserRoleAssignmentBulkImportResponseDTO result = service.bulkImportAssignments(request);

            assertThat(result.getCreated()).isEqualTo(1);
            assertThat(result.getFailed()).isEqualTo(1);
            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(laboratory);
        }
    }

    @Nested
    @DisplayName("multi-scope fan-out over an organisation")
    class FanOut {

        @Test
        @DisplayName("an organisation expands to its hospitals only, never to a provider facility")
        void organisationSkipsProviders() {
            Organization organization = new Organization();
            organization.setId(UUID.randomUUID());
            organization.setHospitals(new HashSet<>(Set.of(hospital, pharmacy)));
            when(organizationRepository.findByIdWithHospitals(organization.getId()))
                .thenReturn(Optional.of(organization));
            UserRoleAssignmentMultiRequestDTO request = new UserRoleAssignmentMultiRequestDTO();
            request.setUserId(assignee.getId());
            request.setRoleId(pharmacist.getId());
            request.setOrganizationIds(List.of(organization.getId()));
            request.setSkipConflicts(true);

            UserRoleAssignmentBatchResponseDTO result = service.assignRoleToMultipleScopes(request);

            assertThat(result.getRequestedAssignments()).isEqualTo(1);
            assertThat(result.getFailures()).isEmpty();
            ArgumentCaptor<UserRoleHospitalAssignment> saved = ArgumentCaptor.forClass(UserRoleHospitalAssignment.class);
            verify(assignmentRepository).save(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(hospital);
        }
    }

    @Nested
    @DisplayName("one kind of facility per user (§3.2a)")
    class OneKindOfFacility {

        @Test
        @DisplayName("an active DOCTOR at a hospital is refused a PHARMACIST row at a pharmacy")
        void hospitalThenProvider() {
            holdsActive(row(assignee, doctor, hospital, true));

            assertRefused(() -> service.assignRole(grant(pharmacist, pharmacy)), FacilityAssignmentGuard.MSG_MIXED);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("and the reverse: an active PHARMACIST at a pharmacy is refused a DOCTOR row at a hospital")
        void providerThenHospital() {
            holdsActive(row(assignee, pharmacist, pharmacy, true));

            assertRefused(() -> service.assignRole(grant(doctor, hospital)), FacilityAssignmentGuard.MSG_MIXED);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a pharmacy and a laboratory are two kinds as well")
        void pharmacyThenLaboratory() {
            holdsActive(row(assignee, pharmacist, pharmacy, true));

            assertRefused(() -> service.assignRole(grant(labScientist, laboratory)), FacilityAssignmentGuard.MSG_MIXED);
        }

        @Test
        @DisplayName("the null-hospital PATIENT row does not count: a patient may work at a pharmacy")
        void globalPatientRowIsExempt() {
            holdsActive(row(assignee, patient, null, true));

            assertThatCode(() -> service.assignRole(grant(pharmacist, pharmacy))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("activation: a pending PHARMACIST row is not switched on beside an active DOCTOR row")
        void activationIsRefused() {
            UserRoleHospitalAssignment pending = row(assignee, pharmacist, pharmacy, false);
            pending.setAssignmentCode("ASG-1");
            pending.setConfirmationSentAt(LocalDateTime.now());
            when(assignmentRepository.findByAssignmentCode("ASG-1")).thenReturn(Optional.of(pending));
            holdsActive(row(assignee, doctor, hospital, true));

            assertRefused(() -> service.verifyAssignmentByCode("ASG-1", "123456"), FacilityAssignmentGuard.MSG_MIXED);
            assertThat(pending.getActive()).isFalse();
            verify(assignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the JPA backstop")
    class Backstop {

        @Test
        @DisplayName("an incompatible row is refused on persist even when it is inactive")
        void inactiveIncompatibleRow() {
            UserRoleHospitalAssignment a = row(assignee, doctor, pharmacy, false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(a, "onAssign"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("and on update")
        void onUpdate() {
            UserRoleHospitalAssignment a = row(assignee, providerAdmin, hospital, true);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(a, "onUpdateAssign"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a compatible row passes, and so does a global one")
        void compatibleRow() {
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(row(assignee, pharmacist, pharmacy, false), "onAssign"))
                .doesNotThrowAnyException();
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(row(assignee, patient, null, true), "onAssign"))
                .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String key) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey()).isEqualTo(key));
    }

    private void holdsActive(UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(assignee.getId())).thenReturn(List.of(rows));
    }

    private static Hospital facility(String code, FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setCode(code);
        h.setName("Facility " + code);
        h.setFacilityType(type);
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
        dto.setHospitalId(target == null ? null : target.getId());
        return dto;
    }
}
