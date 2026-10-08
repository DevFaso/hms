package com.example.hms.security.provider.confinement;

import java.util.Locale;

/**
 * One handler a provider user may reach: the HTTP method and the EXACT
 * mapping pattern Spring MVC matched (the controller's own pattern, class and
 * method mapping combined, e.g. {@code /patients/{patientId}/record-sharing/opt-out}),
 * never a path prefix. The reason is the decision, kept next to the entry.
 */
public record ConfinementRule(String method, String pattern, String reason) {

    public ConfinementRule {
        method = method.toUpperCase(Locale.ROOT);
    }

    /** True when this entry names exactly the handler {@code method handlerPattern}. */
    public boolean matches(String requestMethod, String handlerPattern) {
        return method.equals(requestMethod) && pattern.equals(handlerPattern);
    }
}
