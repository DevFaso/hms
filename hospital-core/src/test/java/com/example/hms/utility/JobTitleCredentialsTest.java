package com.example.hms.utility;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1136 (round 20): "Auteur : Roger Bationo , Nurse" on French and Spanish
 * chart reviews. A job title travels as its code and the portal labels it;
 * rows stored before with the English words are mapped back to the code.
 */
class JobTitleCredentialsTest {

    @ParameterizedTest
    @CsvSource({
        "Nurse, NURSE",
        "NURSE, NURSE",
        "nurse practitioner, NURSE_PRACTITIONER",
        "Lab Technician, LAB_TECHNICIAN",
        "' Midwife ', MIDWIFE",
    })
    void aStoredJobTitleIsReadAsItsCode(String stored, String code) {
        assertThat(JobTitleCredentials.toCode(stored)).isEqualTo(code);
    }

    @Test
    void credentialsAClinicianTypedAreKeptAsWritten() {
        assertThat(JobTitleCredentials.toCode("MD, FWACP")).isEqualTo("MD, FWACP");
        assertThat(JobTitleCredentials.toCode("LIC-2026-0042")).isEqualTo("LIC-2026-0042");
        assertThat(JobTitleCredentials.toCode("  ")).isNull();
        assertThat(JobTitleCredentials.toCode(null)).isNull();
    }

    /**
     * The class guard: no main class turns a job title into English words for
     * display (the removed {@code formatJobTitle(staff.getJobTitle().name())}
     * helpers, or the enum's English {@code getTitle()}).
     */
    @Test
    void noMainClassWritesAJobTitleInEnglish() throws IOException {
        Pattern humanised = Pattern.compile(
            "formatJobTitle\\s*\\(|getJobTitle\\(\\)\\s*\\.\\s*getTitle\\(\\)|JobTitle::getTitle");
        Path main = Paths.get("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                var m = humanised.matcher(source);
                while (m.find()) {
                    offenders.add(main.relativize(file) + ":" + SourceScan.lineOf(source, m.start()));
                }
            }
        }
        assertThat(offenders).as(SourceScan.MANDATE).isEmpty();
    }
}
