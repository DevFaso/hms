package com.example.hms.bootstrap;

import com.example.hms.config.JwtProperties;
import com.example.hms.security.JwtSecretBytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidatorTest {

    private static final String[] PROD = {"prod"};
    private static final String[] DEV = {"dev"};
    private static final String[] LOCAL_H2 = {"local-h2"};
    private static final String[] TEST = {"test"};
    private static final String PRIVATE_SECRET = "Zq8vN2rT6yLp0WcX4bHk9JmS3dFg7AeU";
    private static final String DEV_FALLBACK = "dev-secret-change-me-in-production-minimum-256-bits-long!!";

    /** A {@code ${JWT_SECRET:fallback}} placeholder in a committed config file. */
    private static final Pattern FALLBACK = Pattern.compile("\\$\\{JWT_SECRET:([^}]+)}");

    // ── length rules, every profile ──────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "  \t\n  "})
    void aMissingSecretIsRefused(String secret) {
        assertThatThrownBy(() -> JwtSecretValidator.validate(secret, PROD))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret is missing");
        assertThatThrownBy(() -> JwtSecretValidator.validate(secret, LOCAL_H2))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 16, 31})
    void aShortSecretIsRefusedUnderEveryProfile(int length) {
        String secret = "a".repeat(length);
        for (String[] profiles : List.of(PROD, DEV, LOCAL_H2, TEST)) {
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret, profiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret too short (min 32 bytes once decoded)");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 33, 64, 256})
    void aPrivateSecretOfThirtyTwoOrMoreIsAccepted(int length) {
        String secret = PRIVATE_SECRET.repeat(9).substring(0, length);
        assertThatCode(() -> JwtSecretValidator.validate(secret, PROD)).doesNotThrowAnyException();
        assertThatCode(() -> JwtSecretValidator.validate(secret, DEV)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the length is the decoded key's: a short base64 or hex value is refused, a long one accepted")
    void theLengthIsTheDecodedKeys() {
        // 39 characters of text, but a 24-byte key
        String twentyFourBytes = "base64:" + Base64.getEncoder().encodeToString(new byte[24]);
        assertThatThrownBy(() -> JwtSecretValidator.validate(twentyFourBytes, PROD))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("too short");
        assertThatThrownBy(() -> JwtSecretValidator.validate("hex:" + "ab".repeat(20), PROD))
            .isInstanceOf(StartupConfigurationException.class)
            .hasMessageContaining("too short");
        assertThatCode(() -> JwtSecretValidator.validate("hex:" + HexFormat.of().formatHex(
            PRIVATE_SECRET.getBytes(StandardCharsets.UTF_8)), PROD)).doesNotThrowAnyException();
        assertThatThrownBy(() -> JwtSecretValidator.validate("hex:" + "zz".repeat(32), PROD))
            .isInstanceOf(StartupConfigurationException.class);
        assertThatThrownBy(() -> JwtSecretValidator.validate("base64:%%%%" + "A".repeat(40), PROD))
            .isInstanceOf(StartupConfigurationException.class);
    }

    @Test
    @DisplayName("a published secret is refused in every encoding: plain, base64 and hex")
    void aPublishedSecretIsRefusedInEveryEncoding() {
        for (String published : JwtSecretValidator.PUBLISHED_SECRETS) {
            byte[] key = JwtSecretBytes.decode(published);
            List<String> variants = List.of(
                new String(key, StandardCharsets.UTF_8),
                "base64:" + Base64.getEncoder().encodeToString(key),
                "hex:" + HexFormat.of().formatHex(key),
                "hex:" + HexFormat.of().withUpperCase().formatHex(key));
            for (String variant : variants) {
                assertThatThrownBy(() -> JwtSecretValidator.validate(variant, PROD))
                    .as(variant)
                    .isInstanceOf(StartupConfigurationException.class)
                    .hasMessageContaining("published in the source tree");
                assertThatCode(() -> JwtSecretValidator.validate(variant, TEST)).as(variant)
                    .doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("placeholder wording is found after decoding too")
    void placeholderWordingIsFoundAfterDecoding() {
        byte[] placeholder = "please-change-me-before-going-live-01".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> JwtSecretValidator.validate(
            "base64:" + Base64.getEncoder().encodeToString(placeholder), PROD))
            .isInstanceOf(StartupConfigurationException.class);
        assertThatThrownBy(() -> JwtSecretValidator.validate("hex:" + HexFormat.of().formatHex(placeholder), DEV))
            .isInstanceOf(StartupConfigurationException.class);
    }

    // ── published secrets ────────────────────────────────────────

    @Test
    @DisplayName("a deployed profile refuses every committed secret and placeholder wording")
    void aDeployedProfileRefusesAPublishedSecret() {
        for (String secret : JwtSecretValidator.PUBLISHED_SECRETS) {
            for (String[] profiles : List.of(PROD, DEV, new String[] {"uat"}, new String[] {"staging"})) {
                assertThatThrownBy(() -> JwtSecretValidator.validate(secret, profiles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("published in the source tree");
            }
            assertThatThrownBy(() -> JwtSecretValidator.validate("  " + secret + " ", PROD))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> JwtSecretValidator.validate("please-CHANGE-ME-before-going-live-0001", PROD))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtSecretValidator.validate("changeme-changeme-changeme-changeme", DEV))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a developer or test profile may use the committed fallback; a mix with a deployed one may not")
    void developerAndTestProfilesMayUseTheCommittedSecret() {
        assertThatCode(() -> JwtSecretValidator.validate(DEV_FALLBACK, LOCAL_H2)).doesNotThrowAnyException();
        assertThatCode(() -> JwtSecretValidator.validate(DEV_FALLBACK, new String[] {"local"}))
            .doesNotThrowAnyException();
        assertThatCode(() -> JwtSecretValidator.validate(DEV_FALLBACK, TEST)).doesNotThrowAnyException();

        assertThatThrownBy(() -> JwtSecretValidator.validate(DEV_FALLBACK, new String[] {"local-h2", "prod"}))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtSecretValidator.validate(DEV_FALLBACK, new String[] {"test", "dev"}))
            .isInstanceOf(IllegalStateException.class);
        // no profile at all counts as deployed
        assertThatThrownBy(() -> JwtSecretValidator.validate(DEV_FALLBACK, new String[0]))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the bean validates when it is created, with the environment's profiles")
    void theBeanValidatesOnCreation() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(DEV_FALLBACK);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        JwtSecretValidator validator = new JwtSecretValidator(properties, environment);
        assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        environment.setActiveProfiles("local-h2");
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();

        properties.setSecret(PRIVATE_SECRET);
        environment.setActiveProfiles("prod");
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    // ── committed configuration ──────────────────────────────────

    @Test
    @DisplayName("every JWT secret committed in a config file is on the published list")
    void everyCommittedSecretIsKnown() throws IOException {
        List<String> committed = committedSecrets();
        assertThat(committed).as("the scan found no committed secret: it guards nothing").isNotEmpty();
        assertThat(JwtSecretValidator.PUBLISHED_SECRETS).containsAll(committed);
    }

    @Test
    @DisplayName("the shared configuration has no JWT fallback and no default profile; dev and prod bring their own")
    void onlyDeveloperProfilesCarryAFallback() throws IOException {
        Properties shared = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            shared.load(in);
        }
        assertThat(shared.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET:}");
        assertThat(shared.getProperty("spring.profiles.active"))
            .as("there is no default profile: an environment that names none must not start")
            .isNull();
        assertThat(Files.readString(Path.of("src/main/resources/application-prod.yml")))
            .doesNotContain("JWT_SECRET:");
        assertThat(Files.readString(Path.of("src/main/resources/application-dev.yml")))
            .doesNotContain("JWT_SECRET");
    }

    /** Every literal JWT secret or {@code JWT_SECRET} fallback in the main and test config files. */
    private static List<String> committedSecrets() throws IOException {
        List<String> found = new ArrayList<>();
        var loader = new YamlPropertySourceLoader();
        for (Path dir : List.of(Path.of("src/main/resources"), Path.of("src/test/resources"))) {
            List<Path> files;
            try (Stream<Path> listing = Files.list(dir)) {
                files = listing.filter(p -> p.getFileName().toString().startsWith("application")).toList();
            }
            for (Path file : files) {
                Matcher fallback = FALLBACK.matcher(Files.readString(file));
                while (fallback.find()) {
                    found.add(fallback.group(1));
                }
                if (file.toString().endsWith(".yml")) {
                    for (PropertySource<?> source : loader.load(file.toString(), new FileSystemResource(file))) {
                        Object value = source.getProperty("app.jwt.secret");
                        if (value != null && !value.toString().startsWith("${")) {
                            found.add(value.toString());
                        }
                    }
                }
            }
        }
        return found;
    }
}
