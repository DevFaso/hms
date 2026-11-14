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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The lab-order duplicate warning (found while building PR #1147): the only
 * check needed the exact order timestamp, which the portal never sends, so
 * one patient got three identical fasting-glucose orders in one minute with
 * no warning. Class: a path that places a NEW lab order without asking
 * whether the same test is already active for the patient at that hospital.
 *
 * <p>Every main method that builds a new {@code LabOrder} (a builder, a
 * constructor, or {@code buildLabOrder(null, ...)}) asks
 * {@code LabOrderDuplicateCheck.find} and reports the answer
 * ({@code .report(}), except {@code buildLabOrder} itself (its new-order
 * callers are held here) and the dev data seeder.
 */
class LabOrderDuplicateGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern CREATES = Pattern.compile(
        "\\bLabOrder\\s*+\\.\\s*+builder\\s*+\\(|\\bnew\\s++LabOrder\\s*+\\(|\\bbuildLabOrder\\s*+\\(\\s*+null\\b");
    private static final Pattern FINDS = Pattern.compile("[dD]uplicateCheck\\s*+\\.\\s*+find\\s*+\\(");
    private static final Pattern REPORTS = Pattern.compile("[dD]uplicateCheck\\s*+\\.\\s*+report\\s*+\\(");
    private static final Pattern BUILDER_METHOD = Pattern.compile("^\\s*+private\\s++LabOrder\\s++buildLabOrder\\s*+\\(");

    @Test
    void everyNewLabOrderAsksWhetherTheSameTestIsAlreadyActive() throws IOException {
        List<String> unchecked = new ArrayList<>();
        int creators = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.toString().replace('\\', '/').contains("/bootstrap/")) {
                    continue; // dev synthetic data, not a clinician's order
                }
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = CREATES.matcher(src);
                while (m.find()) {
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(src, m.start());
                    if (BUILDER_METHOD.matcher(method).find()) {
                        continue;
                    }
                    creators++;
                    if (!FINDS.matcher(method).find() || !REPORTS.matcher(method).find()) {
                        unchecked.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()));
                    }
                }
            }
        }
        // createLabOrder and the reflex child order, today.
        assertThat(creators).as("lab-order creation paths read").isGreaterThanOrEqualTo(2);
        assertThat(unchecked).as("a lab order is placed without the duplicate check "
            + "(LabOrderDuplicateCheck.find + report)" + SourceScan.MANDATE).isEmpty();
    }

    /**
     * Rex on #1150: the product rule is warn, never block. The old exact-timestamp
     * check refused a second order ("lab.order.duplicate"); no main code refuses a
     * lab order as a duplicate, by that key or by an exists-query on the order time.
     */
    @Test
    void noPathRefusesALabOrderAsADuplicate() throws IOException {
        Pattern refusal = Pattern.compile(
            "\"lab\\.order\\.duplicate\"|existsByPatient_IdAndLabTestDefinition_Id\\w*+OrderDatetime");
        List<String> blocking = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = refusal.matcher(src);
                while (m.find()) {
                    blocking.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()));
                }
            }
        }
        assertThat(blocking).as("a lab order refused as a duplicate: warn (LabOrderDuplicateCheck), never block"
            + SourceScan.MANDATE).isEmpty();
    }
}
