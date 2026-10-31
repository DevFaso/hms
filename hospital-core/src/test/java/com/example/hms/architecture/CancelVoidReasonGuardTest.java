package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * QA D1069 (class guard, round 19): every path that cancels, voids, reverses,
 * rejects or undoes money or stock in pharmacy and billing asks for a reason,
 * on the server, and a test proves the blank reason is refused.
 *
 * <p>Round 18 fixed the invoice cancel and named the pharmacy dispense cancel
 * in its own class table, but {@code POST /pharmacy/dispense/{id}/cancel}
 * with an empty body still voided the payment and put the stock back. This
 * guard reads every write handler of the billing and pharmacy controllers;
 * one whose path names such an action (or that is a DELETE) must be listed
 * below, either with the request body it reads the reason from and the test
 * that proves a blank reason is refused, or with why it is not a void (a
 * draft that was never issued, a catalogue row). A new handler fails until it
 * is listed; a listed handler that disappears fails until its line goes.
 * The invoice cancel itself is the status move of {@code PUT
 * /billing-invoices/{id}}, listed with its proof as the last row.
 */
class CancelVoidReasonGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path TEST = Path.of("src/test/java/com/example/hms");

    /** The controllers of money and stock: pharmacy, billing, invoices, payments, claims. */
    private static final Pattern MONEY_CONTROLLER = Pattern.compile(
        "controller/(pharmacy/\\w+|\\w*(Billing|Invoice|Payment|Pharmacy|Dispense|Claim|Copay|Cashier)\\w*)\\.java");

    /** A path that undoes something already done. */
    private static final Pattern VOID_PATH = Pattern.compile("(?i)(cancel|void|revers|reject|refund|undo|waive|annul)");

    /**
     * Every cancel / void / reverse / reject / undo handler of money and stock:
     * "METHOD path" -> the reason's source and the test proving a blank one is
     * refused ("Class#method"), or "NOT A VOID: why".
     */
    private static final Map<String, String> HANDLERS = new LinkedHashMap<>();

    static {
        HANDLERS.put("POST /pharmacy/dispense/{id}/cancel",
            "DispenseCancelRequestDTO.reason -> service/pharmacy/DispenseServiceImplTest#aCancelWithoutAReasonIsRefused");
        HANDLERS.put("POST /pharmacy/dispense/{id}/cancel-ready",
            "CancelReadyRequestDTO.reason (@NotNull) -> architecture/CancelVoidReasonGuardTest#cancelReadyNeedsAReason");
        HANDLERS.put("POST /pharmacy/claims/{id}/reject",
            "body.rejectionReason -> service/pharmacy/PharmacyClaimServiceImplTest#rejectRequiresReason");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/payments/{paymentId}/reject",
            "PaymentReasonRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#rejectPayment_needsAReason_D1069");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/payments/{paymentId}/reverse",
            "PaymentReasonRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#reversePayment_needsAReasonAndAConfirmedRow_D142");
        HANDLERS.put("POST /billing-invoices/{invoiceId}/adjustments/{adjustmentId}/reverse",
            "AdjustmentReversalRequestDTO.reason (@NotBlank) -> service/BillingInvoiceServiceImplTest#reverseAdjustment_needsAReasonALiveRowAndAStandingInvoice_D1105");
        HANDLERS.put("DELETE /billing-invoices/{id}",
            "NOT A VOID: only a DRAFT is deleted (billing.invoice.deleteOnlyDraft, QA D613); an issued invoice is cancelled with a reason");
        HANDLERS.put("DELETE /invoice-items/{id}",
            "NOT A VOID: a line is removed only from a DRAFT invoice (billing.invoice.issuedLinesLocked, QA D656); an issued line is credited with a reason");
        HANDLERS.put("DELETE /pharmacy/inventory/items/{id}",
            "NOT A VOID: deactivates a catalogue item, moves no stock and no money");
        HANDLERS.put("DELETE /pharmacy-registry/{id}",
            "NOT A VOID: deactivates a pharmacy registry entry, moves no stock and no money");
    }

    /** The cancel of an issued invoice is a status move on the update, not a path of its own. */
    private static final String INVOICE_CANCEL_PROOF =
        "service/BillingInvoiceServiceImplTest#updateInvoice_anIssuedInvoiceIsNotCancelledWithoutAReason_D1069";

    /** Also proven in each portal screen that offers the action (QA D1069 round 19, UI). */
    private static final List<String> UI_PROOFS = List.of(
        "hospital-portal/src/app/pharmacy/dispensing.spec.ts",
        "hospital-portal/src/app/billing/billing.spec.ts");

    @Test
    void everyCancelOrVoidOfMoneyOrStockIsListedWithItsReasonProof() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path f : controllers()) {
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            if (!MONEY_CONTROLLER.matcher(rel).matches()) {
                continue;
            }
            Class<?> type = Class.forName("com.example.hms." + rel.replace('/', '.').replace(".java", ""));
            String base = first(type.getAnnotation(RequestMapping.class) != null
                ? type.getAnnotation(RequestMapping.class).value() : new String[0]);
            for (Method m : type.getDeclaredMethods()) {
                String verb = null;
                String[] paths = null;
                if (m.getAnnotation(PostMapping.class) != null) {
                    verb = "POST";
                    paths = merge(m.getAnnotation(PostMapping.class).value(), m.getAnnotation(PostMapping.class).path());
                } else if (m.getAnnotation(PutMapping.class) != null) {
                    verb = "PUT";
                    paths = merge(m.getAnnotation(PutMapping.class).value(), m.getAnnotation(PutMapping.class).path());
                } else if (m.getAnnotation(PatchMapping.class) != null) {
                    verb = "PATCH";
                    paths = merge(m.getAnnotation(PatchMapping.class).value(), m.getAnnotation(PatchMapping.class).path());
                } else if (m.getAnnotation(DeleteMapping.class) != null) {
                    verb = "DELETE";
                    paths = merge(m.getAnnotation(DeleteMapping.class).value(), m.getAnnotation(DeleteMapping.class).path());
                }
                if (verb == null) {
                    continue;
                }
                String path = base + first(paths);
                if ("DELETE".equals(verb) || VOID_PATH.matcher(path).find()) {
                    found.add(verb + " " + path);
                }
            }
        }
        assertThat(found).as("cancel / void handlers found").hasSizeGreaterThanOrEqualTo(6);
        assertThat(new TreeSet<>(found))
            .as("a cancel / void / reverse / reject handler of money or stock that is not listed with the test "
                + "proving it refuses a blank reason (QA D1069), or a listed one that is gone")
            .isEqualTo(new TreeSet<>(HANDLERS.keySet()));
    }

    @Test
    void everyListedProofExists() throws IOException {
        List<String> proofs = new ArrayList<>();
        HANDLERS.values().stream().filter(v -> v.contains(" -> ")).map(v -> v.substring(v.indexOf(" -> ") + 4))
            .forEach(proofs::add);
        proofs.add(INVOICE_CANCEL_PROOF);
        for (String proof : proofs) {
            String[] parts = proof.split("#");
            Path file = TEST.resolve(parts[0] + ".java");
            assertThat(file).as(proof).exists();
            assertThat(Files.readString(file)).as(proof).containsPattern("void " + Pattern.quote(parts[1]) + "\\(");
        }
        for (String spec : UI_PROOFS) {
            Path file = Path.of("..").resolve(spec);
            assertThat(Files.readString(file)).as(spec + " proves the reason in the UI").contains("D1069");
        }
    }

    @Test
    void everyReasonBodyCarriesAReasonField() throws Exception {
        for (String v : HANDLERS.values()) {
            if (!v.contains(" -> ") || v.startsWith("body.")) {
                continue;
            }
            String dto = v.substring(0, v.indexOf('.'));
            Class<?> type = findPayload(dto);
            boolean hasReason = Arrays.stream(type.getDeclaredFields())
                .anyMatch(f -> f.getName().toLowerCase().contains("reason") && f.getType() != void.class);
            assertThat(hasReason).as(dto + " carries the reason").isTrue();
        }
    }

    /** QA D1069: the preparation cancel takes a reason from a closed list; none is refused. */
    @Test
    void cancelReadyNeedsAReason() {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new com.example.hms.payload.dto.pharmacy.CancelReadyRequestDTO());
            assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("reason");
        }
    }

    @Test
    void theScanSeesAnUnlistedVoid() {
        assertThat(VOID_PATH.matcher("/pharmacy/sales/{id}/refund").find()).isTrue();
        assertThat(VOID_PATH.matcher("/billing-invoices/{id}/payments").find()).isFalse();
        assertThat(MONEY_CONTROLLER.matcher("controller/pharmacy/PharmacySaleController.java").matches()).isTrue();
        assertThat(MONEY_CONTROLLER.matcher("controller/PatientController.java").matches()).isFalse();
    }

    private static Class<?> findPayload(String simpleName) throws Exception {
        try (Stream<Path> files = Files.walk(MAIN.resolve("payload"))) {
            Path f = files.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).findFirst()
                .orElseThrow(() -> new AssertionError("no payload class " + simpleName));
            String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
            return Class.forName("com.example.hms." + rel.replace('/', '.'));
        }
    }

    private static List<Path> controllers() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String[] merge(String[] a, String[] b) {
        return a.length > 0 ? a : b;
    }

    private static String first(String[] values) {
        return values == null || values.length == 0 ? "" : values[0];
    }
}
