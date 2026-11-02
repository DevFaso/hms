package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.repository.PatientRepository;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * QA D924, D942, D943 guard: every patient search and match folds names
 * (accents) and phone numbers (formatting) the same way, on both sides.
 * <ul>
 *   <li>No {@link PatientRepository} query compares a raw name or phone
 *       column with LIKE: they go through the repository's
 *       {@code NAME_OR_MRN_MATCH} / {@code PHONE_MATCH} folds.</li>
 *   <li>Every caller of the patient search builds its patterns with
 *       {@code PatientMatchKeys}.</li>
 *   <li>The exact-phone finders are used only where an exact lookup is
 *       meant, each named below with its reason.</li>
 * </ul>
 */
class PatientMatchKeysGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Callers allowed to look a number up exactly as stored, and why. */
    private static final Map<String, String> EXACT_PHONE_CALLERS = Map.of(
        "service/PatientServiceImpl.java",
            "findByPhone: the exact fallback for a number too short to name a subscriber",
        "service/AuditEventLogServiceImpl.java",
            "resolves an audit row's resource name, which is the stored value itself",
        "service/impl/PatientPortalServiceImpl.java",
            "profile uniqueness check on a number already normalised to E.164");

    @Test
    void noPatientQueryComparesARawNameOrPhone() {
        for (Method m : PatientRepository.class.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null) {
                continue;
            }
            for (String jpql : List.of(q.value(), q.countQuery())) {
                String flat = jpql.replaceAll("\\s+", " ");
                assertThat(flat).as(m.getName() + " compares a raw name")
                    .doesNotContainPattern("(?<!TRANSLATE\\()LOWER\\(p\\.(firstName|lastName)\\) LIKE")
                    .doesNotContainPattern("(?<!TRANSLATE\\()LOWER\\(CONCAT\\(p\\.(firstName|lastName)");
                assertThat(flat).as(m.getName() + " compares a raw phone")
                    .doesNotContainPattern("p\\.phoneNumber(Primary|Secondary) LIKE");
            }
        }
    }

    /** Patient-name queries that are not a typed-name search, and why (QA D1103). */
    private static final Map<String, String> NAME_QUERIES_WITHOUT_MIDDLE_NAME = Map.of(
        "PatientRepository#findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase",
        "the EMPI probabilistic matcher's candidate block (first OR last name, scored afterwards by Jaro-Winkler "
            + "on every name part) and the audit resource-name lookup: not a name a person types to find a patient");

    /**
     * QA D1103 (class guard): every repository query that compares a
     * patient's name (LIKE or =) also compares the middle name, so a name
     * typed exactly as it is displayed ("Mariam Wendkouni Kiemdé") or the
     * middle name alone finds the patient in every picker, the billing list
     * and the API search. Reads every {@code @Query} of every repository.
     */
    @Test
    void everyPatientNameComparisonIncludesTheMiddleName() throws Exception {
        List<String> missing = new java.util.ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.walk(MAIN.resolve("repository"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
                Class<?> repo = Class.forName("com.example.hms." + rel.replace('/', '.'));
                for (Method m : repo.getDeclaredMethods()) {
                    Query q = m.getAnnotation(Query.class);
                    if (q == null) {
                        continue;
                    }
                    String flat = q.value().replaceAll("\\s+", " ");
                    boolean aboutPatients = java.util.regex.Pattern
                        .compile("(FROM|JOIN) Patient p\\b|\\.patient p\\b|\\.patient\\.firstName").matcher(flat).find();
                    boolean comparesAName = flat.contains("firstName")
                        && (flat.toUpperCase().contains(" LIKE ") || flat.toUpperCase().contains("= LOWER("));
                    if (!aboutPatients || !comparesAName) {
                        continue;
                    }
                    seen++;
                    String key = repo.getSimpleName() + "#" + m.getName();
                    if (!flat.contains("middleName") && !NAME_QUERIES_WITHOUT_MIDDLE_NAME.containsKey(key)) {
                        missing.add(key);
                    }
                }
            }
        }
        assertThat(seen).as("patient name comparisons found").isGreaterThanOrEqualTo(4);
        assertThat(missing).as("a patient name search that ignores the middle name (QA D1103)").isEmpty();
    }

    @Test
    void noNameFinderIsDerived_andEveryNameQueryFolds() {
        // A derived "...NameContainingIgnoreCase" finder is accent-sensitive
        // (the EMPI page's D943 gap, review round 4): it must carry a folded @Query.
        for (Method m : PatientRepository.class.getDeclaredMethods()) {
            String name = m.getName();
            boolean nameColumn = name.contains("FirstName") || name.contains("LastName");
            boolean partialMatch = name.contains("Containing") || name.contains("Like") || name.contains("StartingWith");
            if (!nameColumn || !partialMatch) {
                continue;
            }
            Query q = m.getAnnotation(Query.class);
            assertThat(q).as(m.getName() + " is a derived name search: give it a folded @Query").isNotNull();
            assertThat(q.value()).as(m.getName() + " compares names without the accent fold").contains("TRANSLATE(LOWER(");
        }
    }

    @Test
    void everyRepositoryFoldsPhonesWithTheSharedSql() throws IOException {
        // One SQL digits fold (PatientMatchKeys.DIGITS_SQL_OPEN/CLOSE): a hand-copied
        // REPLACE chain drifts from the others (review round 4).
        for (Path f : javaFiles()) {
            String src = read(f);
            if (f.endsWith("PatientMatchKeys.java")) {
                continue;
            }
            assertThat(src).as(f + " hand-writes a phone REPLACE chain; use PatientMatchKeys.DIGITS_SQL_OPEN/CLOSE")
                .doesNotContain("REPLACE(REPLACE(REPLACE(");
        }
    }

    @Test
    void everyPatientSearchCallerUsesTheSharedKeys() throws IOException {
        List<Path> callers = javaFiles().stream()
            .filter(f -> read(f).contains("searchPatientsExtended("))
            .filter(f -> !f.endsWith("PatientRepository.java"))
            .toList();
        assertThat(callers).as("patient search callers").hasSizeGreaterThanOrEqualTo(3);
        for (Path f : callers) {
            assertThat(read(f)).as(f + " searches patients without PatientMatchKeys")
                .contains("PatientMatchKeys.");
        }
    }

    /**
     * QA D1019 (class guard): a free-text patient search sends a typed phone
     * number to the phone match. {@code GET /patients?search=65112233} used to
     * be matched against names only, so no picker found a patient by phone.
     * Every method that builds a {@code PatientSearchCriteria} with a name
     * must also decide, with {@code PatientMatchKeys.looksLikePhone}, whether
     * the text is a number.
     */
    @Test
    void everyFreeTextPatientSearchSendsANumberToThePhoneMatch() throws IOException {
        int builders = 0;
        for (Path f : javaFiles()) {
            String src = com.example.hms.testsupport.SourceScan.stripComments(read(f));
            for (int at = src.indexOf("PatientSearchCriteria.builder()"); at >= 0;
                    at = src.indexOf("PatientSearchCriteria.builder()", at + 1)) {
                String body = AppointmentSlotRulesGuardTest.enclosingMethod(src, at);
                if (!body.contains(".name(")) {
                    continue;
                }
                builders++;
                assertThat(body).as(MAIN.relativize(f) + ": a name search that never asks whether the text is a "
                        + "phone number (QA D1019)").contains("looksLikePhone(");
            }
        }
        assertThat(builders).as("free-text patient searches found").isGreaterThanOrEqualTo(2);
    }

    /**
     * QA D1019 (class guard): a phone number is checked for length as the
     * desk writes it, with spaces and the country code ("+226 65 11 22 33",
     * 16 characters), before it is normalised. A request DTO that caps a phone
     * field below 20 characters refuses a normal written number.
     */
    @Test
    void noRequestCapsAWrittenPhoneNumberBelowItsWrittenLength() throws IOException {
        java.util.regex.Pattern capped = java.util.regex.Pattern.compile(
            "@(?:jakarta\\.validation\\.constraints\\.)?Size\\(\\s*+max\\s*+=\\s*+(\\d++)[^)]*+\\)\\s*+"
                + "(?:@[\\w.]++(?:\\([^)]*+\\))?+\\s*+)*+private\\s++String\\s++"
                + "((?:[a-oq-zA-OQ-Z0-9_]|[Pp](?!hone))*+[Pp]hone\\w*+)\\s*+;");
        List<String> tooShort = new java.util.ArrayList<>();
        int phoneFields = 0;
        for (Path f : javaFiles()) {
            if (!f.toString().replace('\\', '/').contains("/payload/")) {
                continue;
            }
            java.util.regex.Matcher m = capped.matcher(read(f));
            while (m.find()) {
                phoneFields++;
                if (Integer.parseInt(m.group(1)) < 20) {
                    tooShort.add(MAIN.relativize(f) + "#" + m.group(2) + " max=" + m.group(1));
                }
            }
        }
        assertThat(phoneFields).as("capped phone fields found").isGreaterThanOrEqualTo(5);
        assertThat(tooShort).as("a phone field capped below a written number's length (QA D1019)").isEmpty();
    }

    @Test
    void exactPhoneFindersAreUsedOnlyWhereExactIsMeant() throws IOException {
        for (Path f : javaFiles()) {
            String src = read(f);
            if (f.endsWith("PatientRepository.java")) {
                continue;
            }
            boolean exact = src.contains("ByPhoneNumberPrimary(") || src.contains("ByPhoneNumberSecondary(")
                || src.contains("ByPhoneNumberPrimaryAndIdNot(");
            if (exact) {
                String key = MAIN.relativize(f).toString().replace('\\', '/');
                assertThat(EXACT_PHONE_CALLERS).as(key + " matches a phone number exactly; use "
                    + "PatientRepository.findAllByPhoneSubscriber with PatientMatchKeys.phoneKey").containsKey(key);
            }
        }
    }

    @Test
    void everyPhoneComparisonPassesTheHospitalCallingCode() throws IOException {
        // QA D942 (review rounds 5-7): a national number takes the acting
        // hospital's calling code before the full digits compare. The two-argument
        // samePhone assumes Burkina Faso, so at an Ivorian hospital 70001002 would
        // match another patient's +22670001002. Only PatientMatchKeys may call it.
        int calls = 0;
        for (Path f : javaFiles()) {
            if (f.endsWith("PatientMatchKeys.java")) {
                continue;
            }
            String src = read(f);
            int at = src.indexOf("PatientMatchKeys.samePhone(");
            while (at >= 0) {
                int open = src.indexOf('(', at);
                assertThat(topLevelArguments(src, open)).as(f + " compares phones without the hospital calling code"
                    + " (use samePhone(a, b, callingCode))").isEqualTo(3);
                calls++;
                at = src.indexOf("PatientMatchKeys.samePhone(", open);
            }
        }
        assertThat(calls).as("samePhone callers (lookup, search, duplicates, audit)").isGreaterThanOrEqualTo(4);
    }

    /** The number of top-level arguments of the call whose '(' is at {@code open}. */
    private static int topLevelArguments(String src, int open) {
        int depth = 0;
        int args = 1;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args++;
            }
        }
        throw new IllegalStateException("unbalanced call at " + open);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
