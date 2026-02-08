package com.example.hms.security.tenant;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Exposes the active tenant context to Spring Data SpEL expressions and other framework integrations. This makes it
 * easy to reference scoped identifiers inside {@code @Query} annotations (e.g. {@code :#{@tenantContext.hospitalIds}}).
 */
@Component("tenantContext")
public class TenantContextAccessor {

    private HospitalContext context() {
        return HospitalContextHolder.getContextOrEmpty();
    }

    public boolean isSuperAdmin() {
        return context().isSuperAdmin();
    }

    public boolean isHospitalAdmin() {
        return context().isHospitalAdmin();
    }

    public UUID activeOrganizationId() {
        return context().getActiveOrganizationId();
    }

    public UUID activeHospitalId() {
        return context().getActiveHospitalId();
    }

    public Set<UUID> organizationIds() {
        return context().getPermittedOrganizationIds();
    }

    public Set<UUID> hospitalIds() {
        return context().getPermittedHospitalIds();
    }

    public Set<UUID> departmentIds() {
        return context().getPermittedDepartmentIds();
    }

    public boolean hasOrganizationScope() {
        return !organizationIds().isEmpty() || activeOrganizationId() != null;
    }

    public boolean hasHospitalScope() {
        return !hospitalIds().isEmpty() || activeHospitalId() != null;
    }

    public boolean hasDepartmentScope() {
        return !departmentIds().isEmpty();
    }

    public Set<UUID> effectiveOrganizationIds() {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(organizationIds());
        if (activeOrganizationId() != null) {
            ids.add(activeOrganizationId());
        }
        return ids;
    }

    public Set<UUID> effectiveHospitalIds() {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(hospitalIds());
        if (activeHospitalId() != null) {
            ids.add(activeHospitalId());
        }
        return ids;
    }

    public boolean hasAnyTenantScope() {
        return isSuperAdmin() || hasOrganizationScope() || hasHospitalScope() || hasDepartmentScope();
    }
}
