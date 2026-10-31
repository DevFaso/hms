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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D972 guard, the second half of the class: a visit completed on a path
 * that never tells billing. Only the clinic checkout published the hand-off,
 * so a stay closed by its discharge, the emergency visit an admission ends,
 * and a visit a death closes were never billed at all. Every production
 * method that sets an encounter to COMPLETED must publish
 * {@code EncounterCheckedOutEvent.of(…)} (or call the class's own
 * {@code publishCheckedOut} / {@code publishCompleted}) in the same method.
 */
@DisplayName("Every path that completes a visit hands it to billing (QA D972)")
class VisitCompletionBillingGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern METHOD = Pattern.compile(
        // Possessive, one level of nested parentheses in the parameters (Sonar S8786: no backtracking).
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s++)++(?:[\\w<>,.?\\[\\]]++\\s++)++"
            + "(\\w++)\\s*+\\((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern COMPLETES = Pattern.compile(
        "\\.setStatus\\(\\s*EncounterStatus\\.COMPLETED\\s*\\)|\\.status\\(\\s*EncounterStatus\\.COMPLETED\\s*\\)");
    private static final Pattern HANDS_OFF = Pattern.compile(
        "EncounterCheckedOutEvent\\.of\\(|\\bpublishCheckedOut\\w*\\(|\\bpublishCompleted\\(");

    /** Methods that complete an encounter that is not a visit to bill, each with why. Shrink only. */
    private static final Map<String, String> NOT_A_VISIT = Map.of(
        "PrescriptionServiceImpl#createEncounterSnapshot",
        "the anchor row a stand-alone prescription needs (QA D41): no visit took place, and the medicine is paid "
            + "at the pharmacy checkout");

    @Test
    @DisplayName("a method that completes a visit publishes the billing hand-off")
    void completionsHandOffToBilling() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.startsWith(MAIN.resolve("model")) || file.startsWith(MAIN.resolve("bootstrap"))) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                offenders.addAll(offenders(file.getFileName().toString().replace(".java", ""), source));
            }
        }
        assertThat(offenders.stream().filter(site -> !NOT_A_VISIT.containsKey(site)).toList())
            .as("a visit is completed without telling billing (QA D972)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(NOT_A_VISIT.keySet().stream().filter(site -> !offenders.contains(site)).toList())
            .as("NOT_A_VISIT names a method that no longer completes an encounter: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the shape the discharge had")
    void catchesTheOldShape() {
        String before = """
            class S {
                public void completeAdmissionEncounters(UUID admissionId) {
                    for (Encounter enc : active) {
                        enc.setStatus(EncounterStatus.COMPLETED);
                    }
                    encounterRepository.saveAll(active);
                }
            }
            """;
        assertThat(offenders("S", before)).containsExactly("S#completeAdmissionEncounters");
        String after = before.replace("encounterRepository.saveAll(active);",
            "encounterRepository.saveAll(active); publishCompleted(enc, now);");
        assertThat(offenders("S", after)).isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (COMPLETES.matcher(body).find() && !HANDS_OFF.matcher(body).find()) {
                out.add(file + "#" + m.group(1));
            }
        }
        return out;
    }
}
