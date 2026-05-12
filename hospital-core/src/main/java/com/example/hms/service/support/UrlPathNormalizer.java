package com.example.hms.service.support;

/**
 * Shared normalizer for configurable URL path fragments / prefixes (Sonar
 * S1075 cleanup, PR #315).
 *
 * <p>Both {@code AppointmentServiceImpl} and {@code PatientPortalServiceImpl}
 * concatenate {@code frontendBaseUrl + path + appointmentId} when building
 * confirmation emails, and {@code FileUploadService} concatenates
 * {@code prefix + "/" + subdir + "/" + filename} when building browser-facing
 * patient-document URLs. An ops typo (missing leading slash, extra trailing
 * slash, doubled slashes) silently produces a broken URL in every email or
 * an inconsistent storage key.
 *
 * <p>The two helpers below pin the shape of the configured value so callers
 * can safely concatenate downstream:
 *
 * <ul>
 *   <li>{@link #fragment(String, String)} — exactly one leading {@code /}
 *       and one trailing {@code /}. Use for middle-of-URL fragments like
 *       {@code "/appointments/reschedule/"}.</li>
 *   <li>{@link #prefix(String, String)} — exactly one leading {@code /} and
 *       no trailing {@code /}. Use for prefix segments concatenated with an
 *       explicit separator downstream like {@code "/uploads"}.</li>
 * </ul>
 *
 * <p>Both methods are idempotent and null-safe; a null/blank input returns
 * the supplied fallback (which is assumed to already be in the correct
 * shape — the call sites pass their own default literals).
 */
public final class UrlPathNormalizer {

    private UrlPathNormalizer() {
        // utility class
    }

    /**
     * Returns {@code raw} with exactly one leading {@code /} and one trailing
     * {@code /}. Collapses any number of leading slashes to one, ensures a
     * trailing slash, and falls back to {@code fallback} when {@code raw} is
     * {@code null} or blank.
     */
    public static String fragment(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String p = raw.trim();
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    /**
     * Returns {@code raw} with exactly one leading {@code /} and no trailing
     * {@code /}. Collapses any number of leading slashes to one, strips ALL
     * trailing slashes (callers supply their own separator downstream), and
     * falls back to {@code fallback} when {@code raw} is {@code null} or
     * blank.
     */
    public static String prefix(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String p = raw.trim();
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }
}
