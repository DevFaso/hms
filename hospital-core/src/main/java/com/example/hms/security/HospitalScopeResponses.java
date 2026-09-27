package com.example.hms.security;

import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * The refusals both auth filters write before any controller runs: the 403 for
 * a refused {@code X-Hospital-Id}, in the same shape {@code GlobalExceptionHandler}
 * gives a {@link HospitalScopeRefusedException} ({@code code} and
 * {@code reason}, so the portal can re-read its scope on
 * {@code NO_LONGER_PERMITTED}), and the 423 of the tenant lifecycle gate.
 */
@Slf4j
public final class HospitalScopeResponses {

    private HospitalScopeResponses() {
    }

    public static void writeRefusal(HttpServletResponse response, ActingScope.Reason reason) {
        if (response.isCommitted()) {
            return;
        }
        ActingScope.Reason effective = reason == null ? ActingScope.Reason.NOT_PERMITTED : reason;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            // Every value is a constant or an enum name: nothing the caller sent
            // is echoed back.
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                + "\"code\":\"" + HospitalScopeRefusedException.CODE + "\","
                + "\"reason\":\"" + effective.name() + "\","
                + "\"message\":\"" + ActingScopeResolver.refusalMessage(effective) + "\"}");
        } catch (IOException ex) {
            log.warn("[AUTH] Failed to write the hospital-scope refusal body", ex);
        }
    }

    /**
     * 423 LOCKED with a clear, non-PII JSON body so the portal can surface an
     * actionable message. The frontend interceptor only special-cases 401/403,
     * so a bare 423 with no body would render as a generic request failure.
     */
    public static void writeTenantBlocked(HttpServletResponse response) {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(423); // LOCKED — RFC 4918
        response.setHeader("X-Block-Reason", "tenant-lifecycle");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write(
                "{\"error\":\"tenant_blocked\","
                    + "\"message\":\"Access to this organization is currently unavailable due to its lifecycle status. "
                    + "Contact your super-admin if this is unexpected.\","
                    + "\"status\":423,"
                    + "\"blockReason\":\"tenant-lifecycle\"}");
        } catch (IOException ex) {
            log.warn("[AUTH] Failed to write 423 tenant-blocked response body", ex);
        }
    }
}
