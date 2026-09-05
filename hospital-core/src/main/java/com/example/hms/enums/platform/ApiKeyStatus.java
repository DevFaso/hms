package com.example.hms.enums.platform;

/**
 * Lifecycle of an issued API key (Tier 2 item 45). Revoked, never
 * deleted — an issued credential is an auditable fact.
 */
public enum ApiKeyStatus {
    ACTIVE,
    REVOKED
}
