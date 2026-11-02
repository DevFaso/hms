package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.SourceScan;
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
 * QA D1181 / D1188 class (round 22): a monitoring clock restarted by an entry
 * that checks nothing. A mood note reset the postpartum 15-minute clock; the
 * same shape sat in the nurse station, where the 4-hour vitals round, the
 * workboard's "vitals due" and the "complete triage" shortcut each read the
 * patient's LATEST vitals row - so a weight, a height or a pain score alone
 * restarted the round and counted as the triage's vital signs.
 *
 * <p>A clock or a gate reads the latest OBSERVATION SET
 * ({@code PatientVitalSignRepository.findLatestObservationSet}). The plain
 * "latest row" finders are allowed only where the row is shown as it is (the
 * chart's last-vitals snapshot, the worklist summary), each listed with why.
 * The postpartum half is {@code PostpartumMonitoringCheckGuardTest}.
 */
class ObservationClockGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern LATEST_ROW = Pattern.compile(
        "\\.(findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc|findFirstByPatient_IdOrderByRecordedAtDesc)\\(");

    private static final Map<String, GuardAllowlist.Allowance> ALLOWED = GuardAllowlist.allow(
        "service/impl/PatientVitalSignServiceImpl.java", 2,
            "the chart's last-vitals snapshot shows the latest row as recorded, a weight included; no clock",
        "service/DoctorWorklistServiceImpl.java", 1,
            "the worklist's vitals summary text shows the latest row; no clock");

    @Test
    void everyClockReadsTheLatestObservationSet() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("repository/")) {
                    continue;
                }
                Matcher m = LATEST_ROW.matcher(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
                while (m.find()) {
                    sites.add(rel);
                }
            }
        }
        Map<String, Integer> actual = GuardAllowlist.counts(sites);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a monitoring clock or a triage gate read the latest vitals row, so a weight or a pain score"
                + " alone restarts it (QA D1181/D1188): use findLatestObservationSet%s", SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("allowed sites that are gone: delete their line").isEmpty();
    }
}
