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

    @Test
    void exactPhoneFindersAreUsedOnlyWhereExactIsMeant() throws IOException {
        for (Path f : javaFiles()) {
            String src = read(f);
            if (f.endsWith("PatientRepository.java")) {
                continue;
            }
            boolean exact = src.contains("findByPhoneNumberPrimary(") || src.contains("findByPhoneNumberSecondary(")
                || src.contains("findAllByPhoneNumberPrimary(") || src.contains("findAllByPhoneNumberSecondary(");
            if (exact) {
                String key = MAIN.relativize(f).toString().replace('\\', '/');
                assertThat(EXACT_PHONE_CALLERS).as(key + " matches a phone number exactly; use "
                    + "PatientRepository.findAllByPhoneSubscriber with PatientMatchKeys.phoneKey").containsKey(key);
            }
        }
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
