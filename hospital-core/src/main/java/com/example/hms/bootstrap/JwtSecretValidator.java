package com.example.hms.bootstrap;

import com.example.hms.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Fails the start-up when the JWT signing secret is missing, too short, or (outside the developer
 * and test profiles) a value that is published in the source tree.
 *
 * <p>Runs while the bean is created, before the web server accepts a request, so the application
 * never serves a single request signed with a rejected secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtSecretValidator implements InitializingBean {

    static final int MIN_LENGTH = 32;

    /**
     * Secrets committed to this repository (the developer profiles' fallback and the test
     * profile's value). Anyone can read them, so a deployed environment must never sign with one.
     * {@code JwtSecretValidatorTest} fails when a profile file commits a secret missing here.
     */
    static final Set<String> PUBLISHED_SECRETS = Set.of(
        "dev-secret-change-me-in-production-minimum-256-bits-long!!",
        "base64:dGVzdC1qd3Qtc2VjcmV0LXNlbGYtZ2VuZXJhdGVkLXN0cmluZy1mb3ItZGV2");

    /** Placeholder wording no real secret carries. */
    private static final Set<String> PLACEHOLDER_MARKERS = Set.of("change-me", "changeme", "change_me");

    private final JwtProperties jwtProperties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        validate(jwtProperties.getSecret(), environment.getActiveProfiles());
    }

    static void validate(String secret, String[] activeProfiles) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is missing (env JWT_SECRET not set)");
        }
        if (secret.length() < MIN_LENGTH) {
            throw new IllegalStateException("JWT secret too short (min " + MIN_LENGTH + " chars)");
        }
        if (!DeploymentProfiles.onlyDeveloperOrTest(activeProfiles) && isPublishedOrPlaceholder(secret)) {
            throw new IllegalStateException(
                "JWT secret is a value published in the source tree or a placeholder, and profiles "
                    + Arrays.toString(activeProfiles) + " are not developer or test profiles. "
                    + "Set JWT_SECRET to a private random value of at least " + MIN_LENGTH + " characters.");
        }
        log.info("JWT secret validated (length={})", secret.length());
    }

    private static boolean isPublishedOrPlaceholder(String secret) {
        String trimmed = secret.trim();
        if (PUBLISHED_SECRETS.contains(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }
}
