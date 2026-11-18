package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Default log levels: root and com.example.hms are INFO in every profile; DEBUG is an opt-in through
 * LOG_LEVEL_HMS, never a profile default. The log sink is paid per line.
 */
class DefaultLogLevelGuardTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void propertiesDefaultToInfo() throws IOException {
        String props = Files.readString(RESOURCES.resolve("application.properties"), StandardCharsets.UTF_8);
        assertThat(props).contains("logging.level.root=INFO").contains("logging.level.com.example.hms=INFO");
    }

    @Test
    void noProfileDefaultsTheApplicationToDebug() throws IOException {
        for (String name : List.of("application-dev.yml", "application-local.yml", "application-local-h2.yml",
                "application-prod.yml")) {
            String yml = Files.readString(RESOURCES.resolve(name), StandardCharsets.UTF_8);
            for (String line : yml.split("\\R")) {
                if (line.contains("'[com.example.hms]'")) {
                    assertThat(line).as(name).doesNotContain(": DEBUG").doesNotContain(":DEBUG}");
                }
            }
        }
    }
}
