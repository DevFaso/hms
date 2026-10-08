package com.example.hms.service;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.UserMapper;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.provider.FacilityAssignmentGuard;
import com.example.hms.service.support.UserAccountAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-6 / AC-7 on {@code POST /users/admin-register}: once the
 * caller may grant at the facility, the roles must also be ones that kind of
 * facility takes. DOCTOR at a pharmacy is a 400 {@code role.facility.incompatible}
 * raised before the account or any assignment exists, whoever registers
 * (the grant check is mocked to "anywhere" here, i.e. a super-admin).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRegisterFacilityCompatibilityTest {

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
    @Mock private FacilityAssignmentGuard unusedGuardMock;

    @InjectMocks
    private UserServiceImpl userService;

    private final Hospital pharmacy = new Hospital();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "facilityAssignmentGuard",
            new FacilityAssignmentGuard(assignmentRepository, hospitalRepository));
        pharmacy.setId(UUID.randomUUID());
        pharmacy.setFacilityType(FacilityType.PHARMACY);
        when(hospitalRepository.findById(pharmacy.getId())).thenReturn(Optional.of(pharmacy));
        UserAccountAccess.Grant anywhere = mock(UserAccountAccess.Grant.class);
        when(anywhere.requireAt(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountAccess.requireMayGrant(any())).thenReturn(anywhere);
    }

    @Test
    @DisplayName("DOCTOR at a pharmacy: 400 role.facility.incompatible, no account, no assignment")
    void doctorAtPharmacyIsRefusedBeforeAnythingIsWritten() {
        AdminSignupRequest request = request(Set.of("DOCTOR"));

        assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(request))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo(FacilityAssignmentGuard.MSG_INCOMPATIBLE));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(assignmentService, roleRepository);
    }

    @Test
    @DisplayName("PHARMACIST at the pharmacy passes the facility check")
    void pharmacistPasses() {
        AdminSignupRequest request = request(Set.of("PHARMACIST"));

        // The registration goes on past the facility check (the role lookup is
        // the next step); what matters here is that the check let it through.
        assertThatThrownBy(() -> userService.createUserWithRolesAndHospital(request))
            .isNotInstanceOf(BusinessException.class);
        verify(roleRepository).findByCode("ROLE_PHARMACIST");
    }

    private AdminSignupRequest request(Set<String> roles) {
        AdminSignupRequest request = new AdminSignupRequest();
        request.setUsername("new-staff");
        request.setEmail("staff@example.test");
        request.setPassword("Chosen-Pass-1");
        request.setFirstName("New");
        request.setLastName("Staff");
        request.setPhoneNumber("+22670111111");
        request.setLicenseNumber("LIC-1");
        request.setHospitalId(pharmacy.getId());
        request.setRoleNames(roles);
        return request;
    }
}
