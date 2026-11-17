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
 * QA D1471: a patient's emergency contact name or city written with no rule ("12345"). Every
 * service that puts a request's value on a patient's contact name or city also calls the
 * identity rule ({@code PatientIdentityRules}), which judges only a change, so a row stored
 * before the rule stays editable on its other fields.
 */
class PatientContactTextGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final Pattern WRITES = Pattern.compile("patient(?:::|[.])setEmergencyContactName");
    private static final String RULE = "PatientIdentityRules.requireNameWithLetters";
    /** {@code Class} -> where its rule runs when it is not in the method that writes. */
    private static final Map<String, String> RULE_ELSEWHERE = Map.of(
        "PatientServiceImpl.java", "the PATCH calls patchIdentity (which holds the rule) before this write; the create "
            + "and PUT paths go through the mapper after the rule of the same class");

    @Test
    void everyWriterOfAContactNameCallsTheRuleInTheSameMethod() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writers = 0;
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher write = WRITES.matcher(source);
                while (write.find()) {
                    writers++;
                    boolean ruled = AppointmentSlotRulesGuardTest.enclosingMethod(source, write.start()).contains(RULE);
                    if (!ruled && !(RULE_ELSEWHERE.containsKey(name) && source.contains(RULE))) {
                        offenders.add(name + ":" + source.substring(0, write.start()).lines().count());
                    }
                }
            }
        }
        assertThat(writers).as("contact-name writers found").isGreaterThanOrEqualTo(3);
        assertThat(offenders).as("writes a contact name with no rule (QA D1471)").isEmpty();
    }
}
