package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1021 (class guard): the front desk's queue, flow board and counts read
 * the walk-ins of a calendar day. A walk-in registered at 23:57 and still
 * ARRIVED vanished from all three at midnight, and nobody could close it or
 * mark it left-without-being-seen. The day read now goes through one helper,
 * {@code ReceptionServiceImpl.walkInsShownOn}, which adds the visits still
 * open from an earlier day; this test fails when the calendar-day finder is
 * called anywhere else in {@code src/main}.
 */
class OpenVisitDayWindowGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String DAY_FINDER = "findWalkInsForHospitalAndPeriod(";

    @Test
    void theDaysWalkInsAreReadOnlyWithTheOpenOnesCarriedOver() throws IOException {
        List<String> elsewhere = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("repository/")) {
                    continue;
                }
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                for (int at = src.indexOf(DAY_FINDER); at >= 0; at = src.indexOf(DAY_FINDER, at + 1)) {
                    calls++;
                    String body = AppointmentSlotRulesGuardTest.enclosingMethod(src, at);
                    if (!body.contains("walkInsShownOn(") || !body.contains("carried")) {
                        elsewhere.add(rel + ":" + SourceScan.lineOf(src, at));
                    }
                }
            }
        }
        assertThat(calls).as("calendar-day walk-in reads found").isGreaterThanOrEqualTo(1);
        assertThat(elsewhere)
            .as("the day's walk-ins read without the visits still open from an earlier day (QA D1021): "
                + "use ReceptionServiceImpl.walkInsShownOn; " + SourceScan.MANDATE)
            .isEmpty();
    }
}
