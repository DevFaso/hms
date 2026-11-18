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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan PR 8 (finding 2): a payment reversal was audited as {@code REFUND_ISSUED}, and so was a voided
 * pharmacy payment, so the audit log claimed refunds that were never made. {@code REFUND_ISSUED} now means money
 * actually handed back on a refund; reversals and voids write {@code PAYMENT_REVERSED}. Only the refund service may
 * write it.
 */
@DisplayName("REFUND_ISSUED is written only when a refund is paid out")
class RefundIssuedAuditGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String ONLY_WRITER = "service/billing/InvoiceRefundService.java";

    @Test
    void onlyTheRefundServiceWritesRefundIssued() throws IOException {
        List<String> writers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            writers = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> !f.endsWith("AuditEventType.java"))
                .filter(RefundIssuedAuditGuardTest::namesRefundIssued)
                .map(f -> MAIN.relativize(f).toString().replace('\\', '/'))
                .toList();
        }
        assertThat(writers).as("main code naming REFUND_ISSUED").containsExactly(ONLY_WRITER);
    }

    private static boolean namesRefundIssued(Path file) {
        try {
            return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)).contains("REFUND_ISSUED");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
