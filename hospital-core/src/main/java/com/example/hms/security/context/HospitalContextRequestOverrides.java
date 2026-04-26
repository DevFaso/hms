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
     *   <li>UUID outside the principal's permitted scope (and not super
     *       admin) → warning logged, context returned unchanged.</li>
     *   <li>Otherwise → context with {@code activeHospitalId} replaced
     *       by the requested UUID.</li>
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
            log.warn("[AUTH] Invalid {} header value: {}", HEADER_HOSPITAL_ID, headerValue);
            return effective;
        }

        boolean permitted = effective.isSuperAdmin()
            || effective.getPermittedHospitalIds().isEmpty()
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
            .build();
    }
}
