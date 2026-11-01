package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every {@code application*.yml} must load through Spring's own YAML loader.
 *
 * <p>Written after a duplicate top-level {@code app:} key shipped in
 * {@code application-local.yml}. Spring's {@code OriginTrackedYamlLoader} sets
 * {@code allowDuplicateKeys(false)}, so that file could not be parsed at all and
 * {@code bootRun --spring.profiles.active=local} — the command the observability docs tell you
 * to run — failed before the context started.
 *
 * <p>The full backend suite stayed green through it, because no test activates the {@code local}
 * profile and nothing else reads these files. Only the YAML lint job caught it. This test closes
 * that gap inside the suite: {@link YamlPropertySourceLoader} applies the same duplicate-key
 * strictness the runtime does, so a malformed profile now fails here rather than at someone's
 * first {@code bootRun}.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: this must stay a cheap parse check that runs
 * everywhere, including without Docker.
 */
class ApplicationYamlLoadsTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static Stream<Path> applicationYamls() throws IOException {
        try (var paths = Files.list(RESOURCES)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("application")
                        && (name.endsWith(".yml") || name.endsWith(".yaml"));
                })
                .toList()
                .stream();
        }
    }

    @Test
    @DisplayName("every application*.yml parses under Spring's loader, duplicate keys included")
    void everyProfileYamlLoads() throws IOException {
        List<Path> files = applicationYamls().toList();

        assertThat(files)
            .as("expected to find the profile YAMLs under %s — if this is empty the test is "
                + "silently guarding nothing", RESOURCES.toAbsolutePath())
            .isNotEmpty();

        var loader = new YamlPropertySourceLoader();
        for (Path file : files) {
            assertThatCode(() -> {
                List<PropertySource<?>> sources =
                    loader.load(file.getFileName().toString(), new FileSystemResource(file));
                assertThat(sources)
                    .as("%s produced no property source", file.getFileName())
                    .isNotEmpty();
            })
                .as("%s must load through Spring's YAML loader — a duplicate key here means the "
                    + "profile cannot start, and no other test would notice", file.getFileName())
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the local profile really does carry the metrics scrape token")
    void localProfileCarriesScrapeToken() {
        var loader = new YamlPropertySourceLoader();
        Path local = RESOURCES.resolve("application-local.yml");

        assertThat(local).as("application-local.yml must exist").exists();

        List<PropertySource<?>> sources = loadOrFail(loader, local);

        // Alloy presents this token on every scrape; if the key moves or is nested wrongly the
        // local stack silently collects nothing but 401s.
        boolean present = sources.stream()
            .anyMatch(s -> s.getProperty("app.observability.metrics.scrape-token") != null);

        assertThat(present)
            .as("app.observability.metrics.scrape-token must resolve under the local profile — "
                + "docker-compose's Alloy defaults to the same value and 401s without it")
            .isTrue();
    }

    private static List<PropertySource<?>> loadOrFail(YamlPropertySourceLoader loader, Path file) {
        try {
            return loader.load(file.getFileName().toString(), new FileSystemResource(file));
        } catch (IOException e) {
            throw new AssertionError("could not load " + file, e);
        }
    }
}
