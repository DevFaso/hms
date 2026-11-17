package com.example.hms.security.provider.confinement;

import java.util.Locale;

/**
 * One handler a provider user may reach: the HTTP method and the EXACT
 * mapping pattern Spring MVC matched (the controller's own pattern, class and
 * method mapping combined, e.g. {@code /patients/{patientId}/record-sharing/opt-out}),
 * never a path prefix. The reason is the decision, kept next to the entry.
 *
 * <p>{@code ownIdVariable}, when set, names the path variable that must be the
 * caller's own user id ({@code GET /users/{id}}: their own account only).
 */
public record ConfinementRule(String method, String pattern, String reason, String ownIdVariable) {

    public ConfinementRule {
        method = method.toUpperCase(Locale.ROOT);
    }

    /** A rule with no own-id constraint. */
    public ConfinementRule(String method, String pattern, String reason) {
        this(method, pattern, reason, null);
    }

    /** True when this entry names exactly the handler {@code method handlerPattern}. */
    public boolean matches(String requestMethod, String handlerPattern) {
        return method.equals(requestMethod) && pattern.equals(handlerPattern);
    }
}
