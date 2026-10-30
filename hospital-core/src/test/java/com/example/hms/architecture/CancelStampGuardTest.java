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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1047 guard: a cancellation that records neither when nor who.
 *
 * <p>Two ultrasound orders were cancelled and kept {@code cancelledAt} and
 * {@code cancelledBy} null: the service set the status and the reason only.
 * For every entity that declares a {@code cancelledAt} column, every method
 * of production code that sets its status to {@code <Entity>Status.CANCELLED}
 * must also call {@code setCancelledAt(} (or an entity method named
 * {@code cancel(} that stamps it).
 */
@DisplayName("Every cancellation of a record with a cancelledAt column stamps it")
class CancelStampGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern CANCELLED_AT = Pattern.compile("private\\s+LocalDateTime\\s+cancelledAt\\s*;");
    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s+)+[\\w<>,.? \\[\\]]+\\s+(\\w+)\\s*\\([^;{]*?\\)\\s*(?:throws [\\w., ]+)?\\{");

    /** Known unstamped cancellations, per {@code "file#method Status"}. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();

    @Test
    @DisplayName("setStatus(XStatus.CANCELLED) is stamped with setCancelledAt")
    void cancellationsAreStamped() throws IOException {
        Set<String> stamped = new TreeSet<>();
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : javaFiles) {
            if (file.startsWith(MAIN.resolve("model"))
                    && CANCELLED_AT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                stamped.add(file.getFileName().toString().replace(".java", "") + "Status");
            }
        }
        assertThat(stamped).as("the scan found the entities with a cancelledAt column").contains("UltrasoundOrderStatus");
        Map<String, Integer> actual = new TreeMap<>();
        for (Path file : javaFiles) {
            if (file.startsWith(MAIN.resolve("model"))) {
                continue;
            }
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String site : offenders(file.getFileName().toString().replace(".java", ""), source, stamped)) {
                actual.merge(site, 1, Integer::sum);
            }
        }
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a record with a cancelledAt column is cancelled without stamping when (QA D1047)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1047 shape")
    void catchesTheOldShape() {
        String before = """
            class S {
                public Dto cancelOrder(UUID id, String reason) {
                    order.setStatus(UltrasoundOrderStatus.CANCELLED);
                    order.setCancellationReason(reason);
                    return map(order);
                }
            }
            """;
        assertThat(offenders("S", before, Set.of("UltrasoundOrderStatus")))
            .containsExactly("S#cancelOrder UltrasoundOrderStatus");
        String after = before.replace("order.setCancellationReason(reason);",
            "order.setCancellationReason(reason);\n        order.setCancelledAt(LocalDateTime.now());");
        assertThat(offenders("S", after, Set.of("UltrasoundOrderStatus"))).isEmpty();
    }

    static List<String> offenders(String file, String source, Set<String> stampedStatuses) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            for (String status : stampedStatuses) {
                if (body.contains("setStatus(" + status + ".CANCELLED)")
                        && !body.contains("setCancelledAt(") && !body.matches("(?s).*\\.cancel\\(.*")) {
                    out.add(file + "#" + m.group(1) + " " + status);
                }
            }
        }
        return out;
    }
}
