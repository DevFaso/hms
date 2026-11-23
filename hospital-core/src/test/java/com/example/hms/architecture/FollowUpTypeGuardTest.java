package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3152 (r70a): the discharge summary showed « DISCHARGE_FOLLOW_UP » to the
 * doctor: a follow-up type the server writes is a code, and the portal printed
 * it as written. Every follow-up type the server writes (a literal, or a
 * constant naming one, put as {@code appointmentType}) must be listed in the
 * portal's {@code SYSTEM_FOLLOW_UP_TYPE_KEYS}, which shows it in words.
 */
class FollowUpTypeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path PORTAL_MAP = Path.of("..", "hospital-portal/src/app/discharge/follow-up-type.ts");

    /** {@code row.put("appointmentType", "X")} or {@code .appointmentType("X")}. */
    private static final Pattern LITERAL = Pattern.compile(
        "(?:\"appointmentType\"\\s*,\\s*|\\.appointmentType\\(\\s*)\"([A-Z][A-Z0-9_]*)\"");
    /** {@code .appointmentType(SOME_CONSTANT)} or {@code put("appointmentType", SOME_CONSTANT)}. */
    private static final Pattern CONSTANT = Pattern.compile(
        "(?:\"appointmentType\"\\s*,\\s*|\\.appointmentType\\(\\s*)([A-Z][A-Z0-9_]*)\\s*[),]");

    @Test
    void everyFollowUpTypeTheServerWritesIsShownInWords() throws IOException {
        Set<String> written = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher literal = LITERAL.matcher(source);
                while (literal.find()) {
                    written.add(literal.group(1));
                }
                Matcher constant = CONSTANT.matcher(source);
                while (constant.find()) {
                    Matcher value = Pattern.compile("\\b" + constant.group(1) + "\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"")
                        .matcher(source);
                    assertThat(value.find()).as("the value of %s in %s", constant.group(1), file).isTrue();
                    written.add(value.group(1));
                }
            }
        }
        assertThat(written).as("the follow-up types the server writes were found")
            .contains("DISCHARGE_FOLLOW_UP", "FOLLOW_UP");

        String portal = Files.readString(PORTAL_MAP, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String type : written) {
            if (!Pattern.compile("\\b" + type + "\\s*:").matcher(portal).find()) {
                missing.add(type);
            }
        }
        assertThat(missing)
            .as("a follow-up type the server writes that the portal would show as its code (QA D3152)")
            .isEmpty();
    }
}
