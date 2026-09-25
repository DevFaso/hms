package com.example.hms.service.integration.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
     * Every main source file allowed to open a string literal with
     * {@code MLLP:}, by path rather than by bare filename — a bare name would
     * exempt a new file that merely reused it — mapped to the <b>witness</b>
     * that justifies the exemption.
     *
     * <p>The witness is what makes a temporary exemption temporary. Checking
     * only that the file still contains <em>some</em> {@code "MLLP:} literal
     * would not do it: {@code MllpInboundLabServiceImpl} has two, the id
     * builder and an unrelated audit {@code actorLabel}, so migrating the
     * builder — the exact event that is supposed to end the exemption — would
     * leave the other one satisfying the check and the hole open for good.
     *
     * <p>Adding to this map is a decision, not a formality.
     */
    private static final Map<String, String> ALLOWED = Map.of(
        "com/example/hms/service/integration/message/MllpRecordingContext.java",
        "public static String integrationId(",
        // TEMPORARY — see the class javadoc. When the lab stream moves
        // MllpInboundLabServiceImpl onto MllpRecordingContext this method goes
        // and theAllowSetDoesNotGoStale fails until the entry is deleted too.
        "com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java",
        "private String buildIntegrationId(");

    /**
     * Matches a string literal that starts with {@code MLLP:}, however the id
     * is then assembled — {@code "MLLP:" + app}, {@code "MLLP:%s/%s"} with
     * {@code formatted}, a text block. Matching the exact literal
     * {@code "MLLP:"} alone would be defeated by rewording, which is not much
     * of a guard.
     */
    private static final Pattern MLLP_ID_LITERAL = Pattern.compile("\"MLLP:");

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
                String relativePath = mainSources.relativize(file).toString().replace('\\', '/');
                if (ALLOWED.containsKey(relativePath)) {
                    continue;
                }
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                if (MLLP_ID_LITERAL.matcher(code).find()) {
                    offenders.add(relativePath);
                }
            }
        }

        assertThat(offenders)
            .as("build the integration id with MllpRecordingContext.integrationId(app, facility) "
                + "instead of concatenating it: a copy without the 120-char truncation drops the "
                + "DLQ row for the very sender whose configuration is wrong")
            .isEmpty();
    }

    /**
     * Comments out, so the scan sees code. Several classes describe the id's
     * shape in their javadoc — {@code actorLabel="MLLP:{sendingApp}/..."} —
     * and documentation is not a second implementation. Crude on purpose: it
     * only has to be right about whether a literal is live code, and a string
     * containing {@code //} would at worst hide the rest of one line from the
     * scan, never invent an offender.
     */
    private static String withoutComments(String source) {
        return source
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("Every exemption still names a file that exists and still needs one")
    void theAllowSetDoesNotGoStale() throws IOException {
        // An exemption for a file that has moved, been renamed, or stopped
        // containing the literal is an exemption nobody notices has stopped
        // applying - and the lab entry in particular is meant to be deleted,
        // so it has to be visible when it becomes pointless.
        Path mainSources = Paths.get("src", "main", "java");
        for (Map.Entry<String, String> exemption : ALLOWED.entrySet()) {
            String allowed = exemption.getKey();
            Path file = mainSources.resolve(allowed);
            assertThat(Files.isRegularFile(file))
                .as("exempted file %s does not exist - remove it from ALLOWED", allowed)
                .isTrue();
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("%s no longer declares %s, so its exemption has expired - "
                        + "remove it from ALLOWED", allowed, exemption.getValue())
                .contains(exemption.getValue());
        }
    }
}
