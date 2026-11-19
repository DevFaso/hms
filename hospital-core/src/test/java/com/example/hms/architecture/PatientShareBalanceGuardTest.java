package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.utility.InvoiceBalance;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Billing-money plan PR 7 (class guard): once a payer covers part of an invoice, the patient owes their share, not
 * the total. Every rule that weighs the money held against the bill - a payment capped at what is owed, the status
 * that follows a payment or a reversal, a copay that fits the visit, the refund due, the reception queue's balance,
 * the e-mailed amount due - reads {@link InvoiceBalance#dueOf}, {@link InvoiceBalance#patientShareOf} or
 * {@link InvoiceBalance#settledStatusOf}. Before PR 7, eight places in the services computed it by hand from
 * {@code getTotalAmount()}: each would have let the patient pay the insurer's share.
 *
 * <p>The scan reads every class under {@code service/}, {@code utility/} and {@code controller/} and fails on a line
 * that takes what was paid or held away from the invoice total ({@code getTotalAmount().subtract(}, or
 * {@code keptOf(} / {@code getAmountPaid()} on the same line as {@code getTotalAmount()}), outside
 * {@code InvoiceBalance} and the files listed with why their figure is not what the patient owes.
 */
class PatientShareBalanceGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** file -> why its total-minus-paid is not what the patient owes. */
    private static final Map<String, String> NOT_THE_PATIENTS_BALANCE = Map.of(
        "utility/InvoiceBalance.java", "the one rule itself",
        "service/impl/HospitalAdminDashboardServiceImpl.java",
        "the hospital's open receivables: the payers' shares are owed to the hospital too, so the whole "
            + "total less what came in is the figure the admin dashboard shows");

    private static final Pattern BY_HAND = Pattern.compile(
        "getTotalAmount\\(\\)\\.subtract\\("
            + "|getTotalAmount\\(\\).*(keptOf\\(|getAmountPaid\\(\\))"
            + "|(keptOf\\(|getAmountPaid\\(\\)).*getTotalAmount\\(\\)");

    @Test
    void noServiceWeighsTheMoneyHeldAgainstTheTotalByHand() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                if (!(rel.startsWith("service/") || rel.startsWith("utility/") || rel.startsWith("controller/"))
                        || NOT_THE_PATIENTS_BALANCE.containsKey(rel)) {
                    continue;
                }
                scanned++;
                String src = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(f));
                String[] lines = src.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (BY_HAND.matcher(lines[i]).find()) {
                        offenders.add(rel + ":" + (i + 1) + " " + lines[i].trim());
                    }
                }
            }
        }
        assertThat(scanned).as("service, utility and controller classes scanned").isGreaterThan(200);
        assertThat(offenders)
            .as("the money held weighed against the invoice total by hand (billing-money plan PR 7): the patient owes "
                + "their share - use InvoiceBalance.dueOf / patientShareOf / settledStatusOf")
            .isEmpty();
    }

    @Test
    void theScanSeesTheShapesPr7Removed() {
        assertThat(BY_HAND.matcher(
            "BigDecimal balanceDue = invoice.getTotalAmount().subtract(InvoiceBalance.keptOf(invoice));").find())
            .isTrue();
        assertThat(BY_HAND.matcher(
            "if (InvoiceBalance.keptOf(invoice).compareTo(invoice.getTotalAmount()) >= 0) {").find()).isTrue();
        assertThat(BY_HAND.matcher("return InvoiceBalance.dueOf(invoice);").find()).isFalse();
    }

    @Test
    void theRuleIsThePatientsShare() {
        BillingInvoice invoice = BillingInvoice.builder().totalAmount(new BigDecimal("8500"))
            .payerShareTotal(new BigDecimal("5600")).amountPaid(BigDecimal.ZERO).status(InvoiceStatus.SENT).build();
        assertThat(InvoiceBalance.patientShareOf(invoice)).isEqualByComparingTo("2900");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("2900");
        invoice.setAmountPaid(new BigDecimal("2900"));
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.refundDueOf(invoice)).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.settledStatusOf(invoice)).isEqualTo(InvoiceStatus.PAID);
        // The payer rejected its share: it is the patient's again.
        invoice.setPayerShareTotal(BigDecimal.ZERO);
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("5600");
        assertThat(InvoiceBalance.settledStatusOf(invoice)).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }
}
