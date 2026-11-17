package com.example.hms.i18n;

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
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1179 (round 21): emailing an invoice to "pas-un-email" answered the
 * bean-validation default, "doit être une adresse électronique syntaxiquement
 * correcte" - Hibernate's own wording, which says nothing of what to type.
 * Every {@code @Email} carries a message key of the application's bundles,
 * and every {@code message = "{key}"} of a constraint names a key the
 * bundles define (a missing one renders the braces and the key).
 */
@DisplayName("Constraint messages are keys of the application's bundles")
class ConstraintMessageKeyGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** An {@code @Email} annotation, simple or fully qualified, and what follows it. */
    private static final Pattern EMAIL = Pattern.compile("@(?:jakarta\\.validation\\.constraints\\.)?Email\\b");

    private static final Pattern MESSAGE_KEY = Pattern.compile("message\\s*=\\s*\"\\{([^}\"]+)}\"");

    @Test
    @DisplayName("every @Email names a message key")
    void everyEmailConstraintNamesAMessageKey() throws IOException {
        List<String> bare = new ArrayList<>();
        int seen = 0;
        for (Path file : javaFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = EMAIL.matcher(source);
            while (m.find()) {
                if (insideCommentOrString(source, m.start())) {
                    continue;
                }
                seen++;
                int i = m.end();
                while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
                    i++;
                }
                String arguments = i < source.length() && source.charAt(i) == '('
                    ? source.substring(i, SourceScan.closingParen(source, i) + 1)
                    : "";
                if (!MESSAGE_KEY.matcher(arguments).find()) {
                    bare.add(file.getFileName() + ":" + lineOf(source, m.start()));
                }
            }
        }
        assertThat(seen).as("the scan found no @Email at all").isGreaterThan(20);
        assertThat(bare)
            .as("@Email without message = \"{key}\": the reader gets the validator's default wording%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("every constraint message key is in the bundles")
    void everyConstraintMessageKeyExists() throws IOException {
        Properties base = MessageBundleParityTest.load("");
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (Path file : javaFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = MESSAGE_KEY.matcher(source);
            while (m.find()) {
                String key = m.group(1);
                if (key.startsWith("jakarta.") || key.startsWith("org.hibernate.")) {
                    continue;
                }
                seen++;
                if (base.getProperty(key) == null) {
                    missing.add(file.getFileName() + ":" + lineOf(source, m.start()) + " :: " + key);
                }
            }
        }
        assertThat(seen).as("the scan found no constraint message keys").isGreaterThan(100);
        assertThat(missing)
            .as("Constraint message keys no bundle defines%s", SourceScan.MANDATE)
            .isEmpty();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** Whether {@code index} lies in a comment or a string literal (a Javadoc naming {@code @Email}). */
    private static boolean insideCommentOrString(String source, int index) {
        for (int i = 0; i < index; i++) {
            int end = SourceScan.endOfLiteralOrComment(source, i);
            if (end >= 0) {
                if (index <= end) {
                    return true;
                }
                i = end;
            }
        }
        return false;
    }

    private static int lineOf(String source, int index) {
        return 1 + (int) source.substring(0, index).chars().filter(ch -> ch == '\n').count();
    }
}
