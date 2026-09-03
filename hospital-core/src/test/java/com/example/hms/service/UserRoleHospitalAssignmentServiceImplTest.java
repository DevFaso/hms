package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.UserRoleHospitalAssignmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentPublicViewDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoleHospitalAssignmentServiceImplTest {

    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private AssignmentLinkService assignmentLinkService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRoleHospitalAssignmentMapper mapper;
    @Mock private MessageSource messageSource;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;

    @InjectMocks
    private UserRoleHospitalAssignmentServiceImpl service;

    private UserRoleHospitalAssignment assignment;
    private User assignee;
    private Role role;
    private Hospital hospital;

    private static final String VALID_CODE   = "ASSIGN-001";
    private static final String VALID_PIN    = "123456";
    private static final String TEMP_PASS    = "Temp@1234";

    @BeforeEach
    void setUp() {
        assignee = new User();
        assignee.setId(UUID.randomUUID());
        assignee.setUsername("jdoe");
        assignee.setFirstName("John");
        assignee.setLastName("Doe");
        assignee.setEmail("jdoe@hospital.com");

        role = new Role();
        role.setId(UUID.randomUUID());
        role.setName("ROLE_NURSE");
        role.setCode("ROLE_NURSE");

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Central Hospital");
        hospital.setCode("CH01");

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setAssignmentCode(VALID_CODE);
        assignment.setConfirmationCode(VALID_PIN);
        assignment.setUser(assignee);
        assignment.setRole(role);
        assignment.setHospital(hospital);

        // MessageSource always returns the default message in these unit tests —
        // lenient because not every test path reaches a message lookup.
        org.mockito.Mockito.lenient()
            .when(messageSource.getMessage(anyString(), any(), anyString(), any()))
            .thenAnswer(inv -> inv.getArgument(2));
        org.mockito.Mockito.lenient()
            .when(assignmentLinkService.buildProfileCompletionUrl(anyString()))
            .thenReturn("https://app/complete/" + VALID_CODE);
    }

    // -----------------------------------------------------------------------
    // 1. Successful verification with a valid code
    // -----------------------------------------------------------------------

    @Test
    void verifyAssignmentByCode_success_returnsPublicView() {
        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserRoleAssignmentPublicViewDTO dto = service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        assertThat(dto).isNotNull();
        assertThat(dto.isConfirmationVerified()).isTrue();
        assertThat(dto.getAssignmentCode()).isEqualTo(VALID_CODE);
        verify(assignmentRepository).findByAssignmentCode(VALID_CODE);
    }

    @Test
    void verifyAssignmentByCode_verifiedButInactive_finishesTheActivation() {
        // The wedged state an older registrar confirm left behind:
        // confirmationVerifiedAt stamped, nothing activated. The idempotent
        // branch must NOT swallow this - the assignee still holds the code,
        // so verification completes the activation instead of locking the
        // account out forever.
        assignee.setActive(false);
        assignment.setConfirmationVerifiedAt(LocalDateTime.now().minusDays(1));
        assignment.setActive(false);
        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        assertThat(assignment.getActive()).isTrue();
        assertThat(assignee.isActive()).isTrue();
    }

    @Test
    void confirmAssignment_byRegistrar_activatesBothTheAssignmentAndTheUser() {
        // Registrar confirmation presents the same code the assignee
        // received, so it must complete verification outright: stamping only
        // confirmationVerifiedAt used to trip verifyAssignmentByCode's
        // already-verified branch, and the assignee could then never
        // activate through the advertised path.
        User registrar = new User();
        registrar.setId(UUID.randomUUID());
        registrar.setUsername("registrar");
        assignment.setRegisteredBy(registrar);
        assignee.setActive(false);

        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("registrar", "n/a", java.util.List.of()));
        try {
            when(assignmentRepository.findById(assignment.getId()))
                .thenReturn(Optional.of(assignment));
            when(userRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(
                    "registrar", "registrar", null))
                .thenReturn(Optional.of(registrar));
            when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.confirmAssignment(assignment.getId(), VALID_PIN);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        assertThat(assignment.getConfirmationVerifiedAt()).isNotNull();
        assertThat(assignment.getActive()).isTrue();
        assertThat(assignee.isActive()).isTrue();
        verify(userRepository).save(assignee);
    }

    @Test
    void verifyAssignmentByCode_activatesBothTheAssignmentAndTheUser() {
        // Since option A (2026-09-02), admin-registered accounts - staff AND
        // patients - start inactive, and this call is the ONE thing that
        // makes them usable. If it stopped activating either row, every new
        // registration would be locked out with a green 200 behind it.
        assignee.setActive(false);
        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        assertThat(assignment.getActive()).isTrue();
        assertThat(assignment.getConfirmationVerifiedAt()).isNotNull();
        assertThat(assignee.isActive()).isTrue();
        verify(userRepository).save(assignee);
    }

    // -----------------------------------------------------------------------
    // 2. Wrong / incorrect confirmation PIN → BusinessException
    // -----------------------------------------------------------------------

    @Test
    void verifyAssignmentByCode_wrongPin_throwsBusinessException() {
        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.verifyAssignmentByCode(VALID_CODE, "WRONG"))
            .isInstanceOf(BusinessException.class);

        verify(assignmentRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // 3. Blank / null assignment code → ResourceNotFoundException
    // -----------------------------------------------------------------------

    @Test
    void verifyAssignmentByCode_blankCode_throwsResourceNotFoundException() {
        assertThatThrownBy(() -> service.verifyAssignmentByCode("  ", VALID_PIN))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.verifyAssignmentByCode(null, VALID_PIN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // 4. Already-verified assignment → returns DTO without re-verifying
    // -----------------------------------------------------------------------

    @Test
    void verifyAssignmentByCode_alreadyVerified_returnsExistingViewWithoutSaving() {
        assignment.setConfirmationVerifiedAt(LocalDateTime.now().minusDays(1));
        assignment.setActive(true);

        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));

        UserRoleAssignmentPublicViewDTO dto = service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        assertThat(dto.isConfirmationVerified()).isTrue();
        // Must NOT save again for an already-verified assignment
        verify(assignmentRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // 5. Temp credentials included in DTO and cleared from DB after verify
    // -----------------------------------------------------------------------

    @Test
    void verifyAssignmentByCode_withTempPassword_includesCredentialsAndClearsAfterward() {
        assignment.setTempPlainPassword(TEMP_PASS);

        when(assignmentRepository.findByAssignmentCode(VALID_CODE))
            .thenReturn(Optional.of(assignment));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserRoleAssignmentPublicViewDTO dto = service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        // Credentials must be present in the one-time response
        assertThat(dto.getTempUsername()).isEqualTo("jdoe");
        assertThat(dto.getTempPassword()).isEqualTo(TEMP_PASS);

        // Plaintext must be cleared from the entity before the final save
        assertThat(assignment.getTempPlainPassword()).isNull();
    }

    // ---- Delivery report: the registrar must learn when nothing was sent ----

    @Test
    void sendNotifications_reportsDeadTransportsInsteadOfSilence() {
        // The dev-outage shape: MAIL_USER unset (SMTP send throws, mock
        // reports deliversRealEmail=false) and the mock SMS channel. The
        // report must say NOT_CONFIGURED / MOCKED — before this existed the
        // API returned a green 200 over a swallowed WARN log.
        assignee.setPhoneNumber("+22670123456");
        when(assignmentRepository.findById(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP auth failed"))
            .when(emailService).sendRoleAssignmentConfirmationEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        com.example.hms.utility.ActivationDeliveryTracker.open();
        try {
            service.sendNotifications(assignment.getId());
            var report = com.example.hms.utility.ActivationDeliveryTracker.close();
            assertThat(report)
                .extracting(
                    com.example.hms.payload.dto.NotificationDeliveryStatusDTO::getChannel,
                    com.example.hms.payload.dto.NotificationDeliveryStatusDTO::getOutcome)
                .containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple("EMAIL", "NOT_CONFIGURED"),
                    org.assertj.core.groups.Tuple.tuple("SMS", "MOCKED"));
            assertThat(report)
                .as("targets are masked, never the raw address/number")
                .extracting(com.example.hms.payload.dto.NotificationDeliveryStatusDTO::getTarget)
                .containsExactlyInAnyOrder("j***@hospital.com", "+226*****56");
        } finally {
            com.example.hms.utility.ActivationDeliveryTracker.close();
        }
    }

    @Test
    void sendNotifications_reportsFailedWhenARealTransportRefused() {
        // Same throw, but the transport IS configured — the report must say
        // FAILED (retry material) rather than NOT_CONFIGURED (ops material).
        assignee.setPhoneNumber("+22670123456");
        when(assignmentRepository.findById(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        when(emailService.deliversRealEmail()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("mailbox unavailable"))
            .when(emailService).sendRoleAssignmentConfirmationEmail(
                any(), any(), any(), any(), any(), any(), any(), any(), any());

        com.example.hms.utility.ActivationDeliveryTracker.open();
        try {
            service.sendNotifications(assignment.getId());
            assertThat(com.example.hms.utility.ActivationDeliveryTracker.close())
                .filteredOn(r -> "EMAIL".equals(r.getChannel()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getOutcome()).isEqualTo("FAILED");
                    // The raw exception text must never reach the response:
                    // validateAddresses embeds the FULL unmasked address in
                    // its message, so detail is a fixed operator hint.
                    assertThat(r.getDetail()).doesNotContain("mailbox unavailable");
                });
        } finally {
            com.example.hms.utility.ActivationDeliveryTracker.close();
        }
    }

    @Test
    void sendNotifications_assigneeSmsStillGoesOutWhenAnFyiSmsThrows() {
        // The registrar/hospital FYI messages are couriers of convenience;
        // a throwing FYI send used to abort the whole SMS block before the
        // one activation SMS that matters was even attempted.
        assignee.setPhoneNumber("+22670123456");
        User registrar = new User();
        registrar.setId(UUID.randomUUID());
        registrar.setPhoneNumber("+22699999999");
        assignment.setRegisteredBy(registrar);
        when(assignmentRepository.findById(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        // One doAnswer, not doThrow(eq(...)): under STRICT_STUBS a call with
        // non-matching args to a stubbed method throws PotentialStubbingProblem,
        // which the production catch would record as a FAILED assignee send.
        org.mockito.Mockito.doAnswer(inv -> {
            if ("+22699999999".equals(inv.getArgument(0))) {
                throw new RuntimeException("gateway down");
            }
            return null;
        }).when(smsService).send(any(), any());

        com.example.hms.utility.ActivationDeliveryTracker.open();
        try {
            service.sendNotifications(assignment.getId());
            assertThat(com.example.hms.utility.ActivationDeliveryTracker.close())
                .filteredOn(r -> "SMS".equals(r.getChannel())
                    && "ACTIVATION".equals(r.getPurpose()))
                .singleElement()
                .satisfies(r -> assertThat(r.getOutcome()).isEqualTo("MOCKED"));
        } finally {
            com.example.hms.utility.ActivationDeliveryTracker.close();
        }
    }

    @Test
    void sendNotifications_recordsNothingWhenNoControllerArmedTheTracker() {
        // Bulk import and background flows never arm collection; a pooled
        // thread must not accumulate outcomes for a later request to drain.
        assignee.setPhoneNumber("+22670123456");
        when(assignmentRepository.findById(assignment.getId()))
            .thenReturn(Optional.of(assignment));

        service.sendNotifications(assignment.getId());

        assertThat(com.example.hms.utility.ActivationDeliveryTracker.close()).isEmpty();
    }

    // ---- Tenant isolation: GET /assignments listing ----

    @Test
    void getAllAssignments_scopedAdminNeverGetsTheUnfilteredListing() {
        UUID ownHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(ownHospital);
        when(assignmentRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<UserRoleHospitalAssignment>>any(),
                any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        // Caller probes another hospital explicitly — the scope must win.
        com.example.hms.payload.dto.assignment.AssignmentSearchCriteria criteria =
            com.example.hms.payload.dto.assignment.AssignmentSearchCriteria.builder()
                .hospitalId(UUID.randomUUID().toString())
                .build();

        service.getAllAssignments(org.springframework.data.domain.PageRequest.of(0, 10), criteria);

        // Filtered (Specification) path taken; the unscoped findAll(pageable) never runs.
        verify(assignmentRepository).findAll(
            org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<UserRoleHospitalAssignment>>any(),
            any(org.springframework.data.domain.Pageable.class));
        verify(assignmentRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void getAllAssignments_scopedAdminWithNoFiltersIsStillScoped() {
        UUID ownHospital = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(ownHospital);
        when(assignmentRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<UserRoleHospitalAssignment>>any(),
                any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        service.getAllAssignments(org.springframework.data.domain.PageRequest.of(0, 10));

        verify(assignmentRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void getAllAssignments_superAdminWithoutFiltersGetsTheFullListing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null); // super-admin
        when(assignmentRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        service.getAllAssignments(org.springframework.data.domain.PageRequest.of(0, 10), null);

        verify(assignmentRepository).findAll(any(org.springframework.data.domain.Pageable.class));
    }
}
