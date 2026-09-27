package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;

import com.example.hms.config.LocaleConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ResourceNotFoundException}'s first argument is a message KEY, not a
 * sentence. Passing prose makes {@code MessageUtil.resolve} fail its lookup and
 * fall back to {@code "[Missing translation] " + key}, so the clinician reads
 * the marker followed by untranslated English — in a French deployment, on
 * every 404.
 *
 * <p>319 of the ~1000 call sites did that. This pins the repair: the keys the
 * converted sites now throw must resolve in every locale, and the prose surface
 * must not grow back.
 */
@DisplayName("Not-found message keys")
class NotFoundMessageKeyTest {

    /** The keys the conversion routes through. Each takes the id as {0}. */
    private static final List<String> CONVERTED_KEYS = List.of(
        "patient.notFound",
        "hospital.notFound",
        "staff.notFound",
        "user.notFound",
        "department.notFound",
        "organization.notFound",
        // The lower-case twins. messages_en carried these three without {0}
        // while base, FR and ES rendered it, so an English 404 dropped the id.
        "patient.notfound",
        "staff.notfound",
        "hospital.notfound");

    /**
     * Prose-form {@code ResourceNotFoundException} constructions still in the
     * tree, per file. 319 literal ones before the first conversion, 219 after
     * it, and ~170 more the old line scan never saw (a prose constant, or a
     * message already resolved by {@code messageSource.getMessage} and then
     * looked up a second time as a key). What is left is owned by another
     * change in flight: both files are reworked by PR 4's upload-limit and
     * patient-education fixes, so they are converted there rather than edited
     * twice. Lower a count, never raise one; a file not listed must have none.
     */
    private static final Map<String, Integer> PROSE_RESIDUE = Map.of(
        "FileUploadService.java", 2,
        "PatientEducationServiceImpl.java", 24);

    private static final Pattern STRING_CONSTANT =
        Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    /** A message resolved before it reaches the constructor, which then resolves it again. */
    private static final Pattern PRE_RESOLVED =
        Pattern.compile("(?:this\\.)?(?:messageSource\\.getMessage|getLocalizedMessage|getMessage|message)\\s*\\(.*", Pattern.DOTALL);

    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");

    private static final Pattern CONSTRUCTION = Pattern.compile("new\\s+ResourceNotFoundException\\s*\\(");

    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /**
     * A maximal run of apostrophes. MessageFormat reads a doubled pair as one
     * literal quote, so an ODD-length run leaves one unpaired — and that one
     * opens a quoted section which eats the rest of the pattern, {@code {0}}
     * included. Matching runs rather than single characters is what catches a
     * run of three, which a naive lookaround pair passes.
     */
    private static final Pattern APOSTROPHE_RUN = Pattern.compile("'+");

