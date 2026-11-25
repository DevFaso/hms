package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D4020: whom an {@code /assignments} write may name as the holder of a new
 * staff assignment, through the real {@link UserAccountAccess} rules. A
 * hospital admin names an account already staff at a hospital it administers,
 * never an account known only to another hospital and never a patient-only
 * account, whichever identifier is typed (e-mail, phone, username, id) and
 * whether or not the request names its hospital in {@code X-Hospital-Id}.
 * Unknown, out of reach and patient-only answer the same 404 with the same
 * localized sentence; nothing is saved and nothing is sent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentHolderReachTest {

    private static final String EMAIL = "awa.sanou@other.test";
    private static final String PHONE = "+22670000091";
    private static final String USERNAME = "awa.sanou";

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
    @Mock private com.example.hms.security.provider.FacilityAssignmentGuard facilityAssignmentGuard;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.example.hms.service.support.AssignmentReferences assignmentReferences;

    @InjectMocks
    private UserRoleHospitalAssignmentServiceImpl service;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital hospitalA = hospital("HA");
    private final Hospital hospitalB = hospital("HB");
    private final Role nurse = role("ROLE_NURSE");
    private final Role receptionist = role("ROLE_RECEPTIONIST");
    private final Role patient = role("ROLE_PATIENT");
    private final Role hospitalAdmin = role("ROLE_HOSPITAL_ADMIN");

    /** The account the request names: who it is depends on the test. */
    private final User target = account();

    private Object previousMessageSource;
    private LocaleContext previousLocale;

    @BeforeEach
    void setUp() {
        previousMessageSource = ReflectionTestUtils.getField(com.example.hms.utility.MessageUtil.class, "messageSource");
        previousLocale = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        RoleValidator roleValidator = new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver());
        ReflectionTestUtils.setField(service, "roleValidator", roleValidator);
        ReflectionTestUtils.setField(service, "accountAccess", new UserAccountAccess(assignmentRepository,
            patientRepository, registrationRepository, staffRepository, roleValidator));
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());

        when(messageSource.getMessage(anyString(), any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(2));
        for (Role r : List.of(nurse, receptionist, patient, hospitalAdmin)) {
            when(roleRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        for (Hospital h : List.of(hospitalA, hospitalB)) {
            when(hospitalRepository.findById(h.getId())).thenReturn(Optional.of(h));
        }
        // The target is findable by every identifier a request may carry.
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(target));
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername(anyString())).thenAnswer(inv ->
            USERNAME.equals(inv.getArgument(0)) ? Optional.of(target) : Optional.empty());
        when(mapper.toEntity(any(), any(), any(), any())).thenAnswer(inv -> {
            UserRoleHospitalAssignmentRequestDTO dto = inv.getArgument(0);
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            a.setUser(inv.getArgument(1));
            a.setHospital(inv.getArgument(2));
            a.setRole(inv.getArgument(3));
            a.setActive(dto.getActive());
            return a;
        });
        when(mapper.toResponseDTO(any())).thenAnswer(inv -> {
            UserRoleHospitalAssignment a = inv.getArgument(0);
            return UserRoleHospitalAssignmentResponseDTO.builder()
                .id(a.getId())
                .userId(a.getUser().getId())
                .userName(a.getUser().getFirstName() + " " + a.getUser().getLastName())
                .userEmail(a.getUser().getEmail())
                .build();
        });
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        com.example.hms.utility.MessageUtil.setMessageSource((MessageSource) previousMessageSource);
        LocaleContextHolder.setLocaleContext(previousLocale);
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

    private static User account() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setUsername(USERNAME);
        u.setEmail(EMAIL);
        u.setPhoneNumber(PHONE);
        u.setFirstName("Awa");
        u.setLastName("Sanou");
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
        a.setConfirmationSentAt(LocalDateTime.now());
        a.setCreatedAt(LocalDateTime.now().minusDays(1));
        return a;
    }

    /** What the target holds, active or not. */
    private void targetHolds(UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUserId(target.getId())).thenReturn(List.of(rows));
        when(assignmentRepository.findByUserIdIn(any())).thenReturn(List.of(rows));
    }

    /**
     * A hospital admin of hospital B only. With the header, the request named
     * B in {@code X-Hospital-Id} (the context is pinned there); without it,
     * nothing is pinned. Neither may widen whom the admin names.
     */
    private void signInAsHospitalAdminOfB(boolean withHospitalHeader) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN");
        User callerAccount = new User();
        callerAccount.setId(callerId);
        callerAccount.setUsername("admin-b");
        callerAccount.setUserRoles(new HashSet<>());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CustomUserDetails(callerAccount, authorities), "n/a", authorities));
        HospitalContext.HospitalContextBuilder context = HospitalContext.builder()
            .principalUserId(callerId)
            .superAdmin(false);
        if (withHospitalHeader) {
            context.activeHospitalId(hospitalB.getId()).permittedHospitalIds(Set.of(hospitalB.getId()));
        }
        HospitalContextHolder.setContext(context.build());
        when(userRepository.findById(callerId)).thenReturn(Optional.of(callerAccount));
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(List.of(row(callerAccount, hospitalAdmin, hospitalB, true)));
    }

    private void signInAsSuperAdmin() {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN");
        User callerAccount = new User();
        callerAccount.setId(callerId);
        callerAccount.setUsername("root");
        callerAccount.setUserRoles(new HashSet<>());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CustomUserDetails(callerAccount, authorities), "n/a", authorities));
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(callerId).superAdmin(true).build());
        when(userRepository.findById(callerId)).thenReturn(Optional.of(callerAccount));
    }

    enum Named { EMAIL, PHONE, USERNAME, ID }

    private UserRoleHospitalAssignmentRequestDTO receptionistAtB(Named by, User holder) {
        UserRoleHospitalAssignmentRequestDTO dto = new UserRoleHospitalAssignmentRequestDTO();
        switch (by) {
            case EMAIL -> dto.setUserIdentifier(holder == null ? "nobody@nowhere.test" : EMAIL);
            case PHONE -> dto.setUserIdentifier(holder == null ? "+22670000000" : PHONE);
            case USERNAME -> dto.setUserIdentifier(holder == null ? "nobody" : USERNAME);
            case ID -> dto.setUserId(holder == null ? UUID.randomUUID() : holder.getId());
        }
        dto.setRoleId(receptionist.getId());
        dto.setHospitalId(hospitalB.getId());
        return dto;
    }

    static Stream<Arguments> everyIdentifierWithAndWithoutTheHeader() {
        return Stream.of(Named.values())
            .flatMap(by -> Stream.of(Arguments.of(by, true), Arguments.of(by, false)));
    }

    /** The refusal, compared with the answer for an account that does not exist, and nothing done. */
    private void assertAnsweredAsUnknown(Throwable refused, Named by) {
        assertThat(refused).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(((ResourceNotFoundException) refused).getMessageKey()).isEqualTo("assignment.holder.unavailable");
        Throwable unknown = catchThrowable(() -> service.assignRole(receptionistAtB(by, null)));
        assertThat(unknown).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(refused.getMessage()).isEqualTo(unknown.getMessage())
            .doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain(USERNAME)
            .doesNotContain(target.getId().toString()).doesNotContain("Awa").doesNotContain("Sanou");
        assertNothingSavedOrSent();
    }

    private void assertNothingSavedOrSent() {
        verify(assignmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verifyNoInteractions(smsService, emailService);
    }

    // ------------------------------------------------------------------ tests

    @ParameterizedTest(name = "by {0}, X-Hospital-Id sent: {1}")
    @MethodSource("everyIdentifierWithAndWithoutTheHeader")
    @DisplayName("another hospital's staff member is answered as an unknown account, and gets nothing")
    void anotherHospitalsStaffIsUnknown(Named by, boolean withHeader) {
        signInAsHospitalAdminOfB(withHeader);
        targetHolds(row(target, nurse, hospitalA, true));

        Throwable refused = catchThrowable(() -> service.assignRole(receptionistAtB(by, target)));

        assertAnsweredAsUnknown(refused, by);
    }

    @ParameterizedTest(name = "by {0}, X-Hospital-Id sent: {1}")
    @MethodSource("everyIdentifierWithAndWithoutTheHeader")
    @DisplayName("another hospital's patient is answered as an unknown account, and gets nothing")
    void anotherHospitalsPatientIsUnknown(Named by, boolean withHeader) {
        signInAsHospitalAdminOfB(withHeader);
        targetHolds(row(target, patient, hospitalA, true));

        Throwable refused = catchThrowable(() -> service.assignRole(receptionistAtB(by, target)));

        assertAnsweredAsUnknown(refused, by);
    }

    @Test
    @DisplayName("this hospital's own patient is not made staff through /assignments either")
    void ownPatientIsNotMadeStaff() {
        signInAsHospitalAdminOfB(false);
        targetHolds(row(target, patient, hospitalB, true));

        Throwable refused = catchThrowable(() -> service.assignRole(receptionistAtB(Named.PHONE, target)));

        assertAnsweredAsUnknown(refused, Named.PHONE);
    }

    @Test
    @DisplayName("a patient record with no assignment at all is a patient-only account too, even for the super-admin")
    void superAdminDoesNotMakeAPatientStaff() {
        signInAsSuperAdmin();
        targetHolds(row(target, patient, hospitalA, true));

        assertThatThrownBy(() -> service.assignRole(receptionistAtB(Named.EMAIL, target)))
            .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertNothingSavedOrSent();

        targetHolds();
        when(patientRepository.existsByUserId(target.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.assignRole(receptionistAtB(Named.EMAIL, target)))
            .isExactlyInstanceOf(ResourceNotFoundException.class);
        assertNothingSavedOrSent();
    }

    @Test
    @DisplayName("a staff member of another hospital who is also a patient here is still another hospital's")
    void staffElsewhereAndPatientHereIsUnknown() {
        signInAsHospitalAdminOfB(true);
        targetHolds(row(target, nurse, hospitalA, true), row(target, patient, hospitalB, true));

        Throwable refused = catchThrowable(() -> service.assignRole(receptionistAtB(Named.USERNAME, target)));

        assertAnsweredAsUnknown(refused, Named.USERNAME);
    }

    @Test
    @DisplayName("multi-scope and bulk import: the refused holder fails with the same sentence, nothing saved or sent")
    void multiScopeAndBulkImportHoldTheHolderToo() {
        signInAsHospitalAdminOfB(false);
        targetHolds(row(target, nurse, hospitalA, true));

        UserRoleAssignmentMultiRequestDTO multi = new UserRoleAssignmentMultiRequestDTO();
        multi.setUserIdentifier(PHONE);
        multi.setRoleId(receptionist.getId());
        multi.setHospitalIds(List.of(hospitalB.getId()));
        multi.setSendNotifications(true);
        UserRoleAssignmentBatchResponseDTO batch = service.assignRoleToMultipleScopes(multi);
        assertThat(batch.getCreatedAssignments()).isZero();
        assertThat(batch.getFailures()).singleElement().satisfies(failure ->
            assertThat(failure.getMessage()).doesNotContain(PHONE).doesNotContain("Awa"));

        String csv = "user_identifier,role_id,hospital_id\n"
            + EMAIL + "," + receptionist.getId() + "," + hospitalB.getId() + "\n";
        UserRoleAssignmentBulkImportResponseDTO bulk = service.bulkImportAssignments(
            UserRoleAssignmentBulkImportRequestDTO.builder().csvContent(csv).delimiter(",")
                .sendNotifications(true).skipConflicts(true).build());
        assertThat(bulk.getCreated()).isZero();
        assertThat(bulk.getFailed()).isEqualTo(1);
        assertThat(bulk.getResults()).singleElement().satisfies(result ->
            assertThat(result.getMessage()).isEqualTo(batch.getFailures().get(0).getMessage()));

        assertNothingSavedOrSent();
    }

    @ParameterizedTest(name = "by {0}, X-Hospital-Id sent: {1}")
    @MethodSource("everyIdentifierWithAndWithoutTheHeader")
    @DisplayName("this hospital's own staff member still gets the new role, invited as before")
    void ownStaffStillGetsTheRole(Named by, boolean withHeader) {
        signInAsHospitalAdminOfB(withHeader);
        // A nurse here whose own invitation still waits for its code counts too.
        targetHolds(row(target, nurse, hospitalB, false));

        UserRoleHospitalAssignmentResponseDTO created = service.assignRole(receptionistAtB(by, target));

        verify(assignmentRepository).save(any());
        verify(eventPublisher).publishEvent(any(com.example.hms.event.AssignmentCreatedEvent.class));
        assertThat(created.getUserName()).isEqualTo("Awa Sanou");
    }

    // ------------------------------------- rows given before this rule existed

    @Test
    @DisplayName("a pending row given to another hospital's account is neither re-sent, re-coded, revealed, confirmed nor edited")
    void aStrayPendingRowIsNotReinvited() {
        signInAsHospitalAdminOfB(false);
        UserRoleHospitalAssignment stray = row(target, receptionist, hospitalB, false);
        stray.setRegisteredBy(new User());
        stray.getRegisteredBy().setId(callerId);
        when(assignmentRepository.findById(stray.getId())).thenReturn(Optional.of(stray));
        targetHolds(row(target, patient, hospitalA, true), stray);

        UUID id = stray.getId();
        UserRoleHospitalAssignmentRequestDTO edit = new UserRoleHospitalAssignmentRequestDTO();
        edit.setRoleId(nurse.getId());
        for (Throwable refused : List.of(
                catchThrowable(() -> service.resendNotifications(id)),
                catchThrowable(() -> service.regenerateAssignmentCode(id, true)),
                catchThrowable(() -> service.revealActivationCode(id)),
                catchThrowable(() -> service.confirmAssignment(id, "123456")),
                catchThrowable(() -> service.updateAssignment(id, edit)))) {
            assertThat(refused).isExactlyInstanceOf(ResourceNotFoundException.class);
            assertThat(((ResourceNotFoundException) refused).getMessageKey()).isEqualTo("roleAssignment.notFound");
        }
        assertNothingSavedOrSent();

        // Switching it off stays open: that is how the hospital cleans it up.
        service.deactivateAssignment(id);
        verify(assignmentRepository).save(stray);
    }

    @Test
    @DisplayName("a pending row given to another hospital's account shows no name or e-mail, in the list or by id")
    void aStrayPendingRowShowsNoHolder() {
        signInAsHospitalAdminOfB(true);
        UserRoleHospitalAssignment stray = row(target, receptionist, hospitalB, false);
        when(assignmentRepository.findById(stray.getId())).thenReturn(Optional.of(stray));
        targetHolds(row(target, nurse, hospitalA, true), stray);

        UserRoleHospitalAssignmentResponseDTO byId = service.getAssignmentById(stray.getId());
        assertThat(byId.getUserName()).isNull();
        assertThat(byId.getUserEmail()).isNull();

        // Once the person accepts, the row is theirs to show.
        stray.setConfirmationVerifiedAt(LocalDateTime.now());
        UserRoleHospitalAssignmentResponseDTO accepted = service.getAssignmentById(stray.getId());
        assertThat(accepted.getUserName()).isEqualTo("Awa Sanou");
        assertThat(accepted.getUserEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("a pending row of someone this hospital registered still shows who it is")
    void aPendingRowOfOwnRegistrationShowsTheHolder() {
        signInAsHospitalAdminOfB(false);
        UserRoleHospitalAssignment pending = row(target, receptionist, hospitalB, false);
        when(assignmentRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        targetHolds(pending);

        assertThat(service.getAssignmentById(pending.getId()).getUserEmail()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("an edit never turns a patient's row into a staff role")
    void aPatientRowStaysAPatientRow() {
        signInAsHospitalAdminOfB(false);
        UserRoleHospitalAssignment patientRow = row(target, patient, hospitalB, true);
        patientRow.setConfirmationVerifiedAt(LocalDateTime.now());
        when(assignmentRepository.findById(patientRow.getId())).thenReturn(Optional.of(patientRow));
        targetHolds(patientRow);
        UserRoleHospitalAssignmentRequestDTO edit = new UserRoleHospitalAssignmentRequestDTO();
        edit.setRoleId(receptionist.getId());

        assertThatThrownBy(() -> service.updateAssignment(patientRow.getId(), edit))
            .isInstanceOfSatisfying(BusinessException.class, refused ->
                assertThat(refused.getMessage()).contains("patient"));
        assertNothingSavedOrSent();
    }
}
