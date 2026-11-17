package com.example.hms.architecture;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.service.EncounterClosureRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 13 guard: "left without being seen" became its own closed encounter
 * status. Every "is this visit still open?" check used to spell the closed
 * statuses out as {@code COMPLETED, CANCELLED}; one copy that missed the new
 * status would have kept an LWBS visit open: auto-completed into a
 * consultation, on the tracker board, a treatment relationship that never
 * lapses, an encounter a new order or triage could join. Those checks now read
 * {@link EncounterStatus#CLOSED} (or {@link EncounterStatus#ENDED_UNSEEN}).
 *
 * <p>This test fails when main code spells out the two-status closed set again,
 * or compares a status to CANCELLED alone outside {@link #ALLOWED_CANCELLED_COMPARISONS},
 * and when a new status is added without being classified as never seen, a
 * clinician status or closed.
 */
class EncounterClosedStatusGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final String FQCN = "(?:com\\.example\\.hms\\.enums\\.)?";

    /** {@code COMPLETED, CANCELLED)} with nothing after: the old closed set. */
    private static final Pattern TWO_STATUS_CLOSED_SET = Pattern.compile(
        FQCN + "EncounterStatus\\.COMPLETED\\s*,\\s*" + FQCN + "EncounterStatus\\.CANCELLED\\s*\\)");

    /** {@code == / != EncounterStatus.CANCELLED}: a closed-or-not test on one status. */
    private static final Pattern CANCELLED_COMPARISON = Pattern.compile(
        "[!=]=\\s*" + FQCN + "EncounterStatus\\.CANCELLED\\b");

    /**
     * Comparisons with CANCELLED alone that mean exactly "cancelled by the
     * staff", with the reason. Shrinks only.
     */
    private static final Map<String, String> ALLOWED_CANCELLED_COMPARISONS = Map.of(
        "service/reporting/ReportGenerationService.java",
        "the visit report counts staff cancellations in their own column, LWBS in another");

    @Test
    @DisplayName("every encounter status is never seen, a clinician status, or closed, exactly once")
    void everyStatusIsClassified() {
        Set<EncounterStatus> classified = EnumSet.noneOf(EncounterStatus.class);
        for (Set<EncounterStatus> group : List.of(EncounterClosureRules.NEVER_SEEN,
                EncounterClosureRules.CLINICIAN_STATUSES, EncounterStatus.CLOSED)) {
            for (EncounterStatus status : group) {
                assertThat(classified.add(status)).as("%s is in two groups", status).isTrue();
            }
        }
        assertThat(classified).containsExactlyInAnyOrder(EncounterStatus.values());
        assertThat(EncounterStatus.CLOSED).contains(EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
        assertThat(EncounterStatus.ENDED_UNSEEN)
            .containsExactlyInAnyOrder(EncounterStatus.CANCELLED, EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
        assertThat(EncounterStatus.CLOSED).containsAll(EncounterStatus.ENDED_UNSEEN);
    }

    @Test
    @DisplayName("no main code spells the closed set as COMPLETED, CANCELLED, or tests CANCELLED alone for closed")
    void closedChecksReadTheSharedSet() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/')
                    .replaceFirst("^com/example/hms/", "");
                String text = Files.readString(file, StandardCharsets.UTF_8);
                collect(offenders, rel, text, TWO_STATUS_CLOSED_SET, "two-status closed set");
                if (!ALLOWED_CANCELLED_COMPARISONS.containsKey(rel)) {
                    collect(offenders, rel, text, CANCELLED_COMPARISON, "comparison with CANCELLED alone");
                }
            }
        }
        assertThat(offenders)
            .as("read EncounterStatus.CLOSED / isClosed() / ENDED_UNSEEN instead")
            .isEmpty();
    }

    @Test
    @DisplayName("the allowance list only names files that still need it")
    void allowancesAreStillNeeded() throws IOException {
        for (String rel : ALLOWED_CANCELLED_COMPARISONS.keySet()) {
            String text = Files.readString(MAIN.resolve("com/example/hms/" + rel), StandardCharsets.UTF_8);
            assertThat(CANCELLED_COMPARISON.matcher(text).find()).as(rel).isTrue();
        }
    }

    private static void collect(List<String> offenders, String rel, String text, Pattern pattern, String what) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            offenders.add(rel + ":" + line + " " + what);
        }
    }
}
