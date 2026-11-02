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
import java.util.regex.MatchResult;
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
        "new\\s+(?:[a-z]\\w*\\s*\\.\\s*)*(?:BusinessException|BusinessRuleException|BusinessValidationException|BadRequestException"
            + "|ConflictException|IllegalArgumentException|IllegalStateException|UnauthorizedException"
            // QA D1102 (round 19): a CDS refusal is shown to the prescriber too.
            + "|CdsCriticalBlockException|ResponseStatusException"
            // QA D1187 (round 22): a 403 written for the caller and a 404 reach the reader
            // too, and a sentence handed to String.format is still a sentence.
            + "|ClientSafeAccessDeniedException|ResourceNotFoundException)\\s*\\(\\s*(?:HttpStatus\\.\\w+\\s*,\\s*)?"
            + "(?:(?:String|MessageFormat)\\s*\\.\\s*format\\s*\\(\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"");

    static boolean isProse(String literal) {
        return literal.indexOf(' ') >= 0;
    }

    /** The same exceptions, built from a constant instead of a literal. */
    static final Pattern PROSE_CONSTANT = Pattern.compile(
        "new\\s+(?:[a-z]\\w*\\s*\\.\\s*)*(?:BusinessException|BusinessRuleException|BusinessValidationException"
            + "|BadRequestException|ConflictException|IllegalArgumentException|IllegalStateException"
            + "|UnauthorizedException|CdsCriticalBlockException|ResponseStatusException"
            + "|ClientSafeAccessDeniedException|ResourceNotFoundException)\\s*\\(\\s*(?:HttpStatus\\.\\w+\\s*,\\s*)?"
            + "([A-Z][A-Z0-9_]*)\\s*[,)]");

    /** A String constant declared in the same file. */
    static final Pattern STRING_CONSTANT = Pattern.compile(
        "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    static int countProse(String source) {
        int n = 0;
        Matcher m = PROSE.matcher(source);
        while (m.find()) {
            if (isProse(m.group(1))) {
                n++;
            }
        }
        Map<String, String> constants = new TreeMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        Matcher k = PROSE_CONSTANT.matcher(source);
        while (k.find()) {
            String value = constants.get(k.group(1));
            if (value != null && isProse(value)) {
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
        assertTranslated("# QA round 4 (D226)", 100);
    }

    @Test
    @DisplayName("QA D317: the round-6 messages are really translated, not copied")
    void roundSixKeysAreTranslated() throws IOException {
        assertTranslated("# QA round 6 (D317)", 90);
    }

    @Test
    @DisplayName("QA D424: the round-7 messages are really translated, not copied")
    void roundSevenKeysAreTranslated() throws IOException {
        assertTranslated("# QA round 7 (D424)", 70);
    }

    @Test
    @DisplayName("QA D528: the round-8 signature messages are really translated, not copied")
    void roundEightKeysAreTranslated() throws IOException {
        assertTranslated("# QA round 8 (D528)", 1);
    }

    @Test
    @DisplayName("QA D1232 (round 24): the clinical refusals are really translated, not copied")
    void roundTwentyFourKeysAreTranslated() throws IOException {
        assertTranslated("# QA round 24 (D1232)", 80);
    }

    @Test
    @DisplayName("QA round 14 (D833-D877): the platform and integration refusals are really translated")
    void roundFourteenKeysAreTranslated() throws IOException {
        assertTranslated("# QA round 14 (D833-D877)", 40);
    }

    /**
     * QA D424: the check-in answer ("Patient X successfully checked in") and the
     * generic "Operation successful" reached French screens through a response
     * DTO's {@code message}, a path the exception scan above never looked at.
     * Every {@code .message(...)} builder call in main code must carry a resolved
     * key, never an English sentence or a sentence glued together.
     */
    static final Pattern MESSAGE_PROSE = Pattern.compile(
        "\\.message\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*([+)])");

    static int countMessageProse(String source) {
        int n = 0;
        Matcher m = MESSAGE_PROSE.matcher(source);
        while (m.find()) {
            if (isProse(m.group(1)) || "+".equals(m.group(2))) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("QA D424: no response DTO carries an English sentence in its message")
    void responseMessagesAreKeys() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                int n = countMessageProse(Files.readString(p, StandardCharsets.UTF_8));
                if (n > 0) {
                    found.add(MAIN_JAVA.relativize(p) + ": " + n);
                }
            }
        }
        assertThat(found).as("Resolve a messages*.properties key for the response message").isEmpty();
    }

    @Test
    @DisplayName("the response-message scan recognises a sentence and a concatenation")
    void messageScanRecognisesProse() {
        assertThat(countMessageProse(".message(\"Patient \" + name + \" successfully checked in\")")).isEqualTo(1);
        assertThat(countMessageProse(".message(\"Operation successful\")")).isEqualTo(1);
        assertThat(countMessageProse(".message(MessageUtil.resolveOrRaw(\"common.r7.operationSuccessful\"))")).isZero();
        assertThat(countMessageProse(".message(\"ok\")")).isZero();
    }

    private static void assertTranslated(String header, int atLeast) throws IOException {
        Map<String, String> en = block(header, "");
        Map<String, String> fr = block(header, "_fr");
        Map<String, String> es = block(header, "_es");
        assertThat(en).hasSizeGreaterThan(atLeast);
        assertThat(fr.keySet()).isEqualTo(en.keySet());
        assertThat(es.keySet()).isEqualTo(en.keySet());
        List<String> copied = new ArrayList<>();
        en.forEach((k, v) -> {
            if (!IDENTICAL_BY_DESIGN.contains(k) && (v.equals(fr.get(k)) || v.equals(es.get(k)))) {
                copied.add(k);
            }
        });
        assertThat(copied).as("French or Spanish identical to English").isEmpty();
    }

    /** One QA block of one locale, gathered across every area file (see MessageBundles). */
    private static Map<String, String> block(String header, String suffix) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String area : com.example.hms.config.MessageBundles.AREAS) {
            List<String> lines = Files.readAllLines(
                Paths.get("src/main/resources", com.example.hms.config.MessageBundles.resource(area, suffix)),
                StandardCharsets.UTF_8);
            boolean in = false;
            for (String line : lines) {
                if (line.startsWith(header)) {
                    in = true;
                } else if (in && line.startsWith("#")) {
                    break;
                } else if (in && line.contains("=")) {
                    out.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
                }
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

    @Test
    @DisplayName("a package-qualified exception or a String constant is still seen")
    void scanSeesQualifiedNamesAndConstants() {
        assertThat(countProse("throw new com.example.hms.exception.BusinessException(\n"
            + "    \"Read-back does not match.\");")).isEqualTo(1);
        assertThat(countProse("private static final String MSG = \"Unable to resolve user.\";\n"
            + "throw new BusinessException(MSG);")).isEqualTo(1);
        assertThat(countProse("private static final String MSG = \"nurse.identity.unresolved\";\n"
            + "throw new BusinessException(MSG);")).isZero();
    }

    /** Keys whose French or Spanish value is legitimately the English word (e.g. "Suspension"). */
    private static final java.util.Set<String> IDENTICAL_BY_DESIGN = java.util.Set.of(
        "hospital.lifecycle.action.suspend",
        // "contact" is the French name of contact precautions too.
        "isolation.type.CONTACT");

    /**
     * QA D317 (D226 regression): the screens QA re-tested in round 6 (check-in,
     * checkout, triage, pharmacy payment and dispense, hospital lifecycle,
     * wards and beds, on-call, duplicate email or phone, lab approval, expired
     * lot) still answered in English, because their sentences never reached an
     * exception constructor as a literal: they went through a helper
     * ({@code assertNotSameUser(user, id, "The director's ...")}), a dispense
     * check reason ({@code b.fail(EXPIRY, "lot %s expired on %s")}) or were
     * sitting under the per-file allowance. These files must hold none, under
     * the wider scan below as well as the constructor scan.
     */
    static final List<String> ROUND_SIX_FILES = List.of(
        "com/example/hms/service/ReceptionServiceImpl.java",
        "com/example/hms/service/EncounterServiceImpl.java",
        "com/example/hms/service/BedManagementServiceImpl.java",
        "com/example/hms/service/UserServiceImpl.java",
        "com/example/hms/service/LabTestDefinitionServiceImpl.java",
        "com/example/hms/service/impl/HospitalLifecycleServiceImpl.java",
        "com/example/hms/service/impl/OnCallScheduleServiceImpl.java",
        "com/example/hms/service/pharmacy/PharmacyPaymentServiceImpl.java",
        "com/example/hms/service/pharmacy/DispenseServiceImpl.java",
        "com/example/hms/service/pharmacy/DispenseVerificationService.java");

    /** A literal handed to a refusal helper ({@code assert*}, {@code require*}) or a dispense check ({@code .fail}). */
    // Possessive runs: a per-character alternation overflowed the stack on a long helper call.
    static final Pattern HELPER_PROSE = Pattern.compile(
        "(\\bassert\\w*|\\brequire\\w*|\\.fail)\\s*\\((?:[^;\"]++|\"(?:[^\"\\\\]++|\\\\.)*+\")*?\"((?:[^\"\\\\]++|\\\\.)*+)\"");

    static int countHelperProse(String source) {
        int n = 0;
        Matcher m = HELPER_PROSE.matcher(source);
        while (m.find()) {
            String literal = m.group(2);
            boolean sentence = ".fail".equals(m.group(1)) ? isProse(literal) : isProse(literal) && literal.endsWith(".");
            if (sentence) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("QA D317: the round-6 screens throw keys only, helpers and dispense checks included")
    void roundSixFilesHoldNoProse() throws IOException {
        Map<String, Integer> base = baseline();
        List<String> found = new ArrayList<>();
        for (String file : ROUND_SIX_FILES) {
            String source = Files.readString(MAIN_JAVA.resolve(file), StandardCharsets.UTF_8);
            int n = countProse(source) + countHelperProse(source);
            if (n > base.getOrDefault(file, 0)) {
                found.add(file + ": " + n);
            }
        }
        assertThat(found).as("English sentences in a QA D317 file").isEmpty();
    }

    @Test
    @DisplayName("the wider scan sees a helper sentence and a dispense check reason")
    void helperScanRecognisesProse() {
        assertThat(countHelperProse("assertNotSameUser(user, id,\n  \"The approval must come from someone else.\");")).isEqualTo(1);
        assertThat(countHelperProse("b.fail(DispenseCheck.EXPIRY, \"lot %s expired on %s\".formatted(a, b));")).isEqualTo(1);
        assertThat(countHelperProse("assertNotSameUser(user, id, \"lab.definition.fourEyes.qaNotSubmitter\");")).isZero();
        assertThat(countHelperProse("requireAtLeastOne(\"patientId or patientIdentifier\", a, b);")).isZero();
    }

    /**
     * QA D1187 / D1152 (round 22, D1152 NOT_FIXED twice): the activation code
     * refusal was keyed in round 21, and the recall refusals one screen away
     * still reached French and Spanish desks in English, because the ratchet
     * lets a file keep its old sentences. The front desk, the patient portal,
     * scheduling, phone verification and the registry hold NONE: every file
     * under these scopes (a directory takes every file in it, present and
     * future) is at zero, under the constructor scan and the helper scan,
     * whatever the baseline says.
     */
    static final List<String> ZERO_PROSE_SCOPES = List.of(
        "com/example/hms/service/scheduling/",
        "com/example/hms/service/registry/",
        "com/example/hms/service/ReceptionServiceImpl.java",
        "com/example/hms/service/AppointmentServiceImpl.java",
        "com/example/hms/service/InstrumentOutboxServiceImpl.java",
        "com/example/hms/service/PhoneVerificationServiceImpl.java",
        "com/example/hms/controller/PhoneVerificationController.java",
        "com/example/hms/service/PatientHospitalRegistrationServiceImpl.java",
        "com/example/hms/service/EligibilityServiceImpl.java",
        "com/example/hms/service/UserRoleHospitalAssignmentServiceImpl.java",
        "com/example/hms/service/impl/PatientPortalServiceImpl.java",
        "com/example/hms/service/impl/PatientGuarantorServiceImpl.java",
        "com/example/hms/service/impl/PatientPhotoServiceImpl.java",
        "com/example/hms/service/impl/AdvanceDirectiveServiceImpl.java");

    @Test
    @DisplayName("QA D1187: the front-desk, portal, scheduling and registry refusals are keys only")
    void roundTwentyTwoScopesHoldNoProse() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN_JAVA.relativize(p).toString().replace('\\', '/');
                if (ZERO_PROSE_SCOPES.stream().noneMatch(rel::startsWith)) {
                    continue;
                }
                scanned++;
                String source = Files.readString(p, StandardCharsets.UTF_8);
                int n = countProse(source) + countHelperProse(source);
                if (n > 0) {
                    found.add(rel + ": " + n);
                }
            }
        }
        assertThat(scanned).as("the scopes still name real files").isGreaterThanOrEqualTo(ZERO_PROSE_SCOPES.size());
        assertThat(found).as("Throw a messages*.properties key (en/fr/es), not an English sentence (QA D1187)").isEmpty();
    }

    @Test
    @DisplayName("QA D1187: the D1187 sentences, a 403 for the caller, a 404 and a formatted sentence are all seen")
    void scanSeesTheRoundTwentyTwoShapes() {
        assertThat(countProse("throw new BusinessException(\"This recall is already \" + recall.getStatus() + \".\");"))
            .isEqualTo(1);
        assertThat(countProse("throw new BusinessException(\n    \"Only a message in ERROR can be retried; this one is \" + s);"))
            .isEqualTo(1);
        assertThat(countProse("throw new ClientSafeAccessDeniedException(\"You may only close your own entries.\");"))
            .isEqualTo(1);
        assertThat(countProse("throw new ResourceNotFoundException(\"Recall not found.\");")).isEqualTo(1);
        assertThat(countProse("throw new BusinessException(String.format(\"Unknown outbox status: %s\", s));"))
            .isEqualTo(1);
        assertThat(countProse("throw new BusinessException(\"recall.close.alreadyCancelled\");")).isZero();
        assertThat(countProse("throw new ResourceNotFoundException(\"recall.notFound\", id);")).isZero();
    }

    /**
     * QA D1232 (round 24): a signed imaging report refused its edit in English
     * to French and Spanish doctors. The sentence sat under the per-file
     * allowance, as did the rest of the clinical record's refusals (isolation,
     * cultures, deaths, chart updates, intake/output, vitals, nursing notes,
     * treatment plans and consents, high-risk pregnancy plans). These files
     * are now at zero, whatever the baseline says; the number beside a file
     * counts its internal failures (a missing SHA-256 algorithm), which answer
     * a generic 500 and never show their text.
     */
    static final Map<String, Integer> ROUND_TWENTY_FOUR_CLINICAL = Map.ofEntries(
        Map.entry("com/example/hms/service/impl/ImagingReportServiceImpl.java", 1),
        Map.entry("com/example/hms/service/impl/IsolationServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/MicroCultureServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/MortalityServiceImpl.java", 0),
        Map.entry("com/example/hms/service/PatientChartUpdateServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/IntakeOutputServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/PatientVitalSignServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/NursingNoteServiceImpl.java", 0),
        Map.entry("com/example/hms/service/TreatmentPlanServiceImpl.java", 0),
        Map.entry("com/example/hms/service/impl/TreatmentConsentServiceImpl.java", 1),
        Map.entry("com/example/hms/service/HighRiskPregnancyCarePlanServiceImpl.java", 0));

    @Test
    @DisplayName("QA D1232: the clinical record's refusals are keys only, helpers included")
    void roundTwentyFourClinicalFilesHoldNoProse() throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Integer> file : ROUND_TWENTY_FOUR_CLINICAL.entrySet()) {
            String source = Files.readString(MAIN_JAVA.resolve(file.getKey()), StandardCharsets.UTF_8);
            int n = countProse(source) + countHelperProse(source) + countThrowerProse(source);
            if (n > file.getValue()) {
                found.add(file.getKey() + ": " + n + " (internal failures allowed: " + file.getValue() + ")");
            }
        }
        assertThat(found).as("Throw a messages*.properties key (en/fr/es), not an English sentence (QA D1232)").isEmpty();
    }

    /**
     * QA D1232 (round 24) guard extension: a sentence handed to a private
     * helper that throws its parameter ({@code findPlanInReach(user, id,
     * "You do not have permission…")}, {@code normalizeRequiredText(value,
     * "Clinical indication is required…")}) reached the reader in English
     * while the constructor scan, reading {@code new X(errorMessage)}, saw
     * nothing. A method whose String parameter is thrown is a thrower; every
     * call that hands it a sentence (a literal or a same-file constant) is
     * counted, across the whole tree. The FHIR package is outside: its helpers
     * build HAPI exceptions whose text becomes an OperationOutcome, English by
     * convention (as in {@code StoredTextProseGuardTest}).
     */
    @Test
    @DisplayName("QA D1232: no sentence is handed to a helper that throws it")
    void noProseHandedToAThrowingHelper() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN_JAVA.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("com/example/hms/fhir/")) {
                    continue;
                }
                int n = countThrowerProse(Files.readString(p, StandardCharsets.UTF_8));
                if (n > 0) {
                    found.add(rel + ": " + n);
                }
            }
        }
        assertThat(found).as("Hand the helper a messages*.properties key, not an English sentence (QA D1232)").isEmpty();
    }

    @Test
    @DisplayName("QA D1232: the thrower scan sees a literal and a constant handed to a throwing helper, not a key")
    void throwerScanRecognisesProse() {
        String helper = """
            private String normalizeRequiredText(String value, String errorMessage) {
                if (value == null) {
                    throw new BusinessException(errorMessage);
                }
                return value;
            }
            """;
        assertThat(countThrowerProse(helper
            + "void a() { normalizeRequiredText(x, \"Clinical indication is required for lab orders.\"); }\n"))
            .isEqualTo(1);
        assertThat(countThrowerProse("private static final String DENIED = \"You do not have permission\";\n" + helper
            + "void a() { normalizeRequiredText(x, DENIED); }\n")).isEqualTo(1);
        assertThat(countThrowerProse(helper
            + "void a() { normalizeRequiredText(x, \"labOrder.clinicalIndication.required\"); }\n")).isZero();
        assertThat(countThrowerProse("""
            private void note(String text) {
                logger.info(text);
            }
            void a() { note("Something happened here"); }
            """)).isZero();
    }

    /** A method declaration: its name and its parameter list. */
    // Reluctant before the name: a possessive run would swallow the method name itself.
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "(?:private|static|protected|public)\\s[^;{}=()]*?\\b(\\w++)\\s*+\\(([^()]*+)\\)\\s*+(?:throws[^{;]*+)?\\{");

    private static final String THROWN_TYPES = "(?:BusinessException|BusinessRuleException|BusinessValidationException"
        + "|BadRequestException|ConflictException|IllegalArgumentException|IllegalStateException|UnauthorizedException"
        + "|CdsCriticalBlockException|ResponseStatusException|ClientSafeAccessDeniedException|ResourceNotFoundException"
        + "|FieldBusinessException)";

    /** Calls that hand a sentence to a helper that throws the parameter it lands in. */
    static int countThrowerProse(String source) {
        Map<String, String> constants = new TreeMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        int n = 0;
        for (Map.Entry<String, List<int[]>> thrower : throwers(source).entrySet()) {
            Matcher call = Pattern.compile("(?<![\\w.])" + Pattern.quote(thrower.getKey()) + "\\s*+\\(").matcher(source);
            while (call.find()) {
                List<String> args = splitArguments(source, call.end());
                for (int[] slot : thrower.getValue()) {
                    if (args.size() == slot[1] && isProseArgument(args.get(slot[0]), constants)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Each thrower's name, with the position of the thrown String parameter and its parameter count. */
    private static Map<String, List<int[]>> throwers(String source) {
        Map<String, List<int[]>> out = new TreeMap<>();
        List<MatchResult> methods = METHOD_DECLARATION.matcher(source).results().toList();
        for (int i = 0; i < methods.size(); i++) {
            MatchResult m = methods.get(i);
            int bodyEnd = i + 1 < methods.size() ? methods.get(i + 1).start() : source.length();
            String body = source.substring(m.end(), bodyEnd);
            List<String> params = splitTopLevel(m.group(2));
            for (int p = 0; p < params.size(); p++) {
                String[] words = params.get(p).trim().split("\\s+");
                if (words.length >= 2 && "String".equals(words[words.length - 2])
                    && throwsParameter(body, words[words.length - 1])) {
                    out.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(new int[] {p, params.size()});
                }
            }
        }
        return out;
    }

    private static boolean throwsParameter(String body, String parameter) {
        return Pattern.compile("new\\s+(?:[a-z]\\w*+\\.)*+" + THROWN_TYPES + "\\s*+\\(\\s*+"
            + Pattern.quote(parameter) + "\\s*+[,)]").matcher(body).find();
    }

    private static boolean isProseArgument(String argument, Map<String, String> constants) {
        String a = argument.trim();
        if (a.length() >= 2 && a.startsWith("\"") && a.endsWith("\"")) {
            return isProse(a.substring(1, a.length() - 1));
        }
        String constant = constants.get(a);
        return constant != null && isProse(constant);
    }

    /** The top-level arguments of the call whose opening parenthesis ends at {@code from}. */
    private static List<String> splitArguments(String source, int from) {
        int depth = 1;
        int i = from;
        while (i < source.length() && depth > 0) {
            char ch = source.charAt(i);
            if (ch == '"') {
                i = skipString(source, i);
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            i++;
        }
        return splitTopLevel(source.substring(from, Math.max(from, i - 1)));
    }

    private static int skipString(String source, int quote) {
        int i = quote + 1;
        while (i < source.length() && source.charAt(i) != '"') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '"') {
                i = skipString(text, i);
                continue;
            }
            if (ch == '(' || ch == '<' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == '>' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(text.substring(start, i).trim());
                start = i + 1;
            }
            i++;
        }
        if (!text.substring(start).isBlank()) {
            out.add(text.substring(start).trim());
        }
        return out;
    }
}
