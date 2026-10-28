package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D938 / D931: every class that releases a lab result tells the people a
 * release is for, and every class that supersedes one closes what pointed at
 * the old one.
 *
 * <p>The defects were two halves of one shape: the release path wrote the
 * released flag and the critical page, and nothing else - the patient was
 * never told a result was ready, and a correction withdrew the old result
 * while its in-basket items stayed open on a 404. A new release path (an
 * importer, a FHIR write, a bulk release) would repeat it with nothing
 * failing, so this test reads the source:
 * <ul>
 *   <li>a class that writes {@code setReleased(true)} must call
 *       {@code releaseNotifier.patientResultReleased};</li>
 *   <li>a class that links a superseded result
 *       ({@code setSupersededByResultId(<non-null>)}) must call
 *       {@code removeOpenItemsForReference} and
 *       {@code releaseNotifier.correctionReleased}.</li>
 * </ul>
 */
class LabResultReleaseAnnouncementGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern RELEASES = Pattern.compile("\\.setReleased\\(\\s*true\\s*\\)|\\.released\\(\\s*true\\s*\\)");
    private static final Pattern SUPERSEDES = Pattern.compile("\\.setSupersededByResultId\\(\\s*(?!null\\s*\\))");

    @Test
    void everyReleasePathTellsThePatient() throws IOException {
        TreeSet<String> releasing = filesMatching(RELEASES);
        assertThat(releasing)
            .as("the scan must see the known release paths, or it is scanning nothing")
            .contains("com/example/hms/service/LabResultServiceImpl.java",
                "com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java");
        List<String> silent = releasing.stream()
            .filter(file -> !read(file).contains("releaseNotifier.patientResultReleased("))
            .toList();
        assertThat(silent)
            .as("a class that releases a lab result must announce it (QA D938): call "
                + "LabResultReleaseNotifier.patientResultReleased")
            .isEmpty();
    }

    @Test
    void everySupersedingPathClosesTheOldItemsAndTellsTheClinician() throws IOException {
        TreeSet<String> superseding = filesMatching(SUPERSEDES);
        assertThat(superseding).contains("com/example/hms/service/LabResultServiceImpl.java");
        List<String> incomplete = superseding.stream()
            .filter(file -> {
                String source = read(file);
                return !source.contains("removeOpenItemsForReference(")
                    || !source.contains("releaseNotifier.correctionReleased(");
            })
            .toList();
        assertThat(incomplete)
            .as("a class that supersedes a released lab result must close its open in-basket items and "
                + "tell the ordering clinician (QA D931)")
            .isEmpty();
    }

    private static TreeSet<String> filesMatching(Pattern pattern) throws IOException {
        TreeSet<String> files = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    String relative = MAIN.relativize(p).toString().replace('\\', '/');
                    if (pattern.matcher(read(relative)).find()) {
                        files.add(relative);
                    }
                });
        }
        return files;
    }

    private static String read(String relative) {
        try {
            return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
