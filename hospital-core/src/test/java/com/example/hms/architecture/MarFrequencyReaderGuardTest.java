package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1236 and D1237 (round 25): two medication-safety faults on the MAR, one
 * class each.
 *
 * <ul>
 *   <li>D1237, "a schedule read from a free-text frequency": "toutes les six
 *       heures" was not read, and a frequency that was not read ended the
 *       course after its first dose. {@code MarDoseSchedule} is the ONE reader
 *       of a frequency, and only its {@code read()} tells a one-off from an
 *       unread frequency. Rule 1: no other main file inspects a frequency text
 *       (upper-casing, matching, splitting it). Rule 2: whole-hour
 *       {@code intervalHours(} - which answers null for an unread frequency AND
 *       for "5 fois par jour" - is used only where a whole-hour regimen is the
 *       point (the dispense quantity estimate), never to schedule a dose.</li>
 *   <li>D1236, "a schedule read from the last dose's time": the next dose was
 *       the last SLOT plus the interval, whatever time the dose was really
 *       given. {@code MarNextDose} is the one place that turns dose rows into a
 *       due time. Rule 3: no main file adds time to a dose row's scheduled or
 *       administered time. Rule 4: a file that handles MAR rows and reads a
 *       frequency is one of the two MAR schedule classes.</li>
 * </ul>
 */
class MarFrequencyReaderGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final String READER = "service/impl/MarDoseSchedule.java";

    /** A frequency text being inspected: {@code getFrequency().toUpperCase(}, {@code proposedFrequency().matches(}, ... */
    private static final Pattern INSPECTS_FREQUENCY = Pattern.compile(
        "\\w*[Ff]requency\\w*\\(\\)\\s*\\.\\s*(?:trim\\(\\)\\s*\\.\\s*)?"
            + "(?:toUpperCase|toLowerCase|matches|contains|startsWith|endsWith|equalsIgnoreCase|split|replace\\w*|indexOf)\\(");

    private static final Pattern WHOLE_HOURS = Pattern.compile("MarDoseSchedule\\s*\\.\\s*intervalHours\\(");

    /** Files that may read a frequency in whole hours, and why. */
    private static final Map<String, String> WHOLE_HOUR_READERS = Map.of(
        "service/pharmacy/PrescribedQuantityEstimate.java",
        "units per fill = doses a day x days; it refuses any regimen that is not whole hours dividing the day");

    private static final Pattern TIME_FROM_DOSE_ROW = Pattern.compile(
        "get(?:ScheduledTime|AdministeredAt)\\(\\)\\s*\\.\\s*(?:plus|minus)\\w*\\(");

    private static final Pattern READS_FREQUENCY = Pattern.compile("MarDoseSchedule\\s*\\.");

    /** The MAR's schedule classes: the only files that handle dose rows and read a frequency. */
    private static final List<String> MAR_SCHEDULE = List.of(
        "service/impl/MarNextDose.java", "service/impl/MarDoseInterval.java");

    @Test
    void theMarScheduleIsReadInOnePlace() throws IOException {
        Map<String, String> found = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String src = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                scanned++;
                if (!rel.equals(READER) && INSPECTS_FREQUENCY.matcher(src).find()) {
                    found.put(rel, "inspects a frequency text itself: read it through MarDoseSchedule.read (D1237)");
                }
                if (WHOLE_HOURS.matcher(src).find() && !WHOLE_HOUR_READERS.containsKey(rel)) {
                    found.put(rel, "schedules from intervalHours, which reads an unread frequency and"
                        + " \"5 fois par jour\" as no repeat: use MarDoseSchedule.read (D1237)");
                }
                if (TIME_FROM_DOSE_ROW.matcher(src).find()) {
                    found.put(rel, "adds time to a dose row's slot or administration time:"
                        + " the next dose is MarNextDose's (D1236)");
                }
                if (src.contains("MedicationAdministrationRecord") && READS_FREQUENCY.matcher(src).find()
                    && !MAR_SCHEDULE.contains(rel)) {
                    found.put(rel, "handles MAR rows and reads a frequency: the due time is MarNextDose's (D1236/D1237)");
                }
            }
        }
        assertThat(scanned).as("the scan read the main sources").isGreaterThan(1000);
        assertThat(found)
            .as("a schedule derived outside MarDoseSchedule / MarNextDose%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theAllowlistsNameRealFiles() {
        for (String rel : WHOLE_HOUR_READERS.keySet()) {
            assertThat(MAIN.resolve(rel)).as("stale whole-hour reader " + rel).exists();
        }
        for (String rel : MAR_SCHEDULE) {
            assertThat(MAIN.resolve(rel)).as("stale MAR schedule file " + rel).exists();
        }
        assertThat(MAIN.resolve(READER)).exists();
    }
}
