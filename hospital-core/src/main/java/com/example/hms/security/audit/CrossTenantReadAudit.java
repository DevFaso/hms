package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
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
 * The one writer of cross-tenant read rows (docs/security/tenant-resolution.md §3.6).
 *
 * <p><b>Global-view reads.</b> A request whose final scope is GLOBAL — a
 * verified super-admin (a live SUPER_ADMIN assignment) who named no hospital —
 * writes one {@code DATA_ACCESS} row after the handler completes
 * ({@link #recordGlobalViewRequest}); the hand-wired super-admin views add
 * their view label and row count through {@link #recordCrossTenantRead}. A
 * pinned super-admin reads one hospital like anyone else and writes neither.
 *
 * <p><b>Refusals.</b> An explicitly named hospital the caller may not act at —
 * in {@code X-Hospital-Id}, a {@code ?hospitalId=}, a body field or a path
 * variable — writes a {@code DATA_ACCESS} / {@code REJECTED} row whoever the
 * caller is ({@link #recordRefusal}), split into {@code NO_LONGER_PERMITTED}
 * (a stale chip, a revoked hospital) and {@code NOT_PERMITTED} (a hospital
 * never held: the tenant-UUID probe), deduplicated per actor, hospital and
 * reason per hour so a session holding a revoked chip writes one row, not one
 * per request.
 *
 * <p>Every emission is best-effort: {@link AuditEventLogService#logEvent} is
 * {@code REQUIRES_NEW} and swallows persistence failures, and nothing here
 * propagates. A failed audit must never break the request it traces.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossTenantReadAudit {

    /** One refusal row per actor, hospital and reason per this many minutes (design Q7). */
    static final long REFUSAL_WINDOW_MINUTES = 60;
    private static final int REFUSAL_DEDUPE_MAX_ENTRIES = 10_000;
    private static final String DETAIL_SCOPE = "scope";

    private final AuditEventLogService auditEventLogService;

    /** Per instance, in memory: a restart or a second instance over-records, never under-records. */
    private final PatientAccessDedupe refusalDedupe =
        new PatientAccessDedupe(REFUSAL_WINDOW_MINUTES, REFUSAL_DEDUPE_MAX_ENTRIES);

    /**
     * Record a successful cross-tenant read by a super-admin in global view.
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
            // The scope is read here, so it may no longer change (design §3.4).
            HospitalContextHolder.seal();
            if (!ctx.isGlobalView()) {
                return;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("view", viewLabel);
            details.put("rowsReturned", rowsReturned);
            details.put(DETAIL_SCOPE, "CROSS_TENANT");

            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(ctx.getPrincipalUserId())
                .userName(currentUsername())
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
            markRequestRecorded();
        } catch (RuntimeException ex) {
            // Belt-and-braces: even though logEvent itself is best-effort,
            // we may throw here resolving username/ip. Never propagate.
            log.warn("[AUDIT] Failed to record cross-tenant read for {} ({}): {}",
                entityType, viewLabel, ex.getMessage());
        }
    }

    /**
     * One row for a request a verified super-admin served in global view,
     * written after the handler completed, from the FINAL scope (design §3.4
     * step 4), so a super-admin who narrowed to one hospital writes none.
     * Ids only: this runs in {@code afterCompletion}, outside any persistence
     * context (the #560 → #564 lesson).
     *
     * @param actorUserId the super-admin
     * @param username    their principal name, the row's actor label
     * @param method      the HTTP method
     * @param handlerPath the matched route pattern, e.g. {@code /patients/{id}}
     */
    public void recordGlobalViewRequest(UUID actorUserId, String username, String method, String handlerPath) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", method);
            details.put("path", handlerPath);
            details.put(DETAIL_SCOPE, "GLOBAL_VIEW");
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorUserId)
                .userName(username)
                .eventType(AuditEventType.DATA_ACCESS)
                .eventDescription("Super-admin global-view request: " + method + " " + handlerPath)
                .entityType("PLATFORM")
                .resourceId(handlerPath)
                .resourceName(handlerPath)
                .status(AuditStatus.SUCCESS)
                .ipAddress(resolveClientIp())
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.warn("[AUDIT] Failed to record a global-view request on {}: {}", handlerPath, ex.getMessage());
        }
    }

    /**
     * A refused, explicitly named hospital (design §3.6 item 3): whoever the
     * caller is, one {@code DATA_ACCESS} / {@code REJECTED} row naming the
     * hospital and the reason, at most once per actor, hospital and reason per
     * {@value #REFUSAL_WINDOW_MINUTES} minutes.
     *
     * @param actorUserId         the caller's local user id ({@code null} for a Keycloak principal with none)
     * @param username            the caller's principal name, the dedupe key when there is no user id
     * @param requestedHospitalId the hospital named
     * @param reason              {@code NOT_PERMITTED} or {@code NO_LONGER_PERMITTED}
     * @param source              where it was named: {@code HEADER}, or {@code REQUESTED} for a
     *                            parameter, body field or path variable
     */
    public void recordRefusal(UUID actorUserId, String username, UUID requestedHospitalId,
                              ActingScope.Reason reason, ActingScope.Source source) {
        recordRefusal(actorUserId, username, requestedHospitalId, reason, source, System.currentTimeMillis());
    }

    void recordRefusal(UUID actorUserId, String username, UUID requestedHospitalId,
                       ActingScope.Reason reason, ActingScope.Source source, long nowMillis) {
        try {
            if (requestedHospitalId == null || reason == null) {
                return;
            }
            String actorKey = actorUserId != null ? actorUserId.toString() : username;
            if (actorKey == null
                || !refusalDedupe.shouldRecord(actorKey + ":" + requestedHospitalId + ":" + reason, nowMillis)) {
                return;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestedHospitalId", requestedHospitalId.toString());
            details.put("reason", reason.name());
            details.put("source", source == null ? null : source.name());
            details.put(DETAIL_SCOPE, "REFUSED");
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(actorUserId)
                .userName(username)
                .eventType(AuditEventType.DATA_ACCESS)
                .eventDescription("Hospital scope refused (" + reason.name() + ")")
                .entityType("HOSPITAL")
                .resourceId(requestedHospitalId.toString())
                .status(AuditStatus.REJECTED)
                .ipAddress(resolveClientIp())
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.warn("[AUDIT] Failed to record a hospital-scope refusal ({}): {}", reason, ex.getMessage());
        }
    }

    /** The request has its global-view row; {@link GlobalViewAuditInterceptor} writes no second one. */
    private static void markRequestRecorded() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            attrs.getRequest().setAttribute(GlobalViewAuditInterceptor.ALREADY_RECORDED, Boolean.TRUE);
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "UNKNOWN";
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

}
