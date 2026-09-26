package com.example.hms.security.context;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Applies request-scoped overrides on top of an authenticated principal's
 * {@link HospitalContext}.
 *
 * <p>The portal sends an {@code X-Hospital-Id} header to indicate which of
 * the user's permitted hospitals should be the active scope for this
 * request — used by users with multi-hospital role assignments to switch
 * between hospitals without re-logging-in. This helper validates the
 * header against the principal's permitted scope and returns a
 * {@link HospitalContext} with {@link HospitalContext#getActiveHospitalId()}
 * updated when the override is allowed, or the original context unchanged
 * when the header is absent, malformed, or out-of-scope.</p>
 *
 * <p>Centralised here so the legacy {@code JwtAuthenticationFilter} and
 * the OIDC {@code KeycloakHospitalContextFilter} can both apply the same
 * rule — drift between the two would silently break multi-hospital users
 * once {@code app.auth.oidc.required=true} flips.</p>
 */
public final class HospitalContextRequestOverrides {

    public static final String HEADER_HOSPITAL_ID = "X-Hospital-Id";

    private static final Logger log = LoggerFactory.getLogger(HospitalContextRequestOverrides.class);

    private HospitalContextRequestOverrides() {
        // utility class — no instances
    }

    /**
     * Apply the {@code X-Hospital-Id} header override to {@code context}.
     * <ul>
     *   <li>No header / blank header → context returned unchanged.</li>
     *   <li>Malformed UUID → warning logged, context returned unchanged.</li>
     *   <li>Super admin → context with {@code activeHospitalId} replaced
     *       by the requested UUID (the chip-scoped view).</li>
     *   <li>UUID in the principal's permitted hospital set → context with
     *       {@code activeHospitalId} replaced by the requested UUID.</li>
     *   <li>Anything else → warning logged, context returned unchanged.
     *       That includes a principal whose permitted set is EMPTY: an
     *       empty set means the principal holds no hospital, not that it
     *       may pick any. It is empty for a patient (ROLE_PATIENT is a
     *       global, no-hospital assignment), for a user whose assignments
     *       were revoked after sign-in (the set is read live), and for a
     *       Keycloak token with no hospital claims; honouring the header
     *       for them let each one act at any hospital it named.</li>
     * </ul>
     */
    public static HospitalContext applyRequestOverrides(HospitalContext context,
                                                        HttpServletRequest request) {
        HospitalContext effective = context != null ? context : HospitalContext.empty();
        if (request == null) {
            return effective;
        }

        String headerValue = request.getHeader(HEADER_HOSPITAL_ID);
        if (!StringUtils.hasText(headerValue)) {
            return effective;
        }

        UUID requestedHospital;
        try {
            requestedHospital = UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            // The value itself is not logged: it is caller-controlled, and a
            // CR/LF in it would forge log lines. Its length is enough to tell
            // a truncated id from garbage when supporting a client.
            log.warn("[AUTH] Ignoring malformed {} header (length {})",
                HEADER_HOSPITAL_ID, headerValue.length());
            return effective;
        }

        // No empty-set escape: a principal with no permitted hospital has no
        // hospital to switch to (see the javadoc above).
        boolean permitted = effective.isSuperAdmin()
            || effective.getPermittedHospitalIds().contains(requestedHospital);

        if (!permitted) {
            log.warn("[AUTH] Ignoring {} {} not in permitted scope {}",
                HEADER_HOSPITAL_ID, requestedHospital, effective.getPermittedHospitalIds());
            return effective;
        }

        if (effective.getActiveHospitalId() == null
            || !requestedHospital.equals(effective.getActiveHospitalId())) {
            log.debug("[AUTH] Overriding active hospital via header: {} (previously {})",
                requestedHospital, effective.getActiveHospitalId());
        }

        return effective.toBuilder()
            .activeHospitalId(requestedHospital)
            // Mark the context so RoleValidator can distinguish a
            // header-overridden hospital from a JWT-derived primary.
            // Super-admins specifically need this: their JWT carries a
            // primary hospital, but the design treats them as global by
            // default — only an explicit header scope should pin them.
            .headerOverridden(true)
            .build();
    }
}
