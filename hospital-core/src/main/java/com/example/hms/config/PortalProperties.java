package com.example.hms.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Portal-facing URLs used in notifications so that assignees and assigners land on the
 * appropriate onboarding flows inside the Angular application.
 *
 * <p>URL templates are set via {@code application.properties} using
 * {@code app.frontend.base-url} as their base. They can be overridden individually
 * via environment variables if needed:
 * <ul>
 *   <li>{@code APP_PORTAL_PROFILE_COMPLETION_URL_TEMPLATE}</li>
 *   <li>{@code APP_PORTAL_ASSIGNER_CONFIRMATION_URL_TEMPLATE}</li>
 * </ul>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "app.portal")
public class PortalProperties {

    /**
     * Template used to generate the self-service profile completion link shared with the assignee.
     * The assignment code is substituted into the first <code>%s</code> or <code>{code}</code> placeholder.
     * Resolved from {@code application.properties} — defaults to
     * {@code ${app.frontend.base-url}/onboarding/role-welcome?assignment=%s}.
     */
    private String profileCompletionUrlTemplate;

    /**
     * Template used to generate the confirmation link for assigners when we need to deep-link them
     * back into the admin console. The assignment code is substituted similar to the profile template.
     * Resolved from {@code application.properties} — defaults to
     * {@code ${app.frontend.base-url}/admin-assignments?confirm=%s}.
     *
     * <p>It pointed at {@code /super/assignments} until 2026-08-25 — a path
     * that has never existed in the Angular app, so every assigner-confirmation
     * email ever sent linked to the wildcard route. Nobody hit the 404 because
     * the base URL resolved to a non-production host at the time. If this
     * template is ever changed again, check the value against
     * {@code app.routes.ts}: nothing else does.
     */
    private String assignerConfirmationUrlTemplate;

    /**
     * Logs the resolved portal URL templates at startup so operators can verify the
     * Railway-injected {@code FRONTEND_BASE_URL} resolved to the expected hostname
     * ({@code https://dev.e-keneya.com} on dev, {@code https://e-keneya.com} on prod —
     * not a stale pre-move {@code *.bitnesttechs.com} host).
     * Verification emails go out with whatever this base URL resolves to — a wrong
     * value silently breaks every onboarding link.
     */
    @PostConstruct
    void logResolvedTemplates() {
        log.info("[PORTAL] profile-completion URL template = {}", profileCompletionUrlTemplate);
        log.info("[PORTAL] assigner-confirmation URL template = {}", assignerConfirmationUrlTemplate);
    }
}
