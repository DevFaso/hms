package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2702 (r65, High): a payment reversal handed money back with no second person, and the till's expected cash
 * dropped to match. Every path that takes money OUT of a till now goes through the refund approval
 * ({@code InvoiceRefundService}: asked, approved by another hospital admin or accountant, then paid out). This guard
 * finds every main-code writer of a money-out state and requires it to be that service:
 * <ul>
 *   <li>a payment set to {@code REVERSED} (a setter or a conditional transition naming it);</li>
 *   <li>a refund marked paid ({@code markPaid} on the refund repository, {@code InvoiceRefundStatus.PAID} set).</li>
 * </ul>
 * A new money-out path fails here until it goes through the approval.
 *
 * <p>PR 10 review: the pharmacy has its own money rows, which the scan above never read. A pharmacy payment set to
 * {@code VOIDED} (or, tomorrow, a counter sale set to {@code REFUNDED} or {@code CANCELLED}) is the same class: money
 * that came in and is no longer counted. Every main-code writer of those states is found here too and must be in
 * {@link #ONE_PERSON_PHARMACY}, by name, with the reason it may stay a one-person step.
 */
@DisplayName("Every money-out path goes through the two-person refund approval")
class MoneyOutApprovalGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String ONLY_WRITER = "service/billing/InvoiceRefundService.java";

    /** A payment row moved to REVERSED: {@code setStatus(PaymentStatus.REVERSED)} or a transition naming it. */
    private static final Pattern REVERSES_A_PAYMENT = Pattern.compile(
        "setStatus\\(\\s*+(?:[\\w.]*\\.)?PaymentStatus\\.REVERSED\\s*+\\)"
            + "|transitionStatus\\([^;]*?PaymentStatus\\.REVERSED");
    /** A refund handed over: the conditional UPDATE, or the status set by hand. */
    private static final Pattern PAYS_A_REFUND = Pattern.compile(
        "refundRepository\\s*+\\.\\s*+markPaid\\(|setStatus\\(\\s*+(?:[\\w.]*\\.)?InvoiceRefundStatus\\.PAID\\s*+\\)");

    /**
     * A pharmacy money row moved out of its counted state: a payment set to VOIDED (the setter, or the conditional
     * UPDATE through the repository), a counter sale set to REFUNDED or CANCELLED.
     */
    private static final Pattern VOIDS_PHARMACY_MONEY = Pattern.compile(
        "setStatus\\(\\s*+(?:[\\w.]*\\.)?PharmacyPaymentStatus\\.VOIDED\\s*+\\)"
            + "|\\.\\s*+voidPostedForDispense\\("
            + "|(?:setStatus|status)\\(\\s*+(?:[\\w.]*\\.)?PharmacySaleStatus\\.(?:REFUNDED|CANCELLED)\\s*+\\)");
    /** A repository statement that writes one of those states. */
    private static final Pattern PHARMACY_VOID_STATEMENT = Pattern.compile(
        // A write (UPDATE ... SET x.status = ...), never a filter (WHERE / AND / OR x.status = ...), which only reads.
        "(?<!(?:WHERE|AND|OR)\\s)(?<!(?:WHERE|AND|OR)\\s\\w{1,30}\\.)"
            + "status\\s*+=\\s*+(?:[\\w.]*\\.)?Pharmacy(?:Payment|Sale)Status\\.(?:VOIDED|REFUNDED|CANCELLED)");

    /**
     * The pharmacy writers that stay a one-person step, each with why. The refund of a pharmacy payment or of a
     * counter sale (money handed back at the pharmacy till) does not exist yet: when it is built it goes through an
     * approval, not into this list.
     */
    private static final java.util.Map<String, String> ONE_PERSON_PHARMACY = java.util.Map.of(
        "service/pharmacy/PharmacyPaymentVoider.java",
        "Voids the payment of a dispense the pharmacist (or the prescriber's withdrawal) cancelled, in the same "
            + "transaction as the stock return. It hands nothing back: the till keeps expecting the money "
            + "(CashSessionService counts a pharmacy payment as money in whatever its status, see "
            + "CashSessionServiceTest.aVoidedPharmacyPaymentStaysExpected), so a pharmacist cannot lower what "
            + "their own count must show by cancelling a dispense. The journal names the row VOIDED. Handing the "
            + "money back is the pharmacy refund path (billing plan PR 13), which will need its second person.");
    /**
     * Pharmacy writers that hand money back only after a SECOND person's approval (combined r65 branch: billing
     * plan PR 13 arrived with #1239). PharmacyRefundService marks a counter sale REFUNDED only when its paid refunds
     * reach the sale total, and a refund is paid only after approve() by another hospital admin or accountant
     * (canApproveRefund; self-approval refused in the service and by the V311 CHECK).
     */
    private static final java.util.Map<String, String> APPROVED_PHARMACY = java.util.Map.of(
        "service/pharmacy/PharmacyRefundService.java",
        "Sets a counter sale REFUNDED only once its PAID refunds reach the sale total; each refund is requested, "
            + "approved by a second person (canApproveRefund, no self-approval: service check and V311 CHECK) and "
            + "only then paid out of the till, so no single person can hand pharmacy money back.");

    /** The one repository statement behind that writer. */
    private static final String PHARMACY_VOID_REPOSITORY = "repository/pharmacy/PharmacyPaymentRepository.java";

    @Test
    @DisplayName("PR 10 review: a pharmacy payment or sale leaves its counted state only through a named, reasoned writer")
    void pharmacyMoneyIsVoidedOnlyByTheNamedWriter() throws IOException {
        List<String> writers;
        List<String> statements;
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> all = files.filter(f -> f.toString().endsWith(".java")).toList();
            writers = all.stream()
                .filter(f -> !relative(f).contains("repository/"))
                .filter(f -> matches(f, VOIDS_PHARMACY_MONEY))
                .map(MoneyOutApprovalGuardTest::relative)
                .toList();
            statements = all.stream()
                .filter(f -> relative(f).contains("repository/"))
                .filter(f -> matches(f, PHARMACY_VOID_STATEMENT))
                .map(MoneyOutApprovalGuardTest::relative)
                .toList();
        }
        assertThat(writers)
            .as("main code that voids, refunds or cancels pharmacy money: route it through an approval, or name it in"
                + " ONE_PERSON_PHARMACY with why it may stay a one-person step")
            .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                ONE_PERSON_PHARMACY.keySet().stream(), APPROVED_PHARMACY.keySet().stream()).toList());
        assertThat(statements)
            .as("repository statements that write a voided, refunded or cancelled pharmacy state")
            .containsExactly(PHARMACY_VOID_REPOSITORY);
        assertThat(ONE_PERSON_PHARMACY.values()).as("each exception says why").allMatch(why -> why.length() > 80);
        assertThat(APPROVED_PHARMACY.values()).as("each approved writer says how").allMatch(why -> why.length() > 80);
    }

    @Test
    @DisplayName("the pharmacy scan itself: a void or a refund written anywhere is a finding")
    void thePharmacyScanFindsTheClass() {
        assertThat(VOIDS_PHARMACY_MONEY.matcher("payment.setStatus(PharmacyPaymentStatus.VOIDED);").find()).isTrue();
        assertThat(VOIDS_PHARMACY_MONEY.matcher("paymentRepository.voidPostedForDispense(id, by, why, now);").find())
            .isTrue();
        assertThat(VOIDS_PHARMACY_MONEY.matcher("sale.setStatus(PharmacySaleStatus.REFUNDED);").find()).isTrue();
        assertThat(VOIDS_PHARMACY_MONEY.matcher("PharmacySale.builder().status(PharmacySaleStatus.CANCELLED)").find())
            .isTrue();
        assertThat(VOIDS_PHARMACY_MONEY.matcher("sale.setStatus(PharmacySaleStatus.COMPLETED);").find()).isFalse();
        assertThat(PHARMACY_VOID_STATEMENT.matcher(
            "p.status = com.example.hms.enums.PharmacyPaymentStatus.VOIDED, p.voidedAt = :now").find()).isTrue();
    }

    private static String relative(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    private static boolean matches(Path file, Pattern pattern) {
        try {
            return pattern.matcher(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8))).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("only the refund service reverses a payment or pays a refund out")
    void onlyTheApprovalPathTakesMoneyOut() throws IOException {
        List<String> writers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            writers = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> !f.toString().replace('\\', '/').contains("/repository/"))
                .filter(MoneyOutApprovalGuardTest::takesMoneyOut)
                .map(f -> MAIN.relativize(f).toString().replace('\\', '/'))
                .toList();
        }
        assertThat(writers).as("main code that reverses a payment or pays a refund out").containsExactly(ONLY_WRITER);
    }

    @Test
    @DisplayName("in the refund service, a payout is reached only from APPROVED, and a reversal only at payout")
    void theRefundServiceReversesOnlyAtPayout() throws IOException {
        String code = SourceScan.stripComments(Files.readString(MAIN.resolve(ONLY_WRITER), StandardCharsets.UTF_8));
        String pay = SourceScan.methodBody(code, "pay");
        assertThat(pay).as("pay() checks APPROVED before anything moves")
            .contains("InvoiceRefundStatus.APPROVED")
            .contains("reversePaidOut(");
        String request = SourceScan.methodBody(code, "requestReversal");
        assertThat(REVERSES_A_PAYMENT.matcher(request).find())
            .as("asking for a reversal never reverses the payment").isFalse();
        assertThat(request).as("a reversal is asked as REQUESTED").contains("InvoiceRefundStatus.REQUESTED");
    }

    private static boolean takesMoneyOut(Path file) {
        try {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            return REVERSES_A_PAYMENT.matcher(code).find() || PAYS_A_REFUND.matcher(code).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
