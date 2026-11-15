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
 * QA D1920 (round 53): {@code GET /imaging/orders/patient/{id}/duplicates}
 * listed another hospital's imaging orders for a shared patient, over any
 * window the caller asked for. Class: a "possible duplicate / recent order"
 * check that reads a patient's orders without the hospital the caller acts
 * at, or over a window the caller sets without a server-side bound.
 *
 * <ul>
 *   <li>Every duplicate-order query in a repository takes {@code hospitalId}
 *       and filters on it.</li>
 *   <li>Every main method that runs one bounds its window with a server-side
 *       constant ({@code WINDOW}) or the capped {@code duplicateWindowDays}.</li>
 *   <li>The CDS hooks (whose duplicate-medication and interaction rules read
 *       the patient's active prescriptions) evaluate at the caller's
 *       hospital, never the patient's first one ({@code Patient#getHospitalId},
 *       which must not key an access rule).</li>
 * </ul>
 */
class OrderDuplicateScopeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path REPOSITORIES = MAIN.resolve("repository");
    private static final Pattern DUPLICATE_QUERY = Pattern.compile(
        "\\b(find\\w*?(?:Duplicate|SameTest)\\w*+)\\s*+\\(");
    private static final Pattern BOUNDED = Pattern.compile("\\bWINDOW\\b|\\bduplicateWindowDays\\s*+\\(");

    /** The duplicate-order queries: name -> declaration text (annotations through parameters). */
    private static List<String[]> duplicateQueries() throws IOException {
        List<String[]> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPOSITORIES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = DUPLICATE_QUERY.matcher(src);
                while (m.find()) {
                    int start = Math.max(src.lastIndexOf(';', m.start()), src.lastIndexOf('{', m.start()));
                    int end = SourceScan.closingParen(src, m.end() - 1);
                    found.add(new String[] {m.group(1), src.substring(start + 1, end + 1),
                        REPOSITORIES.relativize(f) + ":" + SourceScan.lineOf(src, m.start())});
                }
            }
        }
        return found;
    }

    @Test
    void everyDuplicateOrderQueryIsScopedToOneHospital() throws IOException {
        List<String[]> queries = duplicateQueries();
        // ImagingOrderRepository.findPotentialDuplicates, LabOrderRepository.findActiveSameTestSince today.
        assertThat(queries).as("duplicate-order queries read").hasSizeGreaterThanOrEqualTo(2);
        List<String> unscoped = new ArrayList<>();
        for (String[] q : queries) {
            String declaration = q[1];
            if (!declaration.contains("@Param(\"hospitalId\")")
                    || !declaration.matches("(?s).*\\.hospital\\.id\\s*+=\\s*+:hospitalId\\b.*")) {
                unscoped.add(q[2] + " " + q[0]);
            }
        }
        assertThat(unscoped).as("a duplicate-order query that does not filter on the caller's hospital "
            + "(take @Param(\"hospitalId\") and filter o.hospital.id = :hospitalId; QA D1920)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    void everyDuplicateOrderReadHasAServerSideWindow() throws IOException {
        List<String> names = duplicateQueries().stream().map(q -> q[0]).distinct().toList();
        assertThat(names).as("duplicate-order queries read").isNotEmpty();
        Pattern call = Pattern.compile("\\.\\s*+(" + String.join("|", names) + ")\\s*+\\(");
        List<String> unbounded = new ArrayList<>();
        int callers = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = call.matcher(src);
                while (m.find()) {
                    callers++;
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(src, m.start());
                    if (!BOUNDED.matcher(method).find()) {
                        unbounded.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()));
                    }
                }
            }
        }
        assertThat(callers).as("duplicate-order reads").isGreaterThanOrEqualTo(2);
        assertThat(unbounded).as("a duplicate-order read whose window is not bounded server-side "
            + "(a WINDOW constant or the capped duplicateWindowDays; QA D1920)" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    void cdsHooksEvaluateAtTheCallersHospital() throws IOException {
        Path hooks = MAIN.resolve("cdshooks/service");
        Pattern firstHospital = Pattern.compile("\\bpatient\\w*+\\s*+\\.\\s*+getHospitalId\\s*+\\(");
        List<String> keyed = new ArrayList<>();
        try (Stream<Path> files = Files.walk(hooks)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("CdsHookContext.java")) {
                    continue; // hospitalScope: the super-admin global-view fallback, the one reader
                }
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                Matcher m = firstHospital.matcher(src);
                while (m.find()) {
                    keyed.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()));
                }
            }
        }
        assertThat(keyed).as("a CDS hook keyed on the patient's first hospital instead of the caller's "
            + "(use CdsHookContext.hospitalScope(callerHospitalId, patient); QA D1920)" + SourceScan.MANDATE)
            .isEmpty();

        String controller = SourceScan.stripComments(Files.readString(
            MAIN.resolve("cdshooks/CdsHooksController.java"), StandardCharsets.UTF_8));
        assertThat(controller).as("the controller hands every hook the caller's resolved hospital")
            .containsPattern("\\.evaluate\\(\\s*+request\\s*+,\\s*+hospitalId\\s*+\\)")
            .doesNotContainPattern("\\.evaluate\\(\\s*+request\\s*+\\)");
    }
}
