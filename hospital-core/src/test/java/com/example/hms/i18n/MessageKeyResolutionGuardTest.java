package com.example.hms.i18n;

import com.example.hms.config.MessageBundles;
import com.example.hms.i18n.MessageKeyScanner.Site;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1536 (round 40): every message key the code resolves is in every
 * bundle - the default, {@code _en}, {@code _fr} and {@code _es}.
 *
 * <p>Saving any nurse's staff record answered 500: the service looked four
 * keys up before checking the fields, and the keys were in no bundle, so
 * {@code messageSource.getMessage} threw {@code NoSuchMessageException}. The
 * existing key guard ({@link MessageBundleParityTest#everyKeyTheCodeResolvesExists})
 * never read a key passed to a service's own wrapper. {@link MessageKeyScanner}
 * reads every sink, wrappers included; this test holds:
 * <ul>
 *   <li>every literal key against every bundle;</li>
 *   <li>every computed key (per role, per field, per enum value) by
 *       enumerating its inputs in {@link ComputedKeys#REGISTRY}: a computed
 *       site that is not registered fails, so a new one must say what it can
 *       produce.</li>
 * </ul>
 */
@DisplayName("Every message key the code resolves is in every bundle (QA D1536)")
class MessageKeyResolutionGuardTest {

    private static List<Site> sites;
    private static Map<String, Properties> bundles;

    @BeforeAll
    static void scan() throws IOException {
        sites = MessageKeyScanner.scanTree();
        bundles = new LinkedHashMap<>();
        for (String suffix : MessageBundles.SUFFIXES) {
            bundles.put(suffix.isEmpty() ? "default" : suffix.substring(1), MessageBundleParityTest.load(suffix));
        }
    }

    @Test
    @DisplayName("every literal key the code resolves is in every bundle")
    void everyLiteralKeyIsInEveryBundle() {
        List<Site> literal = sites.stream().filter(s -> !s.computed()).toList();
        // A floor: a scan that matches nothing guards nothing.
        assertThat(literal).as("literal key sites found").hasSizeGreaterThan(2500);

        List<String> missing = new ArrayList<>();
        for (Site site : literal) {
            List<String> without = bundlesWithout(site.key());
            if (!without.isEmpty()) {
                missing.add(site.where() + " " + site.key() + " (" + site.sink() + ") missing from " + without);
            }
        }
        assertThat(missing)
            .as("Keys the code resolves that a bundle does not carry. A missing key is a 500 "
                    + "(NoSuchMessageException) or the raw key on screen:%n%s",
                String.join(System.lineSeparator(), missing))
            .isEmpty();
    }

    @Test
    @DisplayName("every computed key site is registered with the inputs it can take")
    void everyComputedSiteIsRegistered() {
        Set<String> unregistered = new TreeSet<>();
        for (Site site : sites) {
            if (site.computed() && !ComputedKeys.REGISTRY.containsKey(site.registryId())) {
                unregistered.add(site.registryId() + "  <-  " + site.where() + " " + site.expression());
            }
        }
        assertThat(unregistered)
            .as("Computed message keys with no entry in ComputedKeys.REGISTRY. Register the inputs the "
                    + "key is built from (an enum, a fixed list, the literal arguments of its callers), "
                    + "so the guard can resolve every key it can produce:%n%s",
                String.join(System.lineSeparator(), unregistered))
            .isEmpty();
    }

    @Test
    @DisplayName("every registry entry still matches a computed site")
    void noStaleRegistryEntries() {
        Set<String> seen = new TreeSet<>();
        sites.stream().filter(Site::computed).forEach(s -> seen.add(s.registryId()));
        Set<String> stale = new TreeSet<>(ComputedKeys.REGISTRY.keySet());
        stale.removeAll(seen);
        assertThat(stale).as("ComputedKeys.REGISTRY entries no computed site uses any more").isEmpty();
    }

    @Test
    @DisplayName("every key a computed site can produce is in every bundle")
    void everyEnumeratedKeyIsInEveryBundle() throws IOException {
        List<String> missing = new ArrayList<>();
        int enumerated = 0;
        for (Map.Entry<String, ComputedKeys.Domain> entry : ComputedKeys.REGISTRY.entrySet()) {
            for (String key : entry.getValue().keys()) {
                enumerated++;
                List<String> without = bundlesWithout(key);
                if (!without.isEmpty()) {
                    missing.add(entry.getKey() + " -> " + key + " missing from " + without);
                }
            }
        }
        assertThat(enumerated).as("computed keys enumerated").isGreaterThan(300);
        assertThat(missing)
            .as("Keys a computed site can produce that a bundle does not carry:%n%s",
                String.join(System.lineSeparator(), missing))
            .isEmpty();
    }

    @Test
    @DisplayName("the scanner sees every shape a key reaches the message source in")
    void scannerSeesEveryShape() {
        // The floor for the scans above: each shape is proven on a fixture, the D1536 one first.
        String service = String.join("\n",
            "class FixtureServiceImpl {",
            "    private static final String ALERT_PREFIX = \"fix.alert.\";",
            "    private static final String KEY = \"fix.constant\";",
            "    void d1536(Dto dto, Locale locale) {",
            "        com.example.hms.exception.RequiredFields.check()",
            "            .requireValue(dto.getEmploymentType(), \"employmentType\",",
            "                getLocalizedMessage(\"fix.wrapped\", null, locale))",
            "            .requireValue(dto.getStartDate(), \"startDate\", \"fix.required\")",
            "            .orThrow();",
            "    }",
            "    void shapes(String role, String code, PdfLabelsFixture t) {",
            "        throw new com.example.hms.exception.FieldBusinessException(\"specialization\", \"fix.field\");",
            "    }",
            "    void more(String role, String code, PdfLabelsFixture t, Locale locale) {",
            "        text(\"fix.nested\");",
            "        requireEnd(\"fix.thrown\");",
            "        t.get(\"fix.typed\");",
            "        MessageUtil.resolve(locale, \"fix.resolve\");",
            "        messageSource.getMessage(KEY, null, locale);",
            "        messageSource.getMessage(\"fix.role.\" + role, null, locale);",
            "        String held = \"fix.held.\" + code;",
            "        String alert = ALERT_PREFIX + code;",
            "        throw new IllegalStateException(\"Failed to open \" + code + \".ndjson\");",
            "    }",
            "    Object conflict(Locale locale) {",
            "        return new ConflictException(\"name:\" + messageSource.getMessage(\"fix.inner\", null, locale));",
            "    }",
            "    private static void requireEnd(String messageKey) {",
            "        throw new com.example.hms.exception.FieldBusinessException(\"endDate\", messageKey);",
            "    }",
            "    private String text(String key) {",
            "        return getLocalizedMessage(key, null, Locale.FRENCH);",
            "    }",
            "    private String getLocalizedMessage(String code, Object[] args, Locale locale) {",
            "        return messageSource.getMessage(code, args, locale);",
            "    }",
            "}");
        String labels = String.join("\n",
            "final class PdfLabelsFixture {",
            "    String get(String key, Object... args) {",
            "        return messages.getMessage(key, args, locale);",
            "    }",
            "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("FixtureServiceImpl.java", service);
        sources.put("PdfLabelsFixture.java", labels);
        List<Site> found = MessageKeyScanner.scan(sources);

        Set<String> literal = new TreeSet<>();
        Set<String> computed = new TreeSet<>();
        for (Site s : found) {
            if (s.computed()) {
                computed.add(s.fragment());
            } else {
                literal.add(s.key());
            }
        }
        assertThat(literal).containsExactlyInAnyOrder(
            "fix.wrapped", "fix.required", "fix.field", "fix.nested", "fix.typed", "fix.resolve",
            "fix.constant", "fix.inner", "fix.thrown");
        assertThat(computed).containsExactlyInAnyOrder("fix.role.", "fix.held.", "fix.alert.");
    }

    private static List<String> bundlesWithout(String key) {
        List<String> without = new ArrayList<>();
        bundles.forEach((name, bundle) -> {
            if (bundle.getProperty(key) == null) {
                without.add(name);
            }
        });
        return without;
    }
}
