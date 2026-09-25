package com.example.hms.service.integration.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the line on {@link MllpRecordingContext} being the one place an MLLP
 * {@code integration_id} is built.
 *
 * <p>Why a source scan rather than a unit test: the failure mode is a *second*
 * implementation appearing, and no runtime assertion can see one that nobody
 * calls from the code under test. It has already happened — there were three
 * copies of this string concatenation, two of them missing the 120-char
 * truncation, and an over-long id does not fail loudly: the insert throws
 * inside {@link IntegrationMessageRecorder}, which swallows it by design, and
 * that sender's DLQ rows simply never appear. On the ADT and A40 paths those
 * rows are the only record of why a message was refused.
 *
 * <p>{@code MllpInboundLabServiceImpl} is a <b>known, temporary</b> exception:
 * it keeps its own builder, and that copy is the one with no truncation. It is
 * listed here rather than fixed because three agents have that file open this
 * week. <b>When the lab stream migrates it, delete its entry below</b> — the
 * test will fail until you do, which is the point.
 */
class MllpIntegrationIdSingleSourceTest {

    /**
     * Every main-source file allowed to contain the {@code "MLLP:"} literal.
     * Adding to this set is a decision, not a formality.
     */
    private static final Set<String> ALLOWED = Set.of(
        "MllpRecordingContext.java",
        // TEMPORARY — see the class javadoc. Remove when the lab stream moves
        // MllpInboundLabServiceImpl onto MllpRecordingContext.
        "MllpInboundLabServiceImpl.java");

    @Test
    @DisplayName("Only the shared helper (and the known lab exception) builds an MLLP integration id")
    void onlyTheSharedHelperBuildsTheId() throws IOException {
        Path mainSources = Paths.get("src", "main", "java");
        // The suite's working directory is the module; if that ever changes,
        // say so rather than passing vacuously on an empty scan.
        assertThat(Files.isDirectory(mainSources))
            .as("expected to scan %s from the module directory", mainSources.toAbsolutePath())
            .isTrue();

        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            List<Path> javaFiles = files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                String name = file.getFileName().toString();
                if (ALLOWED.contains(name)) {
                    continue;
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains("\"MLLP:\"")) {
                    offenders.add(name);
                }
            }
        }

        assertThat(offenders)
            .as("build the integration id with MllpRecordingContext.integrationId(app, facility) "
                + "instead of concatenating it: a copy without the 120-char truncation drops the "
                + "DLQ row for the very sender whose configuration is wrong")
            .isEmpty();
    }
}
