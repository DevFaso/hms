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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
        "organization.notFound");

    /**
     * Prose-form {@code ResourceNotFoundException} constructions still in the
     * tree. 319 before the conversion; the remaining ones pass no argument, so
     * each needs an identifier found in its own scope rather than a rewrite
     * rule. Lowering this number is the point; raising it is the regression.
     */
    private static final int PROSE_CALL_BUDGET = 219;

    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** Keys already carrying U+FFFD when this guard was added. Lower it, never raise it. */
    private static final Map<String, Integer> MOJIBAKE_BUDGET =
        Map.of("", 0, "_en", 0, "_fr", 19, "_es", 27);

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
    @DisplayName("bundle mojibake does not spread")
    void bundleMojibakeDoesNotSpread(String suffix) throws IOException {
        // U+FFFD means the file was decoded with the wrong charset and the
        // accent is gone for good — no runtime setting recovers it. These
        // counts are damage that predates the guard: French and Spanish
        // clinicians read a replacement glyph on those keys today. Ratcheted
        // rather than asserted at zero because repairing them needs a native
        // speaker per string, not a find-and-replace. It must not grow.
        Properties bundle = load(suffix);

        List<String> corrupted = bundle.stringPropertyNames().stream()
            .filter(key -> bundle.getProperty(key).indexOf('�') >= 0)
            .sorted()
            .toList();

        assertThat(corrupted)
            .as("New mojibake in messages%s.properties — copy the value from a "
                    + "clean source, never from a corrupted neighbour:%n%s",
                suffix, String.join(System.lineSeparator(), corrupted))
            .hasSizeLessThanOrEqualTo(MOJIBAKE_BUDGET.get(suffix));
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
        List<String> prose = proseConstructions();

        // A floor as well as a ceiling: if the scan ever stops matching, the
        // count collapses to zero and this guards nothing while still passing.
        assertThat(prose)
            .as("The scan matched nothing — the detection is broken, not the surface")
            .isNotEmpty()
            .as("New prose-as-message-key constructions were added:%n%s%n%n"
                    + "ResourceNotFoundException's first argument is a message key. "
                    + "Passing a sentence renders '[Missing translation] <sentence>' "
                    + "to the clinician and is never translated. Use a key from "
                    + "messages.properties and pass the id as an argument.",
                String.join("\n", prose.stream().limit(20).toList()))
            .hasSizeLessThanOrEqualTo(PROSE_CALL_BUDGET);
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

    /** {@code new ResourceNotFoundException("some sentence"...)} — prose, not a key. */
    private static List<String> proseConstructions() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(NotFoundMessageKeyTest::proseIn)
                .sorted()
                .toList();
        }
    }

    private static Stream<String> proseIn(Path file) {
        final String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        String name = file.getFileName().toString();
        return source.lines()
            .map(String::trim)
            .filter(line -> line.contains("new ResourceNotFoundException(\""))
            .filter(NotFoundMessageKeyTest::firstArgumentIsProse)
            .map(line -> name + " :: " + line);
    }

    /**
     * A key looks like {@code patient.notFound}; prose contains a space. The
     * space is what separates the two, because a key never carries one.
     */
    private static boolean firstArgumentIsProse(String line) {
        int open = line.indexOf("new ResourceNotFoundException(\"");
        int start = open + "new ResourceNotFoundException(\"".length();
        int close = line.indexOf('"', start);
        return close > start && line.substring(start, close).contains(" ");
    }
}
