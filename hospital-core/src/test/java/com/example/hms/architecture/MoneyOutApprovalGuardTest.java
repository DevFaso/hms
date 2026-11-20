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
