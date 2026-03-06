package com.example.hms.config;

import lombok.Getter;
import lombok.Setter;
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
     * {@code ${app.frontend.base-url}/super/assignments?confirm=%s}.
     */
    private String assignerConfirmationUrlTemplate;
}
