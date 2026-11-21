package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r63 D2304 class guard: a laboratory result is released in three places
 * today (the worklist, autoverification, an analyzer's explicit normal), and
 * each must ask {@code LabQcReleaseGate} first, so a rejected QC run holds the
 * test's results whichever path would release them. A new path that marks a
 * result released without the gate fails here.
 */
class LabQcReleaseGateGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern RELEASES = Pattern.compile("setReleased\\(\\s*true\\s*\\)|\\.released\\(\\s*true\\s*\\)");

    @Test
    void everyClassThatReleasesALabResultAsksTheQcGate() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> releasing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!RELEASES.matcher(source).find() || !source.contains("LabResult")) {
                    continue;
                }
                releasing.add(file.getFileName().toString());
                if (!source.contains("qcReleaseGate.isBlocked(") && !source.contains("qcReleaseGate.blockingRun(")) {
                    offenders.add(MAIN.relativize(file).toString());
                }
            }
        }
        assertThat(releasing)
            .as("the scan must still find the known release paths, or it proves nothing")
            .contains("LabResultServiceImpl.java", "MllpInboundLabServiceImpl.java");
        assertThat(offenders)
            .as("classes that release a lab result without asking LabQcReleaseGate (r63 D2304)")
            .isEmpty();
    }
}
