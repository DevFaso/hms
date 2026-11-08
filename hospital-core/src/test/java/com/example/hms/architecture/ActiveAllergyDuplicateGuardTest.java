package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
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
import org.junit.jupiter.api.Test;

/**
 * QA D1287 (round 27, class guard): the same allergy could be recorded twice
 * as active, by the nursing intake and by {@code POST /patients/{id}/allergies},
 * because neither path asked whether the allergen was already active. Class:
 * a path that stores an allergy row without the shared duplicate check.
 *
 * <p>Every main method that saves an allergy row asks
 * {@code ActiveAllergyDuplicates.find} (directly or through
 * {@code refuseActiveDuplicate}), unless it only deactivates one (a
 * deactivated row is never a duplicate).
 */
class ActiveAllergyDuplicateGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern SAVES = Pattern.compile("[aA]llergyRepository\\s*+\\.\\s*+save(?:AndFlush|All)?+\\(");

    @Test
    void everyAllergyWriteAsksWhetherTheAllergenIsAlreadyActive() throws IOException {
        List<String> unchecked = new ArrayList<>();
        int writes = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = SAVES.matcher(src);
                while (m.find()) {
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(src, m.start());
                    writes++;
                    boolean deactivatesOnly = method.contains("setActive(false)")
                        && !method.contains("setActive(true)") && !method.contains(".active(");
                    boolean checked = method.contains("ActiveAllergyDuplicates.find(")
                        || method.contains("refuseActiveDuplicate(");
                    if (!deactivatesOnly && !checked) {
                        unchecked.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()));
                    }
                }
            }
        }
        assertThat(writes).as("allergy writes read").isGreaterThanOrEqualTo(4);
        assertThat(unchecked).as("an allergy is stored without asking whether the same allergen is already active "
            + "(QA D1287)" + SourceScan.MANDATE).isEmpty();
    }
}
