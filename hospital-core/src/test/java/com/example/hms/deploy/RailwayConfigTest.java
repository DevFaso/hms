package com.example.hms.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
