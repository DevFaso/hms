package com.example.hms.enums;

/**
 * Represents different types of security policies that can be applied to organizations
 */
public enum SecurityPolicyType {
    ACCESS_CONTROL("Access Control"),
    DATA_PROTECTION("Data Protection"),
    AUDIT_LOGGING("Audit Logging"),
    PASSWORD_POLICY("Password Policy"),
    SESSION_MANAGEMENT("Session Management"),
    ROLE_MANAGEMENT("Role Management"),
    MULTI_FACTOR_AUTH("Multi-Factor Authentication"),
    API_RATE_LIMITING("API Rate Limiting"),
    COMPLIANCE("Compliance");

    private final String displayName;

    SecurityPolicyType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}