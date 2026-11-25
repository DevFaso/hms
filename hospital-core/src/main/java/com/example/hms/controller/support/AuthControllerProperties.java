package com.example.hms.controller.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Date;
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
    /** {@code app.auth.refresh-not-before}; {@code null} when unset. */
    private final Instant refreshNotBefore;
    /**
     * {@code app.auth.session-max-lifetime} (staff) and
     * {@code app.auth.patient-session-max-lifetime} (a patient-only session):
     * how long after its sign-in a session can be refreshed.
     */
    private final com.example.hms.security.SessionLifetime sessionLifetime;

    public AuthControllerProperties(
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.mfa.required-roles:}") List<String> mfaRequiredRoles,
            @Value("${app.auth.oidc.required:false}") boolean oidcRequired,
            @Value("${app.auth.oidc.issuer-uri:}") String oidcIssuerUri,
            @Value("${app.auth.refresh-not-before:}") String refreshNotBefore,
            @Value("${app.auth.session-max-lifetime:PT12H}") String sessionMaxLifetime,
            @Value("${app.auth.patient-session-max-lifetime:P30D}") String patientSessionMaxLifetime) {
        this.frontendBaseUrl = frontendBaseUrl;
        this.mfaRequiredRoles = mfaRequiredRoles == null
                ? Collections.emptyList()
                : List.copyOf(mfaRequiredRoles);
        this.oidcRequired = oidcRequired;
        // Preserve the controller's original trim-to-empty behaviour
        // so the legacyIssuerGone helper's `.isEmpty()` check still
        // works against null / blank inputs.
        this.oidcIssuerUri = oidcIssuerUri == null ? "" : oidcIssuerUri.trim();
        this.refreshNotBefore = parseInstant(refreshNotBefore);
        this.sessionLifetime = new com.example.hms.security.SessionLifetime(
            parseLifetime(sessionMaxLifetime, "app.auth.session-max-lifetime",
                com.example.hms.security.SessionLifetime.DEFAULT_MAX_LIFETIME, "PT12H"),
            parseLifetime(patientSessionMaxLifetime, "app.auth.patient-session-max-lifetime",
                com.example.hms.security.SessionLifetime.DEFAULT_PATIENT_MAX_LIFETIME, "P30D"));
    }

    /**
     * An ISO-8601 duration ({@code PT12H}, {@code P30D}); blank means the default. A
     * value that does not parse, or is not positive, stops the start-up: a
     * lifetime silently dropped would let sessions run on unbounded.
     */
    private static java.time.Duration parseLifetime(String value, String property,
                                                    java.time.Duration fallback, String example) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            java.time.Duration parsed = java.time.Duration.parse(value.trim());
            if (parsed.isNegative() || parsed.isZero()) {
                throw new org.springframework.beans.factory.BeanInitializationException(
                    property + " must be positive, such as " + example);
            }
            return parsed;
        } catch (DateTimeParseException ex) {
            throw new org.springframework.beans.factory.BeanInitializationException(
                property + " must be an ISO-8601 duration such as " + example, ex);
        }
    }

    /**
     * An ISO-8601 instant ({@code 2026-11-24T18:00:00Z}), or {@code null} when
     * blank. A value that does not parse stops the start-up: a cutoff that is
     * silently ignored would leave every older session running.
     */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new org.springframework.beans.factory.BeanInitializationException(
                "app.auth.refresh-not-before must be an ISO-8601 instant such as 2026-11-24T18:00:00Z", ex);
        }
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

    /** {@code app.auth.refresh-not-before} ({@code AUTH_REFRESH_NOT_BEFORE}); {@code null} when unset. */
    public Instant refreshNotBefore() {
        return refreshNotBefore;
    }

    /**
     * The absolute session lifetimes: {@code AUTH_SESSION_MAX_LIFETIME} (12 h by
     * default) and, for a patient-only session, {@code AUTH_PATIENT_SESSION_MAX_LIFETIME}
     * (30 days by default).
     */
    public com.example.hms.security.SessionLifetime sessionLifetime() {
        return sessionLifetime;
    }

    /**
     * A refresh token issued at {@code issuedAt} predates the configured
     * cutoff, so it mints nothing and its session signs in again. A JWT
     * {@code iat} has whole-second precision: compared to the cutoff
     * truncated to the second, strictly, as {@code PasswordChangeCutoff} does.
     */
    public boolean refreshIssuedBeforeCutoff(Date issuedAt) {
        if (refreshNotBefore == null || issuedAt == null) {
            return false;
        }
        return issuedAt.toInstant().isBefore(refreshNotBefore.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
}
