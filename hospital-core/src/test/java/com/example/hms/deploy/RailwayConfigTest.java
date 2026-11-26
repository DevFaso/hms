package com.example.hms.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D16: the Keycloak services must always be restarted by Railway. With
 * {@code on_failure} capped at three retries a Keycloak that crashed a fourth
 * time stayed down and nobody could sign in.
 */
class RailwayConfigTest {

    @Test
    void keycloakIsAlwaysRestarted() throws IOException {
        String toml = Files.readString(repoFile("keycloak/prod/railway.toml"));

        assertThat(toml).containsPattern("(?m)^\\s*restartPolicyType\\s*=\\s*\"always\"\\s*$")
            .doesNotContainPattern("(?m)^\\s*restartPolicyMaxRetries\\s*=");
    }

    /**
     * Railway stops reading {@code railway.toml} on 2026-12-01; from then on the
     * restart policy that counts is the one in {@code .railway/railway.ts}.
     */
    @Test
    void keycloakIsAlwaysRestartedInTheInfrastructureFile() throws IOException {
        String iac = Files.readString(repoFile(".railway/railway.ts"));

        assertThat(iac).containsPattern(
            "(?m)^const RESTART_ALWAYS = \\{ restartPolicyType: \"ALWAYS\" \\} as const;$");

        Matcher keycloakService = Pattern
            .compile("(?ms)^  const keycloak = service\\(\"(hms-keycloak-[a-z]+)\", \\{$(.*?)^  \\}\\);$")
            .matcher(iac);
        List<String> environments = new ArrayList<>();
        while (keycloakService.find()) {
            environments.add(keycloakService.group(1));
            assertThat(keycloakService.group(2))
                .as("restart policy of %s", keycloakService.group(1))
                .contains("...RESTART_ALWAYS")
                .doesNotContain("RESTART_ON_FAILURE")
                .doesNotContain("restartPolicyMaxRetries");
        }
        assertThat(environments).containsExactlyInAnyOrder("hms-keycloak-dev", "hms-keycloak-prod");
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(relative + " not found above the working directory");
    }
}
