package com.example.hms.enums.platform;

/**
 * Enumerates lifecycle events emitted by the platform registry when service configurations change.
 */
public enum PlatformRegistryEventType {

    /** A new organization-level platform service registration was created. */
    ORGANIZATION_SERVICE_REGISTERED,

    /** An organization-level platform service registration was updated. */
    ORGANIZATION_SERVICE_UPDATED,

    /** A hospital was linked to an organization platform service. */
    HOSPITAL_LINKED_TO_SERVICE,

    /** A hospital was unlinked from an organization platform service. */
    HOSPITAL_UNLINKED_FROM_SERVICE,

    /** A department was linked to an organization platform service. */
    DEPARTMENT_LINKED_TO_SERVICE,

    /** A department was unlinked from an organization platform service. */
    DEPARTMENT_UNLINKED_FROM_SERVICE
}
