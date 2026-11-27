package com.example.hms.security.context;

import com.example.hms.security.tenant.ActingScope;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies the {@code X-Hospital-Id} header on top of an authenticated
 * principal's {@link HospitalContext}.
 *
 * <p>The portal sends the header to name the hospital a request acts at. It
 * is an explicit claim, so a claim the caller may not make is <b>refused</b>,
 * not ignored (design Q3, option A): the returned context carries
 * {@link ActingScope.Reason#NOT_PERMITTED} and the two filters answer 403
 * before any controller runs. {@code ActingScopeResolver} then tells a
 * hospital the caller held once ({@code NO_LONGER_PERMITTED}, a stale chip)
 * from one they never held (a probe) for the audit row and the portal.
 *
 * <p>Centralised here so the legacy {@code JwtAuthenticationFilter} and
 * the OIDC {@code KeycloakHospitalContextFilter} apply the same rule.</p>
 */
public final class HospitalContextRequestOverrides {

    public static final String HEADER_HOSPITAL_ID = "X-Hospital-Id";

    /** The patient portal: the caller acts on their own record, as a patient. */
    public static final String PATIENT_PORTAL_PATH = "/me/patient";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final Logger log = LoggerFactory.getLogger(HospitalContextRequestOverrides.class);

    private HospitalContextRequestOverrides() {
        // utility class — no instances
    }

    /**
     * Apply the {@code X-Hospital-Id} header to {@code context}.
     * <ul>
     *   <li>No header / blank header → context returned unchanged.</li>
     *   <li>Verified super admin → acting at the named hospital (the
     *       chip-scoped view).</li>
     *   <li>A hospital in the principal's live permitted set → acting at it.
     *       This also settles a caller holding several hospitals, who
     *       otherwise has none ({@code AMBIGUOUS}). On a write outside the
     *       patient portal, a hospital where a staff caller is only a patient
     *       is ignored: the request runs on the live scope
     *       ({@link #refusesStaffWriteAt}).</li>
     *   <li>Anything else, including a malformed value and a principal whose
     *       permitted set is EMPTY (a patient, a Keycloak principal with no
     *       local account, a user whose assignments were revoked) → refused
     *       with {@code NOT_PERMITTED}: no hospital is acted at, and the
     *       filters answer 403.</li>
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
            log.warn("[AUTH] Refusing malformed {} header (length {})",
                HEADER_HOSPITAL_ID, headerValue.length());
            return refused(effective, null);
        }

        // No empty-set escape: a principal with no permitted hospital has no
        // hospital to name (see the javadoc above).
        boolean permitted = effective.isSuperAdmin()
            || effective.getPermittedHospitalIds().contains(requestedHospital);

        if (!permitted) {
            log.warn("[AUTH] Refusing {} {}: not in the caller's permitted scope",
                HEADER_HOSPITAL_ID, requestedHospital);
            return refused(effective, requestedHospital);
        }

        if (refusesStaffWriteAt(effective, requestedHospital, request)) {
            // A write by a staff caller naming a hospital where they are only a
            // patient (a stale chip, a patient hospital made primary): the
            // header is ignored, not refused. The request runs on the live
            // scope, so a write about their own account still passes and a
            // hospital-scoped write finds no acting hospital there.
            log.info("[AUTH] Ignoring {} {} on a staff write: the caller is only a patient there",
                HEADER_HOSPITAL_ID, requestedHospital);
            return effective;
        }

        // Explicit: a super-admin is pinned by it, and the provisional
        // refusal of a multi-hospital caller is settled by it.
        return effective.actingAt(requestedHospital);
    }

    /**
     * True when {@code request} is a staff write at a hospital where the caller
     * is only a patient ({@link HospitalContext#isTreatedOnlyAt}): a write
     * outside the patient portal acts at a hospital where the caller works.
     * The patient portal's own paths, every read and a request outside HTTP
     * keep the caller's whole permitted set. A caller who works at a provider
     * facility (a pharmacy, a laboratory) is left to the provider confinement,
     * whose answer for a hospital endpoint must stay that of an unmapped path.
     */
    public static boolean refusesStaffWriteAt(HospitalContext context, UUID hospitalId, HttpServletRequest request) {
        return context != null && context.isTreatedOnlyAt(hospitalId)
            && (context.getProviderFacilityTypes() == null || context.getProviderFacilityTypes().isEmpty())
            && isStaffWrite(request);
    }

    /** {@link #refusesStaffWriteAt} for the request being served. */
    public static boolean refusesStaffWriteAt(HospitalContext context, UUID hospitalId) {
        return refusesStaffWriteAt(context, hospitalId, currentRequest());
    }

    /** A write (any method but GET, HEAD, OPTIONS, TRACE) outside the patient portal. */
    public static boolean isStaffWrite(HttpServletRequest request) {
        if (request == null || request.getMethod() == null
            || READ_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return false;
        }
        return !isPatientPortalPath(request);
    }

    /** True for {@link #PATIENT_PORTAL_PATH} and below, relative to the servlet context. */
    public static boolean isPatientPortalPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.equals(PATIENT_PORTAL_PATH) || path.startsWith(PATIENT_PORTAL_PATH + "/");
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
            ? attributes.getRequest() : null;
    }

    private static HospitalContext refused(HospitalContext context, UUID requestedHospital) {
        return context.toBuilder()
            .activeHospitalId(null)
            .headerOverridden(false)
            .scopeRefusal(ActingScope.Reason.NOT_PERMITTED)
            .refusedHospitalId(requestedHospital)
            .build();
    }
}
