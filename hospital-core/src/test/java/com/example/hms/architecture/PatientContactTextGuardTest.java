package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1471: a patient's emergency contact name or city written with no rule ("12345"). Every
 * service that puts a request's value on a patient's contact name or city also calls the
 * identity rule ({@code PatientIdentityRules}), which judges only a change, so a row stored
 * before the rule stays editable on its other fields.
 */
class PatientContactTextGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final Pattern WRITES = Pattern.compile(
        "patient(?:::|\\.)setEmergencyContactName|request\\.getEmergencyContactName\\(\\), patient::");

    @Test
    void everyWriterOfAContactNameCallsTheRule() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writers = 0;
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (WRITES.matcher(source).find()) {
                    writers++;
                    if (!source.contains("requireNameWithLetters(\n") && !source.contains("\"emergencyContactName\"")) {
                        offenders.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertThat(writers).as("contact-name writers found").isGreaterThanOrEqualTo(2);
        assertThat(offenders).as("writes a contact name with no rule (QA D1471)").isEmpty();
    }
}
