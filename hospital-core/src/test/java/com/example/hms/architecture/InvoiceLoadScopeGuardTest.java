package com.example.hms.architecture;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D755 guard: every billing-invoice entry point that loads an invoice by
 * id checks it against the acting hospital (404 otherwise) or against the
 * patient who owns it. InvoiceEmailService loaded with findByIdWithRefs and
 * no scope, so another hospital's billing staff could mail the invoice (PDF,
 * name, MRN) to any address while GET answered 404.
 *
 * <p>A load counts as scoped when one of {@link #SCOPE_CHECKS} appears within
 * a few lines of it. The allowance below may only shrink.
 */
class InvoiceLoadScopeGuardTest {

    private static final Path SERVICE_DIR = Path.of("src/main/java/com/example/hms/service");

    /** Every class that loads a billing invoice by id. A new one must be added here. */
    private static final List<String> FILES = List.of(
        "BillingInvoiceServiceImpl.java",
        "InvoiceEmailService.java",
        "InvoiceItemServiceImpl.java");

    private static final Pattern LOAD = Pattern.compile(
        "[iI]nvoiceRepository\\.(findByIdWithRefs|findWithAllById|findLockedById|findById)\\(");

    /**
     * A method header: modifiers, optional (once-nested) type parameters, a return type (a space only
     * after a generic comma), then the name. Every quantifier is possessive and the
     * alternatives start on disjoint characters, so matching is linear (Sonar S8786).
     */
    private static final Pattern METHOD = Pattern.compile(
        "^\\s++(?:public|private|protected)\\s++(?:(?:static|final|abstract|synchronized)\\s++)*+"
            + "(?:<(?:[^<>]++|<[^<>]*+>)*+>\\s++)?"
            + "(?:[\\w.<>\\[\\]?]++|,\\s*+)++\\s++(\\w++)\\(");

    private static final List<String> SCOPE_CHECKS = List.of(
        "requireActiveHospitalId", "requireInScope", "requirePinned", "requirePayable",
        // QA D982: the patient's own issued invoice, else 404.
        "requirePortalVisible");

    /** Internal loads reached only after a scoped load of the same invoice. Shrink only. */
    private static final Map<String, String> ALLOWED = Map.of(
        "BillingInvoiceServiceImpl#lockCreditableCopay", "re-locks a copay found from an already scoped invoice's encounter",
        "BillingInvoiceServiceImpl#recomputeAndPersistTotals", "called by InvoiceItemServiceImpl after writableInvoice() scoped it",
        "BillingInvoiceServiceImpl#creditCheckInCopays", "follows a scoped issue of the same invoice");

    @Test
    @DisplayName("QA D755: every invoice load by id is scoped to the acting hospital or the owning patient")
    void everyInvoiceLoadIsScoped() throws IOException {
        List<String> unscoped = new ArrayList<>();
        Set<String> seenAllowed = new TreeSet<>();
        int loads = 0;
        for (String file : FILES) {
            List<String> lines = Files.readAllLines(SERVICE_DIR.resolve(file), StandardCharsets.UTF_8);
            String owner = file.replace(".java", "");
            for (int i = 0; i < lines.size(); i++) {
                if (!LOAD.matcher(lines.get(i)).find()) {
                    continue;
                }
                loads++;
                String method = enclosingMethod(lines, i);
                String key = owner + "#" + method;
                if (ALLOWED.containsKey(key)) {
                    seenAllowed.add(key);
                    continue;
                }
                String window = String.join("\n", lines.subList(Math.max(0, i - 3), Math.min(lines.size(), i + 12)));
                if (SCOPE_CHECKS.stream().noneMatch(window::contains)) {
                    unscoped.add(key + " (line " + (i + 1) + ")");
                }
            }
        }
        assertThat(loads).as("the scan found the invoice loads").isGreaterThanOrEqualTo(10);
        assertThat(unscoped).as("invoice loads with no hospital or patient scope check").isEmpty();
        assertThat(seenAllowed).as("a stale allowance must be removed").containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());
    }

    private static String enclosingMethod(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "?";
    }
}
