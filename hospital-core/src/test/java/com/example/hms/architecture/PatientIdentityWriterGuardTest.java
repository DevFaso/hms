package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1403 + D1414: a future birth date, 1800-01-01 and a name of digits were
 * accepted, or refused in raw English, depending on the path. Class: a path that
 * writes a patient's birth date or names without the identity rules. Every class
 * that writes them is listed here with how it is covered; a new writer fails.
 * A class that goes through the rules itself must still call them.
 */
@DisplayName("D1414: every writer of a patient's identity goes through PatientIdentityRules")
class PatientIdentityWriterGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern WRITES_IDENTITY = Pattern.compile(
        "(?:patient|mapped|out|existing|p)(?:\\.setDateOfBirth\\(|::setFirstName|::setLastName|::setMiddleName|\\.setFirstName\\(|\\.setLastName\\(|\\.setMiddleName\\()"
            + "|patientMapper\\.(?:toPatient|updatePatientFromDto)\\(");

    /** File name, and whether it calls the rules itself (true) or why it does not need to. */
    private static final Map<String, String> WRITERS = Map.of(
        "PatientServiceImpl.java", "RULES",
        "MllpInboundAdtServiceImpl.java", "RULES",
        "PatientMapper.java", "only maps; its two callers in PatientServiceImpl judge first");

    private static final String RULES = "PatientIdentityRules.";

    @Test
    void everyWriterIsKnownAndGoesThroughTheRules() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (!name.equals("DevSyntheticDataSeeder.java") && WRITES_IDENTITY.matcher(Files.readString(file)).find()) {
                    found.add(name);
                }
            }
        }
        assertThat(found).as("a new writer of a patient's birth date or names").isSubsetOf(WRITERS.keySet());
        assertThat(found).contains("PatientServiceImpl.java", "MllpInboundAdtServiceImpl.java");
        for (Map.Entry<String, String> writer : WRITERS.entrySet()) {
            if ("RULES".equals(writer.getValue())) {
                try (Stream<Path> files = Files.walk(MAIN)) {
                    Path file = files.filter(p -> p.getFileName().toString().equals(writer.getKey())).findFirst().orElseThrow();
                    assertThat(Files.readString(file)).as(writer.getKey()).contains(RULES);
                }
            }
        }
    }

    /** The internal backfill is the one create that skips the rules, on purpose: it is not a person typing. */
    @Test
    void createPatientStaysTheBackfillsAlone() throws IOException {
        long callers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            callers = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Pattern.compile("\\.createPatient\\(").matcher(Files.readString(p)).find();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).count();
        }
        assertThat(callers).as("only PatientServiceImpl.backfillMissingPatients").isEqualTo(1);
    }
}
