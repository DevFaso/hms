package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 16 residual #13 (class guard): a patient is never taken as "the
 * first" holder of a free-text identifier. The desk records shared family
 * phone numbers, relatives share emails and names repeat, so the first match
 * of a phone, email or name lookup is a guess that can be somebody else, at
 * another hospital. The audit log filed access rows under the wrong holder
 * that way (AuditEventLogServiceImpl, fixed in this guard's commit).
 *
 * <p>Fails when production code calls a {@code findFirstByPhoneNumber…}
 * finder, or takes {@code .findFirst()} straight off a phone, email or name
 * lookup, unless the statement first narrows to the one record it is allowed
 * to mean ({@code .filter(}, e.g. the caller's own record). The allowlist
 * names each remaining caller and why it is not an instance.
 */
class PatientFirstMatchGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Free-text patient lookups whose result may hold several people. */
    private static final Pattern FREE_TEXT_LOOKUP = Pattern.compile(
        "\\.(findFirstByPhoneNumber\\w*|findAllByPhoneNumber\\w*|findAllByPhoneSubscriber\\w*"
            + "|findByEmailContainingIgnoreCase|findAllByEmailIgnoreCase"
            + "|findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase)\\(");

    /** Remaining callers that are not an instance, and why. */
    private static final Map<String, String> ALLOWED = Map.of(
        "bootstrap/DevSyntheticDataSeeder.java",
            "dev-only seed data looked up by its own unique synthetic email");

    @Test
    void noPatientIsTakenAsTheFirstMatchOfAFreeTextLookup() {
        List<String> offenders = new ArrayList<>();
        for (Path f : javaFiles()) {
            String key = MAIN.relativize(f).toString().replace('\\', '/');
            if (key.endsWith("repository/PatientRepository.java") || ALLOWED.containsKey(key)) {
                continue;
            }
            String src = read(f);
            Matcher m = FREE_TEXT_LOOKUP.matcher(src);
            while (m.find()) {
                int end = src.indexOf(';', m.end());
                String statement = src.substring(m.start(), end < 0 ? src.length() : end);
                boolean firstFinder = m.group(1).startsWith("findFirst");
                boolean firstOfMany = statement.contains(".findFirst()") && !statement.contains(".filter(");
                if (firstFinder || firstOfMany) {
                    int line = (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(key + ":" + line + " " + m.group(1));
                }
            }
        }
        assertThat(offenders)
            .as("a free-text patient lookup (phone, email, name) can match several people: resolve by id, "
                + "narrow to the hospital and require exactly one, or filter to the one record meant - never "
                + "take the first")
            .isEmpty();
    }

    @Test
    void theAllowlistHasNoStaleEntries() {
        for (String key : ALLOWED.keySet()) {
            Path f = MAIN.resolve(key);
            assertThat(Files.exists(f)).as(key + " is allowlisted but gone").isTrue();
            assertThat(FREE_TEXT_LOOKUP.matcher(read(f)).find()).as(key + " no longer looks a patient up").isTrue();
        }
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
