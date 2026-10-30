package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1027 guard: a chronology row that hides its record's status.
 *
 * <p>The doctor's chronology listed a cancelled morphine order and three
 * cancelled paracetamol drafts exactly like signed orders: the status went
 * into the row's {@code metadata}, which the chart never prints, and the
 * summary said nothing. Every method that builds a chronology row and puts a
 * status into its metadata ({@code META_STATUS}) must also print it in the
 * summary: through {@code TimelineStatus.withStatus(…)} (the choke point), or
 * a summary key that takes the status label ({@code statusLabel(…)},
 * {@code label(x.getStatus())}). Scans {@code PatientServiceImpl} and every
 * class of {@code service/timeline}.
 */
@DisplayName("Every chronology row with a status prints the status")
class TimelineStatusGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms/service");

    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:private|public|protected)[^;{=]*?\\(([^;{]*?)\\)\\s*(?:throws [\\w., ]+)?\\{");
    private static final Pattern NAME = Pattern.compile("(\\w+)\\s*\\($");
    private static final Pattern STATUS_IN_SUMMARY = Pattern.compile(
        "TimelineStatus\\.withStatus\\(|statusLabel\\(|\\blabel\\(\\s*\\w+\\.getStatus\\(\\)\\s*\\)");

    /** Rows that carry a status without printing it, each with its reason. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "VisitTimelineRows#labOrderRow", 1,
        "only open orders are listed (closed ones are filtered); the summary key itself says "
            + "\"demandée, résultat en attente\"");

    @Test
    @DisplayName("META_STATUS is always printed in the summary")
    void statusIsPrinted() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        List<Path> files = new ArrayList<>();
        files.add(MAIN.resolve("PatientServiceImpl.java"));
        try (Stream<Path> walk = Files.walk(MAIN.resolve("timeline"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        for (Path file : files) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String site : offenders(file.getFileName().toString().replace(".java", ""), source)) {
                actual.merge(site, 1, Integer::sum);
            }
        }
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a chronology row puts a status in its metadata but not in its summary, so a cancelled record "
                + "reads as an active one (QA D1027): wrap the summary in TimelineStatus.withStatus" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1027 shape")
    void catchesTheOldShape() {
        String before = """
            class PatientServiceImpl {
                private List<X> collectPrescriptionEntries(UUID id) {
                    return list.stream().map(p -> {
                        putIfNotNull(metadata, META_STATUS, p.getStatus().name());
                        return X.builder().summary(formatPrescriptionSummary(p)).build();
                    }).toList();
                }
            }
            """;
        assertThat(offenders("PatientServiceImpl", before)).containsExactly("PatientServiceImpl#collectPrescriptionEntries");
        String after = before.replace("summary(formatPrescriptionSummary(p))",
            "summary(TimelineStatus.withStatus(formatPrescriptionSummary(p), p.getStatus()))");
        assertThat(offenders("PatientServiceImpl", after)).isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (!body.contains("META_STATUS") || STATUS_IN_SUMMARY.matcher(body).find()) {
                continue;
            }
            String head = source.substring(m.start(), m.start(1));
            Matcher name = NAME.matcher(head);
            out.add(file + "#" + (name.find() ? name.group(1) : "?"));
        }
        return out;
    }
}
