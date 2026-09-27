package com.example.hms.security.tenant;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Test fixture for the one tenant resolver (docs/security/tenant-resolution.md
 * §4.2): the request scopes the two auth filters produce, set directly on the
 * thread so a unit test or a service-level IT runs under the same scope a real
 * request would have. Tests that used to rely on the removed authorities-based
 * "step 4", or on an unset context, set one of these instead.
 */
public final class ActingScopeTestSupport {

    private ActingScopeTestSupport() {
    }

    /** A resolver over mocks: its consumer side reads only the thread's context. */
    @SuppressWarnings("unchecked")
    public static ActingScopeResolver resolver() {
        return new ActingScopeResolver(mock(TenantRoleAssignmentAccessor.class),
            mock(UserRoleHospitalAssignmentRepository.class), mock(ObjectProvider.class));
    }

    /** A resolver whose refusal classification reads {@code assignmentRepository}. */
    @SuppressWarnings("unchecked")
    public static ActingScopeResolver resolver(UserRoleHospitalAssignmentRepository assignmentRepository,
                                               ObjectProvider<CrossTenantReadAudit> audit) {
        return new ActingScopeResolver(mock(TenantRoleAssignmentAccessor.class), assignmentRepository,
            audit != null ? audit : mock(ObjectProvider.class));
    }

    /** Staff holding exactly one hospital: pinned to it ({@code SOLE_ASSIGNMENT}). */
    public static HospitalContext actingAt(UUID userId, UUID hospitalId) {
        HospitalContext context = HospitalContext.builder()
            .principalUserId(userId)
            .permittedHospitalIds(Set.of(hospitalId))
            .activeHospitalId(hospitalId)
            .build();
        HospitalContextHolder.setContext(context);
        return context;
    }

    /** A verified super-admin who named no hospital: global view. */
    public static HospitalContext globalSuperAdmin(UUID userId) {
        HospitalContext context = HospitalContext.builder()
            .principalUserId(userId)
            .superAdmin(true)
            .build();
        HospitalContextHolder.setContext(context);
        return context;
    }

    /** A verified super-admin who named {@code hospitalId} ({@code X-Hospital-Id}). */
    public static HospitalContext superAdminAt(UUID userId, UUID hospitalId) {
        HospitalContext context = HospitalContext.builder()
            .principalUserId(userId)
            .superAdmin(true)
            .activeHospitalId(hospitalId)
            .headerOverridden(true)
            .build();
        HospitalContextHolder.setContext(context);
        return context;
    }

    public static void clear() {
        HospitalContextHolder.clear();
    }
}
