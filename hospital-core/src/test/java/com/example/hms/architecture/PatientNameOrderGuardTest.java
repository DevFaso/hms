package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r44 imaging review: the imaging list read "Traoré, Seydou" while every other
 * screen reads "Seydou Traoré". The imaging and ultrasound order mappers built
 * their own "Last, First" display name. A patient's display name is the
 * shared {@code Patient#getFullName()} ("First Middle Last", QA D1036); no
 * production code composes a surname-first name with a comma.
 */
@DisplayName("No production code composes a \"Last, First\" display name")
class PatientNameOrderGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** {@code last + ", " + first}, {@code getLastName() + ", " + ...}. */
    static final Pattern SURNAME_COMMA = Pattern.compile(
        "(?i)\\b\\w*last\\w*(?:\\(\\))?\\s*\\+\\s*\", \"\\s*\\+");

    @Test
    void noSurnameFirstDisplayName() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (SURNAME_COMMA.matcher(lines.get(i)).find()) {
                        offenders.add(MAIN.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void theImagingAndUltrasoundOrdersUseTheSharedName() throws IOException {
        for (String mapper : List.of("ImagingOrderMapper.java", "UltrasoundMapper.java")) {
            String src = Files.readString(MAIN.resolve("mapper").resolve(mapper), StandardCharsets.UTF_8);
            assertThat(src).as(mapper).contains("patient.getFullName()");
        }
    }

    @Test
    void theScanSeesTheShapesTheDefectHad() {
        assertThat(SURNAME_COMMA.matcher("return last + \", \" + first;").find()).isTrue();
        assertThat(SURNAME_COMMA.matcher("return lastName + \", \" + firstName;").find()).isTrue();
        assertThat(SURNAME_COMMA.matcher("p.getLastName() + \", \" + p.getFirstName()").find()).isTrue();
        assertThat(SURNAME_COMMA.matcher("return first + \" \" + last;").find()).isFalse();
    }
}
