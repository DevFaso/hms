package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3940 (r77): the product dispensed was checked against the prescription only when the client named one, and a
 * substitution accepted any product; the allergy re-check read only the prescribed drug. Every path that writes a fill
 * now runs THE ONE product check ({@code requireDispensedProduct} in {@code DispenseServiceImpl}): the product derived
 * from the lot, compared with the order's ({@code DispenseVerificationService.classify}), the substitute read against
 * the patient's current allergies, the fill named after the product dispensed. The hand-over of a prepared fill runs
 * it again ({@code requirePreparedProductDispensable}).
 *
 * <p>This reads the main tree:
 * <ul>
 *   <li>a file that writes a fill (maps a dispense entity, builds one, completes a prepared fill, or writes a DISPENSE
 *       stock movement) is a listed gate, or is listed below with the reason it is not;</li>
 *   <li>each gate method runs the check (or {@code validateFill}, which does), and the number of fills written in the
 *       gate file is the number of gate methods, so a new fill method in that file fails here too;</li>
 *   <li>the check itself still derives the product, compares it, reads the allergies and names the fill.</li>
 * </ul>
 * A ward supply, a counter sale or any new path that dispenses against a prescription without the check fails here.
 */
class DispensedProductGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final String GATE_FILE = "service/pharmacy/DispenseServiceImpl.java";

    /** What writes a fill: a dispense row mapped or built, a prepared fill completed, a DISPENSE movement written. */
    private static final Pattern FILL_WRITE = Pattern.compile(
        "dispenseMapper\\s*\\.\\s*toEntity\\(|Dispense\\s*\\.\\s*builder\\(\\)|new\\s+Dispense\\(\\)"
            + "|completePreparedFill\\(|transactionType\\(\\s*StockTransactionType\\.DISPENSE\\s*\\)"
            + "|setTransactionType\\(\\s*StockTransactionType\\.DISPENSE\\s*\\)");

    /** Files that match {@link #FILL_WRITE} without dispensing anything new, and why. */
    private static final Map<String, String> NOT_FILLS = Map.of(
        "mapper/pharmacy/DispenseMapper.java", "the mapper itself (its toEntity is called by the gates)",
        "repository/pharmacy/DispenseRepository.java", "the repository (the conditional UPDATE the hand-over runs)");

    /** The gate methods of {@link #GATE_FILE}, and the call each must make. */
    private static final Map<String, String> GATE_METHODS = Map.of(
        "createDispenseTransactionally", "validateFill(",
        "markReadyForCollectionTransactionally", "validateFill(",
        "validateFill", "requireDispensedProduct(",
        "handOver", "requirePreparedProductDispensable(");

    /** What a fill writes in the gate file, one per fill method (the one-step fill, the prepared fill, the hand-over). */
    private static final Pattern GATE_FILE_FILLS = Pattern.compile(
        "dispenseMapper\\s*\\.\\s*toEntity\\(|completePreparedFill\\(");

    @Test
    void everyFillRunsTheProductCheck() throws IOException {
        Map<String, String> sources = sources();
        List<String> problems = new ArrayList<>();
        int scanned = 0;
        for (Map.Entry<String, String> file : sources.entrySet()) {
            if (!FILL_WRITE.matcher(file.getValue()).find() || NOT_FILLS.containsKey(file.getKey())) {
                continue;
            }
            scanned++;
            if (!GATE_FILE.equals(file.getKey())) {
                problems.add(file.getKey() + " writes a fill outside the product check (requireDispensedProduct)");
            }
        }
        assertThat(scanned).as("the scan found the fill paths").isPositive();

        String gate = sources.getOrDefault(GATE_FILE, "");
        GATE_METHODS.forEach((method, call) -> {
            List<String> bodies = bodiesOf(gate, method);
            if (bodies.isEmpty()) {
                problems.add(GATE_FILE + "#" + method + " is gone: take it off the list");
            }
            for (String body : bodies) {
                if (!body.contains(call)) {
                    problems.add(GATE_FILE + "#" + method + " does not call " + call);
                }
            }
        });
        int fills = count(GATE_FILE_FILLS, gate);
        if (fills != 3) {
            problems.add(GATE_FILE + " writes " + fills + " fills; the gates are the one-step fill, the prepared fill"
                + " and the hand-over (3). A new fill method runs requireDispensedProduct and is listed here.");
        }
        assertThat(problems)
            .as("a fill that does not check the dispensed product against the prescription (QA D3940)%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theCheckDerivesComparesRereadsAndNames() throws IOException {
        String gate = sources().getOrDefault(GATE_FILE, "");
        List<String> bodies = bodiesOf(gate, "requireDispensedProduct");
        assertThat(bodies).as("requireDispensedProduct exists").hasSize(1);
        String body = bodies.get(0);
        assertThat(body)
            .as("the product is the lot's (catalogItemOfFill), never the client's text")
            .contains("catalogItemOfFill(")
            .as("compared with the order's (classify, or the lot's verification)")
            .contains("classify(")
            .contains("productMatch()")
            .as("a substitute is read against the current allergies")
            .contains("requireDispensedProductAllergyFree(")
            .as("the fill is named after the product dispensed")
            .contains("dispensedName(");
        assertThat(bodiesOf(gate, "requirePreparedProductDispensable").get(0))
            .contains("mayBeDispensed(")
            .contains("requireDispensedProductAllergyFree(");
        assertThat(bodiesOf(gate, "applyVerificationOutcome").get(0))
            .as("a substitution carries only a substitute through the DRUG check")
            .contains("DispensedProductMatch.SUBSTITUTE");
    }

    /* -- Reading the source ------------------------------------------------------------------------ */

    private static Map<String, String> sources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                out.put(rel, SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** The bodies of every declaration of {@code method} (not its calls). */
    private static List<String> bodiesOf(String src, String method) {
        List<String> bodies = new ArrayList<>();
        Matcher m = Pattern.compile("(?:public|private|protected|static)[^;{=()]*\\s" + method + "\\(").matcher(src);
        while (m.find()) {
            int params = m.end() - 1;
            int close = SourceScan.closingParen(src, params);
            int open = src.indexOf('{', close);
            int semi = src.indexOf(';', close);
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            bodies.add(src.substring(open, SourceScan.closingBrace(src, open) + 1));
        }
        return bodies;
    }

    private static int count(Pattern pattern, String src) {
        Matcher m = pattern.matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
