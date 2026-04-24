package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.SessionBootstrapResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthBootstrapServiceImpl")
class AuthBootstrapServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    @InjectMocks
    private AuthBootstrapServiceImpl service;

    private static final UUID USER_ID      = UUID.randomUUID();
    private static final UUID HOSPITAL_ID  = UUID.randomUUID();
    private static final UUID STAFF_ID     = UUID.randomUUID();
    private static final UUID DEPT_ID      = UUID.randomUUID();
    private static final UUID PATIENT_ID   = UUID.randomUUID();

    private User staffUser;
    private Hospital hospital;
    private Staff staff;
    private Department department;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("General Hospital");

        department = new Department();
        department.setId(DEPT_ID);
        department.setName("Cardiology");

        staffUser = User.builder()
                .username("john.doe")
                .email("john.doe@hms.test")
                .firstName("John")
                .lastName("Doe")
                .profileImageUrl("/api/uploads/john.png")
                .phoneNumber("+1000000001")
                .passwordHash("hashed")
                .authSource("internal")
                .build();
        staffUser.setId(USER_ID);

        staff = Staff.builder()
                .hospital(hospital)
                .department(department)
                .user(staffUser)
                .jobTitle(com.example.hms.enums.JobTitle.NURSE)
                .employmentType(com.example.hms.enums.EmploymentType.FULL_TIME)
                .build();
        staff.setId(STAFF_ID);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TenantRoleAssignment activeAssignment(UUID hospitalId, String roleCode) {
        return new TenantRoleAssignment(hospitalId, UUID.randomUUID(), roleCode, roleCode, true);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveCurrentSession — staff user (internal auth)")
    class StaffInternalAuth {

        @Test
        @DisplayName("returns full DTO with staff, hospital and role data")
        void returnsFullDtoForStaffUser() {
            when(userRepository.findByUsername("john.doe")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of(activeAssignment(HOSPITAL_ID, "ROLE_NURSE")));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.of(staff));
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            SessionBootstrapResponseDTO result = service.resolveCurrentSession("john.doe");

            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getUsername()).isEqualTo("john.doe");
            assertThat(result.getEmail()).isEqualTo("john.doe@hms.test");
            assertThat(result.getFirstName()).isEqualTo("John");
            assertThat(result.getLastName()).isEqualTo("Doe");
            assertThat(result.getAuthSource()).isEqualTo("internal");
            assertThat(result.getRoles()).containsExactly("ROLE_NURSE");
            assertThat(result.isSuperAdmin()).isFalse();
            assertThat(result.isHospitalAdmin()).isFalse();
            assertThat(result.getPrimaryHospitalId()).isEqualTo(HOSPITAL_ID);
            assertThat(result.getPrimaryHospitalName()).isEqualTo("General Hospital");
            assertThat(result.getPermittedHospitalIds()).containsExactly(HOSPITAL_ID);
            assertThat(result.getStaffId()).isEqualTo(STAFF_ID);
            assertThat(result.getStaffRoleCode()).isEqualTo("ROLE_NURSE");
            assertThat(result.getDepartmentId()).isEqualTo(DEPT_ID);
            assertThat(result.getDepartmentName()).isEqualTo("Cardiology");
            assertThat(result.getPatientId()).isNull();
            assertThat(result.getLastOidcLoginAt()).isNull();
        }

        @Test
        @DisplayName("does NOT update lastOidcLoginAt for internal-auth user")
        void doesNotUpdateOidcTimestampForInternalUser() {
            when(userRepository.findByUsername("john.doe")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of(activeAssignment(HOSPITAL_ID, "ROLE_NURSE")));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.empty());
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            service.resolveCurrentSession("john.doe");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("resolveCurrentSession — Keycloak (OIDC) user")
    class KeycloakUser {

        @Test
        @DisplayName("updates lastOidcLoginAt and returns keycloak authSource")
        void updatesLastOidcLoginAt() {
            staffUser.setAuthSource("keycloak");
            staffUser.setKeycloakSubject("kc-sub-abc");

            when(userRepository.findByUsername("john.doe")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of(activeAssignment(HOSPITAL_ID, "ROLE_DOCTOR")));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.empty());
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            SessionBootstrapResponseDTO result = service.resolveCurrentSession("john.doe");

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLastOidcLoginAt()).isNotNull();

            assertThat(result.getAuthSource()).isEqualTo("keycloak");
            assertThat(result.getLastOidcLoginAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("resolveCurrentSession — patient user")
    class PatientUser {

        @Test
        @DisplayName("returns patientId when user has a patient profile")
        void returnsPatientIdWhenProfileExists() {
            Patient patient = Patient.builder()
                    .firstName("Jane")
                    .lastName("Doe")
                    .dateOfBirth(java.time.LocalDate.of(1990, 1, 1))
                    .phoneNumberPrimary("+1000000002")
                    .email("jane.doe@hms.test")
                    .build();
            patient.setId(PATIENT_ID);

            when(userRepository.findByUsername("jane.doe")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of(activeAssignment(HOSPITAL_ID, "ROLE_PATIENT")));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.empty());
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.of(patient));

            SessionBootstrapResponseDTO result = service.resolveCurrentSession("jane.doe");

            assertThat(result.getPatientId()).isEqualTo(PATIENT_ID);
            assertThat(result.getStaffId()).isNull();
        }
    }

    @Nested
    @DisplayName("resolveCurrentSession — super admin")
    class SuperAdmin {

        @Test
        @DisplayName("sets superAdmin flag and isHospitalAdmin false")
        void setsSuperAdminFlag() {
            when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of(activeAssignment(HOSPITAL_ID, "ROLE_SUPER_ADMIN")));
            when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.empty());
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            SessionBootstrapResponseDTO result = service.resolveCurrentSession("superadmin");

            assertThat(result.isSuperAdmin()).isTrue();
            assertThat(result.isHospitalAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveCurrentSession — unknown user")
    class UnknownUser {

        @Test
        @DisplayName("throws ResourceNotFoundException when user does not exist")
        void throwsWhenUserNotFound() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveCurrentSession("ghost"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("ghost");
        }
    }

    @Nested
    @DisplayName("resolveCurrentSession — no hospital assignments")
    class NoHospitalAssignments {

        @Test
        @DisplayName("returns null primary hospital when user has no assignments")
        void returnsNullPrimaryHospital() {
            when(userRepository.findByUsername("john.doe")).thenReturn(Optional.of(staffUser));
            when(tenantRoleAssignmentAccessor.findAssignmentsForUser(USER_ID))
                    .thenReturn(List.of());
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(USER_ID))
                    .thenReturn(Optional.empty());
            when(patientRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            SessionBootstrapResponseDTO result = service.resolveCurrentSession("john.doe");

            assertThat(result.getPrimaryHospitalId()).isNull();
            assertThat(result.getPrimaryHospitalName()).isNull();
            assertThat(result.getPermittedHospitalIds()).isEmpty();
        }
    }
}
