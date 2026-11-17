package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-009 guard: a validated culture report said growth with no organism,
 * because the rule lived only in finalize. Every path that changes a report's
 * outcome or its organisms now goes through one service, one lock and one rule:
 * <ul>
 *   <li>nothing outside {@code MicroCultureServiceImpl} writes a report's status
 *       or outcome, removes an isolate or writes one, so no other path can reach
 *       CORRECTED;</li>
 *   <li>each public change in that service takes the report's row first
 *       ({@code loadForChange}), never the plain {@code loadScoped};</li>
 *   <li>CORRECTED is stamped in exactly one place, {@code applyCorrectionGuard},
 *       which also writes the correction history;</li>
 *   <li>each method that removes isolates or sets the outcome reaches the rule
 *       ({@code requireConsistentOutcome}), directly or through
 *       {@code reconcileOutcomeWithIsolates}.</li>
 * </ul>
 * Read from the source on purpose: the mock tests of the service can only see the
 * paths somebody thought to test.
 */
@DisplayName("A culture report's outcome and organisms change in one service, through one rule")
class MicroCultureOutcomeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SERVICE = "MicroCultureServiceImpl.java";
    private static final Pattern PUBLIC_METHOD =
        Pattern.compile("@Override\\s+(?:@Transactional[^\\n]*\\s+)?public\\s+\\S+\\s+(\\w+)\\s*\\(");
    private static final Pattern OUTSIDE_WRITE = Pattern.compile(
        "setGrowthResult\\(|setStatus\\(\\s*(?:com\\.example\\.hms\\.enums\\.)?MicroCultureStatus\\.CORRECTED|isolateRepository\\.(?:delete|save)"
            + "|microIsolateRepository\\.(?:delete|save)");
    private static final List<String> READ_ONLY = List.of("getCulture", "getForPatient", "getForHospital", "createCulture");

    private static String service() throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve("service/impl/" + SERVICE), StandardCharsets.UTF_8));
    }

    private static String bodyOf(String source, int declaration) {
        int open = source.indexOf('{', declaration);
        return source.substring(open, SourceScan.closingBrace(source, open) + 1);
    }

    @Test
    @DisplayName("nothing outside the service writes a report's outcome, its CORRECTED stamp or its isolates")
    void onlyTheServiceWrites() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith(SERVICE)) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher write = OUTSIDE_WRITE.matcher(source);
                if (write.find() && source.contains("MicroCulture")) {
                    found.add(file + ":" + SourceScan.lineOf(source, write.start()) + " " + write.group());
                }
            }
        }
        assertThat(found).as("write culture reports through MicroCultureServiceImpl (CX-009)").isEmpty();
    }

    @Test
    @DisplayName("every public change takes the report's row first")
    void everyChangeLocks() throws IOException {
        String source = service();
        List<String> changes = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        Matcher method = PUBLIC_METHOD.matcher(source);
        while (method.find()) {
            String name = method.group(1);
            if (READ_ONLY.contains(name)) {
                continue;
            }
            changes.add(name);
            String body = bodyOf(source, method.end());
            if (!body.contains("loadForChange(") || body.contains("loadScoped(")) {
                unlocked.add(name);
            }
        }
        assertThat(changes).as("the scan must see the service's changes")
            .contains("updateCulture", "finalizeCulture", "addIsolate", "updateIsolate", "deleteIsolate",
                "addSusceptibility", "deleteSusceptibility");
        assertThat(unlocked).as("use loadForChange, so the count a decision reads holds until commit (CX-009)").isEmpty();
    }

    @Test
    @DisplayName("CORRECTED is stamped once, beside the history row")
    void correctedIsStampedOnce() throws IOException {
        String source = service();
        assertThat(Pattern.compile("setStatus\\(MicroCultureStatus\\.CORRECTED\\)").matcher(source).results().count())
            .isOne();
        String guard = bodyOf(source, source.indexOf("private void applyCorrectionGuard("));
        assertThat(guard).contains("setStatus(MicroCultureStatus.CORRECTED)", "correctionRepository.save(");
    }

    @Test
    @DisplayName("whatever removes an isolate or sets an outcome on a report reaches the rule")
    void outcomeWritesReachTheRule() throws IOException {
        String source = service();
        for (String method : List.of("updateCulture", "finalizeCulture", "deleteIsolate")) {
            String body = bodyOf(source, source.indexOf("public MicroCultureResponseDTO " + method + "("));
            assertThat(body.contains("requireConsistentOutcome(") || body.contains("reconcileOutcomeWithIsolates("))
                .as(method + " must go through the outcome rule (CX-009)").isTrue();
        }
        String reconcile = bodyOf(source, source.indexOf("private void reconcileOutcomeWithIsolates("));
        assertThat(reconcile).contains("requireConsistentOutcome(");
    }

    @Test
    @DisplayName("isolates are removed in one place, and the only callers are the ones the rule covers")
    void oneRemoval() throws IOException {
        String source = service();
        assertThat(Pattern.compile("isolateRepository(?:::delete|\\.delete)").matcher(source).results().count()).isOne();
        assertThat(Pattern.compile("removeWithSusceptibilities\\(").matcher(source).results().count())
            .as("the declaration, deleteIsolate and reconcileOutcomeWithIsolates").isEqualTo(3);
    }
}
