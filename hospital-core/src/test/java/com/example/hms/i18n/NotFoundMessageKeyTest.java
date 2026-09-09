package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
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

    /** A {@code '} that is not part of a doubled pair. */
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    private static ReloadableResourceBundleMessageSource messageSource() {
        // Mirrors LocaleConfig: same basenames, same encoding, no fallback to
        // the system locale — a test that resolved through the JVM's default
        // would pass on a developer machine and fail in CI.
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages", "classpath:messages_en",
            "classpath:messages_fr", "classpath:messages_es");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        source.setAlwaysUseMessageFormat(true);
        return source;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"en", "fr", "es"})
    @DisplayName("every converted key resolves, and renders the id rather than a literal placeholder")
    void keysResolveInEveryLocale(String language) {
        ReloadableResourceBundleMessageSource source = messageSource();
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
    @DisplayName("no bundle carries a lone apostrophe, which MessageFormat eats")
    void bundlesAreMessageFormatSafe(String suffix) throws IOException {
        // LocaleConfig sets alwaysUseMessageFormat(true), so EVERY message is a
        // MessageFormat pattern and a lone ' opens a quoted run: the apostrophe
        // disappears and any {0} after it stops substituting. It must be ''.
        // Missed once already — the base bundle was fixed while messages_en,
        // which shadows it for the default locale, kept the broken copy.
        Path bundle = Paths.get("src/main/resources/messages" + suffix + ".properties");
        List<String> offenders = Files.readAllLines(bundle, StandardCharsets.UTF_8).stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
            .filter(line -> LONE_APOSTROPHE.matcher(line.substring(line.indexOf('=') + 1)).find())
            .toList();

        assertThat(offenders)
            .as("Lone apostrophes in %s — double them (''):%n%s",
                bundle, String.join(System.lineSeparator(), offenders))
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
