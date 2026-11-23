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
 *   <li>a refund or a reversal paid out ({@code refundRepository.markPaid(});</li>
 *   <li>a pharmacy refund paid out ({@code setStatus(PharmacyRefundStatus.PAID)}), QA r66 D2950.</li>
 * </ul>
 * A new money writer fails here until it names its till.
 *
 * <p>QA r66 D2950: a stamp that the till never reads is no better than none. A pharmacy refund paid at the counter
 * was counted by the pharmacy reconciliation and not by the till, whose expected cash stayed too high. Every entity
 * that carries a {@code cash_session_id} must be read by {@code CashSessionService.figures} and have its
 * {@code movement(...)} mapping, and every refund is money OUT.
 */
@DisplayName("Every money movement lands in a cash session")
class CashSessionStampGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    private static final Pattern WRITES_MONEY = Pattern.compile(
        "PaymentTransaction\\s*+\\.\\s*+builder\\(\\)"
            + "|PharmacySale\\s*+\\.\\s*+builder\\(\\)"
            + "|paymentMapper\\s*+\\.\\s*+toEntity\\("
            + "|refundRepository\\s*+\\.\\s*+markPaid\\("
            + "|setStatus\\(\\s*+(?:[\\w.]*\\.)?PharmacyRefundStatus\\.PAID\\s*+\\)"
            + "|(?:claimTransition|transitionStatus)\\([^;]*?PaymentStatus\\.PENDING\\s*+,\\s*+PaymentStatus\\.CONFIRMED");
    /** A stamp call: the session service itself, or the writer's one-line wrapper around it. */
    private static final Pattern STAMPS = Pattern.compile(
        "cashSessions\\s*+\\.\\s*+stamp(?:Payout)?\\(|stampCashSession\\(|stampPharmacyTill\\(");
    /** QA D3410 (r70): money OUT (a refund or a reversal handed over). */
    private static final Pattern PAYS_OUT = Pattern.compile(
        "refundRepository\\s*+\\.\\s*+markPaid\\("
            + "|setStatus\\(\\s*+(?:[\\w.]*\\.)?PharmacyRefundStatus\\.PAID\\s*+\\)");
    private static final Pattern STAMPS_PAYOUT = Pattern.compile("cashSessions\\s*+\\.\\s*+stampPayout\\(");
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
            "InvoiceRefundService.java", "PharmacyPaymentServiceImpl.java", "PharmacySaleServiceImpl.java",
            "PharmacyRefundService.java");
        assertThat(missing).as("a money writer whose method does not name its cash session (CashSessionService.stamp)")
            .isEmpty();
    }

    @Test
    @DisplayName("QA D3410: every payout is taken from a till the payer holds open, never below its cash")
    void everyPayoutIsBoundedByItsTill() throws IOException {
        List<String> payouts = new ArrayList<>();
        List<String> unbounded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher out = PAYS_OUT.matcher(code);
                while (out.find()) {
                    payouts.add(file.getFileName().toString());
                    if (!paysOutOfItsTill(code, enclosingMethod(code, out.start()))) {
                        unbounded.add(file.getFileName() + ":" + SourceScan.lineOf(code, out.start()));
                    }
                }
            }
        }
        assertThat(payouts).as("the payout writers").contains("InvoiceRefundService.java",
            "PharmacyRefundService.java");
        assertThat(unbounded)
            .as("money paid out with CashSessionService.stamp, which opens an empty till on the spot and lets its"
                + " cash go negative (QA D3410): use stampPayout")
            .isEmpty();
    }

    @Test
    @DisplayName("the payout scan itself: a payout stamped with the plain stamp is a finding")
    void thePayoutScanFindsTheClass() {
        String plain = """
            class A {
                private UUID stampPharmacyTill(UUID h, UUID c) { return cashSessions.stamp(PHARMACY, h, c); }
                public void pay() { refund.setCashSessionId(stampPharmacyTill(h, c)); refund.setStatus(PharmacyRefundStatus.PAID); }
            }
            """;
        Matcher out = PAYS_OUT.matcher(plain);
        assertThat(out.find()).isTrue();
        assertThat(paysOutOfItsTill(plain, enclosingMethod(plain, out.start()))).isFalse();
        String bounded = plain.replace("cashSessions.stamp(", "cashSessions.stampPayout(");
        Matcher again = PAYS_OUT.matcher(bounded);
        assertThat(again.find()).isTrue();
        assertThat(paysOutOfItsTill(bounded, enclosingMethod(bounded, again.start()))).isTrue();
    }

    /** Whether a payout method stamps through stampPayout, directly or through the one-line till wrapper. */
    private static boolean paysOutOfItsTill(String code, String method) {
        if (STAMPS_PAYOUT.matcher(method).find()) {
            return true;
        }
        return method.contains("stampPharmacyTill(")
            && STAMPS_PAYOUT.matcher(SourceScan.methodBody(code, "stampPharmacyTill")).find();
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

    private static final Path MODELS = Path.of("src/main/java/com/example/hms/model");
    private static final Path TILL = SERVICES.resolve("billing/cash/CashSessionService.java");
    private static final Pattern STAMPED_ROW = Pattern.compile("@Column\\(\\s*+name\\s*+=\\s*+\"cash_session_id\"");

    @Test
    @DisplayName("QA D2950: every row stamped with a cash session is counted in the till's expected amounts")
    void everyStampedRowIsCountedByTheTill() throws IOException {
        List<String> stamped = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MODELS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (STAMPED_ROW.matcher(code).find()) {
                    stamped.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        // The scan must keep finding the rows it was written for.
        assertThat(stamped).as("the entities stamped with a cash session").contains("PaymentTransaction",
            "InvoiceRefund", "PharmacyPayment", "PharmacySale", "PharmacyRefund");

        String till = SourceScan.stripComments(Files.readString(TILL, StandardCharsets.UTF_8));
        String figures = SourceScan.methodBody(till, "figures");
        List<String> uncounted = new ArrayList<>();
        List<String> refundsNotOut = new ArrayList<>();
        for (String entity : stamped) {
            if (!readsAndMaps(till, figures, entity)) {
                uncounted.add(entity);
            }
            if (entity.endsWith("Refund") && !movementOf(till, entity).contains(".direction(OUT)")) {
                refundsNotOut.add(entity);
            }
        }
        assertThat(uncounted)
            .as("a money row stamped with a cash session that CashSessionService.figures does not read: the till's"
                + " expected cash is wrong by every such row (QA D2950)")
            .isEmpty();
        assertThat(refundsNotOut).as("a refund paid out is money OUT of its till").isEmpty();
    }

    @Test
    @DisplayName("the till scan itself: a stamped row figures never reads is a finding")
    void theTillScanFindsTheClass() {
        String till = """
            class CashSessionService {
                Figures figures(CashSession session) {
                    List<PharmacySale> sales = saleRepository.findByCashSessionIdOrderByCreatedAtAsc(id);
                    return null;
                }
                private static Movement movement(PharmacySale s) {
                    return Movement.builder().direction(IN).build();
                }
                private static Movement movement(PharmacyRefund r) {
                    return Movement.builder().direction(OUT).build();
                }
            }
            """;
        String figures = SourceScan.methodBody(till, "figures");
        assertThat(readsAndMaps(till, figures, "PharmacySale")).isTrue();
        // Mapped, but never read: exactly QA D2950.
        assertThat(readsAndMaps(till, figures, "PharmacyRefund")).isFalse();
        assertThat(movementOf(till, "PharmacyRefund")).contains(".direction(OUT)");
        assertThat(movementOf(till, "PharmacySale")).doesNotContain(".direction(OUT)");
        assertThat(STAMPED_ROW.matcher("@Column(name = \"cash_session_id\")").find()).isTrue();
        assertThat(WRITES_MONEY.matcher("refund.setStatus(PharmacyRefundStatus.PAID);").find()).isTrue();
        assertThat(WRITES_MONEY.matcher("refund.setStatus(PharmacyRefundStatus.APPROVED);").find()).isFalse();
    }

    /** Whether the till reads the rows of {@code entity} in figures and maps them to a movement. */
    private static boolean readsAndMaps(String till, String figures, String entity) {
        return figures.contains("List<" + entity + ">") && !movementOf(till, entity).isEmpty();
    }

    /** The body of the till's {@code movement(Entity x)} mapping, or empty when there is none. */
    private static String movementOf(String till, String entity) {
        Matcher m = Pattern.compile("Movement\\s++movement\\(\\s*+" + Pattern.quote(entity)
            + "\\s++\\w++\\s*+\\)\\s*+\\{").matcher(till);
        if (!m.find()) {
            return "";
        }
        int open = m.end() - 1;
        return till.substring(open, SourceScan.closingBrace(till, open) + 1);
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
