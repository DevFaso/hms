package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r53 guard (class: a labour admission of a child with no warning). A 10-year-old was admitted in labour and nothing
 * told anybody. Every production method that writes a labour admission (a new {@code Admission}, which may be of type
 * LABOR_DELIVERY, or a new or continued {@code LaborEpisode}) hands it to the safeguarding screen in the same method
 * ({@code onLabourAdmission(} or LaborServiceImpl's {@code screenMinorLabour(}). No allowlist.
 */
@DisplayName("r53: every path that writes a labour admission screens for a minor")
class MinorLabourSafeguardingPathGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /*
     * A run of plain characters is consumed whole (++), so the matcher recurses once per token, not once per
     * character: a long parameter list overflowed the stack on a CI shard (#1234). It matches the same text.
     */
    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected|static)[^;{=(]*?\\s(\\w+)\\s*\\(((?:[^;{()\"]++|\"[^\";{]*+\"|\\((?:[^;{()\"]++|\"[^\";{]*+\")*+\\))*+)\\)\\s*(?:throws [\\w., ]+)?\\{");

    /** A new admission row saved in the same method. */
    private static final Pattern NEW_ADMISSION = Pattern.compile("new Admission\\(\\)|\\bbuildAdmission\\(");
    private static final Pattern SAVES_ADMISSION = Pattern.compile("\\badmissionRepository\\.save(?:AndFlush)?\\(");
    /** A new or continued labour episode saved in the same method. */
    private static final Pattern NEW_EPISODE = Pattern.compile("LaborEpisode\\.builder\\(\\)|\\bcontinuation\\(");
    private static final Pattern SAVES_EPISODE = Pattern.compile("\\bepisodeRepository\\.save(?:AndFlush)?\\(");
    private static final Pattern SCREENS = Pattern.compile("\\.onLabourAdmission\\(|\\bscreenMinorLabour\\(");

    @Test
    void everyLabourAdmissionWriteIsScreened() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> unscreened = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String cls = file.getFileName().toString().replace(".java", "");
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher method = METHOD.matcher(code);
                while (method.find()) {
                    int open = method.end() - 1;
                    String body = code.substring(open, SourceScan.closingBrace(code, open) + 1);
                    boolean admission = NEW_ADMISSION.matcher(body).find() && SAVES_ADMISSION.matcher(body).find();
                    boolean episode = NEW_EPISODE.matcher(body).find() && SAVES_EPISODE.matcher(body).find();
                    if (!admission && !episode) {
                        continue;
                    }
                    String name = cls + "#" + method.group(1);
                    writers.add(name);
                    if (!SCREENS.matcher(body).find()) {
                        unscreened.add(name);
                    }
                }
            }
        }
        assertThat(writers).as("the labour admission write paths found").contains(
            "AdmissionServiceImpl#admitPatient", "LaborServiceImpl#startEpisode",
            "LaborServiceImpl#transferEpisode", "LaborServiceImpl#recordArrivalWithoutHandover",
            "MllpInboundAdtVisitProjectionServiceImpl#tryAutoCreateAdmission");
        assertThat(unscreened)
            .as("a method writes a labour admission without the safeguarding screen (r53: a 10-year-old was admitted in"
                + " labour with no warning); call minorLabourSafeguarding.onLabourAdmission(...) after the save"
                + SourceScan.MANDATE)
            .isEmpty();
    }
}
