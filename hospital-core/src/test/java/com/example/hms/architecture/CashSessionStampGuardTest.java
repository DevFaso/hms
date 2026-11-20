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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 10 (QA r65 D2672, D2325): counter-sale money was in no till, and a till could not be closed.
 * Every money movement now names the cash session it was taken in, through {@code CashSessionService.stamp}, which
 * also refuses a closed till. This guard finds every main-code writer of a money row and requires the METHOD that
 * writes it to stamp (a helper that merely exists is not enough):
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
    /** A stamp call: the session service itself, or the writer's one-line wrapper around it. */
    private static final Pattern STAMPS = Pattern.compile(
        "cashSessions\\s*+\\.\\s*+stamp\\(|stampCashSession\\(|stampPharmacyTill\\(");
    private static final Pattern METHOD_HEADER = Pattern.compile(
        "\\n\\s*+(?:(?:public|private|protected|static|final|synchronized)\\s++)++[\\w<>\\[\\], .]++\\(");

    @Test
    @DisplayName("every method writing a money row stamps its cash session")
    void everyMoneyWriterStamps() throws IOException {
        List<String> writers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher write = WRITES_MONEY.matcher(code);
                while (write.find()) {
                    String site = file.getFileName() + ":" + SourceScan.lineOf(code, write.start());
                    writers.add(file.getFileName().toString());
                    if (!STAMPS.matcher(enclosingMethod(code, write.start())).find()) {
                        missing.add(site);
                    }
                }
            }
        }
        // The scan must keep finding the writers it was written for.
        assertThat(writers).as("the money writers").contains("BillingInvoiceServiceImpl.java",
            "InvoiceRefundService.java", "PharmacyPaymentServiceImpl.java", "PharmacySaleServiceImpl.java");
        assertThat(missing).as("a money writer whose method does not name its cash session (CashSessionService.stamp)")
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

    @Test
    @DisplayName("the scan itself: a writer without a stamp in its own method is a finding")
    void theScanFindsTheClass() {
        String helperOnly = """
            class A {
                private UUID stampPharmacyTill(UUID h, UUID c) { return cashSessions.stamp(PHARMACY, h, c); }
                public Sale sell() { PharmacySale sale = PharmacySale.builder().build(); return save(sale); }
            }
            """;
        Matcher write = WRITES_MONEY.matcher(helperOnly);
        assertThat(write.find()).isTrue();
        assertThat(STAMPS.matcher(enclosingMethod(helperOnly, write.start())).find()).isFalse();
    }

    /** The method around {@code index}: from its header to the next method's header. */
    private static String enclosingMethod(String code, int index) {
        Matcher m = METHOD_HEADER.matcher(code);
        int start = 0;
        int end = code.length();
        while (m.find()) {
            if (m.start() < index) {
                start = m.start();
            } else {
                end = m.start();
                break;
            }
        }
        return code.substring(start, end);
    }
}
