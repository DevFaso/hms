package com.example.hms.enums;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Predefined role blueprints that represent common hospital personas.
 * Each blueprint references a curated list of permission codes that can be used
 * to bootstrap a new role quickly.
 */
public enum RoleBlueprint {
    CLINICAL_LEADERSHIP(
        "Clinical Leadership",
        "Full clinical oversight with patient and care coordination capabilities.",
        Set.of(
            "PATIENT_READ",
            "PATIENT_UPDATE",
            "CARE_PLAN_APPROVE",
            "APPOINTMENT_MANAGE",
            "LAB_RESULT_REVIEW",
            "ENCOUNTER_CLOSE"
        )
    ),
    OPERATIONAL_ADMIN(
        "Operational Admin",
        "Operational administration across staff, departments, and scheduling.",
        Set.of(
            "STAFF_READ",
            "STAFF_UPDATE",
            "DEPARTMENT_MANAGE",
            "SCHEDULE_PUBLISH",
            "RESOURCE_ASSIGN",
            "NOTIFICATION_MANAGE"
        )
    ),
    FINANCE_BILLING(
        "Finance & Billing",
        "Billing supervision, revenue cycle, and claim management.",
        Set.of(
            "BILLING_VIEW",
            "BILLING_ADJUST",
            "CLAIM_SUBMIT",
            "PAYMENT_POST",
            "INVOICE_MANAGE",
            "INSURANCE_VERIFY"
        )
    ),
    SECURITY_OFFICER(
        "Security Officer",
        "Security and compliance oversight including audit visibility.",
        Set.of(
            "ROLE_MANAGE",
            "PERMISSION_MANAGE",
            "AUDIT_LOG_VIEW",
            "ACCESS_POLICY_EDIT",
            "SECURITY_EVENT_REVIEW",
            "USER_DISABLE"
        )
    );

    private final String displayName;
    private final String description;
    private final Set<String> permissionCodes;

    RoleBlueprint(String displayName, String description, Set<String> permissionCodes) {
        this.displayName = displayName;
        this.description = description;
        this.permissionCodes = Collections.unmodifiableSet(new LinkedHashSet<>(permissionCodes));
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Set<String> getPermissionCodes() {
        return permissionCodes;
    }
}
