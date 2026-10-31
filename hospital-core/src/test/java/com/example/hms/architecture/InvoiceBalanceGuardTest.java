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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1104 (class guard, round 19): every surface that shows a patient what
 * is left to pay on an invoice reads it from {@link InvoiceBalance#dueOf}.
 * The API response zeroed a cancelled invoice's balance (QA D202) but the
 * invoice PDF, downloaded by staff and by the patient, computed its own and
 * printed "Annulée" then "Reste à payer : 1 500 F CFA".
 *
 * <p>The scan reads every class that renders an invoice for a reader (the
 * mappers, the PDF and e-mail services, the patient portal, FHIR) and fails
 * on a hand-written {@code total - paid}: a {@code .subtract(} whose line
 * names what was paid. The listed sites are not a balance of the invoice as
 * it stands, each with its reason.
 */
class InvoiceBalanceGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** file -> why its paid subtraction is not the invoice's current balance. */
    private static final Map<String, String> NOT_A_BALANCE = Map.of(
        "service/PdfInvoiceService.java#receipt",
        "the receipt prints the balance as of that payment (QA D228), a snapshot of its time; a receipt is "
            + "only issued for a CONFIRMED row, and a cancelled invoice holds none");

    @Test
    void noRenderingClassComputesABalanceByHand() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path f : rendering()) {
            scanned++;
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            String src = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(f));
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.contains(".subtract(") || !line.toLowerCase().contains("paid")) {
                    continue;
                }
                int offset = 0;
                for (int k = 0; k < i; k++) {
                    offset += lines[k].length() + 1;
                }
                boolean receipt = rel.equals("service/PdfInvoiceService.java")
                    && AppointmentSlotRulesGuardTest.enclosingMethod(src, offset).contains("generateReceiptPdf(");
                if (receipt && NOT_A_BALANCE.containsKey(rel + "#receipt")) {
                    continue;
                }
                offenders.add(rel + ":" + (i + 1) + " " + line.trim());
            }
        }
        assertThat(scanned).as("rendering classes scanned").isGreaterThan(20);
        assertThat(offenders)
            .as("a balance shown to a reader computed by hand (QA D1104): use InvoiceBalance.dueOf(invoice)")
            .isEmpty();
    }

    @Test
    void theRuleOwesNothingOnACancelledInvoiceAndNeverLessThanZero() {
        BillingInvoice invoice = BillingInvoice.builder()
            .totalAmount(new BigDecimal("1500")).amountPaid(BigDecimal.ZERO).status(InvoiceStatus.CANCELLED).build();
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("0");
        invoice.setStatus(InvoiceStatus.SENT);
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("1500");
        invoice.setAmountPaid(new BigDecimal("2000"));
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.dueOf(null)).isEqualByComparingTo("0");
    }

    @Test
    void theScanSeesTheShapeTheDefectHad() {
        String line = "w.right(BOLD, 11, t.get(BALANCE_DUE, money(total.subtract(paid).max(BigDecimal.ZERO))));";
        assertThat(line.contains(".subtract(") && line.toLowerCase().contains("paid")).isTrue();
    }

    private static List<Path> rendering() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> {
                    String rel = MAIN.relativize(f).toString().replace('\\', '/');
                    String name = f.getFileName().toString();
                    return rel.startsWith("mapper/") || rel.startsWith("fhir/")
                        || name.startsWith("Pdf") || name.contains("Email") || name.startsWith("PatientPortal");
                })
                .toList();
        }
    }
}
