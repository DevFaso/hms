package com.example.hms.architecture;

import com.example.hms.model.Prescription;
import com.example.hms.service.allergy.CurrentAllergyRecheck;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Policy 4 (user recommendation 2026-11-10, pending clinical approval; it replaces the provisional
 * "acknowledge with a reason" default of #1108): an allergy conflict found after the signature is
 * released only by an AUTHORIZED PRESCRIBER's review on the order. A nurse's or a pharmacist's own
 * reason never releases it.
 *
 * <ul>
 *   <li>Nothing in the main sources reads an {@code allergyAcknowledgementReason} (or its token) any
 *       more: no request DTO carries the field, no service reads it.</li>
 *   <li>The one re-check every gate calls ({@link CurrentAllergyRecheck#requirePrescriberReview}) takes
 *       the gate, the order and its drug, and nothing a caller could put a reason in; no other public
 *       method of the re-check hands out a pass.</li>
 *   <li>Each gate's wrapper ({@code requireAllergyAcknowledged(}, which AllergyRecheckGuardTest holds every
 *       write to) delegates to it, and takes no reason or token.</li>
 *   <li>Only the prescription service writes a review, from the reviewer-checked endpoint or the
 *       signer's own signature, never from a gate.</li>
 * </ul>
 */
class AllergyPrescriberReviewGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The gates (AllergyRecheckGuardTest's list, the eMAR included). */
    private static final List<String> GATE_FILES = List.of(
        "service/impl/NurseTaskServiceImpl.java",
        "service/pharmacy/PharmacistVerificationService.java",
        "service/pharmacy/DispenseServiceImpl.java",
        "service/pharmacy/StockOutRoutingServiceImpl.java",
        "service/impl/PrescriptionSmsDispatchServiceImpl.java");

    private static final Pattern STAFF_REASON = Pattern.compile("[aA]llergyAcknowledgement(Reason|Token)");
    private static final Pattern WRAPPER = Pattern.compile(
        "private [\\w.]+Acknowledgement requireAllergyAcknowledged\\(([^)]*)\\)");
    private static final Pattern REASON_PARAM = Pattern.compile("(?i)reason|token|acknowledg|override");

    @Test
    void noMainSourceReadsAStaffAllergyReason() throws IOException {
        Map<String, String> found = new TreeMap<>();
        sources().forEach((file, src) -> {
            Matcher m = STAFF_REASON.matcher(src);
            if (m.find()) {
                found.put(file, m.group());
            }
        });
        assertThat(found)
            .as("an allergy conflict is released only by the prescriber's review (Policy 4): nothing may read a "
                + "nurse's or a pharmacist's allergyAcknowledgementReason%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theReCheckTakesNoReasonAndHandsOutAPassOnlyThere() {
        List<String> problems = new ArrayList<>();
        int passes = 0;
        for (Method method : CurrentAllergyRecheck.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || method.getReturnType() != CurrentAllergyRecheck.Acknowledgement.class) {
                continue;
            }
            passes++;
            List<Class<?>> params = Arrays.asList(method.getParameterTypes());
            if (!method.getName().equals("requirePrescriberReview")
                    || !params.equals(List.of(CurrentAllergyRecheck.Gate.class, Prescription.class, String.class))) {
                problems.add(method.getName() + params);
            }
        }
        assertThat(passes).as("the scan found the re-check's pass").isEqualTo(1);
        assertThat(problems)
            .as("only requirePrescriberReview(Gate, Prescription, medicationName) hands out a pass (Policy 4)")
            .isEmpty();
    }

    @Test
    void everyGateDelegatesToThePrescribersReviewAndTakesNoReason() throws IOException {
        Map<String, String> sources = sources();
        List<String> problems = new ArrayList<>();
        for (String file : GATE_FILES) {
            String src = sources.get(file);
            if (src == null) {
                problems.add(file + " is gone: take it off the list");
                continue;
            }
            Matcher m = WRAPPER.matcher(src);
            int wrappers = 0;
            while (m.find()) {
                wrappers++;
                if (REASON_PARAM.matcher(m.group(1)).find()) {
                    problems.add(file + ": requireAllergyAcknowledged takes a reason or token (" + m.group(1).trim() + ")");
                }
                int open = src.indexOf('{', m.end());
                String body = src.substring(open, SourceScan.closingBrace(src, open) + 1);
                if (!body.contains("requirePrescriberReview(")) {
                    problems.add(file + ": requireAllergyAcknowledged does not delegate to requirePrescriberReview");
                }
            }
            if (wrappers != 1) {
                problems.add(file + ": " + wrappers + " requireAllergyAcknowledged wrapper(s), expected 1");
            }
        }
        assertThat(problems)
            .as("every gate holds an allergy conflict for the prescriber's review (Policy 4)%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void onlyThePrescriptionServiceWritesAReviewAndOnlyAfterCheckingTheReviewer() throws IOException {
        Map<String, String> sources = sources();
        Set<String> writers = new TreeSet<>();
        sources.forEach((file, src) -> {
            if (src.contains("setAllergyReviewToken(") || src.contains("setAllergyReviewDecision(")) {
                writers.add(file);
            }
        });
        writers.remove("model/Prescription.java");
        assertThat(writers).as("the files that write an allergy review").containsExactly("service/PrescriptionServiceImpl.java");

        String svc = sources.get("service/PrescriptionServiceImpl.java");
        Set<String> callers = new TreeSet<>();
        Matcher m = Pattern.compile("(?:public|private|protected)[^;{=()]*\\s(\\w+)\\(").matcher(svc);
        while (m.find()) {
            int close = SourceScan.closingParen(svc, m.end() - 1);
            int open = svc.indexOf('{', close);
            int semi = svc.indexOf(';', close);
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            String body = svc.substring(open, SourceScan.closingBrace(svc, open) + 1);
            if (body.contains("recordAllergyReview(") && !m.group(1).equals("recordAllergyReview")) {
                callers.add(m.group(1));
                if (m.group(1).equals("reviewAllergyConflict")) {
                    assertThat(body).as("the review endpoint checks the reviewer").contains("requireAllergyReviewer(");
                }
            }
        }
        assertThat(callers).as("who records an allergy review: the reviewer-checked endpoint and the signer's signature")
            .containsExactlyInAnyOrder("reviewAllergyConflict", "recordSigningAllergyReview");
    }

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
}
