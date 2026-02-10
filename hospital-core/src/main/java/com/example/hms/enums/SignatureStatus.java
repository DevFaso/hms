package com.example.hms.enums;

/**
 * Enumeration for the lifecycle status of a digital signature.
 * Story #17: Generic Report Signing API
 */
public enum SignatureStatus {
    /**
     * Signature has been created and is awaiting completion
     */
    PENDING,

    /**
     * Signature has been successfully applied and is valid
     */
    SIGNED,

    /**
     * Signature has been revoked/invalidated
     */
    REVOKED,

    /**
     * Signature has expired based on timestamp or policy
     */
    EXPIRED,

    /**
     * Signature verification failed
     */
    INVALID
}
