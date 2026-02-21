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
        when(userRoleRepository.existsByUserIdAndRoleId(any(), any())).thenReturn(false);

        UserRoleAssignmentPublicViewDTO dto = service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        assertThat(dto).isNotNull();
        assertThat(dto.isConfirmationVerified()).isTrue();
        assertThat(dto.getAssignmentCode()).isEqualTo(VALID_CODE);
        verify(assignmentRepository).findByAssignmentCode(VALID_CODE);
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
        when(userRoleRepository.existsByUserIdAndRoleId(any(), any())).thenReturn(false);

        UserRoleAssignmentPublicViewDTO dto = service.verifyAssignmentByCode(VALID_CODE, VALID_PIN);

        // Credentials must be present in the one-time response
        assertThat(dto.getTempUsername()).isEqualTo("jdoe");
        assertThat(dto.getTempPassword()).isEqualTo(TEMP_PASS);

        // Plaintext must be cleared from the entity before the final save
        assertThat(assignment.getTempPlainPassword()).isNull();
    }
}
