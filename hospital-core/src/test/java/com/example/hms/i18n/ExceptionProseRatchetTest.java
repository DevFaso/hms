package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard-coded English in an exception that reaches the client (QA D121, D174,
 * D226). The global handler resolves a message KEY in the request locale and
 * passes a sentence through untouched, so every sentence below is read in
 * English by a French or Spanish user.
 *
 * <p>The tree still holds several hundred of them, so this is a ratchet: the
 * count per file may only go down. A new sentence in a file, or any sentence in
 * a file the baseline does not list, fails the build; throw a key from the
 * {@code messages*.properties} bundles instead. When a file's count drops,
 * lower its line in {@code i18n/exception-prose-baseline.properties} (delete
 * the line at zero) so the gain cannot be spent again.
 *
 * <p>Areas QA reported in round 4 (billing, maternity, users, documents, nurse
 * handoff, lab, stock) were converted with this test and sit at zero, apart
 * from internal failures (PDF or CSV generation, "should be unreachable"
 * assertions) that answer a generic 500 and never show their text.
 */
@DisplayName("Client-facing exception prose may only shrink")
class ExceptionProseRatchetTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final String BASELINE = "/i18n/exception-prose-baseline.properties";

    /** Exception types whose message the global handler writes into the response body. */
    static final Pattern PROSE = Pattern.compile(
        "new\\s+(?:BusinessException|BusinessRuleException|BusinessValidationException|BadRequestException"
            + "|ConflictException|IllegalArgumentException|IllegalStateException|UnauthorizedException"
            + "|ResponseStatusException)\\s*\\(\\s*(?:HttpStatus\\.\\w+\\s*,\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"");

    static boolean isProse(String literal) {
        return literal.indexOf(' ') >= 0;
    }

    static int countProse(String source) {
        int n = 0;
        Matcher m = PROSE.matcher(source);
        while (m.find()) {
            if (isProse(m.group(1))) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Integer> actual() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int n = countProse(Files.readString(p, StandardCharsets.UTF_8));
                if (n > 0) {
                    counts.put(MAIN_JAVA.relativize(p).toString().replace('\\', '/'), n);
                }
            }
        }
        return counts;
    }

    private static Map<String, Integer> baseline() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ExceptionProseRatchetTest.class.getResourceAsStream(BASELINE)) {
            assertThat(in).as(BASELINE).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, Integer> out = new TreeMap<>();
        props.forEach((k, v) -> out.put(k.toString(), Integer.parseInt(v.toString().trim())));
        return out;
    }

    @Test
    @DisplayName("no file holds more client-facing English exception messages than its baseline")
    void proseDoesNotGrow() throws IOException {
        Map<String, Integer> base = baseline();
        List<String> grew = new ArrayList<>();
        actual().forEach((file, n) -> {
            int allowed = base.getOrDefault(file, 0);
            if (n > allowed) {
                grew.add(file + ": " + n + " (allowed " + allowed + ")");
            }
        });
        assertThat(grew)
            .as("Throw a messages*.properties key, not an English sentence")
            .isEmpty();
    }

    @Test
    @DisplayName("the baseline is lowered whenever a file is converted")
    void baselineIsTight() throws IOException {
        Map<String, Integer> now = actual();
        List<String> slack = new ArrayList<>();
        baseline().forEach((file, allowed) -> {
            int n = now.getOrDefault(file, 0);
            if (n < allowed) {
                slack.add(file + "=" + n + " (baseline says " + allowed + ")");
            }
        });
        assertThat(slack)
            .as("Lower these lines in " + BASELINE + " (remove a line at zero)")
            .isEmpty();
    }

    @Test
    @DisplayName("QA D226: the converted messages are really translated, not copied")
    void convertedKeysAreTranslated() throws IOException {
        Map<String, String> en = d226Block("messages.properties");
        Map<String, String> fr = d226Block("messages_fr.properties");
        Map<String, String> es = d226Block("messages_es.properties");
        assertThat(en).hasSizeGreaterThan(100);
        assertThat(fr.keySet()).isEqualTo(en.keySet());
        assertThat(es.keySet()).isEqualTo(en.keySet());
        List<String> copied = new ArrayList<>();
        en.forEach((k, v) -> {
            if (v.equals(fr.get(k)) || v.equals(es.get(k))) {
                copied.add(k);
            }
        });
        assertThat(copied).as("French or Spanish identical to English").isEmpty();
    }

    private static Map<String, String> d226Block(String bundle) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get("src/main/resources", bundle), StandardCharsets.UTF_8);
        Map<String, String> out = new TreeMap<>();
        boolean in = false;
        for (String line : lines) {
            if (line.startsWith("# QA round 4 (D226)")) {
                in = true;
            } else if (in && line.startsWith("#")) {
                break;
            } else if (in && line.contains("=")) {
                out.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("the scan recognises a sentence and ignores a key")
    void scanRecognisesProse() {
        assertThat(countProse("throw new BusinessException(\"Payment exceeds balance.\");")).isEqualTo(1);
        assertThat(countProse("throw new IllegalStateException(\n  \"Unknown status: \" + s);")).isEqualTo(1);
        assertThat(countProse("throw new BusinessException(\"billing.invoice.alreadyPaid\");")).isZero();
        assertThat(countProse("throw new ConflictException(\"patient:users.patient.alreadyRegistered\");")).isZero();
    }
}
