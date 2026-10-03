package com.example.hms.security.tenant;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Exposes the request's tenant scope to Spring Data SpEL expressions
 * ({@code :#{@tenantContext.effectiveHospitalIds()}} in {@code PatientRepository}).
 *
 * <p>It answers exactly what the repository filter
 * ({@code TenantScopeSpecification}) answers, from the same rule
 * ({@link ActingScopeResolver#readableHospitalIds}): global view for a
 * super-admin who named no hospital, the pin for one who did, the live
 * permitted hospitals for everyone else. Organisations are not a read scope
 * (design Q6, option A), so nothing here exposes them. Reading the scope seals
 * it for the rest of the request.
 */
@Component("tenantContext")
public class TenantContextAccessor {

    private HospitalContext context() {
        HospitalContextHolder.seal();
        return HospitalContextHolder.getContextOrEmpty();
    }

    /** A verified super-admin who named no hospital: queries span every tenant. */
    public boolean isGlobalView() {
        return context().isGlobalView();
    }

    public boolean isHospitalAdmin() {
        return context().isHospitalAdmin();
    }

    /** The hospitals a query may read; empty in global view (test {@link #isGlobalView()} first). */
    public Set<UUID> effectiveHospitalIds() {
        return ActingScopeResolver.readableHospitalIds(context());
    }

    public boolean hasHospitalScope() {
        return !effectiveHospitalIds().isEmpty();
    }

    public boolean hasAnyTenantScope() {
        return isGlobalView() || hasHospitalScope();
    }
}
