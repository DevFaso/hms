package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
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
 * QA D1039 / D990 / D1040 guards, over every production class.
 *
 * <ul>
 *   <li><b>A wait counted from the visit day.</b> The worklist read
 *   "1226 min" for a patient who arrived 50 minutes earlier: it measured from
 *   {@code encounterDate}, which a visit created from the encounters page holds
 *   as the day's midnight. A duration whose start is {@code getEncounterDate()}
 *   fails; {@code EncounterWait} is the one reading (arrival, else when the
 *   visit was recorded).</li>
 *   <li><b>A UUID served as the MRN.</b> The doctor's "Patients récents" read
 *   "Fatimata Zongo fe81dfc5-…": the DTO's {@code mrn} was filled with the
 *   patient's id. {@code .mrn(…getId().toString())},
 *   {@code setMrn(…getId()…)} and {@code String.valueOf(…getId())} fail;
 *   the MRN is {@code getMrnForHospital(hospitalId)}.</li>
 * </ul>
 */
@DisplayName("Waits start at the arrival; an MRN is never a UUID")
class EncounterWaitGuardTest {

    /** An id turned into text: {@code String.valueOf(x.getId())} (possessive: no backtracking). */
    private static final java.util.regex.Pattern ID_AS_STRING =
        java.util.regex.Pattern.compile("String\\.valueOf\\((?:\\w++\\.)*+getId\\(\\)\\)");
    /** The id itself: {@code x.getId()}. */
    private static final java.util.regex.Pattern ID_ITSELF = java.util.regex.Pattern.compile("(?:\\w++\\.)*+getId\\(\\)");

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern DURATION = Pattern.compile(
        "\\b(?:ElapsedTime\\.(?:minutesBetween|between)|Duration\\.between|ChronoUnit\\.\\w+\\.between)\\(");
    private static final Pattern MRN = Pattern.compile("(?:\\.mrn|\\bsetMrn|\\.patientMrn|\\bsetPatientMrn)\\(");

    @Test
    @DisplayName("no wait is computed from getEncounterDate(), no MRN from getId()")
    void noMidnightWaitNoUuidMrn() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                offenders.addAll(offenders(MAIN.relativize(file).toString().replace('\\', '/'), source));
            }
        }
        assertThat(offenders)
            .as("a wait measured from the visit day's midnight (QA D1039: use EncounterWait), or a patient id "
                + "served as the MRN (QA D1040: use getMrnForHospital)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1039 and D1040 shapes")
    void catchesTheOldShapes() {
        String before = """
            class X {
                void a() {
                    long w = ElapsedTime.minutesBetween(enc.getEncounterDate(), LocalDateTime.now());
                    dto.setMrn(p.getId().toString());
                    b.mrn(String.valueOf(patient.getId()));
                }
            }
            """;
        assertThat(offenders("X.java", before)).hasSize(3);
        String after = """
            class X {
                void a() {
                    long w = EncounterWait.minutes(enc, LocalDateTime.now());
                    dto.setMrn(p.getMrnForHospital(hospitalId));
                    b.mrn(patient.getMrnForHospital(h.getId()));
                }
            }
            """;
        assertThat(offenders("X.java", after)).isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher d = DURATION.matcher(source);
        while (d.find()) {
            int open = d.end() - 1;
            String args = source.substring(open + 1, SourceScan.closingParen(source, open));
            int comma = firstTopLevelComma(args);
            String start = comma < 0 ? args : args.substring(0, comma);
            if (start.contains("getEncounterDate()")) {
                out.add(file + ":" + SourceScan.lineOf(source, d.start()) + " wait from getEncounterDate()");
            }
        }
        Matcher m = MRN.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            String arg = source.substring(open + 1, SourceScan.closingParen(source, open)).replaceAll("\\s+", "");
            if (arg.contains("getId().toString()") || ID_AS_STRING.matcher(arg).find() || ID_ITSELF.matcher(arg).matches()) {
                out.add(file + ":" + SourceScan.lineOf(source, m.start()) + " MRN from an id: " + arg);
            }
        }
        return out;
    }

    private static int firstTopLevelComma(String args) {
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
