package com.example.hms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Portal-facing URLs used in notifications so that assignees and assigners land on the
 * appropriate onboarding flows inside the Angular application. These values can be overridden
 * via environment variables in different deployment environments.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.portal")
public class PortalProperties {

    /**
     * Template used to generate the self-service profile completion link shared with the assignee.
     * The assignment code is substituted into the first <code>%s</code> or <code>{code}</code> placeholder.
     */
    private String profileCompletionUrlTemplate = "http://localhost:4200/onboarding/role-welcome?assignment=%s";

    /**
     * Template used to generate the confirmation link for assigners when we need to deep-link them
     * back into the admin console. The assignment code is substituted similar to the profile template.
     */
    private String assignerConfirmationUrlTemplate = "http://localhost:4200/super/assignments?confirm=%s";
}
