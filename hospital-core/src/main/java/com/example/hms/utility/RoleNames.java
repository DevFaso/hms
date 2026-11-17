package com.example.hms.utility;

/**
 * The one definition of how a role NAME reaches a client.
 *
 * <p>{@code audit_event_logs.role_name} holds two spellings of one role:
 * {@code WriteAuditInterceptor} strips the {@code ROLE_} prefix and
 * {@code AuditEventLogServiceImpl} stores {@code security.roles.name} as it is
 * ({@code ROLE_DOCTOR}), and when it cannot resolve a role at all it stamps the
 * English sentence {@link #UNKNOWN_ROLE}. Rows written years ago keep whatever
 * they were given, so no write-side fix can reach them; the read paths
 * normalise instead, through {@link #bareRole}, and every client then receives
 * a bare token ({@code DOCTOR}) it can look up in its role vocabulary, or null
 * where there is nothing to show.
 *
 * <p>The portal's {@code core/role-token.ts} applies the same rule; the two
 * must stay in step.
 */
public final class RoleNames {

    /**
     * What {@code AuditEventLogServiceImpl} stores when it cannot resolve a
     * role. A sentence, not a token: never send it to a client.
     */
    public static final String UNKNOWN_ROLE = "Unknown Role";

    private static final String ROLE_PREFIX = "ROLE_";

    private RoleNames() {
    }

    /**
     * A role name in the bare form clients key on: {@code ROLE_DOCTOR} and
     * {@code DOCTOR} both become {@code DOCTOR}. Null for a missing or blank
     * value and for the {@link #UNKNOWN_ROLE} sentence, so a caller hides the
     * role instead of rendering an English placeholder.
     */
    public static String bareRole(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || UNKNOWN_ROLE.equals(value)) {
            return null;
        }
        String bare = value.startsWith(ROLE_PREFIX) ? value.substring(ROLE_PREFIX.length()) : value;
        return bare.isEmpty() ? null : bare;
    }
}
