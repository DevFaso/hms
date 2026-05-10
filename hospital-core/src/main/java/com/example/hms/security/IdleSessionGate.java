package com.example.hms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Cohesive integration surface for the idle-session timeout. Wraps the
 * {@link IdleSessionTracker} with two policy concerns the call sites
 * shouldn't have to reimplement:
 *
 * <ol>
 *   <li><strong>Service-to-server carve-out</strong> — FHIR / HL7 MLLP /
 *       CDS Hooks / DHIS2 ADX export / partner pharmacy SMS webhook
 *       clients are machines, not humans, and an idle gate would force
 *       them to renew tokens on every long-running batch. The carve-out
 *       is configured via {@code app.auth.idle-tracking.machine-roles}
 *       (comma-separated). It is positive-list by design: any new
 *       machine role added later is subject to idle tracking until the
 *       team explicitly opts it out.</li>
 *   <li><strong>Feature-flag short-circuit</strong> — the gate consults
 *       {@link IdleSessionTracker#isEnabled()} once per call and exits
 *       cheaply when the feature is disabled, so deployments that
 *       haven't enabled idle tracking pay only one boolean check per
 *       request.</li>
 * </ol>
 *
 * <p>Used by {@code JwtAuthenticationFilter} (legacy bearer path),
 * {@code KeycloakHospitalContextFilter} (OIDC path so row 8's
 * {@code OIDC_REQUIRED=true} cutover doesn't silently disable the
 * gate), and {@code AuthController.refreshToken} (refresh-while-idle
 * must reject — silent refresh of an idle session would defeat the
 * purpose of the timeout).
 *
 * <p>Added in v1.0 / Security / Idle session timeout (roadmap row 7).
 */
@Slf4j
@Component
public class IdleSessionGate {

    private static final String[] DEFAULT_MACHINE_ROLES = {
        "ROLE_FHIR_CLIENT",
        "ROLE_HL7_CLIENT",
        "ROLE_CDS_CLIENT",
        "ROLE_DHIS2_CLIENT",
        "ROLE_PARTNER_WEBHOOK"
    };

    /**
     * The {@code WWW-Authenticate} challenge a 401 carries when the
     * rejection is due to idle timeout. Lets the frontend interceptor
     * render a "session timed out" toast instead of the generic
     * "session ended" message.
     */
    public static final String WWW_AUTHENTICATE_CHALLENGE =
        "Bearer realm=\"Hospital\", error=\"invalid_token\", error_description=\"idle_timeout\"";

    private final IdleSessionTracker tracker;
    private final Set<String> machineRoles;

    public IdleSessionGate(
        IdleSessionTracker tracker,
        @Value("${app.auth.idle-tracking.machine-roles:}") String machineRolesCsv
    ) {
        this.tracker = tracker;
        this.machineRoles = parseMachineRoles(machineRolesCsv);
    }

    /**
     * Returns {@code true} when the request must be rejected because the
     * user has gone idle. Returns {@code false} (the success path) when
     * the tracker is disabled, the user is null, the user holds a
     * machine-role authority, or the user has had recent activity.
     *
     * <p>Caller is responsible for writing the 401 response.
     */
    public boolean shouldReject(UUID userId, Collection<? extends GrantedAuthority> authorities) {
        if (!tracker.isEnabled() || userId == null) return false;
        if (hasMachineRole(authorities)) return false;
        return tracker.isIdle(userId);
    }

    /**
     * Mark the user as having had recent activity. Skipped for service-to-
     * server clients so machines don't pin entries in Redis.
     */
    public void touchIfHuman(UUID userId, Collection<? extends GrantedAuthority> authorities) {
        if (!tracker.isEnabled() || userId == null) return;
        if (hasMachineRole(authorities)) return;
        tracker.touch(userId);
    }

    /** Drop the user's entry on logout. No-op for null users / disabled tracker. */
    public void clear(UUID userId) {
        if (!tracker.isEnabled() || userId == null) return;
        tracker.clear(userId);
    }

    private boolean hasMachineRole(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null || authorities.isEmpty() || machineRoles.isEmpty()) {
            return false;
        }
        for (GrantedAuthority a : authorities) {
            if (a == null) continue;
            if (machineRoles.contains(a.getAuthority())) return true;
        }
        return false;
    }

    /**
     * Resolve the configured machine-role set, defaulting to the canonical
     * five integration-client roles when no override is supplied.
     */
    private static Set<String> parseMachineRoles(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        }
        if (out.isEmpty()) {
            Collections.addAll(out, DEFAULT_MACHINE_ROLES);
        }
        return Set.copyOf(out);
    }
}
