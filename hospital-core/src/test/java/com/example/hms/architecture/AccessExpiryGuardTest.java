package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1464: an end date taken from a request and stored without being judged. A proxy grant
 * dated 2020 was answered "access granted" and born expired. Every builder or setter in a
 * service that stores {@code request.getExpiresAt()} / {@code dto.getExpiresOn()} goes through
 * {@code ExpiryRules.requireFuture} or the file compares that value with the clock itself.
 */
class AccessExpiryGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    /** Reads the request's end date as a stored value. */
    private static final Pattern READS_END_DATE =
        Pattern.compile("\\b(?:request|dto|req)\\.get(ExpiresAt|ExpiresOn)\\(\\)");
    /** The value compared with now/today, in the file: requireInFuture, isAfter(today) and the like. */
    private static final Pattern JUDGED = Pattern.compile(
        "ExpiryRules\\.requireFuture|requireInFuture\\(|get(?:ExpiresAt|ExpiresOn)\\(\\)\\.isAfter\\(");
    /** Stock and blood-bag dates are a product's, judged by their own rule, and read here for other reasons. */
    private static final Map<String, String> NOT_A_GRANT = Map.of();

    @Test
    void everyStoredEndDateIsJudged() throws IOException {
        List<String> offenders = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher reads = READS_END_DATE.matcher(source);
                if (reads.find()) {
                    found++;
                    if (!JUDGED.matcher(source).find() && !NOT_A_GRANT.containsKey(file.getFileName().toString())) {
                        offenders.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertThat(found).as("request end dates found in services").isGreaterThanOrEqualTo(4);
        assertThat(offenders).as("stores a request's end date without judging it (QA D1464): ExpiryRules.requireFuture")
            .isEmpty();
    }
}
