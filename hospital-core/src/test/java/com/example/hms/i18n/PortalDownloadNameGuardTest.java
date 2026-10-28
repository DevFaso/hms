package com.example.hms.i18n;

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
 * QA D1009: invoice PDFs were saved as "invoice-&lt;uuid&gt;.pdf" and every receipt
 * of an invoice under one name. Guard: no file the portal saves is named from
 * a database id; it keeps the server's name or a readable reference.
 */
class PortalDownloadNameGuardTest {

    private static final Path APP = Path.of("..", "hospital-portal", "src", "app");
    /** A template expression inside a saved file name; possessive, so the scan is linear. */
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]*+)\\}");
    private static final Pattern DOWNLOAD_ASSIGNMENT = Pattern.compile("\\.download\\s*+=");

    @Test
    void noSavedFileIsNamedByADatabaseId() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(APP)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".ts") && !f.toString().endsWith(".spec.ts")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (namedById(lines.get(i))) {
                        offenders.add(APP.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** `x.download = ...${...id}...`: a file named from an identifier (an expression ending in "id"). */
    private static boolean namedById(String line) {
        java.util.regex.Matcher assignment = DOWNLOAD_ASSIGNMENT.matcher(line);
        if (!assignment.find()) {
            return false;
        }
        java.util.regex.Matcher m = EXPRESSION.matcher(line);
        m.region(assignment.end(), line.length());
        while (m.find()) {
            if (m.group(1).toLowerCase(java.util.Locale.ROOT).endsWith("id")) {
                return true;
            }
        }
        return false;
    }
}
