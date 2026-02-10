package com.example.hms.enums;

/**
 * Represents different types of security rules that can be applied
 */
public enum SecurityRuleType {
    ROLE_PERMISSION("Role Permission"),
    ENDPOINT_ACCESS("Endpoint Access"),
    DATA_FILTER("Data Filter"),
    PASSWORD_STRENGTH("Password Strength"),
    SESSION("Session"),
    SESSION_TIMEOUT("Session Timeout"),
    IP_WHITELIST("IP Whitelist"),
    API_RATE_LIMIT("API Rate Limit"),
    MFA("Multi-Factor Authentication"),
    TWO_FACTOR_AUTH("Two Factor Authentication"),
    AUDIT_REQUIREMENT("Audit Requirement"),
    COMPLIANCE_CHECK("Compliance Check");

    private final String displayName;

    SecurityRuleType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}