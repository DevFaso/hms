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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 10 (QA r65 D2672, D2325): counter-sale money was in no till, and a till could not be closed.
 * Every money movement now names the cash session it was taken in, through {@code CashSessionService.stamp}, which
 * also refuses a closed till. This guard finds every main-code writer of a money row and requires it to stamp:
 * <ul>
 *   <li>an invoice payment or copay built ({@code PaymentTransaction.builder()}) or a declaration confirmed
 *       (a transition from PENDING to CONFIRMED);</li>
 *   <li>a pharmacy dispense payment ({@code paymentMapper.toEntity(}) or a counter sale ({@code PharmacySale.builder()});</li>
 *   <li>a refund or a reversal paid out ({@code refundRepository.markPaid(}).</li>
 * </ul>
 * A new money writer fails here until it names its till.
 */
@DisplayName("Every money movement lands in a cash session")
class CashSessionStampGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    private static final Pattern WRITES_MONEY = Pattern.compile(
        "PaymentTransaction\\s*+\\.\\s*+builder\\(\\)"
            + "|PharmacySale\\s*+\\.\\s*+builder\\(\\)"
            + "|paymentMapper\\s*+\\.\\s*+toEntity\\("
            + "|refundRepository\\s*+\\.\\s*+markPaid\\("
            + "|(?:claimTransition|transitionStatus)\\([^;]*?PaymentStatus\\.PENDING\\s*+,\\s*+PaymentStatus\\.CONFIRMED");
    /** The stamp: the session service itself, or the writer's one-line wrapper around it. */
    private static final Pattern STAMPS = Pattern.compile(
        "cashSessions\\s*+\\.\\s*+stamp\\(|stampCashSession\\(|stampPharmacyTill\\(");

    @Test
    @DisplayName("every service writing a money row stamps its cash session")
    void everyMoneyWriterStamps() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!WRITES_MONEY.matcher(code).find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                writers.add(name);
                if (!STAMPS.matcher(code).find()) {
                    missing.add(name);
                }
            }
        }
        // The scan must keep finding the writers it was written for.
        assertThat(writers).as("the money writers").contains("BillingInvoiceServiceImpl.java",
            "InvoiceRefundService.java", "PharmacyPaymentServiceImpl.java", "PharmacySaleServiceImpl.java");
        assertThat(missing).as("a money writer that does not name its cash session (CashSessionService.stamp)")
            .isEmpty();
    }

    @Test
    @DisplayName("each payment path of the invoice service names its till")
    void eachInvoicePaymentPathNamesItsTill() throws IOException {
        String code = SourceScan.stripComments(Files.readString(
            SERVICES.resolve("BillingInvoiceServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(code, "recordStaffPayment")).contains("CashTill.BILLING");
        assertThat(SourceScan.methodBody(code, "doRecordCheckInCopay")).contains("CashTill.RECEPTION");
        assertThat(SourceScan.methodBody(code, "confirmPayment")).contains("stampCashSession(");
        assertThat(SourceScan.methodBody(code, "savePaymentRow")).contains("stampCashSession(");
    }
}
