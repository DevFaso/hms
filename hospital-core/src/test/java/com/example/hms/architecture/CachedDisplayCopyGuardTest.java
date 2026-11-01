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

    /** {@code if (<cond reading getXDisplayY()>) { … setXDisplayY(} - the same property both sides. */
    static final Pattern SET_ONLY_WHEN_BLANK = Pattern.compile(
        "if\\s*\\(([^{;]*?)\\b(\\w+)\\.get(\\w*Display\\w*)\\(\\)([^{;]*?)\\)\\s*\\{?\\s*\\2\\.set\\3\\(");

    @Test
    void noDisplayCopyIsWrittenOnlyWhileBlank() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = SET_ONLY_WHEN_BLANK.matcher(source);
                while (m.find()) {
                    String condition = m.group(1) + m.group(4);
                    if (condition.contains("null") || condition.contains("lank") || condition.contains("hasText")
                            || condition.contains("isEmpty")) {
                        int line = 1 + (int) source.substring(0, m.start()).chars().filter(ch -> ch == '\n').count();
                        offenders.add(MAIN.relativize(file) + ":" + line + " " + m.group(3));
                    }
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
        assertThat(SET_ONLY_WHEN_BLANK.matcher(
            "if (!notBlank(target.getMedicationDisplayName())) {\n    target.setMedicationDisplayName(dto.getMedicationName());")
            .find()).isTrue();
        assertThat(SET_ONLY_WHEN_BLANK.matcher(
            "if (entity.getAllergenDisplay() == null) entity.setAllergenDisplay(x);").find()).isTrue();
        assertThat(SET_ONLY_WHEN_BLANK.matcher("target.setMedicationDisplayName(dto.getMedicationName());").find())
            .isFalse();
        assertThat(SET_ONLY_WHEN_BLANK.matcher(
            "if (request.getProblemDisplay() != null) {\n    problem.setProblemDisplay(x);").find())
            .as("a partial update that reads the request is not the class").isFalse();
    }
}
