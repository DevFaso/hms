package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** D1574: backend-only PRs must enforce this guard too, even when portal CI is skipped. */
class MedicationDisplayTextGuardTest {
    private static final Pattern SOURCE_PROSE = Pattern.compile(
        "(?:\\.source\\(\\s*|String\\s+source\\s*=\\s*)\"[^\"\\n]*[a-zA-Z] [a-zA-Z][^\"\\n]*\"");
    private static final Pattern WARNING_PROSE = Pattern.compile(
        "warnings\\.add\\([^;]*\"[^\"\\n]*[a-zA-Z] [a-zA-Z][^\"\\n]*\"");
    private static final Pattern DAY_DURATION = Pattern.compile("\\.duration\\([^;]*\" days\"");

    private static List<String> problems(String text) {
        List<String> out = new ArrayList<>();
        if (SOURCE_PROSE.matcher(text).find()) out.add("literal source label");
        if (WARNING_PROSE.matcher(text).find()) out.add("literal warning prose");
        if (DAY_DURATION.matcher(text).find()) out.add("literal day duration");
        return out;
    }

    @Test
    void detectsGeneratedProseButPreservesKeysAndAuthoredValues() {
        assertThat(problems(".source(\"Internal Prescription\")")).containsExactly("literal source label");
        assertThat(problems("String source = \"Pharmacy Fill\";")).containsExactly("literal source label");
        assertThat(problems("warnings.add(n + \" medications have overlapping therapy\");"))
            .containsExactly("literal warning prose");
        assertThat(problems(".duration(n + \" days\")")).containsExactly("literal day duration");
        assertThat(problems(".source(fill.getSourceSystem())")).isEmpty();
        assertThat(problems("warnings.add(MessageUtil.resolve(locale, \"feature.warning\", n));")).isEmpty();
    }

    @Test
    void generatedDisplayTextAcrossProductionJavaUsesKeys() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // Infrastructure startup diagnostics, never clinical display text (not a D1574 instance).
                if (file.endsWith("UploadStoragePersistenceCheck.java")) continue;
                for (String problem : problems(Files.readString(file, StandardCharsets.UTF_8))) {
                    violations.add(root.relativize(file) + ": " + problem);
                }
            }
        }
        assertThat(violations).as("D1574: generated text must resolve a key in the reader locale").isEmpty();
    }
}
