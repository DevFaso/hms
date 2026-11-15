package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisional rule 1 (2026-11-15): urgent imaging is never blocked by a
 * pre-authorisation, and a study done without its number is flagged and
 * followed up. That holds only if every write that moves an imaging order's
 * status consults the one policy, {@code ImagingPreAuthorisationPolicy}: a
 * path that sets the status on its own (a new import, a worklist, a report
 * signature) would start or result a study with no flag and no task.
 *
 * <p>The scan finds every method of the main code that writes an
 * {@code ImagingOrder}'s status (a {@code setStatus} on a variable declared
 * {@code ImagingOrder}, on {@code getImagingOrder()}, or a {@code status(...)}
 * on {@code ImagingOrder.builder()}) and requires the method body to call
 * {@code ImagingPreAuthorisationPolicy.}.
 */
@DisplayName("Every imaging status write consults the pre-authorisation policy")
class ImagingPreAuthorisationPolicyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern DECLARED = Pattern.compile("\\bImagingOrder\\s++(\\w++)\\s*+[=,);]");
    private static final Pattern BUILDER_STATUS = Pattern.compile("ImagingOrder\\.builder\\(\\)[^;]*?\\.status\\(");
    private static final Pattern GETTER_STATUS = Pattern.compile("getImagingOrder\\(\\)\\.setStatus\\(");
    private static final Pattern DECLARATION = Pattern.compile(
        "\\n\\s++(?:(?:public|private|protected|static|final|synchronized)\\s++)++(?:[\\w<>\\[\\]?.]|,\\s*+)++\\s++(\\w++)\\s*+\\(");
    /** A call of the policy's reconcile: naming its FollowUp type is not consulting it. */
    private static final String POLICY = "ImagingPreAuthorisationPolicy.reconcile(";

    /** Status writes that need no policy call, each with why. Shrink only. */
    private static final Map<String, String> ALLOWED = Map.of(
        "ImagingOrderMapper#toEntity",
        "builds a NEW order as DRAFT or ORDERED (never a started study); createOrder reconciles it before saving");

    @Test
    void everyImagingStatusWriteConsultsThePolicy() throws IOException {
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!code.contains("ImagingOrder")) {
                    continue;
                }
                writers.addAll(statusWriters(file.getFileName().toString().replace(".java", ""), code));
            }
        }
        assertThat(writers).as("imaging status writers found")
            .contains("ImagingOrderServiceImpl#updateOrderStatus", "ImagingOrderServiceImpl#captureProviderSignature",
                "ImagingReportServiceImpl#promoteOrderToResultsAvailable", "ImagingOrderMapper#toEntity");
        assertThat(ALLOWED.keySet()).as("an allowance whose site is gone: delete its line").allMatch(writers::contains);
        List<String> offenders = new ArrayList<>();
        for (String writer : writers) {
            if (ALLOWED.containsKey(writer)) {
                continue;
            }
            offenders.add(writer);
        }
        offenders.removeIf(ImagingPreAuthorisationPolicyGuardTest::consults);
        assertThat(offenders)
            .as("a method writes an imaging order's status without consulting ImagingPreAuthorisationPolicy "
                + "(provisional rule 1, 2026-11-15): call ImagingPreAuthorisationPolicy.requireStartAllowed before "
                + "a start and ImagingPreAuthorisationPolicy.reconcile after the write" + SourceScan.MANDATE)
            .isEmpty();
    }

    /** The scan itself: a status write without the policy is caught; one with it is not. */
    @Test
    void theScanCatchesAWriteThatSkipsThePolicy() {
        String skipping = """
            class HL7ImagingImport {
                public void start(UUID id) {
                    ImagingOrder order = repository.findById(id).orElseThrow();
                    order.setStatus(ImagingOrderStatus.IN_PROGRESS);
                }
            }
            """;
        String consulting = """
            class HL7ImagingImport {
                public void start(UUID id) {
                    ImagingOrder order = repository.findById(id).orElseThrow();
                    order.setStatus(ImagingOrderStatus.IN_PROGRESS);
                    ImagingPreAuthorisationPolicy.reconcile(order, now);
                }
            }
            """;
        assertThat(statusWriters("HL7ImagingImport", skipping)).containsExactly("HL7ImagingImport#start");
        assertThat(consults("HL7ImagingImport#start", skipping)).isFalse();
        assertThat(consults("HL7ImagingImport#start", consulting)).isTrue();
        // Naming the policy's type is not consulting it.
        String naming = skipping.replace("order.setStatus(ImagingOrderStatus.IN_PROGRESS);",
            "order.setStatus(ImagingOrderStatus.IN_PROGRESS);\n"
                + "ImagingPreAuthorisationPolicy.FollowUp f = ImagingPreAuthorisationPolicy.FollowUp.UNCHANGED;");
        assertThat(consults("HL7ImagingImport#start", naming)).isFalse();
    }

    private static final Map<String, String> SOURCES = new java.util.HashMap<>();

    private static Set<String> statusWriters(String className, String code) {
        Set<String> found = new TreeSet<>();
        List<Integer> sites = new ArrayList<>();
        Matcher declared = DECLARED.matcher(code);
        Set<String> vars = new TreeSet<>();
        while (declared.find()) {
            vars.add(declared.group(1));
        }
        for (String var : vars) {
            Matcher write = Pattern.compile("\\b" + Pattern.quote(var) + "\\.setStatus\\(").matcher(code);
            while (write.find()) {
                sites.add(write.start());
            }
        }
        for (Pattern p : List.of(BUILDER_STATUS, GETTER_STATUS)) {
            Matcher m = p.matcher(code);
            while (m.find()) {
                sites.add(m.end());
            }
        }
        for (int site : sites) {
            String method = enclosingMethod(code, site);
            if (method != null) {
                String key = className + "#" + method;
                found.add(key);
                SOURCES.put(key, code);
            }
        }
        return found;
    }

    private static boolean consults(String writer) {
        return consults(writer, SOURCES.get(writer));
    }

    private static boolean consults(String writer, String code) {
        String body = SourceScan.methodBody(code, writer.substring(writer.indexOf('#') + 1));
        return body != null && body.contains(POLICY);
    }

    private static String enclosingMethod(String code, int index) {
        Matcher m = DECLARATION.matcher(code);
        String name = null;
        while (m.find() && m.start() < index) {
            String candidate = m.group(1);
            if (!Set.of("if", "for", "while", "switch", "catch", "return", "new").contains(candidate)) {
                name = candidate;
            }
        }
        return name;
    }
}
