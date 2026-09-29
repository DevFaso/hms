package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four backend bundles agree with each other, and the exception sites that
 * name a key agree with the bundle. The portal has had a strict
 * {@code i18n:parity} gate for a long time; until this test the backend had
 * none, and {@code messages_fr} and {@code messages_es} had drifted 77 and 73
 * keys behind the base - each one falling back to English with no marker, so
 * invisible in any test that resolves a single locale.
 *
 * <p>Both halves guard the same failure: a {@code {0}} that one side has and
 * the other does not. {@code messages_en} once carried {@code patient.notfound},
 * {@code staff.notfound} and {@code hospital.notfound} without the id every
 * other bundle rendered, so an English 404 silently dropped it; and thirty-odd
 * sites threw those keys with no argument at all, so French and Spanish
 * clinicians read a literal {@code {0}}. Neither is visible to a test that
 * resolves a key in one locale.
 */
@DisplayName("Backend message bundle parity")
class MessageBundleParityTest {

    private static final Path RESOURCES = Paths.get("src/main/resources");
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** Exceptions whose keyed sites must name a key the base bundle defines. */
    private static final Set<String> KEYS_MUST_EXIST = Set.of("BusinessException", "ResourceNotFoundException");

    /** MessageFormat argument references: {@code {0}}, {@code {1,number}}, ... */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*+}");

    /** A key definition at the start of a line (the bundles use no continuation lines). */
    private static final Pattern DEFINITION = Pattern.compile("^\\s*([^#!\\s=:][^\\s=:]*)\\s*[=:]");

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");

    private static final Pattern KEYED_EXCEPTION =
        Pattern.compile("new\\s+(ResourceNotFoundException|BusinessException)\\s*\\(");

    /** The names this codebase gives a caught exception it passes on as a cause. */
    private static final Pattern CAUSE = Pattern.compile("e|ex|exc|exception|cause|err|\\w+Exception");

    private static final Pattern KEY_CONSTANT = Pattern.compile(
        "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"(" + KEY.pattern() + ")\"\\s*;");

    @ParameterizedTest(name = "messages{0}.properties")
    @ValueSource(strings = {"_en", "_fr", "_es"})
    @DisplayName("a key renders the same arguments in every locale")
    void placeholdersAgreeWithTheBase(String suffix) throws IOException {
        Properties base = load("");
        Properties bundle = load(suffix);

        List<String> drift = new ArrayList<>();
        for (String key : new TreeSet<>(base.stringPropertyNames())) {
            String translated = bundle.getProperty(key);
            if (translated == null) {
                continue;
            }
            SortedSet<Integer> expected = placeholders(base.getProperty(key));
            SortedSet<Integer> actual = placeholders(translated);
            if (!expected.equals(actual)) {
                drift.add(key + ": base " + expected + ", messages" + suffix + " " + actual);
            }
        }

        assertThat(drift)
            .as("Placeholders that differ from messages.properties in messages%s.properties "
                    + "— a missing {n} silently drops the argument the caller passed:%n%s",
                suffix, String.join(System.lineSeparator(), drift))
            .isEmpty();
    }

    @ParameterizedTest(name = "messages{0}.properties")
    @ValueSource(strings = {"_en", "_fr", "_es"})
    @DisplayName("a locale carries exactly the keys the base bundle carries")
    void everyLocaleCarriesEveryBaseKey(String suffix) throws IOException {
        SortedSet<String> base = new TreeSet<>(load("").stringPropertyNames());
        SortedSet<String> bundle = new TreeSet<>(load(suffix).stringPropertyNames());

        SortedSet<String> missing = new TreeSet<>(base);
        missing.removeAll(bundle);
        SortedSet<String> extra = new TreeSet<>(bundle);
        extra.removeAll(base);

        // A missing key falls back to the base bundle, which is English, so a
        // French or Spanish clinician reads English with no marker at all and no
        // test that resolves the key in one locale notices. An extra key is one
        // the base (and so every other locale) has no text for.
        assertThat(missing)
            .as("Keys in messages.properties missing from messages%s.properties:%n%s",
                suffix, String.join(System.lineSeparator(), missing))
            .isEmpty();
        assertThat(extra)
            .as("Keys in messages%s.properties that messages.properties does not define:%n%s",
                suffix, String.join(System.lineSeparator(), extra))
            .isEmpty();
    }

    @ParameterizedTest(name = "messages{0}.properties")
    @ValueSource(strings = {"", "_en", "_fr", "_es"})
    @DisplayName("no bundle defines a key twice")
    void noDuplicateKeys(String suffix) throws IOException {
        // java.util.Properties keeps the last of two definitions silently, so a
        // duplicate is invisible to every test that goes through it.
        Map<String, Integer> seen = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        List<String> lines = Files.readAllLines(RESOURCES.resolve("messages" + suffix + ".properties"),
            StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = DEFINITION.matcher(lines.get(i));
            if (m.find()) {
                Integer first = seen.putIfAbsent(m.group(1), i + 1);
                if (first != null) {
                    duplicates.add(m.group(1) + " (lines " + first + " and " + (i + 1) + ")");
                }
            }
        }

        assertThat(duplicates)
            .as("Keys defined twice in messages%s.properties:%n%s",
                suffix, String.join(System.lineSeparator(), duplicates))
            .isEmpty();
    }

    @Test
    @DisplayName("every keyed exception passes exactly the arguments its message renders")
    void keyedExceptionSitesPassEveryPlaceholder() throws IOException {
        Properties base = load("");
        List<String> sites = keyedExceptionSites();

        // A floor as well as a ceiling: a scan that matches nothing guards nothing.
        assertThat(sites).as("The scan found no keyed exception sites").hasSizeGreaterThan(100);

        List<String> wrong = new ArrayList<>();
        for (String site : sites) {
            String[] parts = site.split("\\|", -1);
            String key = parts[2];
            int passed = Integer.parseInt(parts[3]);
            String value = base.getProperty(key);
            if (value == null) {
                continue;
            }
            SortedSet<Integer> used = placeholders(value);
            int needed = used.isEmpty() ? 0 : used.last() + 1;
            if (passed != needed) {
                wrong.add(parts[0] + " :: " + key + " renders " + needed + " argument(s), the site passes " + passed);
            }
        }

        assertThat(wrong)
            .as("Keyed exceptions whose arguments disagree with the bundle — too few render a "
                    + "literal {0} to the user, too many are silently dropped:%n%s",
                String.join(System.lineSeparator(), wrong))
            .isEmpty();
    }

    @Test
    @DisplayName("every key a keyed exception throws is in the base bundle")
    void thrownKeysExist() throws IOException {
        Properties base = load("");

        List<String> missing = keyedExceptionSites().stream()
            .map(site -> site.split("\\|", -1))
            .filter(parts -> KEYS_MUST_EXIST.contains(parts[1]))
            .filter(parts -> base.getProperty(parts[2]) == null)
            .map(parts -> parts[0] + " :: " + parts[2])
            .toList();

        // BusinessException passes an unknown key through as raw text, so a
        // key missing from every bundle reaches the client as
        // "prescription.patient.required" — no marker, no failure, no log.
        assertThat(missing)
            .as("Keys thrown by a keyed exception that no bundle defines:%n%s",
                String.join(System.lineSeparator(), missing))
            .isEmpty();
    }

    static Properties load(String suffix) throws IOException {
        Properties bundle = new Properties();
        try (Reader reader = Files.newBufferedReader(
                RESOURCES.resolve("messages" + suffix + ".properties"), StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        return bundle;
    }

    static SortedSet<Integer> placeholders(String value) {
        SortedSet<Integer> indices = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            indices.add(Integer.parseInt(m.group(1)));
        }
        return indices;
    }

    /**
     * {@code file:line|exception|key|argumentCount} for every
     * {@code new ResourceNotFoundException(...)} and {@code new BusinessException(...)}
     * whose first argument is a key literal or a
     * same-file {@code static final String} constant holding one. Sites that pass prose or a
     * computed string are not keyed and are skipped here.
     */
    static List<String> keyedExceptionSites() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // HAPI FHIR has a ResourceNotFoundException of its own, which takes prose.
                if (source.contains("import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;")) {
                    continue;
                }
                Map<String, String> constants = new HashMap<>();
                Matcher c = KEY_CONSTANT.matcher(source);
                while (c.find()) {
                    constants.put(c.group(1), c.group(2));
                }
                Matcher m = KEYED_EXCEPTION.matcher(source);
                while (m.find()) {
                    int close = matchingParen(source, m.end());
                    if (close < 0) {
                        continue;
                    }
                    List<String> args = topLevelArguments(source.substring(m.end(), close));
                    if (args.isEmpty()) {
                        continue;
                    }
                    String key = keyOf(args.get(0), constants);
                    if (key == null) {
                        continue;
                    }
                    int line = 1 + (int) source.substring(0, m.start()).chars().filter(ch -> ch == '\n').count();
                    out.add(file.getFileName() + ":" + line + "|" + m.group(1) + "|" + key + "|" + argumentCount(args));
                }
            }
        }
        return out;
    }

    private static String keyOf(String firstArgument, Map<String, String> constants) {
        String arg = firstArgument.trim();
        if (arg.length() > 2 && arg.startsWith("\"") && arg.endsWith("\"")) {
            String literal = arg.substring(1, arg.length() - 1);
            return KEY.matcher(literal).matches() ? literal : null;
        }
        return constants.get(arg);
    }

    private static int argumentCount(List<String> args) {
        if (args.size() == 2 && args.get(1).trim().startsWith("new Object[]")) {
            String array = args.get(1);
            return topLevelArguments(array.substring(array.indexOf('{') + 1, array.lastIndexOf('}'))).size();
        }
        // A trailing Throwable is the cause, not a message argument: the
        // (String, Throwable) constructor wins overload resolution for it.
        boolean trailingCause = args.size() > 1 && CAUSE.matcher(args.get(args.size() - 1).trim()).matches();
        return args.size() - 1 - (trailingCause ? 1 : 0);
    }

    /** Index of the ')' closing the '(' just before {@code from}, skipping string and char literals. */
    static int matchingParen(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = endOfLiteral(s, i);
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfLiteral(String s, int open) {
        char quote = s.charAt(open);
        for (int i = open + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == quote) {
                return i;
            }
        }
        return s.length();
    }

    static List<String> topLevelArguments(String s) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = endOfLiteral(s, i);
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                args.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isEmpty()) {
            args.add(last);
        }
        return args;
    }
}
