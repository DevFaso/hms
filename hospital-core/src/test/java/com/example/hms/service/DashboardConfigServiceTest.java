package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DashboardConfigResponseDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardConfigServiceTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PermissionRepository permissionRepository;

    @InjectMocks private DashboardConfigService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // ---------- getDashboardConfig ----------

    @Test
    void getDashboardConfig_noAssignments_returnsEmptyConfig() {
        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getPrimaryRoleCode()).isNull();
        assertThat(result.getRoles()).isEmpty();
        assertThat(result.getMergedPermissions()).isEmpty();
    }

    @Test
    void getDashboardConfig_inactiveAssignment_filtered() {
        UserRoleHospitalAssignment inactive = buildAssignment(false, "ROLE_DOCTOR", "Doctor");
        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(inactive));

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getRoles()).isEmpty();
    }

    @Test
    void getDashboardConfig_singleActiveDoctor_returnsConfig() {
        UserRoleHospitalAssignment assignment = buildAssignment(true, "ROLE_DOCTOR", "Doctor");

        Permission perm = new Permission();
        perm.setName("Custom Permission");
        perm.setAssignment(assignment);

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(assignment));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(List.of(perm));

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_DOCTOR");
        assertThat(result.getRoles()).hasSize(1);
        // Should have custom permission + all default DOCTOR permissions merged
        assertThat(result.getMergedPermissions()).contains("Custom Permission");
        assertThat(result.getMergedPermissions()).contains("View Patient Records");
    }

    @Test
    void getDashboardConfig_superAdminAndDoctor_primaryIsSuperAdmin() {
        UserRoleHospitalAssignment superAdmin = buildAssignment(true, "ROLE_SUPER_ADMIN", "Super Admin");
        UserRoleHospitalAssignment doctor = buildAssignment(true, "ROLE_DOCTOR", "Doctor");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(superAdmin, doctor));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_SUPER_ADMIN");
        assertThat(result.getRoles()).hasSize(2);
    }

    @Test
    void getDashboardConfig_hospitalAdmin_hasCorrectPermissions() {
        UserRoleHospitalAssignment admin = buildAssignment(true, "ROLE_HOSPITAL_ADMIN", "Hospital Admin");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(admin));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_HOSPITAL_ADMIN");
        assertThat(result.getMergedPermissions()).contains("Manage Hospital Staff");
    }

    @Test
    void getDashboardConfig_nurseRole_hasNurseDefaults() {
        UserRoleHospitalAssignment nurse = buildAssignment(true, "ROLE_NURSE", "Nurse");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(nurse));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_NURSE");
        assertThat(result.getMergedPermissions()).contains("Update Vital Signs");
    }

    @Test
    void getDashboardConfig_pharmacistRole_hasPharmacistDefaults() {
        UserRoleHospitalAssignment pharmacist = buildAssignment(true, "ROLE_PHARMACIST", "Pharmacist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(pharmacist));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_PHARMACIST");
        assertThat(result.getMergedPermissions()).contains("Dispense Medications");
    }

    @Test
    void getDashboardConfig_surgeonRole() {
        UserRoleHospitalAssignment surgeon = buildAssignment(true, "ROLE_SURGEON", "Surgeon");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(surgeon));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_SURGEON");
        assertThat(result.getMergedPermissions()).contains("Schedule Surgeries");
    }

    @Test
    void getDashboardConfig_midwifeRole() {
        UserRoleHospitalAssignment midwife = buildAssignment(true, "ROLE_MIDWIFE", "Midwife");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(midwife));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_MIDWIFE");
        assertThat(result.getMergedPermissions()).contains("Monitor Labor Progress");
    }

    @Test
    void getDashboardConfig_radiologistRole() {
        UserRoleHospitalAssignment radiologist = buildAssignment(true, "ROLE_RADIOLOGIST", "Radiologist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(radiologist));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_RADIOLOGIST");
        assertThat(result.getMergedPermissions()).contains("View Imaging Orders");
    }

    @Test
    void getDashboardConfig_anesthesiologistRole() {
        UserRoleHospitalAssignment anest = buildAssignment(true, "ROLE_ANESTHESIOLOGIST", "Anesthesiologist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(anest));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_ANESTHESIOLOGIST");
        assertThat(result.getMergedPermissions()).contains("Administer Anesthesia");
    }

    @Test
    void getDashboardConfig_receptionistRole() {
        UserRoleHospitalAssignment receptionist = buildAssignment(true, "ROLE_RECEPTIONIST", "Receptionist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(receptionist));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_RECEPTIONIST");
        assertThat(result.getMergedPermissions()).contains("Register Patients");
    }

    @Test
    void getDashboardConfig_billingSpecialistRole() {
        UserRoleHospitalAssignment billing = buildAssignment(true, "ROLE_BILLING_SPECIALIST", "Billing Specialist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(billing));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_BILLING_SPECIALIST");
        assertThat(result.getMergedPermissions()).contains("Create Invoices");
    }

    @Test
    void getDashboardConfig_labScientistRole() {
        UserRoleHospitalAssignment lab = buildAssignment(true, "ROLE_LAB_SCIENTIST", "Lab Scientist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(lab));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_LAB_SCIENTIST");
        assertThat(result.getMergedPermissions()).contains("View Lab Orders");
    }

    @Test
    void getDashboardConfig_physiotherapistRole() {
        UserRoleHospitalAssignment physio = buildAssignment(true, "ROLE_PHYSIOTHERAPIST", "Physiotherapist");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(physio));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_PHYSIOTHERAPIST");
        assertThat(result.getMergedPermissions()).contains("Prescribe Exercises");
    }

    @Test
    void getDashboardConfig_patientRole() {
        UserRoleHospitalAssignment patient = buildAssignment(true, "ROLE_PATIENT", "Patient");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(patient));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_PATIENT");
        assertThat(result.getMergedPermissions()).contains("View Own Records");
    }

    @Test
    void getDashboardConfig_labManagerRole() {
        UserRoleHospitalAssignment labMgr = buildAssignment(true, "ROLE_LAB_MANAGER", "Lab Manager");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(labMgr));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getMergedPermissions()).contains("Approve Lab Results");
    }

    @Test
    void getDashboardConfig_unknownRole_noDefaultPermissions() {
        UserRoleHospitalAssignment unknown = buildAssignment(true, "ROLE_CUSTOM", "Custom Role");

        Permission perm = new Permission();
        perm.setName("Special Permission");
        perm.setAssignment(unknown);

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(unknown));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(List.of(perm));

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getMergedPermissions()).containsExactly("Special Permission");
    }

    @Test
    void getDashboardConfig_multipleRoles_mergesPermissionsDeduplicated() {
        UserRoleHospitalAssignment doctor = buildAssignment(true, "ROLE_DOCTOR", "Doctor");
        UserRoleHospitalAssignment nurse = buildAssignment(true, "ROLE_NURSE", "Nurse");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(doctor, nurse));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        // Primary should be DOCTOR (higher priority than NURSE)
        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_DOCTOR");
        // View Patient Records should appear once (deduplicated)
        long count = result.getMergedPermissions().stream()
            .filter("View Patient Records"::equals)
            .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void getDashboardConfig_assignmentWithNullRole_handledGracefully() {
        UserRoleHospitalAssignment noRole = UserRoleHospitalAssignment.builder()
            .active(true)
            .build();
        noRole.setId(UUID.randomUUID());

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(noRole));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getRoles()).hasSize(1);
        assertThat(result.getRoles().get(0).getRoleCode()).isNull();
    }

    @Test
    void getDashboardConfig_assignmentWithNullHospital_handledGracefully() {
        Role role = new Role();
        role.setCode("ROLE_DOCTOR");
        role.setName("Doctor");

        UserRoleHospitalAssignment noHosp = UserRoleHospitalAssignment.builder()
            .role(role)
            .active(true)
            .build();
        noHosp.setId(UUID.randomUUID());

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(noHosp));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getRoles()).hasSize(1);
        assertThat(result.getRoles().get(0).getHospitalId()).isNull();
        assertThat(result.getRoles().get(0).getHospitalName()).isNull();
    }

    @Test
    void getDashboardConfig_permissionWithNullName_filtered() {
        UserRoleHospitalAssignment assignment = buildAssignment(true, "ROLE_DOCTOR", "Doctor");

        Permission nullNamePerm = new Permission();
        nullNamePerm.setName(null);
        nullNamePerm.setAssignment(assignment);

        Permission validPerm = new Permission();
        validPerm.setName("Valid");
        validPerm.setAssignment(assignment);

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(assignment));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(List.of(nullNamePerm, validPerm));

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getMergedPermissions()).contains("Valid");
        assertThat(result.getMergedPermissions()).doesNotContainNull();
    }

    @Test
    void getDashboardConfig_adminRole_hasPriority85() {
        UserRoleHospitalAssignment admin = buildAssignment(true, "ROLE_ADMIN", "Admin");
        UserRoleHospitalAssignment nurse = buildAssignment(true, "ROLE_NURSE", "Nurse");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(nurse, admin));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void getDashboardConfig_headOfDepartmentRole() {
        UserRoleHospitalAssignment head = buildAssignment(true, "ROLE_HEAD_OF_DEPARTMENT", "Head of Department");
        UserRoleHospitalAssignment nurse = buildAssignment(true, "ROLE_NURSE", "Nurse");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(nurse, head));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_HEAD_OF_DEPARTMENT");
    }

    @Test
    void getDashboardConfig_physicianRole_samePriorityAsDoctor() {
        UserRoleHospitalAssignment physician = buildAssignment(true, "ROLE_PHYSICIAN", "Physician");

        when(assignmentRepository.findAllDetailedByUserId(userId)).thenReturn(List.of(physician));
        when(permissionRepository.findByAssignment_IdIn(any())).thenReturn(Collections.emptyList());

        DashboardConfigResponseDTO result = service.getDashboardConfig(userId);

        assertThat(result.getPrimaryRoleCode()).isEqualTo("ROLE_PHYSICIAN");
    }

    // ---------- helpers ----------

    private UserRoleHospitalAssignment buildAssignment(boolean active, String roleCode, String roleName) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleName);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Test Hospital");

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .role(role)
            .hospital(hospital)
            .active(active)
            .build();
        assignment.setId(UUID.randomUUID());
        return assignment;
    }
}
