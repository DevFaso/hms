package com.example.hms.architecture;

import com.example.hms.service.clinicalrules.ClinicalRuleRegistry;
import com.example.hms.service.clinicalrules.ClinicalRuleSet;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner direction 2026-11-21 (V356): every rule that is « provisoire » in code is a rule set a hospital's approver
 * can sign off, and every rule set a hospital can sign off is enforced by code that says so.
 *
 * <p>The class this guards: a provisional rule nobody can sign (built, marked PROVISIONAL, absent from the
 * « Règles cliniques et validation » screen and the sign-off pack), and its mirror, a registry entry whose code
 * moved or was renamed so the sign-off no longer points at anything. Four checks:
 * <ul>
 *   <li>every {@link ClinicalRuleSetKey} has exactly one complete registry entry (a source, a version, at least one
 *       enforcing class) and its title, description and approver role in the EN, FR and ES bundles;</li>
 *   <li>registry → code: every class an entry names exists and carries {@link ProvisionalRule} with that key;</li>
 *   <li>code → registry: every class carrying {@link ProvisionalRule} is named by the entry of each of its keys;</li>
 *   <li>the existing markers: a file that says PROVISIONAL (the codebase's marker for an unapproved rule), or prints
 *       « provisoire », carries the annotation itself, or is listed below as a mention of the class that does
 *       (a DTO, an enum or a caller that only names the rule), or as no rule at all, with the reason.</li>
 * </ul>
 */
@DisplayName("Every provisional rule in code has a sign-off entry, and every entry has its code (V356)")
class ClinicalRuleRegistryGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern ANNOTATION = Pattern.compile("@ProvisionalRule\\s*\\(([^)]*)\\)");
    private static final Pattern KEY = Pattern.compile("ClinicalRuleSetKey\\.(\\w+)");
    /** The codebase's marker for a rule awaiting its approver ("PROVISIONAL (user decision …)", "PROVISIONAL_DEFAULT…"). */
    private static final Pattern PROVISIONAL_MARKER = Pattern.compile("\\bPROVISIONAL(?:_\\w+)?\\b");
    /** A string literal that prints « provisoire » to a user. */
    private static final Pattern PROVISOIRE_LITERAL = Pattern.compile("\"[^\"\\n]*provisoire[^\"\\n]*\"");

    /**
     * Files that say PROVISIONAL only to name a rule enforced elsewhere: the file, and the class (named from
     * com.example.hms) that enforces the rule and carries the annotation.
     */
    private static final Map<String, String> MENTIONS = Map.of(
        "enums/MaternalConcern.java", "service.vitals.MaternalEarlyWarningRules",
        "enums/OrganDamageSymptom.java", "service.vitals.SevereHypertension",
        "payload/dto/NewbornVitalAlertDTO.java", "service.maternity.NewbornAlertRules",
        "payload/dto/PatientVitalSignResponseDTO.java", "service.vitals.MaternalEarlyWarningRules",
        "service/LabTestDefinitionServiceImpl.java", "service.recordaccess.RecommendedLabSensitivity",
        "service/vitals/VitalSignJudgement.java", "service.vitals.SevereHypertension");

    /** Files where the word is not a rule marker at all, with the reason. */
    private static final Map<String, String> NOT_A_RULE = Map.of(
        "enums/AllergyVerificationStatus.java", "PROVISIONAL is a clinical verification status of an allergy, not a rule",
        "service/clinicalrules/ProvisionalRule.java", "the annotation itself",
        "service/clinicalrules/ClinicalRuleRegistry.java", "the registry itself");

    @Test
    @DisplayName("every rule set key has exactly one complete registry entry")
    void everyKeyHasOneCompleteEntry() {
        List<ClinicalRuleSetKey> keys = ClinicalRuleRegistry.all().stream().map(ClinicalRuleSet::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(EnumSet.copyOf(keys)).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ClinicalRuleSetKey.class));

        org.springframework.context.MessageSource bundles = com.example.hms.i18n.TestMessageSources.bundles();
        List<String> incomplete = new ArrayList<>();
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            for (String key : List.of(rule.titleKey(), rule.descriptionKey(), rule.approverKey())) {
                Map<String, String> byLanguage = new TreeMap<>();
                for (String language : List.of("en", "fr", "es")) {
                    String text = bundles.getMessage(key, null, null, java.util.Locale.forLanguageTag(language));
                    if (blank(text)) {
                        incomplete.add(rule.key() + ": " + key + " has no " + language + " text");
                    }
                    byLanguage.put(language, text);
                }
                if (byLanguage.get("fr") != null && byLanguage.get("fr").equals(byLanguage.get("en"))) {
                    incomplete.add(rule.key() + ": " + key + " is the English text in French");
                }
            }
            if (blank(rule.source())) {
                incomplete.add(rule.key() + ": no source");
            }
            if (blank(rule.version()) || rule.version().length() > 64) {
                incomplete.add(rule.key() + ": no version (or longer than the 64 characters V356 stores)");
            }
            if (rule.enforcedIn().isEmpty()) {
                incomplete.add(rule.key() + ": names no enforcing class");
            }
        }
        assertThat(incomplete).as("registry entries missing a field").isEmpty();
    }

    @Test
    @DisplayName("registry to code: every class an entry names carries @ProvisionalRule with that key")
    void everyNamedClassCarriesTheAnnotation() {
        List<String> problems = new ArrayList<>();
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            for (String name : rule.enforcedIn()) {
                Set<ClinicalRuleSetKey> keys = annotatedKeys(name);
                if (keys == null) {
                    problems.add(rule.key() + " names " + name + ", which does not exist");
                } else if (!keys.contains(rule.key())) {
                    problems.add(rule.key() + " names " + name + ", which does not carry @ProvisionalRule("
                        + rule.key() + ")");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("code to registry: every class carrying @ProvisionalRule is named by each of its keys' entries")
    void everyAnnotatedClassIsInTheRegistry() throws IOException {
        Map<String, List<ClinicalRuleSetKey>> annotated = annotatedClassesInSource();
        assertThat(annotated).as("no @ProvisionalRule found: the scan is broken").isNotEmpty();
        List<String> problems = new ArrayList<>();
        annotated.forEach((name, keys) -> {
            for (ClinicalRuleSetKey key : keys) {
                ClinicalRuleSet rule = ClinicalRuleRegistry.find(key).orElseThrow();
                if (!rule.enforcedIn().contains(name)) {
                    problems.add(name + " carries @ProvisionalRule(" + key + ") but the registry entry does not name it");
                }
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every file that says PROVISIONAL or prints « provisoire » is a rule's code, or names the code that is")
    void everyProvisionalMarkerHasItsRuleSet() throws IOException {
        Set<String> annotatedFiles = annotatedFiles();
        List<String> unregistered = new ArrayList<>();
        Set<String> seenMentions = new java.util.TreeSet<>();
        for (Path file : javaFiles()) {
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            boolean marked = PROVISIONAL_MARKER.matcher(raw).find()
                || PROVISOIRE_LITERAL.matcher(SourceScan.stripComments(raw)).find();
            if (!marked || annotatedFiles.contains(relative) || NOT_A_RULE.containsKey(relative)) {
                continue;
            }
            String owner = MENTIONS.get(relative);
            if (owner == null) {
                unregistered.add(relative + ": says PROVISIONAL (or prints « provisoire ») without @ProvisionalRule;"
                    + " annotate the rule's class and add it to ClinicalRuleRegistry, or list this file in MENTIONS");
                continue;
            }
            seenMentions.add(relative);
            Set<ClinicalRuleSetKey> ownerKeys = annotatedKeys(owner);
            if (ownerKeys == null || ownerKeys.isEmpty()) {
                unregistered.add(relative + ": names " + owner + " as its rule's code, which carries no @ProvisionalRule");
            }
        }
        assertThat(unregistered).isEmpty();
        assertThat(seenMentions).as("a MENTIONS entry whose file no longer says PROVISIONAL: remove it")
            .containsExactlyInAnyOrderElementsOf(MENTIONS.keySet());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The keys of {@code name}'s annotation, an empty set when it has none, null when the class does not exist. */
    private static Set<ClinicalRuleSetKey> annotatedKeys(String name) {
        try {
            Class<?> type = Class.forName(ClinicalRuleSet.BASE_PACKAGE + name);
            ProvisionalRule annotation = type.getAnnotation(ProvisionalRule.class);
            return annotation == null ? Set.of() : Set.copyOf(Arrays.asList(annotation.value()));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Every main class whose source carries {@code @ProvisionalRule(...)}, by name from com.example.hms. */
    private static Map<String, List<ClinicalRuleSetKey>> annotatedClassesInSource() throws IOException {
        Map<String, List<ClinicalRuleSetKey>> annotated = new TreeMap<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher annotation = ANNOTATION.matcher(code);
            if (!annotation.find()) {
                continue;
            }
            List<ClinicalRuleSetKey> keys = new ArrayList<>();
            Matcher key = KEY.matcher(annotation.group(1));
            while (key.find()) {
                keys.add(ClinicalRuleSetKey.valueOf(key.group(1)));
            }
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            annotated.put(relative.substring(0, relative.length() - ".java".length()).replace('/', '.'), keys);
        }
        return annotated;
    }

    private static Set<String> annotatedFiles() throws IOException {
        Set<String> files = new java.util.TreeSet<>();
        for (String name : annotatedClassesInSource().keySet()) {
            files.add(name.replace('.', '/') + ".java");
        }
        return files;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
