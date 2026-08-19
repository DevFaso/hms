package com.example.hms.controller.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Configuration bundle for {@code AuthController}.
 *
 * <p>Aggregates the four {@code @Value}-injected scalars that the
 * controller used to declare as separate constructor parameters:
 * frontend base URL, MFA-required role list, OIDC-required flag, and
 * OIDC issuer URI. Folding them into a single dependency cuts the
 * controller's constructor by 3 parameters (21 &rarr; 18) and gives
 * future configuration additions a natural home.
 *
 * <p>Sonar S107 ("constructor has too many parameters") was firing on
 * {@code AuthController}'s 21-arg constructor. This bean is part of
 * Pattern 7 in docs/SonarQubeInstructions.md. The remaining 18-arg
 * shape reflects the controller's inherent breadth as the auth
 * orchestrator; full helper-class extraction is tracked separately as
 * a feature-scope refactor and the residual S107 finding is documented
 * as "won't fix" until that lands.
 *
 * <p>Implemented as a Spring {@code @Component} (rather than a
 * {@code @ConfigurationProperties} bean) because the underlying
 * property keys live across three different prefixes
 * ({@code app.frontend.base-url}, {@code app.mfa.required-roles},
 * {@code app.auth.oidc.*}) and we deliberately do not want to move
 * them into a single namespace just to satisfy the wrapper. Defaults
 * mirror the original {@code @Value} annotations exactly so behaviour
 * is byte-for-byte identical.
 */
@Component
public class AuthControllerProperties {

    private final String frontendBaseUrl;
    private final List<String> mfaRequiredRoles;
    private final boolean oidcRequired;
    private final String oidcIssuerUri;

    public AuthControllerProperties(
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.mfa.required-roles:}") List<String> mfaRequiredRoles,
            @Value("${app.auth.oidc.required:false}") boolean oidcRequired,
            @Value("${app.auth.oidc.issuer-uri:}") String oidcIssuerUri) {
        this.frontendBaseUrl = frontendBaseUrl;
        this.mfaRequiredRoles = mfaRequiredRoles == null
                ? Collections.emptyList()
                : List.copyOf(mfaRequiredRoles);
        this.oidcRequired = oidcRequired;
        // Preserve the controller's original trim-to-empty behaviour
        // so the legacyIssuerGone helper's `.isEmpty()` check still
        // works against null / blank inputs.
        this.oidcIssuerUri = oidcIssuerUri == null ? "" : oidcIssuerUri.trim();
    }

    public String frontendBaseUrl() {
        return frontendBaseUrl;
    }

    /** Unmodifiable; safe to expose directly. */
    public List<String> mfaRequiredRoles() {
        return mfaRequiredRoles;
    }

    public boolean oidcRequired() {
        return oidcRequired;
    }

    /** Never null; empty string means "no OIDC issuer configured". */
    public String oidcIssuerUri() {
        return oidcIssuerUri;
    }
}