    private static MessageSource messageSource() {
        // The production bean itself, not a replica. A copy carries its own
        // alwaysUseMessageFormat(true) — the setting that makes a lone
        // apostrophe dangerous — so flipping it in LocaleConfig would leave
        // these tests green while every clinician saw the broken rendering.
        return new LocaleConfig().messageSource();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"en", "fr", "es"})
    @DisplayName("every converted key resolves, and renders the id rather than a literal placeholder")
    void keysResolveInEveryLocale(String language) {
        MessageSource source = messageSource();
        Locale locale = Locale.of(language);

        for (String key : CONVERTED_KEYS) {
            String rendered = source.getMessage(key, new Object[] {"THE-ID"}, locale);

            // Asserting the argument landed, not merely that a string came
            // back: a bundle carrying the key with no {0} would resolve fine
            // and silently drop the id the caller passed.
            assertThat(rendered)
                .as("%s in %s", key, language)
                .contains("THE-ID")
                .doesNotContain("{0}")
                .doesNotContain("[Missing translation]")
                // U+FFFD means the bundle was decoded with the wrong charset at
                // some point and the accent is gone for good. Caught exactly
                // this on hospital.notFound in fr, copied from a corrupted
                // neighbour — a value that satisfied every other assertion here
                // while rendering "H�pital" to the clinician.
                .doesNotContain("�");
        }
    }

    @ParameterizedTest(name = "messages{0}.properties")
    @ValueSource(strings = {"", "_en", "_fr", "_es"})
    @DisplayName("no bundle value carries an unpaired apostrophe, which MessageFormat eats")
    void bundlesAreMessageFormatSafe(String suffix) throws IOException {
        Properties bundle = load(suffix);

        List<String> offenders = bundle.stringPropertyNames().stream()
            .filter(key -> hasUnpairedApostrophe(bundle.getProperty(key)))
            .sorted()
            .toList();

        assertThat(offenders)
            .as("Unpaired apostrophes in messages%s.properties — double them:%n%s",
                suffix, String.join(System.lineSeparator(), offenders))
            .isEmpty();
    }

    @ParameterizedTest(name = "messages{0}.properties")
    @ValueSource(strings = {"", "_en", "_fr", "_es"})
    @DisplayName("no bundle value carries mojibake")
    void bundleMojibakeDoesNotSpread(String suffix) throws IOException {
        // U+FFFD means the file was decoded with the wrong charset and the
        // accent is gone for good — no runtime setting recovers it. This was a
        // ratchet (FR 19, ES 27) until both reached zero: FR was retranslated
        // from the English source on 2026-09-13; ES was repaired word by word,
        // each replacement glyph standing in a word that admits exactly one
        // accented spelling ("n<U+FFFD>mero" can only be "número"), and the
        // Spanish-only keys no code reads were removed rather than repaired.
        Properties bundle = load(suffix);

        List<String> corrupted = bundle.stringPropertyNames().stream()
            .filter(key -> bundle.getProperty(key).indexOf('�') >= 0)
            .sorted()
            .toList();

        assertThat(corrupted)
            .as("New mojibake in messages%s.properties — copy the value from a "
                    + "clean source, never from a corrupted neighbour:%n%s",
                suffix, String.join(System.lineSeparator(), corrupted))
            .isEmpty();
    }

    @Test
    @DisplayName("an apostrophe survives rendering for the default locale")
    void apostropheSurvivesRendering() {
        // The end-to-end version of the rule above, on the key that was broken:
        // the bundle-level check would pass on a file nobody resolves against.
        String rendered = messageSource().getMessage(
            "schedule.staff.permissionDenied", new Object[] {}, Locale.ENGLISH);

        assertThat(rendered).contains("member's");
    }

    @Test
    @DisplayName("the prose-as-key surface has not grown back")
    void proseSurfaceHasNotGrown() throws IOException {
        Map<String, List<String>> prose = proseConstructions();

        List<String> overBudget = prose.entrySet().stream()
            .filter(e -> e.getValue().size() > PROSE_RESIDUE.getOrDefault(e.getKey(), 0))
            .flatMap(e -> e.getValue().stream())
            .sorted()
            .toList();

        assertThat(overBudget)
            .as("New prose-as-message-key constructions were added:%n%s%n%n"
                    + "ResourceNotFoundException's first argument is a message key. "
                    + "Passing a sentence - a literal, a String constant holding one, or "
                    + "a message already resolved by messageSource - renders "
                    + "'[Missing translation] <sentence>' to the clinician and is never "
                    + "translated. Use a key from messages.properties and pass the id "
                    + "as an argument.",
                String.join("\n", overBudget.stream().limit(20).toList()))
            .isEmpty();
    }

    @Test
    @DisplayName("the prose detector sees every shape prose has taken")
    void proseDetectorRecognisesEveryShape() {
        // The floor for the scan above: its residue may reach zero, so the
        // detector is proven on a fixture instead of on the tree's own count.
        String fixture = String.join("\n",
            "class Fixture {",
            "    private static final String GONE = \"Ward not found: \";",
            "    private static final String KEY = \"ward.notFound\";",
            "    void a() { throw new ResourceNotFoundException(\"Ward not found\"); }",
            "    void b() { throw new ResourceNotFoundException(",
            "        \"Ward not found: \" + id); }",
            "    void c() { throw new ResourceNotFoundException(GONE + id); }",
            "    void d() { throw new ResourceNotFoundException(",
            "        messageSource.getMessage(\"ward.notFound\", new Object[]{id}, locale)); }",
            "    void e() { throw new ResourceNotFoundException(getLocalizedMessage(KEY, null, locale)); }",
            "    void f() { throw new ResourceNotFoundException(KEY, id); }",
            "    void g() { throw new ResourceNotFoundException(\"ward.notFound\", id); }",
            "    void h() { throw new ResourceNotFoundException(messageKey, id); }",
            "}");

        assertThat(proseIn("Fixture.java", fixture))
            .as("a, b, c, d and e are prose; f, g and h are keys")
            .hasSize(5);
    }

    private static Properties load(String suffix) throws IOException {
        // java.util.Properties, not a hand-rolled split: it is the parser Spring
        // uses, so it agrees about ":" and " " separators, "!" comments,
        // backslash continuations and duplicate keys — each of which a line
        // scanner silently skips.
        Properties bundle = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Paths.get("src/main/resources/messages" + suffix + ".properties"),
                StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        return bundle;
    }

    private static boolean hasUnpairedApostrophe(String value) {
        Matcher run = APOSTROPHE_RUN.matcher(value);
        while (run.find()) {
            if (run.group().length() % 2 != 0) {
                return true;
            }
        }
        return false;
    }

    /** Every prose-form construction in the main tree, grouped by file name. */
    private static Map<String, List<String>> proseConstructions() throws IOException {
        Map<String, List<String>> byFile = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // HAPI FHIR has a ResourceNotFoundException of its own, which takes prose.
                if (source.contains("import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;")) {
                    continue;
                }
                String name = file.getFileName().toString();
                List<String> prose = proseIn(name, source);
                if (!prose.isEmpty()) {
                    byFile.put(name, prose);
                }
            }
        }
        return byFile;
    }

    /**
     * The prose-form constructions in one source file. The first argument is
     * prose when it is anything but a key: a string literal that is not
     * key-shaped (or is concatenated), a same-file String constant whose value
     * is not key-shaped, or a call that has already resolved a message. A
     * variable or a key constant passes; the bundle tests hold those.
     */
    private static List<String> proseIn(String name, String source) {
        Map<String, String> constants = new HashMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        List<String> prose = new ArrayList<>();
        Matcher m = CONSTRUCTION.matcher(source);
        while (m.find()) {
            int close = MessageBundleParityTest.matchingParen(source, m.end());
            if (close < 0) {
                continue;
            }
            List<String> args = MessageBundleParityTest.topLevelArguments(source.substring(m.end(), close));
            if (!args.isEmpty() && isProse(args.get(0), constants)) {
                int line = 1 + (int) source.substring(0, m.start()).chars().filter(ch -> ch == '\n').count();
                prose.add(name + ":" + line + " :: " + args.get(0).replaceAll("\\s+", " "));
            }
        }
        return prose;
    }

    private static boolean isProse(String firstArgument, Map<String, String> constants) {
        if (firstArgument.startsWith("\"")) {
            boolean singleLiteral = firstArgument.endsWith("\"")
                && firstArgument.indexOf('"', 1) == firstArgument.length() - 1;
            return !(singleLiteral
                && MESSAGE_KEY.matcher(firstArgument.substring(1, firstArgument.length() - 1)).matches());
        }
        if (PRE_RESOLVED.matcher(firstArgument).matches()) {
            return true;
        }
        String head = firstArgument.split("[\\s+]", 2)[0];
        String value = constants.get(head);
        return value != null && (!MESSAGE_KEY.matcher(value).matches() || !head.equals(firstArgument));
    }
}
