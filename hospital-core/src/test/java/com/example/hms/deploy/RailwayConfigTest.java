package com.example.hms.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build and deploy settings of the Railway services live in
 * {@code .railway/railway.ts} and nowhere else
 * (docs/runbooks/railway-config-as-code.md).
 */
class RailwayConfigTest {

    private static final String INFRASTRUCTURE_FILE = ".railway/railway.ts";

    /** Railway's Config-as-Code file names. */
    private static final Set<String> CONFIG_AS_CODE_FILES = Set.of("railway.toml", "railway.json");

    /** Build output, dependencies and tool caches: never part of what Railway checks out. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        ".git", ".gradle", ".angular", ".idea", ".vscode", "node_modules", "build", "dist", "out", "target");

    /**
     * D16: the Keycloak services must always be restarted by Railway. With
     * {@code on_failure} capped at three retries a Keycloak that crashed a fourth
     * time stayed down and nobody could sign in.
     */
    @Test
    void keycloakIsAlwaysRestarted() throws IOException {
        String iac = Files.readString(repoRoot().resolve(INFRASTRUCTURE_FILE));

        assertThat(iac).containsPattern(
            "(?m)^const RESTART_ALWAYS = \\{ restartPolicyType: \"ALWAYS\" \\} as const;\\r?$");

        Matcher keycloakService = Pattern
            .compile("(?ms)^  const keycloak = service\\(\"(hms-keycloak-[a-z]+)\", \\{\\r?$(.*?)^  \\}\\);\\r?$")
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

    /**
     * A service whose stored config path is empty reads a {@code railway.toml} or
     * {@code railway.json} found at the repo root, whatever service it was written
     * for: on 2026-11-26 the dev Keycloak built the backend image that way. A file
     * elsewhere is one dashboard setting away from the same thing, and Railway stops
     * reading all of them on 2026-12-01, so none may come back anywhere.
     */
    @Test
    void noConfigAsCodeFileIsLeftInTheRepo() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                // A nested checkout (a git worktree of another branch) is not this repo.
                boolean skipped = SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                    || Files.exists(dir.resolve(".git"));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (CONFIG_AS_CODE_FILES.contains(file.getFileName().toString())) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(found)
            .as("Config-as-Code files; the settings belong in %s", INFRASTRUCTURE_FILE)
            .isEmpty();
    }

    /** A watch pattern for a deleted file would never match and hides that the file is gone. */
    @Test
    void theInfrastructureFileDoesNotWatchAConfigAsCodeFile() throws IOException {
        String iac = Files.readString(repoRoot().resolve(INFRASTRUCTURE_FILE));

        assertThat(iac).doesNotContainPattern("\"[^\"\\r\\n]*railway\\.(toml|json)\"");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(INFRASTRUCTURE_FILE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(INFRASTRUCTURE_FILE + " not found above the working directory");
    }
}
