package com.example.hms.security.tenant;

import com.example.hms.security.context.HospitalContext;

import java.util.UUID;

/**
 * Marker interface for domain entities that participate in tenant (organization / hospital / department) scoping.
 * Implementations should expose their resolved tenant identifiers so that repository filters can enforce row-level
 * access control, and allow the scope to be applied automatically from the active {@link HospitalContext}.
 */
public interface TenantScoped {

    /**
     * @return the organization identifier associated with this record, or {@code null} if none has been assigned yet.
     */
    UUID getTenantOrganizationId();

    /**
     * @return the hospital identifier associated with this record, or {@code null} if none has been assigned yet.
     */
    UUID getTenantHospitalId();

    /**
     * @return the department identifier associated with this record, or {@code null} if none has been assigned yet.
     */
    UUID getTenantDepartmentId();

    /**
     * Apply/normalize tenant scope values given the current request context. Implementations should only override
     * tenant identifiers when no value has been explicitly set to avoid corrupting pre-seeded data.
     */
    void applyTenantScope(HospitalContext context);
}
