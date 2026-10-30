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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1080 guard (the D922 class, round 18): a response that carries a
 * {@code controlledSubstance} flag but is built without setting it, so the
 * flag silently reads {@code false}. The MAR administer response told the
 * client morphine was not controlled while the GET of the same row said it
 * was: three of the five builders of {@code NurseMedicationTaskResponseDTO}
 * left the flag out.
 *
 * <p>For every response DTO with a {@code controlledSubstance} field, every
 * {@code Dto.builder()} chain in production code must call
 * {@code .controlledSubstance(…)} — with the catalogue-or-tick answer
 * ({@code PrescriptionControlledResolver}, #978 rule), which
 * {@code ControlledFlagGuardTest} already holds the decisions to.
 */
@DisplayName("Every response with a controlled flag sets it")
class ControlledFlagResponseGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern FLAG_FIELD = Pattern.compile("private\\s+(?:boolean|Boolean)\\s+controlledSubstance\\s*;");
    private static final Pattern CLASS_DECL = Pattern.compile("\\bclass\\s+(\\w+)");

    @Test
    @DisplayName("no builder of a flagged response leaves the flag out")
    void everyBuilderSetsTheFlag() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        Set<String> flagged = new TreeSet<>();
        for (Path file : files) {
            if (!file.toString().replace('\\', '/').contains("/payload/")) {
                continue;
            }
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            flagged.addAll(flaggedResponseTypes(source));
        }
        assertThat(flagged).as("response DTOs carrying a controlled flag")
            .contains("NurseMedicationTaskResponseDTO", "PrescriptionResponseDTO", "MedicationEntryDTO");

        List<String> missing = new ArrayList<>();
        for (Path file : files) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String site : buildersWithoutFlag(source, flagged)) {
                missing.add(MAIN.relativize(file).toString().replace('\\', '/') + ":" + site);
            }
        }
        assertThat(missing)
            .as("a response with a controlledSubstance flag is built without it, so morphine reads as not "
                + "controlled (QA D1080): set .controlledSubstance(…) from the catalogue-or-tick resolver"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1080 shape")
    void catchesTheOldShape() {
        String dto = """
            public class NurseMedicationTaskResponseDTO {
                private UUID id;
                private boolean controlledSubstance;
            }
            """;
        Set<String> flagged = flaggedResponseTypes(dto);
        assertThat(flagged).containsExactly("NurseMedicationTaskResponseDTO");
        String before = """
            return NurseMedicationTaskResponseDTO.builder()
                .id(saved.getId())
                .status(status.name())
                .build();
            """;
        assertThat(buildersWithoutFlag(before, flagged)).hasSize(1);
        String after = before.replace(".status(status.name())",
            ".status(status.name()).controlledSubstance(verification.isControlledSubstance(rx))");
        assertThat(buildersWithoutFlag(after, flagged)).isEmpty();
        assertThat(flaggedResponseTypes("public class PrescriptionRequestDTO { private Boolean controlledSubstance; }"))
            .as("a request DTO carries the prescriber's tick, not an answer").isEmpty();
    }

    /** The response types (outer or nested) declaring a controlled flag; request DTOs are the tick itself. */
    static Set<String> flaggedResponseTypes(String source) {
        Set<String> out = new TreeSet<>();
        Matcher field = FLAG_FIELD.matcher(source);
        while (field.find()) {
            String owner = null;
            Matcher decl = CLASS_DECL.matcher(source);
            while (decl.find() && decl.start() < field.start()) {
                int open = source.indexOf('{', decl.end());
                if (open >= 0 && open < field.start() && SourceScan.closingBrace(source, open) > field.start()) {
                    owner = decl.group(1);
                }
            }
            if (owner != null && !owner.endsWith("RequestDTO")) {
                out.add(owner);
            }
        }
        return out;
    }

    static List<String> buildersWithoutFlag(String source, Set<String> flagged) {
        List<String> out = new ArrayList<>();
        for (String type : flagged) {
            Matcher m = Pattern.compile("\\b" + type + "\\.builder\\(\\)").matcher(source);
            while (m.find()) {
                int build = source.indexOf(".build()", m.end());
                String chain = build < 0 ? source.substring(m.end()) : source.substring(m.end(), build);
                if (!chain.contains(".controlledSubstance(")) {
                    out.add(type + " at line " + SourceScan.lineOf(source, m.start()));
                }
            }
        }
        return out;
    }
}
