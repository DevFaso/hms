package com.example.hms.security.tenant;

import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * QA D845: which organisations a caller may read through {@code /organizations}.
 *
 * <p>The organisation reads admitted {@code ROLE_HOSPITAL_ADMIN} with no filter,
 * so any hospital administrator read every tenant's contacts, onboarding notes,
 * security policies and hospitals. A verified super-admin (a live SUPER_ADMIN
 * assignment, never a token claim) reads every organisation; anyone else reads
 * only the organisations of the hospitals they hold an ACTIVE
 * {@code ROLE_HOSPITAL_ADMIN} assignment at, computed live from the database.
 * A clinical assignment elsewhere grants no organisation read.
 */
@Component
@RequiredArgsConstructor
public class OrganizationReadScope {

    private final ActingScopeResolver actingScopeResolver;
    private final OrganizationRepository organizationRepository;

    /** True for a verified super-admin, who reads every organisation. */
    public boolean readsEveryOrganization() {
        return actingScopeResolver.isVerifiedSuperAdmin();
    }

    /**
     * The organisations a caller who is NOT a verified super-admin may read;
     * empty when they administer no hospital. Ask {@link #readsEveryOrganization()} first.
     */
    public Set<UUID> administeredOrganizationIds() {
        UUID userId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        if (userId == null) {
            return Set.of();
        }
        return organizationRepository.findAdministeredOrganizationIds(userId);
    }

    /** 403 unless the caller may read this organisation. */
    public void requireReadable(UUID organizationId) {
        if (readsEveryOrganization()) {
            return;
        }
        if (organizationId == null || !administeredOrganizationIds().contains(organizationId)) {
            throw new AccessDeniedException("Access Denied: this organisation is not one you administer.");
        }
    }
}
