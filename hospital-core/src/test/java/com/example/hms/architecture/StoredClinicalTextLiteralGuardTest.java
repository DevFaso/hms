package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D952/D989 guard: text the server writes INTO a stored clinical record
 * (encounter notes, vitals notes, recall reasons, summaries) is read by the
 * hospital's French- or Spanish-speaking clinicians, so it comes from the
 * message bundle, never from an English literal in code. The nursing intake
 * wrote "--- Nursing Intake --- [Nursing Assessment] … 1 medication(s)
 * reported" and triage wrote "Pain scale: 8" into the chart.
 *
 * <p>Scans the clinical workflow services (encounter, triage, intake,
 * nursing notes, discharge summaries, referrals, immunizations, admissions,
 * maternity) and fails when
 * <ul>
 *   <li>a text setter or builder call ({@code .notes(}, {@code setReason(},
 *       {@code .summary(}, …) or any {@code append…(} call is given a literal
 *       that contains a word, or</li>
 *   <li>a literal anywhere in those services is shaped like a section header
 *       ({@code "[Nursing Assessment]"}, {@code "--- Nursing Intake ---"}).</li>
 * </ul>
 * A message key ({@code "triage.vitals.painScale"}) is not prose and passes.
 */
class StoredClinicalTextLiteralGuardTest {

    private static final Path SERVICES = Paths.get("src/main/java/com/example/hms/service");
    private static final Pattern CLINICAL_SERVICE = Pattern.compile(
        "(Encounter|Triage|NursingNote|NursingIntake|DischargeSummary|GeneralReferral|Immunization"
            + "|Admission|MaternalHistory|Prenatal|Labor|Postpartum|Newborn)\\w*Impl\\.java");
    private static final Pattern TEXT_WRITE = Pattern.compile(
        "(?:\\.notes|\\.setNotes|\\.reason|\\.setReason|\\.description|\\.setDescription|\\.summary"
            + "|\\.setSummary|\\.chiefComplaint|\\.setChiefComplaint|\\.instructions|\\.setInstructions"
            + "|\\.append\\w*|append\\w*)\\s*\\(([^;]*)");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{3,}");
    private static final Pattern MESSAGE_KEY = Pattern.compile("[a-z][a-zA-Z0-9]*(?:\\.[a-zA-Z0-9_]+)+");
    private static final Pattern HEADER_SHAPED = Pattern.compile("\\[[A-Z][a-z][^\\]]*]|-{3}\\s*[A-Za-z]");

    @Test
    @DisplayName("clinical services write no English prose into stored records")
    void noEnglishLiteralIsWrittenIntoAStoredClinicalRecord() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SERVICES)) {
            files = walk.filter(p -> CLINICAL_SERVICE.matcher(p.getFileName().toString()).matches()).toList();
        }
        assertThat(files).as("the scan must find the clinical services").hasSizeGreaterThan(5);

        List<String> offences = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || line.contains("log.") || line.contains("Exception(")) {
                    continue;
                }
                Matcher write = TEXT_WRITE.matcher(line);
                while (write.find()) {
                    Matcher literal = LITERAL.matcher(write.group(1));
                    while (literal.find()) {
                        if (isProse(literal.group(1))) {
                            offences.add(file.getFileName() + ":" + (i + 1) + " writes \"" + literal.group(1) + "\"");
                        }
                    }
                }
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    if (HEADER_SHAPED.matcher(literal.group(1)).find()) {
                        offences.add(file.getFileName() + ":" + (i + 1) + " header \"" + literal.group(1) + "\"");
                    }
                }
            }
        }
        assertThat(offences)
            .as("stored clinical text must come from the message bundle (see EncounterServiceImpl.storedText)")
            .isEmpty();
    }

    private static boolean isProse(String literal) {
        return WORD.matcher(literal).find() && !MESSAGE_KEY.matcher(literal).matches();
    }
}
