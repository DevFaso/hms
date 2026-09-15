package com.example.hms.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A temporal query parameter standing alone in {@code (:p IS NULL OR ...)} must be cast:
 * {@code (CAST(:p AS LocalDateTime) IS NULL OR ...)}. Hibernate 7 renders the bare parameter
 * as {@code ? is null} and PostgreSQL cannot type a timestamp it receives that way, so the
 * query fails with "could not determine data type of parameter" - on the real engine only,
 * never on H2, which is why this scans the sources instead of running the queries.
 * {@code JavaTimeParameterBindingIT} proves the cast on PostgreSQL; this keeps the next
 * search predicate from reintroducing the bare form. Thirty predicates in eight repositories
 * were rewritten on 2026-09-13 after every super-admin search screen on dev answered 500.
 */
class TemporalNullPredicateGuardTest {

    private static final Path REPOSITORIES = Path.of("src", "main", "java", "com", "example", "hms", "repository");
    private static final Set<String> TEMPORAL = Set.of(
        "LocalDateTime", "LocalDate", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime");
    private static final Pattern BARE_NULL_CHECK = Pattern.compile("\\(\\s*:(\\w+)\\s+IS NULL\\s+OR", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAM_TYPE = Pattern.compile("@Param\\(\\s*\"(\\w+)\"\\s*\\)\\s*(?:final\\s+)?([\\w.<>\\[\\]]+)\\s+\\w+");

    @Test
    void everyTemporalParameterInANullOrPredicateIsCast() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPOSITORIES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher types = PARAM_TYPE.matcher(source);
                java.util.Map<String, String> typeByName = new java.util.HashMap<>();
                while (types.find()) {
                    String simple = types.group(2).substring(types.group(2).lastIndexOf('.') + 1);
                    typeByName.put(types.group(1), simple);
                }
                Matcher bare = BARE_NULL_CHECK.matcher(source);
                while (bare.find()) {
                    String type = typeByName.get(bare.group(1));
                    if (type != null && TEMPORAL.contains(type)) {
                        offenders.add(REPOSITORIES.relativize(file) + " :" + bare.group(1) + " (" + type + ")");
                    }
                }
            }
        }
        assertThat(offenders)
            .as("temporal parameters in (:p IS NULL OR ...) must read (CAST(:p AS <type>) IS NULL OR ...)")
            .isEmpty();
    }
}
