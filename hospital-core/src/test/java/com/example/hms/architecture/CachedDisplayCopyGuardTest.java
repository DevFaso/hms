package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
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
 * QA D1147 (round 21): a cached display copy written only while it is blank.
 * {@code PrescriptionMapper.updateEntity} rewrote the drug's name, code and
 * catalogue link on an edit but set {@code medicationDisplayName} only
 * {@code if (!notBlank(target.getMedicationDisplayName()))}, so the list, the
 * pharmacist's view and the record PDF kept naming the first drug of an order
 * edited to Morphine. A copy of another field is rewritten with it, or not
 * kept at all.
 *
 * <p>The scan reads all of {@code src/main}: an {@code if} whose condition
 * reads an object's {@code get…Display…()} for null or blank, and whose body
 * sets that same field on that same object, fails (a partial update that
 * reads the request and sets the entity is not the class). Allowlist: none.
 */
class CachedDisplayCopyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern IF_OPEN = Pattern.compile("\\bif\\s*\\(");
    private static final Pattern GETTER = Pattern.compile("\\b(\\w+)\\.get(\\w+)\\(\\)");

    /**
     * {@code if (<cond reading x.getYDisplayZ() for blank>) { … x.setYDisplayZ(} - the same object and
     * property both sides. Returns {@code "<line> <property>"} per hit. Scanned by hand (the condition is
     * cut at its own closing parenthesis), so no pattern backtracks over the source.
     */
    static List<String> setOnlyWhenBlank(String source) {
        List<String> hits = new ArrayList<>();
        Matcher open = IF_OPEN.matcher(source);
        while (open.find()) {
            int paren = open.end() - 1;
            int close = SourceScan.closingParen(source, paren);
            if (close >= source.length()) {
                continue;
            }
            String condition = source.substring(paren + 1, close);
            if (condition.indexOf('{') >= 0 || condition.indexOf(';') >= 0 || !readsBlank(condition)) {
                continue;
            }
            int next = skipSpaces(source, close + 1);
            if (next < source.length() && source.charAt(next) == '{') {
                next = skipSpaces(source, next + 1);
            }
            Matcher getter = GETTER.matcher(condition);
            while (getter.find()) {
                String property = getter.group(2);
                if (property.contains("Display")
                        && source.startsWith(getter.group(1) + ".set" + property + "(", next)) {
                    hits.add(SourceScan.lineOf(source, open.start()) + " " + property);
                }
            }
        }
        return hits;
    }

    private static boolean readsBlank(String condition) {
        return condition.contains("null") || condition.contains("lank") || condition.contains("hasText")
            || condition.contains("isEmpty");
    }

    private static int skipSpaces(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    @Test
    void noDisplayCopyIsWrittenOnlyWhileBlank() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String hit : setOnlyWhenBlank(source)) {
                    offenders.add(MAIN.relativize(file) + ":" + hit);
                }
            }
        }
        assertThat(offenders)
            .as("a display copy set only while blank keeps the old value after an edit (QA D1147)%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theScanSeesTheD1147Shape() {
        assertThat(setOnlyWhenBlank(
            "if (!notBlank(target.getMedicationDisplayName())) {\n    target.setMedicationDisplayName(dto.getMedicationName());"))
            .containsExactly("1 MedicationDisplayName");
        assertThat(setOnlyWhenBlank("if (entity.getAllergenDisplay() == null) entity.setAllergenDisplay(x);"))
            .hasSize(1);
        assertThat(setOnlyWhenBlank("target.setMedicationDisplayName(dto.getMedicationName());")).isEmpty();
        assertThat(setOnlyWhenBlank("if (request.getProblemDisplay() != null) {\n    problem.setProblemDisplay(x);"))
            .as("a partial update that reads the request is not the class").isEmpty();
    }
}
