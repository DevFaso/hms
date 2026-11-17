package com.example.hms.security.audit;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One {@code DATA_ACCESS} row per request a super-admin served in global view
 * (docs/security/tenant-resolution.md §3.6 item 2, Q7 option A).
 *
 * <p>Written in {@code afterCompletion}, from the FINAL scope: a super-admin
 * who narrowed to one hospital ({@code ?hospitalId=}) writes none. Only a
 * request that actually READ the scope counts — the seal of design §3.4 — so a
 * page that never consults it (a profile, a notification count keyed on the
 * user) is not a cross-tenant read. A failed request (4xx/5xx) returned no
 * rows and writes none. A request whose view already wrote its own labelled
 * row ({@link CrossTenantReadAudit#recordCrossTenantRead}) writes no second one.
 *
 * <p>Ids only, never an entity: this runs outside any persistence context.
 */
@Component
public class GlobalViewAuditInterceptor implements HandlerInterceptor {

    /** Set by {@link CrossTenantReadAudit#recordCrossTenantRead} when it wrote the request's row. */
    static final String ALREADY_RECORDED = GlobalViewAuditInterceptor.class.getName() + ".recorded";

    /**
     * A provider, not the bean: {@code @WebMvcTest} slices build every
     * {@code HandlerInterceptor} and would otherwise need the audit service.
     */
    private final ObjectProvider<CrossTenantReadAudit> auditProvider;

    public GlobalViewAuditInterceptor(ObjectProvider<CrossTenantReadAudit> auditProvider) {
        this.auditProvider = auditProvider;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (!context.isGlobalView() || !HospitalContextHolder.isSealed()
            || ex != null || response.getStatus() >= 400
            || request.getAttribute(ALREADY_RECORDED) != null) {
            return;
        }
        CrossTenantReadAudit audit = auditProvider.getIfAvailable();
        if (audit == null) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        audit.recordGlobalViewRequest(context.getPrincipalUserId(), context.getPrincipalUsername(),
            request.getMethod(), pattern != null ? pattern.toString() : request.getRequestURI());
    }
}
