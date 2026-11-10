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
 * QA D1563 / D1555 (round 43): an allergy recorded after the order was signed was never read again.
 * Amoxicillin was given on the eMAR and dispensed at the pharmacy to a patient with a SEVERE
 * penicillin allergy recorded after the signature (and paracetamol to a patient with a paracetamol
 * allergy). The eMAR showed only the cross-reactivity advisory, silent on a direct match, and the
 * dispense service had no allergy check at all.
 *
 * <p>Every gate after the signature runs THE ONE re-check ({@code CurrentAllergyRecheck}):
 * <ul>
 *   <li>every write that records an administration on a MAR row ({@code setAdministeredAt(} /
 *       {@code .administeredAt(}) runs {@code requireAllergyAcknowledged(} once per write, and a file
 *       that writes a bedside check ({@code setScanVerifiedAt(}) answers with {@code allergyCardsFor(};</li>
 *   <li>the pharmacist's verification, every fill (one-step, prepared, a refill's), the hand-over of a
 *       prepared fill, and every routing to an outside pharmacy (the routing screen and the SMS
 *       dispatch) call {@code requireAllergyAcknowledged(} in the method that writes;</li>
 *   <li>a file that writes a pharmacist verification, a fill or a routing is one of those gates, or is
 *       listed below with the reason it is not.</li>
 * </ul>
 * A new administration, verification, dispense or routing path that forgets it fails here.
 */
class AllergyRecheckGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final String CHECK = "requireAllergyAcknowledged(";

    /* -- The eMAR ---------------------------------------------------------------------------------- */

    private static final Pattern WRITE = Pattern.compile("\\.(?:setAdministeredAt|administeredAt)\\(");
    private static final Pattern SCAN_WRITE = Pattern.compile("\\.setScanVerifiedAt\\(");
    private static final Pattern CHECK_CALL = Pattern.compile("\\brequireAllergyAcknowledged\\(");
    private static final Pattern CHECK_DEFINITION = Pattern.compile(
        "private [\\w.]+Acknowledgement requireAllergyAcknowledged\\(");
    private static final Pattern CARDS_CALL = Pattern.compile("\\ballergyCardsFor\\(");
    private static final Pattern ADVISORY_ALONE = Pattern.compile("CrossReactivityAdvisory\\s*\\.\\s*card\\(");

    /** Files that write the MAR fields without recording an administration, and why. */
    private static final Map<String, String> NOT_ADMINISTRATIONS = Map.of(
        "model/MedicationAdministrationRecord.java", "the entity itself (its builder and setters)");

    @Test
    void everyAdministrationWriteRechecksTheAllergies() throws IOException {
        Map<String, String> missing = new TreeMap<>();
        int scanned = 0;
        for (Map.Entry<String, String> file : sources().entrySet()) {
            String src = file.getValue();
            if (!src.contains("MedicationAdministrationRecord") || NOT_ADMINISTRATIONS.containsKey(file.getKey())) {
                continue;
            }
            int writes = count(WRITE, src);
            int scans = count(SCAN_WRITE, src);
            if (writes == 0 && scans == 0) {
                continue;
            }
            scanned++;
            int calls = count(CHECK_CALL, src) - count(CHECK_DEFINITION, src);
            if (calls < writes) {
                missing.put(file.getKey(), writes + " administration write(s), " + calls + " allergy re-check(s)");
            }
            if (scans > 0 && !CARDS_CALL.matcher(src).find()) {
                missing.put(file.getKey() + " (bedside check)", "writes a bedside check without allergyCardsFor(");
            }
            if (ADVISORY_ALONE.matcher(src).find()) {
                missing.put(file.getKey() + " (advisory alone)",
                    "builds the cross-reactivity card alone: a direct match says nothing (CurrentAllergyRecheck.cards)");
            }
        }
        assertThat(scanned).as("the scan found the eMAR administration paths").isPositive();
        assertThat(missing)
            .as("a MAR administration path that does not re-check the patient's current allergies (QA D1563)%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    /* -- The pharmacy ------------------------------------------------------------------------------ */

    /** What a pharmacy gate writes: a verification stamp, a fill, a hand-over, an order sent outside. */
    private static final Pattern PHARMACY_WRITE = Pattern.compile(
        "setPharmacistVerifiedAt\\(LocalDateTime|dispenseRepository\\s*\\.\\s*save\\(|completePreparedFill\\("
            + "|setStatus\\(PrescriptionStatus\\.SENT_TO_PARTNER\\)");

    /** Each gate: the methods whose body must run the re-check (or delegate to the overload that does). */
    private static final Map<String, List<String>> GATES = Map.of(
        "service/pharmacy/PharmacistVerificationService.java", List.of("verify", "verifyInTransaction"),
        "service/pharmacy/DispenseServiceImpl.java", List.of("validateFill", "handOver"),
        "service/pharmacy/StockOutRoutingServiceImpl.java", List.of("routeToPartner"),
        "service/impl/PrescriptionSmsDispatchServiceImpl.java", List.of("dispatch"));

    /** The fills: each runs {@code validateFill}, the gate above. */
    private static final Map<String, List<String>> FILLS = Map.of(
        "service/pharmacy/DispenseServiceImpl.java",
        List.of("createDispenseTransactionally", "markReadyForCollectionTransactionally"));

    /** Files that write a pharmacy fact without dispensing anything new, and why. */
    private static final Map<String, String> NOT_DISPENSES = Map.of(
        "repository/pharmacy/DispenseRepository.java", "the repository (the queries themselves)");

    @Test
    void everyVerificationFillAndRoutingRechecksTheAllergies() throws IOException {
        Map<String, String> sources = sources();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> file : sources.entrySet()) {
            if (PHARMACY_WRITE.matcher(file.getValue()).find()
                    && !GATES.containsKey(file.getKey()) && !NOT_DISPENSES.containsKey(file.getKey())) {
                problems.add(file.getKey() + " verifies, fills or routes an order and is not a listed allergy gate");
            }
        }
        GATES.forEach((file, methods) -> {
            String src = sources.get(file);
            if (src == null) {
                problems.add(file + " is gone: take it off the list");
                return;
            }
            for (String method : methods) {
                List<String> bodies = bodiesOf(src, method);
                if (bodies.isEmpty()) {
                    problems.add(file + "#" + method + " is gone: take it off the list");
                }
                for (String body : bodies) {
                    boolean delegatesVerification = file.equals("service/pharmacy/PharmacistVerificationService.java")
                        && method.equals("verify") && body.contains("verifyInTransaction(");
                    if (!body.contains(CHECK) && !body.contains(method + "(") && !delegatesVerification) {
                        problems.add(file + "#" + method + " writes without " + CHECK);
                    }
                }
            }
        });
        FILLS.forEach((file, methods) -> {
            for (String method : methods) {
                for (String body : bodiesOf(sources.getOrDefault(file, ""), method)) {
                    if (!body.contains("validateFill(")) {
                        problems.add(file + "#" + method + " fills without validateFill (the allergy gate)");
                    }
                }
            }
        });
        assertThat(problems)
            .as("a verification, fill or routing that does not re-check the patient's current allergies (QA D1555)%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    /* -- Reading the source ------------------------------------------------------------------------ */

    /** The source, comments stripped, of every main file. */
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
        Matcher m = Pattern.compile("(?:public|private|protected)[^;{=()]*\\s" + method + "\\(").matcher(src);
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
