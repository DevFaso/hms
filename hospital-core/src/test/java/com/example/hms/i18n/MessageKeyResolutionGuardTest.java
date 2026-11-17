package com.example.hms.i18n;

import com.example.hms.config.MessageBundles;
import com.example.hms.i18n.MessageKeyScanner.Kind;
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
 * bundle - the default, {@code _en}, {@code _fr} and {@code _es} - and no
 * refusal is handed a message looked up before it is decided.
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
 *       enumerating its inputs in {@link ComputedKeys#REGISTRY}, and every
 *       key the scan cannot resolve, registered as open with the reason it is
 *       safe or the place its keys are checked: a site that is not registered
 *       fails, so a new one must say what it can produce;</li>
 *   <li>that no {@code FieldBusinessException}, {@code BusinessException},
 *       {@code BusinessRuleException} or {@code RequiredFields} check is handed
 *       {@code getMessage(…)} / {@code getLocalizedMessage(…)} in its key
 *       position.</li>
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
        List<Site> literal = sites.stream().filter(s -> s.kind() == Kind.LITERAL).toList();
        // A floor: a scan that matches nothing guards nothing.
        assertThat(literal).as("literal key sites found").hasSizeGreaterThan(3500);

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
    @DisplayName("every computed or unresolved key site is registered with the inputs it can take")
    void everyComputedSiteIsRegistered() {
        Set<String> unregistered = new TreeSet<>();
        for (Site site : sites) {
            if (site.kind() != Kind.LITERAL && !ComputedKeys.REGISTRY.containsKey(site.registryId())) {
                unregistered.add(site.kind() + " " + site.registryId() + "  <-  " + site.where() + " (" + site.sink() + ")");
            }
        }
        assertThat(unregistered)
            .as("Key sites with no entry in ComputedKeys.REGISTRY. Register the inputs a computed key "
                    + "is built from (an enum, a fixed list, the literal arguments of its callers), so the "
                    + "guard can resolve every key it can produce; register a key the scan cannot follow "
                    + "as open, with the reason it is safe or where its keys are checked:%n%s",
                String.join(System.lineSeparator(), unregistered))
            .isEmpty();
    }

    @Test
    @DisplayName("every registry entry is still one site")
    void noStaleRegistryEntries() {
        Set<String> seen = new TreeSet<>();
        List<String> shared = new ArrayList<>();
        for (Site site : sites) {
            if (site.kind() != Kind.LITERAL && !seen.add(site.registryId())) {
                shared.add(site.registryId());
            }
        }
        assertThat(shared).as("Two sites with one registry id").isEmpty();
        Set<String> stale = new TreeSet<>(ComputedKeys.REGISTRY.keySet());
        stale.removeAll(seen);
        assertThat(stale).as("ComputedKeys.REGISTRY entries no site uses any more").isEmpty();
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
    @DisplayName("no refusal is handed a message looked up before it is decided")
    void noRefusalIsHandedAResolvedMessage() throws IOException {
        List<String> eager = MessageKeyScanner.eagerRefusals(MessageKeyScanner.mainSources());
        assertThat(eager)
            .as("Refusals handed getMessage(…) / getLocalizedMessage(…) in their key position: the lookup "
                    + "runs before the refusal is decided, and a missing key is a 500 (D1536). Pass the key "
                    + "and its arguments (BusinessRuleException.ofKey for a BusinessRuleException):%n%s",
                String.join(System.lineSeparator(), eager))
            .isEmpty();
    }

    @Test
    @DisplayName("the eager-refusal scan sees the D1536 shape and its siblings")
    void eagerRefusalScanSeesEveryShape() {
        String fixture = String.join("\n",
            "package com.example.hms.service;",
            "class EagerFixture {",
            "    void a(Dto dto, Locale locale) {",
            "        RequiredFields.check()",
            "            .requireValue(dto.getEmploymentType(), \"employmentType\",",
            "                getLocalizedMessage(\"fix.wrapped\", null, locale))",
            "            .requireValue(dto.getStartDate(), \"startDate\", \"fix.required\")",
            "            .orThrow();",
            "        throw new com.example.hms.exception.FieldBusinessException(\"name\",",
            "            messageSource.getMessage(\"fix.field\", null, locale));",
            "    }",
            "    void b(Locale locale) {",
            "        throw new BusinessException(messageSource.getMessage(\"fix.business\", null, \"x\", locale));",
            "    }",
            "    void c(Locale locale) {",
            "        throw new BusinessRuleException(messageSource.getMessage(\"fix.rule\", null, locale));",
            "    }",
            "    void fine(Locale locale) {",
            "        throw new com.example.hms.exception.FieldBusinessException(\"name\", \"fix.key\",",
            "            DisplayDates.date(day, locale));",
            "    }",
            "}");
        List<String> eager = MessageKeyScanner.eagerRefusals(Map.of("com/example/hms/service/EagerFixture.java", fixture));
        assertThat(eager).hasSize(4)
            .anyMatch(s -> s.contains("requireValue") && s.contains("fix.wrapped"))
            .anyMatch(s -> s.contains("FieldBusinessException") && s.contains("fix.field"))
            .anyMatch(s -> s.contains("BusinessException") && s.contains("fix.business"))
            .anyMatch(s -> s.contains("BusinessRuleException") && s.contains("fix.rule"));
    }

    @Test
    void scannerFollowsDeepCrossClassWrappers() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/Caller.java", "package fixture; class Caller { void call() { W0.text(\"fix.deep\"); } }");
        for (int i = 0; i < 12; i++) {
            String call = i == 11 ? "MessageUtil.resolveOrRaw(key)" : "W" + (i + 1) + ".text(key)";
            sources.put("fixture/W" + i + ".java", "package fixture; class W" + i
                + " { static String text(String key) { return " + call + "; } }");
        }

        assertThat(MessageKeyScanner.scan(sources))
            .anyMatch(site -> site.kind() == Kind.LITERAL && "fix.deep".equals(site.key()));
    }

    @Test
    @DisplayName("the scanner sees every shape a key reaches the message source in")
    void scannerSeesEveryShape() {
        // The floor for the scans above: each shape is proven on a fixture, the D1536 one first.
        String service = String.join("\n",
            "package com.example.hms.service;",
            "import com.example.hms.mapper.LabelsFixture;",
            "class FixtureServiceImpl {",
            "    private static final String ALERT_PREFIX = \"fix.alert.\";",
            "    private static final String KEY = \"fix.constant\";",
            "    private static final String BUILT = ALERT_PREFIX + \"built\";",
            "    void d1536(Dto dto, Locale locale) {",
            "        com.example.hms.exception.RequiredFields.check()",
            "            .requireValue(dto.getEmploymentType(), \"employmentType\",",
            "                getLocalizedMessage(\"fix.wrapped\", null, locale))",
            "            .requireValue(dto.getStartDate(), \"startDate\", \"fix.required\")",
            "            .orThrow();",
            "    }",
            "    void shapes(String role, String code, LabelsFixture t) {",
            "        throw new com.example.hms.exception.FieldBusinessException(\"specialization\", \"fix.field\");",
            "    }",
            "    void more(String role, String code, LabelsFixture t, Locale locale, boolean flag) {",
            "        text(\"fix.nested\");",
            "        requireEnd(\"fix.thrown\");",
            "        t.get(\"fix.typed\");",
            "        LabelsFixture.label(\"fix.static\", 1);",
            "        LabelsFixture.label(1, \"fix.second\");",
            "        MessageUtil.resolve(locale, \"fix.resolve\");",
            "        MessageUtil.resolveOrRaw(LabelsFixture.SHARED_KEY);",
            "        MessageUtil.resolveOrRaw(BUILT);",
            "        String local = \"fix.local\";",
            "        MessageUtil.resolveOrRaw(local);",
            "        MessageUtil.resolveOrRaw(flag ? \"fix.yes\" : \"fix.no\");",
            "        MessageUtil.resolveOrRaw(pick(flag));",
            "        messageSource.getMessage(KEY, null, locale);",
            "        messageSource.getMessage(\"fix.role.\" + role, null, locale);",
            "        String held = \"fix.held.\" + code;",
            "        String alert = ALERT_PREFIX + code;",
            "        MessageUtil.resolveOrRaw(t.unknownKey());",
            "        outbox.getMessage(code);",
            "        photoDir.resolve(code);",
            "        throw new IllegalStateException(\"Failed to open \" + code + \".ndjson\");",
            "    }",
            "    private static String pick(boolean flag) {",
            "        return flag ? \"fix.pick.a\" : \"fix.pick.b\";",
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
            "package com.example.hms.mapper;",
            "public final class LabelsFixture {",
            "    public static final String SHARED_KEY = \"fix.shared\";",
            "    String get(String key, Object... args) {",
            "        return messages.getMessage(key, args, locale);",
            "    }",
            "    static String label(String key, int n) {",
            "        return MessageUtil.resolveOrRaw(key, n);",
            "    }",
            "    static String label(int n, String key) {",
            "        return MessageUtil.resolveOrRaw(key, n);",
            "    }",
            "}");
        // A second class with the simple name of the first: both are read, under their paths.
        String twin = String.join("\n",
            "package com.example.hms.other;",
            "class FixtureServiceImpl {",
            "    void twin() {",
            "        MessageUtil.resolveOrRaw(\"fix.twin\");",
            "    }",
            "}");
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/example/hms/service/FixtureServiceImpl.java", service);
        sources.put("com/example/hms/mapper/LabelsFixture.java", labels);
        sources.put("com/example/hms/other/FixtureServiceImpl.java", twin);
        List<Site> found = MessageKeyScanner.scan(sources);

        Set<String> literal = new TreeSet<>();
        Set<String> computed = new TreeSet<>();
        Set<String> unresolved = new TreeSet<>();
        for (Site s : found) {
            switch (s.kind()) {
                case LITERAL -> literal.add(s.key());
                case COMPUTED -> computed.add(s.fragment());
                case UNRESOLVED -> unresolved.add(s.expression());
            }
        }
        assertThat(literal).containsExactlyInAnyOrder(
            "fix.wrapped", "fix.required", "fix.field", "fix.nested", "fix.thrown", "fix.typed", "fix.static",
            "fix.second", "fix.resolve", "fix.shared", "fix.alert.built", "fix.local", "fix.yes", "fix.no",
            "fix.pick.a", "fix.pick.b", "fix.constant", "fix.inner", "fix.twin");
        assertThat(computed).containsExactlyInAnyOrder("fix.role.", "fix.held.", "fix.alert.");
        assertThat(unresolved).containsExactly("t.unknownKey()");
    }

    @Test
    @DisplayName("a very long qualified name is read without overflowing the stack (Rex on #1096)")
    void aVeryLongQualifiedNameIsRead() {
        // The call regex repeated one group per qualifier segment: 20,000 segments overflowed the
        // stack (StackOverflowError) and aborted the whole scan.
        String chain = "segment.".repeat(20_000);
        String source = String.join("\n",
            "package com.example.hms.service;",
            "class LongChainFixture {",
            "    void a(Locale locale) {",
            "        Object x = " + chain + "value();",
            "        MessageUtil.resolveOrRaw(\"fix.after.chain\");",
            "        throw new " + chain + "BusinessException(messageSource.getMessage(\"fix.eager\", null, locale));",
            "    }",
            "}");
        Map<String, String> sources = Map.of("com/example/hms/service/LongChainFixture.java", source);

        Set<String> literal = new TreeSet<>();
        for (Site s : MessageKeyScanner.scan(sources)) {
            if (s.kind() == Kind.LITERAL) {
                literal.add(s.key());
            }
        }
        assertThat(literal).contains("fix.after.chain", "fix.eager");
        assertThat(MessageKeyScanner.eagerRefusals(sources)).singleElement().asString().contains("fix.eager");
    }

    @Test
    @DisplayName("the call reader keeps new, the qualifier and the name apart")
    void theCallReaderSplitsACall() {
        MessageKeyScanner.CallMatcher m = new MessageKeyScanner.CallMatcher(
            "x = new com.example.hms.exception.FieldBusinessException(a); foo().bar(b); this . messageSource . getMessage(c);");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isNotNull();
        assertThat(m.group(2)).isEqualTo("com.example.hms.exception.");
        assertThat(m.group(3)).isEqualTo("FieldBusinessException");
        assertThat(m.find()).isTrue();
        assertThat(m.group(3)).isEqualTo("foo");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isNull();
        assertThat(m.group(3)).isEqualTo("bar");
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).isEqualTo("this . messageSource . ");
        assertThat(m.group(3)).isEqualTo("getMessage");
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
