package com.example.hms.bootstrap;

import com.example.hms.config.JwtProperties;
import com.example.hms.security.JwtSecretBytes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fails the start-up when the JWT signing secret is missing, too short, or (outside the developer
 * and test profiles) a value that is published in the source tree.
 *
 * <p>Every rule judges the key the token provider will sign with: the value is decoded first
 * ({@code base64:}, {@code hex:} or plain text, {@link JwtSecretBytes}), so a published secret
 * written in another encoding is still recognised.
 *
 * <p>Runs while the bean is created, before the web server accepts a request, so the application
 * never serves a single request signed with a rejected secret.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtSecretValidator implements InitializingBean {

    /** 256 bits: the HMAC-SHA256 key size the token provider requires. */
    static final int MIN_BYTES = 32;

    /**
     * Secrets committed to this repository (the developer profiles' fallback and the test
     * profile's value). Anyone can read them, so a deployed environment must never sign with one,
     * in any encoding. {@code JwtSecretValidatorTest} fails when a profile file commits a secret
     * missing here.
     */
    static final Set<String> PUBLISHED_SECRETS = Set.of(
        "dev-secret-change-me-in-production-minimum-256-bits-long!!",
        "base64:dGVzdC1qd3Qtc2VjcmV0LXNlbGYtZ2VuZXJhdGVkLXN0cmluZy1mb3ItZGV2");

    /** Placeholder wording no real secret carries. */
    private static final Set<String> PLACEHOLDER_MARKERS = Set.of("change-me", "changeme", "change_me");

    private static final List<byte[]> PUBLISHED_KEYS =
        PUBLISHED_SECRETS.stream().map(JwtSecretBytes::decode).toList();

    private final JwtProperties jwtProperties;
    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        validate(jwtProperties.getSecret(), environment.getActiveProfiles());
    }

    static void validate(String secret, String[] activeProfiles) {
        if (secret == null || secret.isBlank()) {
            throw new StartupConfigurationException("JWT secret is missing (env JWT_SECRET not set)");
        }
        byte[] key = JwtSecretBytes.decode(secret);
        if (key.length < MIN_BYTES) {
            throw new StartupConfigurationException(
                "JWT secret too short (min " + MIN_BYTES + " bytes once decoded)");
        }
        if (!DeploymentProfiles.onlyDeveloperOrTest(activeProfiles) && isPublishedOrPlaceholder(secret, key)) {
            throw new StartupConfigurationException(
                "JWT secret is a value published in the source tree or a placeholder, and profiles "
                    + Arrays.toString(activeProfiles) + " are not developer or test profiles. "
                    + "Set JWT_SECRET to a private random value of at least " + MIN_BYTES + " bytes.");
        }
        log.info("JWT secret validated (key length={} bytes)", key.length);
    }

    private static boolean isPublishedOrPlaceholder(String secret, byte[] key) {
        if (isPublished(key)) {
            return true;
        }
        String trimmed = secret.trim();
        if (!trimmed.equals(secret) && isPublished(JwtSecretBytes.decode(trimmed))) {
            return true;
        }
        return hasPlaceholderWording(secret) || hasPlaceholderWording(new String(key, StandardCharsets.UTF_8));
    }

    private static boolean isPublished(byte[] key) {
        return PUBLISHED_KEYS.stream().anyMatch(published -> MessageDigest.isEqual(published, key));
    }

    private static boolean hasPlaceholderWording(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }
}
