package com.example.hms.service;

import com.example.hms.model.Permission;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.DashboardConfigResponseDTO.RoleAssignmentConfigDTO;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service responsible for providing dashboard configuration based on user roles and permissions.
 * This service aggregates permissions from all user assignments to provide a unified view
 * of what features should be visible on the dashboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardConfigService {

        private final UserRoleHospitalAssignmentRepository assignmentRepository;
        private final PermissionRepository permissionRepository;

        private static final String PERM_VIEW_PATIENT_RECORDS = "View Patient Records";
        private static final String PERM_UPDATE_PATIENT_RECORDS = "Update Patient Records";
        private static final String PERM_VIEW_LAB_RESULTS = "View Lab Results";
        private static final String PERM_ACCESS_PATIENT_ALLERGIES = "Access Patient Allergies";
        private static final String PERM_VIEW_LAB_ORDERS = "View Lab Orders";

        private static final Map<String, List<String>> ROLE_DEFAULT_PERMISSIONS = createDefaultPermissions();

    /**
     * Get complete dashboard configuration for a user, including all their roles and merged permissions.
     * 
     * @param userId The user's UUID
     * @return Dashboard configuration with roles and permissions
     */
    @Transactional(readOnly = true)
    public DashboardConfigResponseDTO getDashboardConfig(UUID userId) {
        log.debug("Fetching dashboard config for user: {}", userId);

        // Get all active assignments for the user
        List<UserRoleHospitalAssignment> assignments = assignmentRepository
                .findAllDetailedByUserId(userId)
                .stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .toList();

        if (assignments.isEmpty()) {
            log.warn("No active assignments found for user: {}", userId);
            return DashboardConfigResponseDTO.builder()
                    .userId(userId)
                    .primaryRoleCode(null)
                    .roles(Collections.emptyList())
                    .mergedPermissions(Collections.emptyList())
                    .build();
        }

        // Extract assignment IDs to fetch permissions
        Set<UUID> assignmentIds = assignments.stream()
                .map(UserRoleHospitalAssignment::getId)
                .collect(Collectors.toSet());

        // Fetch all permissions for these assignments
        Map<UUID, List<Permission>> permissionsByAssignment = permissionRepository
                .findByAssignment_IdIn(assignmentIds)
                .stream()
                .collect(Collectors.groupingBy(
                        p -> p.getAssignment().getId()
                ));

        // Build role configurations
        List<RoleAssignmentConfigDTO> roleConfigs = assignments.stream()
                .map(assignment -> {
                    List<Permission> permissions = permissionsByAssignment
                            .getOrDefault(assignment.getId(), Collections.emptyList());

                    return RoleAssignmentConfigDTO.builder()
                            .roleCode(assignment.getRole() != null ? assignment.getRole().getCode() : null)
                            .roleName(assignment.getRole() != null ? assignment.getRole().getName() : null)
                            .hospitalId(assignment.getHospital() != null ? assignment.getHospital().getId() : null)
                            .hospitalName(assignment.getHospital() != null ? assignment.getHospital().getName() : null)
                            .permissions(mergeWithDefaultPermissions(
                                    permissions,
                                    assignment.getRole() != null ? assignment.getRole().getCode() : null))
                            .build();
                })
                .toList();

        // Merge all permissions (deduplicated)
        Set<String> mergedPermissionsSet = roleConfigs.stream()
                .flatMap(roleConfig -> roleConfig.getPermissions().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Determine primary role (prioritize SUPER_ADMIN > HOSPITAL_ADMIN > others)
        String primaryRoleCode = determinePrimaryRole(roleConfigs);

        DashboardConfigResponseDTO config = DashboardConfigResponseDTO.builder()
                .userId(userId)
                .primaryRoleCode(primaryRoleCode)
                .roles(roleConfigs)
                .mergedPermissions(new ArrayList<>(mergedPermissionsSet))
                .build();

        log.debug("Dashboard config for user {}: {} roles, {} unique permissions", 
                userId, roleConfigs.size(), mergedPermissionsSet.size());

        return config;
    }

    /**
     * Determine the primary role based on hierarchy.
     * Priority: SUPER_ADMIN > HOSPITAL_ADMIN > DEPARTMENT_HEAD > DOCTOR/SURGEON > NURSE > others
     */
    private String determinePrimaryRole(List<RoleAssignmentConfigDTO> roleConfigs) {
        if (roleConfigs.isEmpty()) {
            return null;
        }

        // Define role hierarchy (higher value = higher priority)
        Map<String, Integer> rolePriority = new HashMap<>();
        rolePriority.put("ROLE_SUPER_ADMIN", 100);
        rolePriority.put("ROLE_HOSPITAL_ADMIN", 90);
        rolePriority.put("ROLE_ADMIN", 85);
        rolePriority.put("ROLE_DEPARTMENT_HEAD", 80);
        rolePriority.put("ROLE_HEAD_OF_DEPARTMENT", 80);
        rolePriority.put("ROLE_DOCTOR", 70);
        rolePriority.put("ROLE_PHYSICIAN", 70);
        rolePriority.put("ROLE_SURGEON", 70);
        rolePriority.put("ROLE_ANESTHESIOLOGIST", 65);
        rolePriority.put("ROLE_NURSE", 60);
        rolePriority.put("ROLE_MIDWIFE", 55);
        rolePriority.put("ROLE_PHARMACIST", 50);
        rolePriority.put("ROLE_RADIOLOGIST", 50);
        rolePriority.put("ROLE_LAB_SCIENTIST", 45);
        rolePriority.put("ROLE_PHYSIOTHERAPIST", 40);
        rolePriority.put("ROLE_RECEPTIONIST", 30);
        rolePriority.put("ROLE_BILLING_SPECIALIST", 25);
        rolePriority.put("ROLE_PATIENT", 10);

        return roleConfigs.stream()
                .map(RoleAssignmentConfigDTO::getRoleCode)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(code -> rolePriority.getOrDefault(code, 0)))
                .orElse(roleConfigs.get(0).getRoleCode());
    }

    private static List<String> mergeWithDefaultPermissions(List<Permission> persistedPermissions, String roleCode) {
        LinkedHashSet<String> merged = persistedPermissions.stream()
                .map(Permission::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> defaults = ROLE_DEFAULT_PERMISSIONS.getOrDefault(roleCode, Collections.emptyList());
                if (!defaults.isEmpty()) {
                        merged.addAll(defaults);
                }

        return new ArrayList<>(merged);
    }

    private static Map<String, List<String>> createDefaultPermissions() {
        Map<String, List<String>> map = new HashMap<>();
        map.put("ROLE_SUPER_ADMIN", List.of(
                "Manage All Organizations",
                "Manage All Hospitals",
                "Manage All Users",
                "Manage System Settings",
                "View All Audit Logs",
                "Manage Permissions",
                "Manage Roles",
                "Delete Organizations",
                "Delete Hospitals",
                "Manage Global Configurations",
                "View System Analytics",
                "Manage Backups",
                "Access All Patient Records",
                "Override Security Settings",
                "Manage Database Access"));
        map.put("ROLE_HOSPITAL_ADMIN", List.of(
                "Manage Hospital Staff",
                "Manage Departments",
                "View Hospital Reports",
                "Manage Hospital Settings",
                "Approve Billing",
                "View Staff Schedules",
                "Assign Staff Roles",
                "Manage Bed Capacity",
                "View Financial Reports",
                "Manage Equipment Inventory",
                "Configure Department Settings",
                "View Patient Statistics",
                "Approve Leave Requests",
                "Manage Emergency Protocols",
                "Access Hospital Audit Logs",
                "Manage Insurance Contracts",
                "Set Billing Rates",
                "Approve Large Expenses"));
        map.put("ROLE_DOCTOR", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                PERM_UPDATE_PATIENT_RECORDS,
                "Create Prescriptions",
                "Order Lab Tests",
                PERM_VIEW_LAB_RESULTS,
                "Create Encounters",
                "Update Diagnoses",
                "Request Imaging Studies",
                "View Imaging Results",
                "Create Treatment Plans",
                "Update Medical History",
                "Order Medications",
                "Request Consultations",
                "View Patient Vitals",
                "Discharge Patients",
                "Admit Patients",
                "Create Procedure Orders",
                "Sign Medical Reports",
                PERM_ACCESS_PATIENT_ALLERGIES,
                "View Medication History",
                "Create Referrals",
                "Update Encounter Notes"));
        map.put("ROLE_SURGEON", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                PERM_UPDATE_PATIENT_RECORDS,
                "Schedule Surgeries",
                "Create Surgical Plans",
                "Document Surgical Procedures",
                "Order Pre-op Tests",
                "View Imaging Results",
                "Request Anesthesia Consultation",
                "Update Surgical Notes",
                "Create Post-op Orders",
                "Admit Patients to Surgery",
                "Discharge from Recovery",
                "Sign Operative Reports",
                "View Patient Vitals",
                PERM_ACCESS_PATIENT_ALLERGIES,
                "Request Blood Products",
                "Create Referrals",
                "Manage Operating Room Schedule",
                "View Surgical History",
                "Document Complications"));
        map.put("ROLE_NURSE", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                "Update Vital Signs",
                "Administer Medication",
                "View Schedules",
                "Update Patient Status",
                "Record Intake Output",
                "Document Nursing Notes",
                "View Medication Orders",
                "Verify Medication Administration",
                "Update Patient Observations",
                "Prepare Patients for Procedures",
                "View Treatment Plans",
                "Alert Doctors",
                "Manage Patient Care Plans",
                "Document Patient Progress",
                "Update Wound Care Records",
                "Check Patient Alerts",
                PERM_VIEW_LAB_RESULTS,
                "Coordinate Patient Transfers"));
        map.put("ROLE_MIDWIFE", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                PERM_UPDATE_PATIENT_RECORDS,
                "Monitor Labor Progress",
                "Document Delivery Notes",
                "Perform Prenatal Assessments",
                "Create Birth Plans",
                "Administer Medications",
                "Update Vital Signs",
                "Perform Postpartum Care",
                "Provide Breastfeeding Support",
                "Schedule Prenatal Appointments",
                "Document Newborn Assessment",
                "Order Lab Tests",
                PERM_VIEW_LAB_RESULTS,
                "Create Referrals to OB-GYN",
                "Educate Patients",
                "Manage High-Risk Pregnancies",
                "Perform Ultrasound Scans",
                "Document Maternal History",
                "Alert Obstetricians"));
        map.put("ROLE_PHARMACIST", List.of(
                "View Prescriptions",
                "Dispense Medications",
                "Verify Drug Interactions",
                "Update Medication Inventory",
                "Counsel Patients on Medications",
                "Create Pharmaceutical Reports",
                "Monitor Controlled Substances",
                "Review Medication Orders",
                "Suggest Medication Alternatives",
                "Document Adverse Reactions",
                "Manage Pharmacy Stock",
                "Order Medications from Suppliers",
                "Verify Insurance Coverage",
                "Calculate Dosages",
                "Compound Medications",
                "Track Medication Expiry",
                "Communicate with Prescribers",
                "Update Drug Formulary"));
        map.put("ROLE_RADIOLOGIST", List.of(
                "View Imaging Orders",
                "Perform Imaging Studies",
                "Interpret X-rays",
                "Interpret CT Scans",
                "Interpret MRI Scans",
                "Interpret Ultrasounds",
                "Create Radiology Reports",
                "Sign Imaging Reports",
                PERM_VIEW_PATIENT_RECORDS,
                "Request Additional Views",
                "Communicate Findings to Physicians",
                "Perform Interventional Procedures",
                "Manage Imaging Equipment",
                "Schedule Imaging Appointments",
                "Document Radiation Dosage",
                "Flag Critical Findings",
                "Access Patient History"));
        map.put("ROLE_ANESTHESIOLOGIST", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                "Perform Pre-anesthetic Evaluation",
                "Create Anesthesia Plans",
                "Administer Anesthesia",
                "Monitor Patient During Surgery",
                "Manage Post-anesthetic Care",
                "Document Anesthesia Records",
                PERM_VIEW_LAB_RESULTS,
                "Order Pre-op Tests",
                "Manage Pain Control",
                PERM_ACCESS_PATIENT_ALLERGIES,
                "View Medication History",
                "Alert Surgical Team",
                "Manage Airway",
                "Document Vital Signs",
                "Handle Anesthetic Complications",
                "Consult on Pain Management"));
        map.put("ROLE_RECEPTIONIST", List.of(
                "Register Patients",
                "Schedule Appointments",
                "View Appointments",
                "Update Patient Contact Info",
                "Check-in Patients",
                "Cancel Appointments",
                "Reschedule Appointments",
                "View Patient Demographics",
                "Print Patient Cards",
                "Update Emergency Contacts",
                "Verify Insurance Information",
                "Generate Patient Reports",
                "Manage Waiting List",
                "Send Appointment Reminders",
                "Check-out Patients",
                "Update Visit Reasons",
                "View Doctor Availability"));
        map.put("ROLE_BILLING_SPECIALIST", List.of(
                "Create Invoices",
                "Process Payments",
                "View Billing Reports",
                "Manage Insurance Claims",
                "Update Invoice Status",
                "Generate Payment Receipts",
                "Process Refunds",
                "View Payment History",
                "Submit Claims to Insurers",
                "Track Outstanding Payments",
                "Apply Payment Plans",
                "Send Payment Reminders",
                "Reconcile Accounts",
                "Generate Financial Statements",
                "Verify Insurance Eligibility",
                "Update Billing Codes",
                "Process Co-payments",
                "Manage Discounts",
                "Handle Billing Disputes"));
        map.put("ROLE_LAB_SCIENTIST", List.of(
                PERM_VIEW_LAB_ORDERS,
                "Create Lab Results",
                "Update Lab Results",
                "Manage Lab Tests",
                "View Test Definitions",
                "Approve Lab Results",
                "Request Sample Re-collection",
                "Update Test Status",
                "Calibrate Equipment",
                "Perform Quality Control",
                "Document Test Procedures",
                "Verify Critical Results",
                "Manage Lab Inventory",
                "Update Reference Ranges",
                "Generate Lab Reports",
                "Flag Abnormal Results",
                "Communicate with Physicians"));
        map.put("ROLE_PHYSIOTHERAPIST", List.of(
                PERM_VIEW_PATIENT_RECORDS,
                "Create Treatment Plans",
                "Document Therapy Sessions",
                "Update Patient Progress",
                "Schedule Therapy Appointments",
                "Perform Physical Assessments",
                "Prescribe Exercises",
                "Monitor Rehabilitation Progress",
                "Create Discharge Plans",
                "Order Assistive Devices",
                "Educate Patients on Exercises",
                "Coordinate with Physicians",
                "View Medical History",
                "Document Pain Levels",
                "Measure Range of Motion",
                "Create Home Exercise Programs"));
        map.put("ROLE_PATIENT", List.of(
                "View Own Records",
                "View Own Appointments",
                "Request Appointments",
                "View Own Lab Results",
                "Update Contact Info",
                "View Own Prescriptions",
                "View Own Vital Signs",
                "Download Medical Records",
                "View Billing Statements",
                "Make Payments",
                "View Medication Instructions",
                "Access Treatment Plans",
                "View Immunization Records",
                "Update Emergency Contacts",
                "Request Medical Reports",
                "View Test Results",
                "Cancel Own Appointments",
                "View Insurance Information",
                "Consent to Data Sharing"));
        map.put("ROLE_LAB_MANAGER", List.of(
                PERM_VIEW_LAB_ORDERS,
                "Approve Lab Results",
                "Manage Lab Tests",
                "View Test Definitions",
                "Update Lab Results",
                "Assign Lab Tasks",
                "Manage Lab Inventory",
                "Generate Lab Reports",
                "Calibrate Equipment",
                "Perform Quality Control",
                "Document Test Procedures",
                "Update Test Status",
                "Flag Abnormal Results",
                "Communicate with Physicians"));
        return Collections.unmodifiableMap(map);
    }
}
