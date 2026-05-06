package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Audit hook for cross-tenant reads performed by super-admins through the
 * {@code /super-admin/...} endpoints (and any future cross-tenant
 * controller branches).
 *
 * <p>This closes the F3 follow-up from
 * {@code docs/super-admin-cross-tenant-design.md}: design call #4 said
 * "every cross-tenant read goes through the existing audit layer", but
 * the cross-tenant list endpoints went live in commit {@code 2678681f}
 * without any explicit {@link AuditEventLogService#logEvent} calls and
 * the audit aspect that auto-records writes does not auto-record GET
 * reads. The component below makes the audit hook explicit and
 * single-call-site for every cross-tenant read endpoint, so wiring is
 * one method call per endpoint instead of an ad-hoc {@code AuditEventLog}
 * builder block per resource type.</p>
 *
 * <p>The emission is best-effort: we delegate to
 * {@link AuditEventLogService#logEvent} which is annotated
 * {@code Propagation.REQUIRES_NEW} and swallows persistence failures.
 * A failed audit must never break the read it's tracing — the read
 * already succeeded by the time we get here.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossTenantReadAudit {

    private final AuditEventLogService auditEventLogService;

    /**
     * Record a successful cross-tenant read.
     *
     * @param entityType DTO/entity classifier (e.g. {@code "ENCOUNTER"},
     *                   {@code "CONSULTATION"}, {@code "HOSPITAL"}).
     * @param viewLabel  human-readable view name (e.g. "recent-encounters")
     *                   used in the audit description so an analyst can tell
     *                   what the super-admin was looking at without joining
     *                   to the entity table.
     * @param rowsReturned the number of rows returned to the caller, so
     *                     anomaly detection can flag bulk-export-shaped
     *                     reads.
     */
    public void recordCrossTenantRead(String entityType, String viewLabel, int rowsReturned) {
        try {
            HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
            // Only audit when the caller is a real super-admin (per JWT claim,
            // not authorities — same gate as F1). Non-super-admin paths are
            // already scoped and audited by their per-resource code.
            if (!ctx.isSuperAdmin()) {
                return;
            }

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = auth != null ? auth.getName() : "UNKNOWN";

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("view", viewLabel);
            details.put("rowsReturned", rowsReturned);
            details.put("scope", "CROSS_TENANT");

            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(ctx.getPrincipalUserId())
                .userName(username)
                .eventType(AuditEventType.DATA_ACCESS)
                .eventDescription("Super-admin cross-tenant read: " + viewLabel
                    + " (" + rowsReturned + " rows)")
                .entityType(entityType)
                .resourceId(viewLabel) // no single resource — the view itself is the target
                .resourceName(viewLabel)
                .status(AuditStatus.SUCCESS)
                .ipAddress(resolveClientIp())
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            // Belt-and-braces: even though logEvent itself is best-effort,
            // we may throw here resolving username/ip. Never propagate.
            log.warn("[AUDIT] Failed to record cross-tenant read for {} ({}): {}",
                entityType, viewLabel, ex.getMessage());
        }
    }

    /**
     * Best-effort {@code X-Forwarded-For}-aware client IP. Returns
     * {@code null} when called outside an HTTP request context (e.g. from
     * a scheduled job that triggers a cross-tenant read).
     */
    private static String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // First hop is the original client when the proxy chain is
                // honest; we trust whatever the upstream tier set here.
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // Suppress "unused" warning while keeping the import explicit for clarity.
    @SuppressWarnings("unused")
    private static UUID nullSafePrincipal(HospitalContext ctx) {
        return ctx == null ? null : ctx.getPrincipalUserId();
    }
}
